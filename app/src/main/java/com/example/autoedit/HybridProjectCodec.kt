package com.veycad.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Collections
import java.util.IdentityHashMap

class ProjectLoadResult(val project: HybridProject, missingAnalysisHashes: Set<String>) {
    val missingAnalysisHashes: Set<String> = Collections.unmodifiableSet(LinkedHashSet(missingAnalysisHashes))
    val analysisRegenerationRequired: Boolean get() = missingAnalysisHashes.isNotEmpty()
}

/** Explicit primitive format. Physical v2 adds camera phase; project schema remains 1. */
class HybridProjectCodec(private val sidecars: AnalysisSidecarStore) {
    fun encode(project: HybridProject): ByteArray {
        val bytes = ByteArrayOutputStream()
        Writer(DataOutputStream(bytes), sidecars).apply {
            out.writeInt(MAGIC); out.writeInt(FORMAT_VERSION)
            string(project.id); out.writeInt(project.fps); out.writeLong(project.nextRevisionId)
            list(project.assets) {
                string(it.id); string(it.fileName); enum(it.kind); out.writeLong(it.durationUs)
                string(it.contentHash); string(it.displayName)
            }
            val revisions = (listOf(project.original, project.current) + project.undo + project.redo).distinctBy { it.id }
            list(revisions) { revision(it) }
            out.writeLong(project.original.id); out.writeLong(project.current.id)
            list(project.undo) { out.writeLong(it.id) }; list(project.redo) { out.writeLong(it.id) }
            list(project.exports) {
                out.writeLong(it.revisionId); string(it.fileName)
                with(it.settings) { out.writeInt(width); out.writeInt(height); out.writeInt(fps); out.writeInt(bitrate); out.writeInt(audioBitrate) }
            }
        }
        require(bytes.size() <= MAX_MANIFEST_BYTES)
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): HybridProject = decodeWithAnalysisStatus(bytes).project

    /** Store-level immutable revision blobs use the same schema and field codec. */
    internal fun encodeRevisions(revisions: List<HybridRevision>): Map<Long, ByteArray> {
        val planes = IdentityHashMap<FloatArray, String>()
        val hashes = hashSetOf<String>()
        return revisions.associate { revision ->
            val bytes = ByteArrayOutputStream()
            val output = DataOutputStream(bytes)
            output.writeInt(MAGIC); output.writeInt(FORMAT_VERSION)
            Writer(output, sidecars, planes, hashes).revision(revision)
            require(bytes.size() <= MAX_MANIFEST_BYTES)
            revision.id to bytes.toByteArray()
        }
    }

    internal fun decodeRevision(bytes: ByteArray): HybridRevision {
        require(bytes.size in 8..MAX_MANIFEST_BYTES)
        val reader = Reader(DataInputStream(ByteArrayInputStream(bytes)), sidecars)
        require(reader.input.readInt() == MAGIC)
        val format = reader.formatVersion()
        return reader.revision(format).also { require(reader.input.available() == 0) }
    }

    fun decodeWithAnalysisStatus(bytes: ByteArray): ProjectLoadResult {
        require(bytes.size in 8..MAX_MANIFEST_BYTES)
        val reader = Reader(DataInputStream(ByteArrayInputStream(bytes)), sidecars)
        with(reader) {
            require(input.readInt() == MAGIC) { "Not a hybrid project" }
            val format = formatVersion()
            val id = string(); val fps = input.readInt(); val next = input.readLong()
            val assets = list { ProjectAsset(string(), string(), enum(), input.readLong(), string(), string()) }
            val revisions = list { revision(format) }
            require(revisions.map { it.id }.distinct().size == revisions.size)
            val canonical = revisions.associateBy { it.id }
            fun reference(): HybridRevision = requireNotNull(canonical[input.readLong()]) { "Unknown revision reference" }
            val original = reference(); val current = reference()
            val undo = list { reference() }; val redo = list { reference() }
            val exports = list { ProjectExportRef(input.readLong(), string(), ProjectExportSettings(
                input.readInt(), input.readInt(), input.readInt(), input.readInt(), input.readInt())) }
            require(input.available() == 0) { "Trailing project data" }
            return ProjectLoadResult(HybridProject(id, 1, fps, next, assets, original, current, undo, redo, exports), missing)
        }
    }

    private class Writer(val out: DataOutputStream, val sidecars: AnalysisSidecarStore,
        val planes: IdentityHashMap<FloatArray, String> = IdentityHashMap(),
        val hashes: MutableSet<String> = hashSetOf()) {
        fun string(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_STRING_BYTES)
            out.writeInt(bytes.size); out.write(bytes)
        }
        fun enum(value: Enum<*>) = string(value.name)
        fun <T> list(values: Collection<T>, write: (T) -> Unit) {
            require(values.size <= MAX_ITEMS); out.writeInt(values.size); values.forEach(write)
        }
        fun sourceMap(value: SourceTimeMap) {
            val count = value.points.size
            require(count in 2..MAX_SOURCE_POINTS && 4L + count * 12L <= MAX_MANIFEST_BYTES - out.size().toLong()) {
                "Source map exceeds the project byte budget"
            }
            out.writeInt(count)
            value.points.forEach { out.writeInt(it.localFrame); out.writeLong(it.sourceTimeUs) }
        }
        fun <T : Any> optional(value: T?, write: (T) -> Unit) { out.writeBoolean(value != null); if (value != null) write(value) }
        fun nullableLong(value: Long?) = optional(value) { out.writeLong(it) }
        fun span(value: FrameSpan) { out.writeInt(value.start); out.writeInt(value.endExclusive) }
        fun curve(value: MontageGraph.SpeedRamp.CubicBezier) { with(value) { out.writeFloat(x1); out.writeFloat(y1); out.writeFloat(x2); out.writeFloat(y2) } }
        fun revision(value: HybridRevision) = with(value) {
            out.writeLong(id); nullableLong(parentId); graph(graph)
            list(clips) {
                string(it.id); string(it.assetId); span(it.span)
                sourceMap(it.sourceMap)
                clip(it.original)
                out.writeInt(it.originalFrameOffset)
            }
            with(music) { string(assetId); out.writeLong(startUs); out.writeFloat(gain); out.writeLong(fadeInUs); out.writeLong(fadeOutUs); out.writeBoolean(repeat) }
            list(texts) {
                string(it.id); string(it.text); span(it.span); out.writeFloat(it.x); out.writeFloat(it.y)
                out.writeFloat(it.width); out.writeFloat(it.size); out.writeInt(it.color); enum(it.appearance); out.writeInt(it.fadeFrames)
            }
            with(style) { string(recipeId); out.writeInt(recipeVersion); enum(mode); out.writeBoolean(showAuthoredText) }
            list(lockedCutIds) { string(it) }
        }
        fun clip(value: MontageGraph.Clip) = with(value) {
            string(id); out.writeLong(sourceStartMs); out.writeLong(sourceEndMs); out.writeLong(outputDurationMs)
            enum(role); enum(transitionIn); enum(motion); out.writeFloat(confidence); out.writeLong(beatAnchorMs)
            list(transform.keyframes) { with(it) { out.writeFloat(at); out.writeFloat(scale); out.writeFloat(translateX); out.writeFloat(translateY); out.writeFloat(rotationDegrees) } }
            list(speedRamp.keyframes) { out.writeFloat(it.at); out.writeFloat(it.speed); curve(it.curveToNext) }
            out.writeFloat(exposureBias); out.writeFloat(redBias); out.writeFloat(greenBias); out.writeFloat(blueBias)
            out.writeFloat(flowStrength); nullableLong(transitionDurationMs); out.writeInt(sourceIndex)
        }
        fun graph(value: MontageGraph) = with(value) {
            out.writeInt(version); out.writeLong(sourceDurationMs); out.writeLong(outputDurationMs); enum(profile)
            list(clips) { clip(it) }
            optional(audioTrack) { string(it.sourceId); out.writeFloat(it.gain) }
            list(overlays) { with(it) {
                string(id); out.writeLong(startMs); out.writeLong(endMs); string(kind); enum(blendMode); enum(overlayKind)
                out.writeFloat(opacity); out.writeFloat(red); out.writeFloat(green); out.writeFloat(blue)
                out.writeLong(secondarySourceOffsetMs); nullableLong(secondaryTimelineStartMs)
            } }
            with(metadata) { out.writeInt(schemaVersion); out.writeLong(revision); nullableLong(parentRevision); string(generator) }
            list(parameterTracks) { track ->
                string(track.id); string(track.target); enum(track.type)
                list(track.keyframes) { out.writeLong(it.timeUs); list(it.value) { v -> out.writeFloat(v) }; enum(it.interpolation); curve(it.curveToNext) }
            }
            list(frameAttachments.frames) { frame(it) }; list(frameAttachments.maskRefinements) { frame(it) }
            list(effectGraph.nodes) { with(it) { string(id); enum(kind); out.writeLong(startUs); out.writeLong(endUs); out.writeFloat(amount); out.writeFloat(secondary) } }
        }
        fun floats(kind: String, values: FloatArray) {
            planes[values]?.let { string(it); return }
            require(values.size <= AnalysisSidecarStore.MAX_BYTES / 4)
            val snapshot = values.copyOf()
            val bytes = ByteArrayOutputStream(snapshot.size * 4)
            DataOutputStream(bytes).use { output -> snapshot.forEach { output.writeFloat(it) } }
            val data = bytes.toByteArray()
            val hash = contentHash(data)
            if (hashes.add(hash)) sidecars.write(kind, data)
            planes[values] = hash
            string(hash)
        }
        fun plane(value: FrameAttachments.Plane?) = optional(value) {
            out.writeInt(it.width); out.writeInt(it.height); out.writeFloat(it.confidence); floats("plane", it.values)
        }
        fun frame(value: FrameAttachments) = with(value) {
            out.writeLong(sourceTimeUs); plane(mask); plane(depth)
            optional(flow) { out.writeInt(it.width); out.writeInt(it.height); out.writeFloat(it.confidence); floats("flow", it.vectors.toFloatArray()) }
            out.writeFloat(subjectQuality); out.writeFloat(subjectOcclusion); out.writeFloat(maskTemporalIou)
            optional(faceRegion) { with(it) { out.writeFloat(centerX); out.writeFloat(centerY); out.writeFloat(width); out.writeFloat(height); out.writeFloat(confidence) } }
            plane(maskBlendTarget); out.writeFloat(maskBlendProgress); out.writeBoolean(maskIsOpacity)
        }
    }

    private class Reader(val input: DataInputStream, val sidecars: AnalysisSidecarStore) {
        val missing = linkedSetOf<String>()
        private val arrays = mutableMapOf<String, FloatArray?>()
        private var arrayBytes = 0L
        private var itemBudget = 100_000
        fun string(): String {
            val count = input.readInt()
            require(count in 0..MAX_STRING_BYTES && count <= input.available())
            return ByteArray(count).also { input.readFully(it) }.toString(Charsets.UTF_8)
        }
        inline fun <reified E : Enum<E>> enum(): E = enumValueOf(string())
        fun <T> list(read: () -> T): List<T> {
            val count = input.readInt()
            require(count in 0..MAX_ITEMS && count <= input.available() && count <= itemBudget)
            itemBudget -= count
            return List(count) { read() }
        }
        fun sourceMap(): SourceTimeMap {
            val count = input.readInt()
            // Points have a fixed 12-byte representation. The manifest's byte limit bounds
            // their aggregate allocation; the generic object-list budget does not apply.
            require(count in 2..MAX_SOURCE_POINTS && count * 12L <= input.available()) {
                "Source map exceeds the available project bytes"
            }
            return SourceTimeMap(List(count) { SourceTimeMap.Point(input.readInt(), input.readLong()) })
        }
        fun <T> optional(read: () -> T): T? = if (input.readBoolean()) read() else null
        fun nullableLong(): Long? = optional { input.readLong() }
        fun span() = FrameSpan(input.readInt(), input.readInt())
        fun curve() = MontageGraph.SpeedRamp.CubicBezier(input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat())
        fun formatVersion(): Int = input.readInt().also {
            require(it == 1 || it == FORMAT_VERSION) { "Unsupported hybrid format $it" }
        }
        fun revision(format: Int): HybridRevision {
            val id = input.readLong(); val parent = nullableLong(); val graph = graph()
            val clips = list {
                val clip = HybridClip(string(), string(), span(), sourceMap(), clip())
                when (format) {
                    1 -> clip
                    2 -> clip.copy(originalFrameOffset = input.readInt())
                    else -> error("Unsupported hybrid format")
                }
            }
            val music = ProjectMusic(string(), input.readLong(), input.readFloat(), input.readLong(), input.readLong(), input.readBoolean())
            val texts = list { TextItem(string(), string(), span(), input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat(), input.readInt(), enum(), input.readInt()) }
            val style = ProjectStyle(string(), input.readInt(), enum(), input.readBoolean())
            val locked = list { string() }
            require(locked.distinct().size == locked.size)
            return HybridRevision(id, parent, graph, clips, music, texts, style, locked.toSet())
        }
        fun clip() = MontageGraph.Clip(string(), input.readLong(), input.readLong(), input.readLong(), enum(), enum(), enum(),
            input.readFloat(), input.readLong(),
            MontageGraph.ClipTransform(list { MontageGraph.ClipTransform.Keyframe(input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat()) }),
            MontageGraph.SpeedRamp(list { MontageGraph.SpeedRamp.Keyframe(input.readFloat(), input.readFloat(), curve()) }),
            input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat(), nullableLong(), input.readInt())
        fun graph(): MontageGraph {
            val version = input.readInt()
            require(version in 1..MontageGraph.CURRENT_VERSION)
            val source = input.readLong(); val duration = input.readLong(); val profile = enum<MontageGraph.Profile>()
            val clips = list { clip() }
            val audio = optional { MontageGraph.AudioTrack(string(), input.readFloat()) }
            val overlays = list { MontageGraph.Overlay(string(), input.readLong(), input.readLong(), string(), enum(), enum(),
                input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat(), input.readLong(), nullableLong()) }
            val metadata = NleProjectMetadata(input.readInt(), input.readLong(), nullableLong(), string())
            val tracks = list { ParameterTrack(string(), string(), enum(), list {
                ParameterTrack.Keyframe(input.readLong(), list { input.readFloat() }, enum(), curve()) }) }
            val frames = list { frame() }.filterNotNull()
            val refinements = list { frame() }.filterNotNull().filter { it.mask != null }
            val effects = GpuEffectGraph(list { GpuEffectGraph.Node(string(), enum(), input.readLong(), input.readLong(), input.readFloat(), input.readFloat()) })
            return MontageGraph(source, duration, profile, clips, audio, overlays, metadata, tracks,
                FrameAttachmentTimeline(frames, refinements), effects, version)
        }
        fun floats(expected: Long): FloatArray? {
            require(expected in 1..(AnalysisSidecarStore.MAX_BYTES / 4).toLong())
            val hash = string()
            if (!arrays.containsKey(hash)) {
                val bytes = sidecars.read(hash)
                if (bytes == null) { missing += hash; arrays[hash] = null }
                else {
                    require(bytes.size.toLong() == expected * 4)
                    arrayBytes += bytes.size
                    require(arrayBytes <= 64L * 1024 * 1024) { "Analysis allocation limit exceeded" }
                    val stream = DataInputStream(ByteArrayInputStream(bytes))
                    arrays[hash] = FloatArray(expected.toInt()) { stream.readFloat() }
                }
            }
            return arrays[hash]?.also { require(it.size.toLong() == expected) }
        }
        fun plane(): FrameAttachments.Plane? = optional {
            val width = input.readInt(); val height = input.readInt(); val confidence = input.readFloat()
            require(width > 0 && height > 0)
            floats(width.toLong() * height)?.let { FrameAttachments.Plane(width, height, it, confidence) }
        }
        fun frame(): FrameAttachments? {
            val time = input.readLong(); val mask = plane(); val depth = plane()
            val flow = optional {
                val width = input.readInt(); val height = input.readInt(); val confidence = input.readFloat()
                require(width > 0 && height > 0)
                floats(width.toLong() * height * 2)?.let { values ->
                    // A read-only view avoids a second, boxed allocation for every revision/frame.
                    val vectors = object : AbstractList<Float>() {
                        override val size: Int get() = values.size
                        override fun get(index: Int): Float = values[index]
                    }
                    FrameAttachments.FlowPlane(width, height, vectors, confidence)
                }
            }
            val quality = input.readFloat(); val occlusion = input.readFloat(); val iou = input.readFloat()
            val face = optional { FrameAttachments.FaceRegion(input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat()) }
            val target = plane(); val progress = input.readFloat(); val opacity = input.readBoolean()
            if (mask == null && depth == null && flow == null) return null
            return FrameAttachments(time, mask, depth, flow, quality, occlusion, iou, face, target, progress, opacity && mask != null)
        }
    }

    companion object {
        private const val MAGIC = 0x56485942
        private const val FORMAT_VERSION = 2
        internal const val MAX_MANIFEST_BYTES = 16 * 1024 * 1024
        internal const val MAX_SOURCE_POINTS = MAX_MANIFEST_BYTES / 12
        private const val MAX_STRING_BYTES = 1024 * 1024
        private const val MAX_ITEMS = 10_000
    }
}
