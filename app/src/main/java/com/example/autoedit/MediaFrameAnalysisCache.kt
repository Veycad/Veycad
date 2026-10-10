package com.veycad.app

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Exact, local cache of the expensive full-frame analysis. It never enters an APK or gallery. */
internal object MediaFrameAnalysisCache {
    class Key internal constructor(val fileName: String, val profile: SourceAnalysisProfile)

    fun key(source: File, intervalUs: Long, profile: SourceAnalysisProfile): Key {
        require(source.isFile && intervalUs > 0L)
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(source).use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return Key("v$VERSION-${profile.cacheToken}-$intervalUs-$hex.bin.gz", profile)
    }

    fun load(context: Context, key: Key): MediaFrameVisualAnalyzer.Result? {
        val file = File(directory(context), key.fileName)
        if (!file.isFile) return null
        return runCatching { read(file, key.profile) }
            .onFailure { file.delete() }
            .getOrNull()
            ?.also {
                file.setLastModified(System.currentTimeMillis())
                LocalDiagnostics.record(context, "video_analysis_cache_hit", mapOf(
                    "semantic_frames" to it.semanticFrames.toString(),
                    "mask_frames" to it.maskFrames.toString(),
                    "source_analysis_profile" to it.profile.cacheToken,
                    "correspondence_assessments_completed" to it.correspondenceAssessmentsCompleted.toString()
                ))
            }
    }

    fun store(context: Context, key: Key, result: MediaFrameVisualAnalyzer.Result) {
        require(result.profile == key.profile) { "Cache key and result source capabilities disagree" }
        runCatching {
            val directory = directory(context)
            val target = File(directory, key.fileName)
            val temporary = File(directory, "${key.fileName}.${android.os.Process.myPid()}.partial")
            write(result, temporary)
            target.delete()
            require(temporary.renameTo(target)) { "Unable to publish video analysis cache" }
            directory.listFiles { file -> file.extension == "gz" }
                ?.sortedByDescending(File::lastModified)
                ?.drop(MAXIMUM_ENTRIES)
                ?.forEach(File::delete)
            LocalDiagnostics.record(context, "video_analysis_cache_store", mapOf(
                "bytes" to target.length().toString(),
                "semantic_frames" to result.semanticFrames.toString()
            ))
        }.onFailure {
            LocalDiagnostics.record(context, "video_analysis_cache_failed", mapOf(
                "type" to it.javaClass.simpleName
            ))
        }
    }

    internal fun write(result: MediaFrameVisualAnalyzer.Result, file: File) {
        file.parentFile?.mkdirs()
        DataOutputStream(BufferedOutputStream(GZIPOutputStream(FileOutputStream(file)))).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeUTF(result.profile.cacheToken)
            out.writeInt(result.correspondenceAssessmentsCompleted)
            out.writeLong(result.durationUs)
            out.writeInt(result.semanticFrames)
            out.writeInt(result.maskFrames)
            out.writeInt(result.semanticModelSuccesses)
            out.writeInt(result.observations.size)
            result.observations.forEach { out.writeObservation(it) }
            out.writeTimeline(result.attachments)
        }
    }

    internal fun read(file: File, expectedProfile: SourceAnalysisProfile): MediaFrameVisualAnalyzer.Result =
        DataInputStream(BufferedInputStream(GZIPInputStream(FileInputStream(file)))).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == VERSION)
            val profile = SourceAnalysisProfile.fromCacheToken(input.readUTF())
            require(profile == expectedProfile) { "Cache header and requested source capabilities disagree" }
            val correspondenceAssessmentsCompleted = input.checkedCount(MAXIMUM_FRAMES)
            val durationUs = input.readLong()
            val semanticFrames = input.readInt()
            val maskFrames = input.readInt()
            val semanticModelSuccesses = input.readInt()
            val observationCount = input.checkedCount(MAXIMUM_FRAMES)
            val observations = List(observationCount) { input.readObservation() }
            val attachments = input.readTimeline()
            require(input.read() == -1) { "Unexpected trailing source analysis cache data" }
            require(durationUs > 0L && observations.isNotEmpty())
            MediaFrameVisualAnalyzer.Result(
                profile, correspondenceAssessmentsCompleted,
                durationUs, observations, attachments,
                semanticFrames, maskFrames, semanticModelSuccesses
            )
        }

    private fun DataOutputStream.writeObservation(value: VisualEventMap.Observation) {
        writeLong(value.sourceTimeUs)
        writeVector(value.cameraMotion)
        writeVector(value.subjectMotion)
        writeOptional(value.face) {
            writeFloat(it.confidence); writeFloat(it.yawDegrees); writeFloat(it.pitchDegrees)
            writeFloat(it.rollDegrees); writeFloat(it.gazeX); writeFloat(it.gazeY)
        }
        writeFloat(value.gestureConfidence); writeFloat(value.occlusionConfidence)
        writeFloat(value.personMaskConfidence); writeFloat(value.personMaskTemporalIou)
        writeFloat(value.visualQuality); writeFloat(value.meanLuma)
        writeOptional(value.composition) {
            writeFloat(it.subjectScale); writeFloat(it.headroomScore); writeFloat(it.lookRoomScore)
            writeFloat(it.backgroundSimplicity); writeFloat(it.contrastScore)
        }
        writeFloat(value.sceneChangeConfidence)
        writeFloat(value.humanPresenceConfidence)
        writeOptional(value.motionMeasurement) {
            writeFloat(it.subjectIntensity); writeFloat(it.cameraX); writeFloat(it.cameraY)
            writeFloat(it.cameraConfidence); writeInt(it.subjectCells); writeFloat(it.subjectSupportedFraction)
            writeInt(it.cameraCells); writeInt(it.cameraQuadrants); writeLong(it.intervalUs)
        }
        writeBoolean(value.faceInferenceSucceeded)
        writeBoolean(value.gestureEvidenceAvailable)
        writeOptional(value.cameraMeasurement) {
            writeFloat(it.x); writeFloat(it.y); writeFloat(it.confidence)
            writeInt(it.cells); writeInt(it.quadrants)
            writeLong(it.previousTimeUs); writeLong(it.currentTimeUs); writeLong(it.semanticTimeUs)
            writeLong(it.intervalUs); writeUTF(it.method)
        }
    }

    private fun DataInputStream.readObservation() = VisualEventMap.Observation(
        sourceTimeUs = readLong(),
        cameraMotion = readVector(),
        subjectMotion = readVector(),
        face = readOptional {
            VisualEventMap.Face(readFloat(), readFloat(), readFloat(), readFloat(), readFloat(), readFloat())
        },
        gestureConfidence = readFloat(),
        occlusionConfidence = readFloat(),
        personMaskConfidence = readFloat(),
        personMaskTemporalIou = readFloat(),
        visualQuality = readFloat(),
        meanLuma = readFloat(),
        composition = readOptional {
            VisualEventMap.Composition(readFloat(), readFloat(), readFloat(), readFloat(), readFloat())
        },
        sceneChangeConfidence = readFloat(),
        humanPresenceConfidence = readFloat(),
        motionMeasurement = readOptional {
            VisualEventMap.MotionMeasurement(readFloat(), readFloat(), readFloat(), readFloat(),
            readInt(), readFloat(), readInt(), readInt(), readLong())
        },
        faceInferenceSucceeded = readBoolean(),
        gestureEvidenceAvailable = readBoolean(),
        cameraMeasurement = readOptional {
            VisualEventMap.CameraMeasurement(readFloat(), readFloat(), readFloat(), readInt(), readInt(),
                readLong(), readLong(), readLong(), readLong(), readUTF())
        }
    )

    private fun DataOutputStream.writeTimeline(value: FrameAttachmentTimeline) {
        writeInt(value.frames.size)
        value.frames.forEach { writeAttachment(it) }
        writeInt(value.maskRefinements.size)
        value.maskRefinements.forEach { writeAttachment(it) }
    }

    private fun DataInputStream.readTimeline(): FrameAttachmentTimeline {
        val frames = List(checkedCount(MAXIMUM_FRAMES)) { readAttachment() }
        val refinements = List(checkedCount(MAXIMUM_REFINEMENTS)) { readAttachment() }
        return FrameAttachmentTimeline(frames, refinements)
    }

    private fun DataOutputStream.writeAttachment(value: FrameAttachments) {
        writeLong(value.sourceTimeUs)
        writeOptional(value.mask) { writePlane(it) }
        writeOptional(value.depth) { writePlane(it) }
        writeOptional(value.flow) { writeFlow(it) }
        writeFloat(value.subjectQuality); writeFloat(value.subjectOcclusion)
        writeFloat(value.maskTemporalIou)
        writeOptional(value.faceRegion) {
            writeFloat(it.centerX); writeFloat(it.centerY); writeFloat(it.width)
            writeFloat(it.height); writeFloat(it.confidence)
        }
        writeOptional(value.maskBlendTarget) { writePlane(it) }
        writeFloat(value.maskBlendProgress)
        writeBoolean(value.maskIsOpacity)
    }

    private fun DataInputStream.readAttachment() = FrameAttachments(
        sourceTimeUs = readLong(),
        mask = readOptional { readPlane() },
        depth = readOptional { readPlane() },
        flow = readOptional { readFlow() },
        subjectQuality = readFloat(),
        subjectOcclusion = readFloat(),
        maskTemporalIou = readFloat(),
        faceRegion = readOptional {
            FrameAttachments.FaceRegion(readFloat(), readFloat(), readFloat(), readFloat(), readFloat())
        },
        maskBlendTarget = readOptional { readPlane() },
        maskBlendProgress = readFloat(),
        maskIsOpacity = readBoolean()
    )

    private fun DataOutputStream.writePlane(value: FrameAttachments.Plane) {
        writeInt(value.width); writeInt(value.height); writeFloat(value.confidence)
        value.values.forEach(::writeFloat)
    }

    private fun DataInputStream.readPlane(): FrameAttachments.Plane {
        val width = readInt(); val height = readInt(); val confidence = readFloat()
        require(width in 1..MAXIMUM_PLANE_SIDE && height in 1..MAXIMUM_PLANE_SIDE)
        val values = FloatArray(Math.multiplyExact(width, height)) { readFloat() }
        return FrameAttachments.Plane(width, height, values, confidence)
    }

    private fun DataOutputStream.writeFlow(value: FrameAttachments.FlowPlane) {
        writeInt(value.width); writeInt(value.height); writeFloat(value.confidence)
        value.vectors.forEach(::writeFloat)
    }

    private fun DataInputStream.readFlow(): FrameAttachments.FlowPlane {
        val width = readInt(); val height = readInt(); val confidence = readFloat()
        require(width in 1..MAXIMUM_PLANE_SIDE && height in 1..MAXIMUM_PLANE_SIDE)
        val count = Math.multiplyExact(Math.multiplyExact(width, height), 2)
        return FrameAttachments.FlowPlane(width, height, List(count) { readFloat() }, confidence)
    }

    private fun DataOutputStream.writeVector(value: VisualEventMap.Vector) {
        writeFloat(value.x); writeFloat(value.y); writeFloat(value.radial)
    }

    private fun DataInputStream.readVector() = VisualEventMap.Vector(readFloat(), readFloat(), readFloat())

    private inline fun <T> DataOutputStream.writeOptional(value: T?, write: DataOutputStream.(T) -> Unit) {
        writeBoolean(value != null)
        if (value != null) write(value)
    }

    private inline fun <T> DataInputStream.readOptional(readValue: DataInputStream.() -> T): T? =
        if (readBoolean()) readValue(this) else null

    private fun DataInputStream.checkedCount(maximum: Int): Int =
        readInt().also { require(it in 0..maximum) }

    private fun directory(context: Context) = File(context.cacheDir, DIRECTORY).apply { mkdirs() }

    private const val MAGIC = 0x56414E4C
    internal const val VERSION = 18 // Explicit profile/header and completed assessment coverage; older caches have no capability contract.
    private const val DIRECTORY = "full-video-analysis"
    private const val MAXIMUM_ENTRIES = 3
    private const val MAXIMUM_FRAMES = 4_000
    private const val MAXIMUM_REFINEMENTS = 90
    private const val MAXIMUM_PLANE_SIDE = 1_024
}
