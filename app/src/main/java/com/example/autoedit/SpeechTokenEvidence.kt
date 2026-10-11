package com.veycad.app

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Collections
import java.util.Locale
import java.util.UUID

internal enum class SpeechAlignmentKind { SEGMENT_ONLY, TOKEN_HEURISTIC, ACOUSTIC_WORD }
internal enum class SpeechConfidenceKind { UNAVAILABLE, TOKEN_AGGREGATE, CALIBRATED_WORD }
internal enum class SpeechLanguageOrigin { REQUESTED, BACKEND_DETECTED }
internal data class SpeechTokenIdentity(val windowStartFrame:Long,val segmentIndex:Int,val tokenIndex:Int)
internal data class SpeechWordIdentity(val windowStartFrame:Long,val segmentIndex:Int,val wordIndex:Int)
internal data class SpeechRawToken(val identity:SpeechTokenIdentity,val tokenId:Int,val special:Boolean,
    val startTick:Long,val endTick:Long,val probability:Double,val bytesHex:String)
internal data class SpeechRawSegment(val index:Int,val startTick:Long,val endTick:Long,
    val noSpeechProbability:Double,val text:String)
private fun <T> frozen(values:List<T>):List<T> = Collections.unmodifiableList(ArrayList(values))

/** Token intervals are backend heuristics. A missing/zero-length contributor leaves a word untimed. */
internal class SpeechTokenWord(val identity:SpeechWordIdentity,val text:String,
    val startUs:Long?,val endUs:Long?,contributors:List<SpeechTokenIdentity>,
    val tokenProbabilityMean:Double?,val sharesTokenWithOtherWords:Boolean) {
    val contributors=frozen(contributors)
    val alignment=SpeechAlignmentKind.TOKEN_HEURISTIC
    val confidenceKind=if(tokenProbabilityMean==null) SpeechConfidenceKind.UNAVAILABLE else SpeechConfidenceKind.TOKEN_AGGREGATE
    val confidenceCalibrated=false
}
internal class SpeechWindowEvidence(val startFrame:Long,val frames:Int,val backendCommit:String,
    val bridgeSha256:String,val language:String?,val languageOrigin:SpeechLanguageOrigin,
    cues:List<CaptionCue>,segments:List<SpeechRawSegment>,tokens:List<SpeechRawToken>,words:List<SpeechTokenWord>) {
    val cues=frozen(cues); val segments=frozen(segments); val tokens=frozen(tokens); val words=frozen(words)
}
internal data class SpeechSuppressedDuplicate(val kept:SpeechWordIdentity,val suppressed:SpeechWordIdentity)

/** Raw windows stay untouched; the derived view only suppresses one observed counterpart at a time. */
internal class SpeechRawEvidence(windows:List<SpeechWindowEvidence>) {
    val windows=frozen(windows)
    val words:List<SpeechTokenWord>
    val suppressedDuplicates:List<SpeechSuppressedDuplicate>
    init {
        require(windows.zipWithNext().all { (a,b)->a.startFrame<b.startFrame })
        require(windows.all { it.backendCommit==windows.first().backendCommit && it.bridgeSha256==windows.first().bridgeSha256 })
        val merged=mutableListOf<SpeechTokenWord>(); val suppressed=mutableListOf<SpeechSuppressedDuplicate>()
        for(window in this.windows) {
            val used=mutableSetOf<SpeechWordIdentity>()
            for(word in window.words) {
                val previous=if(word.startUs==null || word.endUs==null) null else merged.filter { old ->
                    old.identity.windowStartFrame!=window.startFrame && old.identity !in used &&
                        old.text.lowercase(Locale.ROOT)==word.text.lowercase(Locale.ROOT) &&
                        old.startUs!=null && old.endUs!=null && old.startUs<word.endUs && word.startUs<old.endUs &&
                        old.endUs>frameUs(window.startFrame)
                }.minByOrNull { kotlin.math.abs(it.startUs!!-word.startUs) }
                if(previous==null) merged.add(word)
                else { used.add(previous.identity); suppressed.add(SpeechSuppressedDuplicate(previous.identity,word.identity)) }
            }
        }
        words=frozen(merged); suppressedDuplicates=frozen(suppressed)
    }
}
private fun frameUs(frames:Long):Long {
    require(frames>=0 && frames<=Long.MAX_VALUE/1_000_000)
    return frames*1_000_000/16000
}

/** ASCII v1 rows carry raw ticks and hex bytes, never independently decoded BPE pieces. */
internal object SpeechTokenParser {
    const val BACKEND_COMMIT="48f628a84833905ee4a0658ee6d4a5c915ce1997"
    private val lexical=Regex("[\\p{L}\\p{M}\\p{N}]+(?:['’\\-][\\p{L}\\p{M}\\p{N}]+)*")
    private fun bytes(hex:String):ByteArray {
        require(hex.length%2==0 && hex.length<=1_000_000 && hex.matches(Regex("[0-9a-fA-F]*")))
        return ByteArray(hex.length/2) { hex.substring(it*2,it*2+2).toInt(16).toByte() }
    }
    private fun unicode(bytes:ByteArray)=Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
    private fun probability(value:String)=value.toDouble().also { require(it.isFinite() && it in 0.0..1.0) }
    private fun tick(value:String,max:Long)=value.toLong().also {
        require(it in -1..max) { "Backend tick $it outside -1..$max" }
    }
    fun window(rows:Array<String>,offsetFrames:Long,totalFrames:Long,windowFrames:Int,requestedLanguage:String):SpeechWindowEvidence = try {
        require(offsetFrames>=0 && windowFrames>0 && offsetFrames<=totalFrames-windowFrames)
        require(requestedLanguage in setOf("auto","ru","en"))
        val offsetUs=frameUs(offsetFrames); val durationUs=frameUs(totalFrames); val windowUs=frameUs(windowFrames.toLong())
        val header=rows.first().split('\t')
        require(header.size==9 && header[0]=="H" && header[1]=="1" && header[2]==BACKEND_COMMIT)
        require(header[3].matches(Regex("[0-9a-f]{64}")) && header[4].toInt()==windowFrames)
        val languageId=header[5].toInt(); val language=unicode(bytes(header[6])).ifEmpty { null }
        require(languageId>=-1 && (language==null || language.matches(Regex("[a-z]{2,3}"))))
        require((languageId==-1)==(language==null))
        val eot=header[7].toInt(); val vocab=header[8].toInt(); require(eot>0 && vocab>eot)
        val segments=mutableListOf<SpeechRawSegment>(); val tokens=mutableListOf<SpeechRawToken>()
        val maxTick=windowUs/10000
        for(row in rows.drop(1)) {
            val f=row.split('\t')
            when(f.first()) {
                "S" -> {
                    require(f.size==6); val index=f[1].toInt(); require(index==segments.size)
                    val start=tick(f[2],maxTick); val end=tick(f[3],maxTick)
                    require(start>=0 && end>=start)
                    segments.add(SpeechRawSegment(index,start,end,probability(f[4]),unicode(bytes(f[5]))))
                }
                "T" -> {
                    require(f.size==9); val segment=f[1].toInt(); val index=f[2].toInt(); val id=f[3].toInt()
                    require(segment>=0 && segment==segments.lastIndex && index==tokens.count { it.identity.segmentIndex==segment } && id in 0 until vocab)
                    require(f[4] in setOf("TEXT","SPECIAL") && (f[4]=="SPECIAL")== (id>=eot))
                    val start=tick(f[5],maxTick); val end=tick(f[6],maxTick)
                    require(start==-1L || end==-1L || start<=end)
                    bytes(f[8])
                    tokens.add(SpeechRawToken(SpeechTokenIdentity(offsetFrames,segment,index),id,id>=eot,
                        start,end,probability(f[7]),f[8].lowercase(Locale.ROOT)))
                }
                else -> error("Unknown native row")
            }
        }
        val cues=mutableListOf<CaptionCue>(); val words=mutableListOf<SpeechTokenWord>()
        for(segment in segments) {
            val ordinary=tokens.filter { it.identity.segmentIndex==segment.index && !it.special }
            val spans=mutableListOf<Pair<Int,Int>>(); val stream=java.io.ByteArrayOutputStream()
            for(token in ordinary) { val start=stream.size(); stream.write(bytes(token.bytesHex)); spans.add(start to stream.size()) }
            val text=unicode(stream.toByteArray()); require(text==segment.text)
            if(segment.noSpeechProbability>.6 || segment.endTick==segment.startTick || text.isBlank()) continue
            require(text.trim().length<=2000)
            cues.add(CaptionCue(UUID.randomUUID().toString(),text.trim(),offsetUs+segment.startTick*10000,offsetUs+segment.endTick*10000))
            val matches=lexical.findAll(text).toList()
            val contributors=matches.map { match ->
                val start=text.substring(0,match.range.first).toByteArray(Charsets.UTF_8).size
                val end=text.substring(0,match.range.last+1).toByteArray(Charsets.UTF_8).size
                ordinary.filterIndexed { i,_->spans[i].first<end && start<spans[i].second }
            }
            for((index,match) in matches.withIndex()) {
                val support=contributors[index]; require(support.isNotEmpty())
                val timed=support.all { it.startTick>=0 && it.endTick>it.startTick }
                val start=if(timed) offsetUs+support.minOf { it.startTick }*10000 else null
                val end=if(timed) offsetUs+support.maxOf { it.endTick }*10000 else null
                require(!timed || (start!!>=0 && end!!>start && end<=durationUs))
                words.add(SpeechTokenWord(SpeechWordIdentity(offsetFrames,segment.index,index),match.value,start,end,
                    support.map { it.identity },support.map { it.probability }.average(),
                    support.any { token->contributors.count { list->token in list }>1 }))
            }
        }
        SpeechWindowEvidence(offsetFrames,windowFrames,header[2],header[3],language,
            if(requestedLanguage=="auto") SpeechLanguageOrigin.BACKEND_DETECTED else SpeechLanguageOrigin.REQUESTED,
            cues,segments,tokens,words)
    } catch(failure:SpeechFailureException) { throw failure }
    catch(failure:Exception) { throw SpeechFailureException(SpeechFailureCode.RESULT_INVALID,failure) }
}
