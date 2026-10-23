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

/** Primitive format: v5 source facts; v6 manual graph; v7 music offset; v8 rich text. Schema stays 1. */
class HybridProjectCodec(private val sidecars: AnalysisSidecarStore,
    private val authoredMaps: AuthoredSourceMapStore = sidecars.authoredMaps()) {
    fun encode(project: HybridProject): ByteArray {
        val bytes = ManifestOutput()
        Writer(DataOutputStream(bytes), sidecars, authoredMaps.writer()).apply {
            out.writeInt(MAGIC); out.writeInt(FORMAT_VERSION)
            string(project.id); out.writeInt(project.fps); out.writeLong(project.nextRevisionId)
            list(project.assets) {
                string(it.id); string(it.fileName); enum(it.kind); out.writeLong(it.durationUs)
                string(it.contentHash); string(it.displayName)
                optional(it.videoMetadata) { metadata -> videoMetadata(metadata) }
            }
            optional(project.selectedVideos) { list(it) { selection -> selectedVideo(selection) } }
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
        val maps = authoredMaps.writer()
        return revisions.associate { revision ->
            val bytes = ManifestOutput()
            val output = DataOutputStream(bytes)
            output.writeInt(MAGIC); output.writeInt(FORMAT_VERSION)
            Writer(output, sidecars, maps, planes, hashes).revision(revision)
            require(bytes.size() <= MAX_MANIFEST_BYTES)
            revision.id to bytes.toByteArray()
        }
    }

    internal fun decodeRevision(bytes: ByteArray): HybridRevision = decodePayload(bytes) { reader, format ->
        reader.revision(format).also { revision ->
            require(reader.input.available() == 0)
            require(revision.sourceBackings.keys.all { id -> revision.clips.any { it.id == id } })
            val budget = HybridSourceWindow.ComparisonBudget()
            revision.clips.forEach { clip -> revision.sourceBackings[clip.id]?.validate(clip, budget) }
        }
    }

    fun decodeWithAnalysisStatus(bytes: ByteArray): ProjectLoadResult = decodePayload(bytes) { reader, format ->
        with(reader) {
            val id = string(); val fps = input.readInt(); val next = input.readLong()
            val assets = list {
                val asset = ProjectAsset(string(), string(), enum(), input.readLong(), string(), string())
                if (format >= 4) asset.copy(videoMetadata = optional { videoMetadata(format) }) else asset
            }
            val selections = if (format >= 4) optional { list { selectedVideo() } } else null
            val revisions = list { revision(format) }
            require(revisions.map { it.id }.distinct().size == revisions.size)
            val canonical = revisions.associateBy { it.id }
            fun reference(): HybridRevision = requireNotNull(canonical[input.readLong()]) { "Unknown revision reference" }
            val original = reference(); val current = reference()
            val undo = list { reference() }; val redo = list { reference() }
            val exports = list { ProjectExportRef(input.readLong(), string(), ProjectExportSettings(
                input.readInt(), input.readInt(), input.readInt(), input.readInt(), input.readInt())) }
            require(input.available() == 0) { "Trailing project data" }
            ProjectLoadResult(HybridProject(id, 1, fps, next, assets, original, current, undo, redo, exports, selections), missing)
        }
    }

    private fun <T> decodePayload(bytes: ByteArray, read: (Reader, Int) -> T): T {
        require(bytes.size in 8..MAX_MANIFEST_BYTES)
        val header = DataInputStream(ByteArrayInputStream(bytes))
        require(header.readInt() == MAGIC) { "Not a hybrid project" }
        val format = header.readInt()
        require(format in 1..FORMAT_VERSION) { "Unsupported hybrid format $format" }
        fun parse(richTextV7: Boolean): T {
            val input = DataInputStream(ByteArrayInputStream(bytes, 8, bytes.size - 8))
            return read(Reader(input, sidecars, authoredMaps.reader(), richTextV7), format)
        }
        if (format != 7) return parse(false)

        // Two published writers used v7. Validate each whole payload; never guess from a field.
        val valid = mutableListOf<T>()
        val failures = mutableListOf<Exception>()
        for (richText in listOf(false, true)) {
            try { valid += parse(richText) } catch (failure: Exception) { failures += failure }
        }
        if (valid.size != 1) {
            throw IllegalArgumentException(if (valid.isEmpty()) "Invalid hybrid v7 payload" else "Ambiguous hybrid v7 payload")
                .also { error -> failures.forEach(error::addSuppressed) }
        }
        return valid.single()
    }

    /** A whole manifest/revision budget, never reset between lists or history entries. */
    private class ItemBudget {
        private var remaining = MAX_TOTAL_ITEMS

        fun consume(count: Int) {
            require(count in 0..MAX_ITEMS) { "Hybrid list exceeds $MAX_ITEMS entries" }
            require(count <= remaining) {
                "Hybrid payload exceeds $MAX_TOTAL_ITEMS generic collection entries across all revisions"
            }
            remaining -= count
        }
    }

    /** Both project and standalone revision output reject before an append/allocation. */
    private class ManifestOutput : ByteArrayOutputStream() {
        private fun prepare(length: Int) {
            require(length.toLong() <= MAX_MANIFEST_BYTES.toLong() - count) {
                "Hybrid output exceeds the manifest byte budget"
            }
            val needed = count + length
            if (needed > buf.size) {
                buf = buf.copyOf(minOf(MAX_MANIFEST_BYTES, maxOf(needed, buf.size * 2)))
            }
        }

        override fun write(value: Int) {
            prepare(1)
            buf[count++] = value.toByte()
        }

        override fun write(values: ByteArray, offset: Int, length: Int) {
            require(offset in 0..values.size && length in 0..(values.size - offset))
            prepare(length)
            System.arraycopy(values, offset, buf, count, length)
            count += length
        }
    }

    private class Writer(val out: DataOutputStream, val sidecars: AnalysisSidecarStore,
        val maps: AuthoredSourceMapStore.Writer,
        val planes: IdentityHashMap<FloatArray, String> = IdentityHashMap(),
        val hashes: MutableSet<String> = hashSetOf()) {
        private val itemBudget = ItemBudget()
        fun string(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_STRING_BYTES)
            out.writeInt(bytes.size); out.write(bytes)
        }
        fun enum(value: Enum<*>) = string(value.name)
        fun <T> list(values: Collection<T>, write: (T) -> Unit) {
            itemBudget.consume(values.size); out.writeInt(values.size); values.forEach(write)
        }
        fun sourceMap(value: SourceTimeMap) {
            string(maps.reference(value))
        }
        fun <T : Any> optional(value: T?, write: (T) -> Unit) { out.writeBoolean(value != null); if (value != null) write(value) }
        fun nullableLong(value: Long?) = optional(value) { out.writeLong(it) }
        fun span(value: FrameSpan) { out.writeInt(value.start); out.writeInt(value.endExclusive) }
        fun curve(value: MontageGraph.SpeedRamp.CubicBezier) { with(value) { out.writeFloat(x1); out.writeFloat(y1); out.writeFloat(x2); out.writeFloat(y2) } }
        fun videoMetadata(value: VideoSourceMetadata) = with(value) {
            with(geometry) { out.writeInt(encodedWidth); out.writeInt(encodedHeight); out.writeInt(rotation); out.writeFloat(pixelAspectRatio) }
            out.writeLong(sizeBytes); string(mime); optional(colorTransfer) { out.writeInt(it) }; out.writeBoolean(hasAudio)
            nullableLong(firstVideoPtsUs); nullableLong(videoEndPtsUs)
        }
        fun selectedVideo(value: SelectedVideo) = with(value) {
            string(id.value); string(assetId); enum(ownership)
            optional(captureOrigin) { string(it.sessionId); out.writeInt(it.takeOrdinal); out.writeBoolean(it.recommended) }
            string(displayName)
        }
        fun framing(value: FramingSettings) = with(value) {
            enum(mode); out.writeFloat(centerX); out.writeFloat(centerY); out.writeFloat(zoom)
        }
        fun visuals(value: ProjectVisualSettings) = with(value) {
            enum(aspect); out.writeBoolean(explicitlySelected)
            list(framings.entries) { (key, settings) ->
                string(key.sourceId.value); enum(key.aspect); enum(settings.mode)
                framing(settings.manual); framing(settings.smartPerson); framing(settings.blurredFit)
            }
        }
        fun textStyle(value: TextStyle) = with(value) {
            enum(position); enum(font); out.writeFloat(sizeRatio); out.writeInt(color); out.writeBoolean(darkPlate); enum(animation)
            optional(plate) { enum(it) }
        }
        fun textState(value: HybridTextState) = with(value) {
            list(layers) {
                string(it.id); string(it.text); out.writeLong(it.startUs); out.writeLong(it.endUs); textStyle(it.style); enum(it.kind)
            }
            list(captions) {
                string(it.id); string(it.text); out.writeLong(it.startUs); out.writeLong(it.endUs)
                optional(it.confidence) { confidence -> out.writeDouble(confidence) }
                out.writeBoolean(it.manuallyReviewed); out.writeBoolean(it.confidenceCalibrated)
            }
            textStyle(captionStyle); out.writeBoolean(captionsEdited); string(language)
        }
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
            out.writeBoolean(restoresAutomaticSources)
            visuals(visualSettings); textState(textState)
            list(sourceBackings.entries) { (id, backing) ->
                string(id); string(backing.assetId); sourceMap(backing.map); out.writeInt(backing.phaseStart)
                out.writeLong(backing.slipUs); out.writeLong(backing.selectedRevisionId); out.writeBoolean(backing.automatic)
            }
        }
        fun clip(value: MontageGraph.Clip) = with(value) {
            string(id); out.writeLong(sourceStartMs); out.writeLong(sourceEndMs); out.writeLong(outputDurationMs)
            enum(role); enum(transitionIn); enum(motion); out.writeFloat(confidence); out.writeLong(beatAnchorMs)
            list(transform.keyframes) { with(it) { out.writeFloat(at); out.writeFloat(scale); out.writeFloat(translateX); out.writeFloat(translateY); out.writeFloat(rotationDegrees) } }
            list(speedRamp.keyframes) { out.writeFloat(it.at); out.writeFloat(it.speed); curve(it.curveToNext) }
            out.writeFloat(exposureBias); out.writeFloat(redBias); out.writeFloat(greenBias); out.writeFloat(blueBias)
            out.writeFloat(flowStrength); nullableLong(transitionDurationMs); out.writeInt(sourceIndex)
        }
        fun track(value: ParameterTrack) = with(value) {
            string(id); string(target); enum(type)
            list(keyframes) { out.writeLong(it.timeUs); list(it.value) { v -> out.writeFloat(v) }; enum(it.interpolation); curve(it.curveToNext) }
        }
        fun sampleWindow(value: EffectSampleWindow) = with(value) {
            out.writeLong(startUs); out.writeLong(endUs); out.writeLong(offsetUs)
            out.writeLong(authoredStartUs); out.writeLong(authoredEndUs)
            nullableLong(frameShift); optional(fps) { out.writeInt(it) }
        }
        fun effectPhase(originId: String, start: Float, end: Float, window: EffectSampleWindow?) {
            string(originId); out.writeFloat(start); out.writeFloat(end); optional(window) { sampleWindow(it) }
        }
        fun overlay(value: MontageGraph.Overlay) = with(value) {
            string(id); out.writeLong(startMs); out.writeLong(endMs); string(kind); enum(blendMode); enum(overlayKind)
            out.writeFloat(opacity); out.writeFloat(red); out.writeFloat(green); out.writeFloat(blue)
            out.writeLong(secondarySourceOffsetMs); nullableLong(secondaryTimelineStartMs)
            effectPhase(originId, phaseStart, phaseEnd, sampleWindow)
        }
        fun node(value: GpuEffectGraph.Node) = with(value) {
            string(id); enum(kind); out.writeLong(startUs); out.writeLong(endUs); out.writeFloat(amount); out.writeFloat(secondary)
            effectPhase(originId, phaseStart, phaseEnd, sampleWindow)
        }
        fun range(value: FrameRange) { out.writeLong(value.start); out.writeLong(value.endExclusive) }
        fun phase(value: ClipPhase) { out.writeLong(value.firstFrame); out.writeLong(value.startUs); out.writeLong(value.durationUs) }
        fun anchor(value: EffectAnchor) {
            when (value) {
                is EffectAnchor.Clip -> { string("clip"); string(value.clipId); out.writeLong(value.localStartUs); out.writeLong(value.localEndUs) }
                is EffectAnchor.Boundary -> { string("boundary"); string(value.incomingClipId); out.writeLong(value.offsetUs); out.writeLong(value.durationUs) }
                is EffectAnchor.Music -> { string("music"); out.writeLong(value.startUs); out.writeLong(value.endUs) }
            }
        }
        fun manualState(value: ManualMontageState) = with(value) {
            list(clips) {
                string(it.clipId); range(it.visible); out.writeLong(it.originFrameCount)
                list(it.localTracks) { track(it) }; optional(it.phase) { phase(it) }
            }
            list(effects) {
                string(it.id); string(it.logicalId); string(it.originId); anchor(it.anchor); out.writeBoolean(it.enabled)
                optional(it.originalOverlay) { overlay(it) }; optional(it.originalNode) { node(it) }
                out.writeFloat(it.phaseStart); out.writeFloat(it.phaseEnd)
            }
        }
        fun timeMapping(value: ClipTimeMapping) = with(value) {
            // Dense compiler samples are exact; no interpolation/rebasing at persistence.
            require(frameCount in 1 until MAX_SOURCE_POINTS.toLong())
            // Include the entire fixed header + dense block before obtaining its copy.
            // out.size includes all preceding graph/history bytes in either encoding entry point.
            val requiredBytes = 24L + (frameCount + 1L) * 8L
            require(requiredBytes <= MAX_MANIFEST_BYTES.toLong() - out.size().toLong()) {
                "Dense timing exceeds the remaining manifest byte budget"
            }
            out.writeInt(fps); out.writeLong(sourceDurationUs); out.writeLong(frameOrigin)
            val samples = sourceUsByFrame
            out.writeInt(samples.size); samples.forEach { out.writeLong(it) }
        }
        fun editableTiming(value: EditableFrameTiming) = with(value) {
            out.writeInt(fps)
            list(clips) {
                string(it.clipId); timeMapping(it.timeMap); range(it.visible)
                out.writeLong(it.startFrame); out.writeLong(it.endFrameExclusive); out.writeLong(it.originFrameCount)
                optional(it.phase) { phase(it) }; list(it.localTracks) { track(it) }; nullableLong(it.transitionPhaseDurationUs)
            }
        }
        fun timeline(value: FrameAttachmentTimeline) = with(value) {
            list(frames) { frame(it) }; list(maskRefinements) { frame(it) }
        }
        fun graph(value: MontageGraph) = with(value) {
            out.writeInt(version); out.writeLong(sourceDurationMs); out.writeLong(outputDurationMs); enum(profile)
            list(clips) { clip(it) }
            optional(audioTrack) { string(it.sourceId); out.writeFloat(it.gain); out.writeLong(it.sourceStartUs) }
            list(overlays) { overlay(it) }
            with(metadata) { out.writeInt(schemaVersion); out.writeLong(revision); nullableLong(parentRevision); string(generator) }
            list(parameterTracks) { track(it) }
            timeline(frameAttachments)
            list(effectGraph.nodes) { node(it) }
            optional(editableTiming) { editableTiming(it) }
            optional(manualMontageState) { manualState(it) }
            list(sourceAttachments) { out.writeInt(it.sourceIndex); timeline(it.timeline) }
            optional(manualOverrides) {
                list(it.disabledTransitionClipIds) { string(it) }; list(it.disabledEffectIds) { string(it) }
            }
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

    private class Reader(val input: DataInputStream, val sidecars: AnalysisSidecarStore,
        val maps: AuthoredSourceMapStore.Reader, val richTextV7: Boolean) {
        val missing = linkedSetOf<String>()
        private val arrays = mutableMapOf<String, FloatArray?>()
        private var arrayBytes = 0L
        private val itemBudget = ItemBudget()
        fun string(): String {
            val count = input.readInt()
            require(count in 0..MAX_STRING_BYTES && count <= input.available())
            return ByteArray(count).also { input.readFully(it) }.toString(Charsets.UTF_8)
        }
        inline fun <reified E : Enum<E>> enum(): E = enumValueOf(string())
        fun <T> list(read: () -> T): List<T> {
            val count = input.readInt()
            itemBudget.consume(count)
            require(count <= input.available())
            return List(count) { read() }
        }
        fun sourceMap(format: Int): SourceTimeMap {
            if (format >= 5) return maps.resolve(string())
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
        fun videoMetadata(format: Int) = VideoSourceMetadata(SourceGeometry(input.readInt(), input.readInt(), input.readInt(), input.readFloat()),
            input.readLong(), string(), optional { input.readInt() }, input.readBoolean(),
            if (format >= 5) nullableLong() else null, if (format >= 5) nullableLong() else null)
        fun selectedVideo() = SelectedVideo(SourceId(string()), string(), enum(),
            optional { CaptureOrigin(string(), input.readInt(), input.readBoolean()) }, string())
        fun framing() = FramingSettings(enum(), input.readFloat(), input.readFloat(), input.readFloat())
        fun visuals(): ProjectVisualSettings {
            val aspect = enum<ProjectAspect>(); val explicit = input.readBoolean()
            val framings = list { FramingKey(SourceId(string()), enum()) to
                SourceFramingSettings(enum(), framing(), framing(), framing()) }
            require(framings.map { it.first }.distinct().size == framings.size)
            return ProjectVisualSettings(aspect, explicit, framings.toMap())
        }
        fun textStyle(format: Int) = TextStyle(enum(), enum(), input.readFloat(), input.readInt(), input.readBoolean(), enum(),
            if (format >= 8 || format == 7 && richTextV7) optional { enum<TextPlate>() } else null)
        fun textState(format: Int) = HybridTextState(
            list { TextLayer(string(), string(), input.readLong(), input.readLong(), textStyle(format),
                if (format >= 8 || format == 7 && richTextV7) enum<TextLayerKind>() else TextLayerKind.LEGACY) },
            list { CaptionCue(string(), string(), input.readLong(), input.readLong(),
                if (format >= 8 || format == 7 && richTextV7) optional { input.readDouble() } else null,
                if (format >= 8 || format == 7 && richTextV7) input.readBoolean() else false,
                if (format >= 8 || format == 7 && richTextV7) input.readBoolean() else false) },
            textStyle(format), input.readBoolean(), string())
        fun revision(format: Int): HybridRevision {
            val id = input.readLong(); val parent = nullableLong(); val graph = graph(format)
            val clips = list {
                val clip = HybridClip(string(), string(), span(), sourceMap(format), clip())
                when (format) {
                    1 -> clip
                    in 2..FORMAT_VERSION -> clip.copy(originalFrameOffset = input.readInt())
                    else -> error("Unsupported hybrid format")
                }
            }
            val music = ProjectMusic(string(), input.readLong(), input.readFloat(), input.readLong(), input.readLong(), input.readBoolean())
            val texts = list { TextItem(string(), string(), span(), input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat(), input.readInt(), enum(), input.readInt()) }
            val style = ProjectStyle(string(), input.readInt(), enum(), input.readBoolean())
            val locked = list { string() }
            require(locked.distinct().size == locked.size)
            val resetsSources = when (format) {
                1, 2 -> false
                in 3..FORMAT_VERSION -> input.readBoolean()
                else -> error("Unsupported hybrid format")
            }
            val visuals = if (format >= 4) visuals() else ProjectVisualSettings(ProjectAspect.PORTRAIT_9_16, false)
            val text = if (format >= 4) textState(format) else HybridTextState()
            val backings = if (format >= 5) list {
                string() to HybridSourceBacking(string(), sourceMap(format), input.readInt(), input.readLong(), input.readLong(), input.readBoolean())
            } else emptyList()
            require(backings.map { it.first }.distinct().size == backings.size)
            return HybridRevision(id, parent, graph, clips, music, texts, style, locked.toSet(), resetsSources, visuals, text, backings.toMap())
        }
        fun clip() = MontageGraph.Clip(string(), input.readLong(), input.readLong(), input.readLong(), enum(), enum(), enum(),
            input.readFloat(), input.readLong(),
            MontageGraph.ClipTransform(list { MontageGraph.ClipTransform.Keyframe(input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat()) }),
            MontageGraph.SpeedRamp(list { MontageGraph.SpeedRamp.Keyframe(input.readFloat(), input.readFloat(), curve()) }),
            input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat(), nullableLong(), input.readInt())
        fun track() = ParameterTrack(string(), string(), enum(), list {
            ParameterTrack.Keyframe(input.readLong(), list { input.readFloat() }, enum(), curve()) })
        fun sampleWindow() = EffectSampleWindow(input.readLong(), input.readLong(), input.readLong(),
            input.readLong(), input.readLong(), nullableLong(), optional { input.readInt() })
        fun overlay(format: Int): MontageGraph.Overlay {
            val value = MontageGraph.Overlay(string(), input.readLong(), input.readLong(), string(), enum(), enum(),
                input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat(), input.readLong(), nullableLong())
            return if (format >= 6) value.copy(originId = string(), phaseStart = input.readFloat(), phaseEnd = input.readFloat(),
                sampleWindow = optional { sampleWindow() }) else value
        }
        fun node(format: Int): GpuEffectGraph.Node {
            val value = GpuEffectGraph.Node(string(), enum(), input.readLong(), input.readLong(), input.readFloat(), input.readFloat())
            return if (format >= 6) value.copy(originId = string(), phaseStart = input.readFloat(), phaseEnd = input.readFloat(),
                sampleWindow = optional { sampleWindow() }) else value
        }
        fun range() = FrameRange(input.readLong(), input.readLong())
        fun phase() = ClipPhase(input.readLong(), input.readLong(), input.readLong())
        fun anchor(): EffectAnchor = when (val kind = string()) {
            "clip" -> EffectAnchor.Clip(string(), input.readLong(), input.readLong())
            "boundary" -> EffectAnchor.Boundary(string(), input.readLong(), input.readLong())
            "music" -> EffectAnchor.Music(input.readLong(), input.readLong())
            else -> throw IllegalArgumentException("Unknown manual effect anchor $kind")
        }
        fun manualState(format: Int) = ManualMontageState(
            list { ManualClipState(string(), range(), input.readLong(), list { track() }, optional { phase() }) },
            list { AnchoredMontageEffect(string(), string(), string(), anchor(), input.readBoolean(),
                optional { overlay(format) }, optional { node(format) }, input.readFloat(), input.readFloat()) })
        fun timeMapping(): ClipTimeMapping {
            val fps = input.readInt(); val duration = input.readLong(); val origin = input.readLong()
            val count = input.readInt()
            // Inline fixed-width samples share the manifest's total byte bound, not the
            // generic object-list budget. Check before either dense-array allocation.
            require(count in 2..MAX_SOURCE_POINTS && count * 8L <= input.available())
            return ClipTimeMapping(fps, duration, LongArray(count) { input.readLong() }, origin)
        }
        fun editableTiming() = EditableFrameTiming(input.readInt(), list {
            EditableClipTiming(string(), timeMapping(), range(), input.readLong(), input.readLong(), input.readLong(),
                optional { phase() }, list { track() }, nullableLong()) })
        fun timeline(): FrameAttachmentTimeline {
            val frames = list { frame() }.filterNotNull()
            val refinements = list { frame() }.filterNotNull().filter { it.mask != null }
            return FrameAttachmentTimeline(frames, refinements)
        }
        fun stringSet(): Set<String> = list { string() }.also { require(it.distinct().size == it.size) }.toSet()
        fun graph(format: Int): MontageGraph {
            val version = input.readInt()
            require(version in 1..MontageGraph.CURRENT_VERSION)
            val source = input.readLong(); val duration = input.readLong(); val profile = enum<MontageGraph.Profile>()
            val clips = list { clip() }
            val audio = optional { MontageGraph.AudioTrack(string(), input.readFloat(),
                if (format >= 8 || format == 7 && !richTextV7) input.readLong() else 0L) }
            val overlays = list { overlay(format) }
            val metadata = NleProjectMetadata(input.readInt(), input.readLong(), nullableLong(), string())
            val tracks = list { track() }
            val attachments = timeline()
            val effects = GpuEffectGraph(list { node(format) })
            val timing = if (format >= 6) optional { editableTiming() } else null
            val manual = if (format >= 6) optional { manualState(format) } else null
            val sources = if (format >= 6) list { SourceAttachments(input.readInt(), timeline()) } else emptyList()
            val overrides = if (format >= 6) optional { ManualRenderOverrides(stringSet(), stringSet()) } else null
            return MontageGraph(source, duration, profile, clips, audio, overlays, metadata, tracks,
                attachments, effects, version, timing, manual, sources, overrides)
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
        private const val FORMAT_VERSION = 8
        internal const val MAX_MANIFEST_BYTES = 16 * 1024 * 1024
        internal const val MAX_SOURCE_POINTS = MAX_MANIFEST_BYTES / 12
        private const val MAX_STRING_BYTES = 1024 * 1024
        private const val MAX_ITEMS = 10_000
        // 51 revisions with 2,000 layers + 2,000 cues need ~204k entries plus graph
        // and reference lists. Keep a fixed allocation/work ceiling above that case;
        // byte/string/sidecar bounds and the separate source-point budget still apply.
        private const val MAX_TOTAL_ITEMS = 256 * 1024
    }
}
