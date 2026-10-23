package com.veycad.app

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.math.roundToInt

/** Detached revision input to the existing streaming composer, shared by playback/export/STT. */
internal class PreparedProjectAudio private constructor(
    val projectId: String,
    val revisionId: Long,
    val settings: AudioMixSettings,
    val music: ProjectMusic,
    val musicFile: File?,
    val musicHash: String?,
    val durationUs: Long,
    val sampleFrames: Long,
    private val fps: Int,
    clips: List<SourceAudioClip>,
    private val files: Map<String, File>,
    private val hashes: Map<String, String>
) {
    val clips = montageSnapshot(clips)
    val hasSourceAudio: Boolean get() = clips.any { it.hasAudio }

    fun requireFiles(checkCancelled: () -> Unit = {}) {
        files.forEach { (id, file) ->
            checkCancelled()
            require(file.isFile && sha256(file, checkCancelled) == hashes.getValue(id)) {
                "Сохранённый исходник изменился или отсутствует; откройте проект заново"
            }
        }
        musicFile?.let { file ->
            require(file.isFile && sha256(file, checkCancelled) == musicHash) { "Сохранённый музыкальный файл изменился или отсутствует" }
        }
    }

    /** Constant headroom also accounts for the persisted music track gain (which can be 2x). */
    internal fun compositionSettings(): AudioMixSettings {
        val musicGain = music.gain * settings.musicGain
        return when (settings.mode) {
            AudioMixMode.MUSIC -> AudioMixSettings(AudioMixMode.MUSIC, 0f, settings.musicGain)
            AudioMixMode.SOURCE -> settings
            AudioMixMode.SPEECH_AND_MUSIC -> {
                val sum = settings.sourceGain + musicGain
                val scale = if (sum > 1f) .89125094f / sum else 1f
                AudioMixSettings(settings.mode, settings.sourceGain * scale, musicGain * scale)
            }
        }
    }

    internal fun composeTo(output: PcmSink, speech: PcmSink? = null, checkCancelled: () -> Unit = {}): PcmCompositionStats {
        requireFiles(checkCancelled)
        val sources = MediaCodecAudioDecoder.sourceProvider(files, checkCancelled = checkCancelled)
        val score = musicFile?.let { file ->
            val raw = MediaCodecAudioDecoder.sourceProvider(mapOf("app-music" to file), checkCancelled = checkCancelled)
            PcmStreamProvider { _, _ -> ProjectMusicPcmStream(raw, music, durationUs) }
        }
        return SourceAudioComposer(ProjectClock(fps), clips, sources).composeProject(
            compositionSettings(), output, speech, score, checkCancelled, durationUs,
            musicMultiplier = if (settings.mode == AudioMixMode.MUSIC) music.gain else 1f)
    }

    fun compose(directory: File, checkCancelled: () -> Unit = {}): ComposedProjectAudio {
        checkCancelled()
        require(directory.isDirectory || directory.mkdirs())
        val bytes = Math.multiplyExact(sampleFrames, 4L)
        require(bytes <= 0xffff_ffffL - 36) { "Звуковая дорожка превышает размер WAV" }
        val playback = File.createTempFile("project-audio-", ".wav", directory)
        var speech: File? = null
        try {
            val speechFile = File.createTempFile("project-speech-", ".pcm", directory).also { speech = it }
            val scratch = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
            var mixedFrames = 0L
            var speechFrames = 0L
            val stats = RandomAccessFile(playback, "rw").use { wave ->
                writeWaveHeader(wave, bytes)
                speechFile.outputStream().buffered(4096).use { mono ->
                    composeTo(PcmSink { format, start, samples, count ->
                        checkCancelled()
                        check(format == PcmFormat(48000, 2) && start == mixedFrames && count in 1..1024)
                        scratch.clear(); repeat(count * 2) { scratch.putShort(samples[it]) }
                        wave.write(scratch.array(), 0, count * 4)
                        mixedFrames += count
                    }, PcmSink { format, start, samples, count ->
                        checkCancelled()
                        check(format == PcmFormat(16000, 1) && start == speechFrames && count in 1..1024)
                        scratch.clear(); repeat(count) { scratch.putShort(samples[it]) }
                        mono.write(scratch.array(), 0, count * 2)
                        speechFrames += count
                    }, checkCancelled)
                }.also { wave.fd.sync() }
            }
            check(mixedFrames == sampleFrames && speechFrames == sampleFrames / 3)
            check(playback.length() == 44 + bytes && speechFile.length() == speechFrames * 2)
            checkCancelled()
            return ComposedProjectAudio(playback, speechFile, mixedFrames, hasSourceAudio, stats)
        } catch (failure: Throwable) {
            for (file in listOfNotNull(playback, speech)) runCatching { Files.deleteIfExists(file.toPath()) }
                .exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    companion object {
        fun from(project: HybridProject, assets: ProjectAssetStore, checkCancelled: () -> Unit = {}): PreparedProjectAudio {
            val revision = project.current
            val byId = project.assets.associateBy { it.id }
            val sourceIds = revision.clips.map { it.assetId }.distinct()
            val files = sourceIds.associateWith { id -> assets.resolve(byId.getValue(id)) }
            val hashes = sourceIds.associateWith { id -> byId.getValue(id).contentHash }
            val clips = revision.clips.map { clip ->
                val asset = byId.getValue(clip.assetId)
                require(asset.kind == ProjectAsset.Kind.VIDEO && asset.videoMetadata != null)
                SourceAudioClip(clip.assetId, clip.span, clip.sourceMap, asset.videoMetadata.hasAudio)
            }
            val musicAsset = byId.getValue(revision.music.assetId)
            require(musicAsset.kind == ProjectAsset.Kind.AUDIO)
            val compiled = EditableMontageCompiler.compile(EditableMontageProject(project,
                ProjectExportSettings(720, 1280, project.fps, 5000000, 192000)))
            val duration = compiled.editableTiming?.let { frameTimeUs(it.frameCount, project.fps) }
                ?: (compiled.outputDurationMs * 1000L)
            val frames = (duration * 48000 + 500000) / 1000000
            val withMusic = revision.audioMix.mode != AudioMixMode.SOURCE
            return PreparedProjectAudio(project.id, revision.id, revision.audioMix, revision.music,
                if (withMusic) assets.resolve(musicAsset) else null, if (withMusic) musicAsset.contentHash else null,
                duration, frames, project.fps, clips, files, hashes).also {
                it.compositionSettings()
                it.requireFiles(checkCancelled)
            }
        }

        private fun sha256(file: File, checkCancelled: () -> Unit): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val bytes = ByteArray(65536)
                while (true) { checkCancelled(); val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun writeWaveHeader(output: RandomAccessFile, dataBytes: Long) {
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt((dataBytes + 36).toInt())
            header.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(2)
            header.putInt(48000).putInt(192000).putShort(4).putShort(16)
            header.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataBytes.toInt())
            output.write(header.array())
        }
    }
}

/** Playback and recognition borrow these files until their owner has acknowledged stop. */
internal class ComposedProjectAudio(val playbackFile: File, val speechPcmFile: File,
    val sampleFrames: Long, val hasSourceAudio: Boolean, val compositionStats: PcmCompositionStats) : AutoCloseable {
    private var closed = false
    fun requireOpen() { check(!closed && playbackFile.isFile && speechPcmFile.isFile) { "Prepared project audio is closed" } }
    override fun close() {
        if (closed) return
        var failure: Throwable? = null
        for (file in listOf(playbackFile, speechPcmFile)) try { Files.deleteIfExists(file.toPath()) }
            catch (error: Throwable) { if (failure == null) failure = error else failure.addSuppressed(error) }
        failure?.let { throw it }
        closed = true
    }
}

/** The existing EOF-based tail loop also handles a nonrepeating tail. Fades use output time. */
internal class ProjectMusicPcmStream(raw: PcmStreamProvider, private val music: ProjectMusic,
    private val durationUs: Long) : PcmStream {
    private val selected = LoopingMusicPcmStream(raw, music.startUs)
    override val format: PcmFormat get() = selected.format
    override fun read(): PcmChunk? {
        val chunk = selected.readNext(music.repeat) ?: return null
        val samples = ShortArray(chunk.frames * format.channels)
        repeat(chunk.frames) { frame ->
            val time = chunk.startPtsUs + frame * 1000000L / format.sampleRate
            val fade = minOf(if (music.fadeInUs == 0L) 1.0 else time.toDouble() / music.fadeInUs,
                if (music.fadeOutUs == 0L) 1.0 else (durationUs - time).toDouble() / music.fadeOutUs).coerceIn(0.0, 1.0)
            repeat(format.channels) { channel -> samples[frame * format.channels + channel] =
                (chunk.sample(frame, channel) * 32768 * fade).roundToInt().coerceIn(-32768, 32767).toShort() }
        }
        return PcmChunk(format, chunk.startPtsUs, samples)
    }
    override fun close() = selected.close()
}
