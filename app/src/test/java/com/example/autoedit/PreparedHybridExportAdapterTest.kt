package com.veycad.app

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreparedHybridExportAdapterTest {
    @get:Rule val temporary = TemporaryFolder()
    private val projects = mutableMapOf<PreparedHybridPreview, HybridProject>()

    // Rebuilding the schedule or filtering/deduplicating selected files loses this contract.
    @Test fun exportUsesPreparedPlanAndAllOrderedSlotsAtSaved30And60Fps() {
        for (fps in listOf(30, 60)) {
            val prepared = fixture(fps, temporal = true)
            val request = request(prepared)
            assertSame(prepared.graph, request.graph)
            assertSame(prepared.framePlan, request.resolvedFramePlan())
            assertEquals(fps, request.fps)
            assertEquals(listOf(0, 1, 2), prepared.sources.map { it.sourceIndex })
            assertEquals(prepared.sources.map { it.file }, request.sourceFiles)
            assertEquals(request.sourceFiles[0], request.sourceFiles[1])
            assertNotEquals(request.sourceFiles[0], request.sourceFiles[2])
            assertEquals(listOf("first", "repeat", "unused"), request.preparedExport!!.framing.sourceOrder.map { it.value })
            assertEquals(.1f, request.resolvedFramePlan().frames.first { it.clipIndex == 1 }.redBias, 0f)
            assertEquals(prepared.graph.overlays, request.graph.overlays)
            val opening = request.resolvedFramePlan().frames.first()
            assertEquals(MontageGraph.OverlayKind.DOUBLE_EXPOSURE, opening.layer.kind)
            assertEquals(1, opening.secondarySourceIndex)
            assertEquals(4_000_000L, opening.secondarySourceTimeUs)
        }
    }

    // Rotation/SAR and per-selection pan/zoom must come from prepared metadata, never file inspection.
    @Test fun manualTableKeepsDistinctSelectionsAndOrientedMetadataForAllAspects() {
        for (aspect in ProjectAspect.values()) {
            val prepared = fixture(aspect = aspect)
            val settings = ProjectVisualSettings(aspect, true, mapOf(
                FramingKey(SourceId("first"), aspect) to SourceFramingSettings(
                    manual = FramingSettings(FramingMode.MANUAL, .2f, .7f, 2f)),
                FramingKey(SourceId("repeat"), aspect) to SourceFramingSettings(
                    manual = FramingSettings(FramingMode.MANUAL, .8f, .3f, 2f))))
            val table = request(prepared, settings).preparedExport!!.framing
            // Encoded 400x200, SAR=2, rotation=90 => display aspect .25.
            val outputAspect = when (aspect) {
                ProjectAspect.PORTRAIT_9_16 -> 9f / 16f
                ProjectAspect.LANDSCAPE_16_9 -> 16f / 9f
                ProjectAspect.SQUARE_1_1 -> 1f
                ProjectAspect.FEED_4_5 -> 4f / 5f
            }
            val height = .125f / outputAspect
            assertEquals(0f, table.crop(0).sourceX(0f), .000001f)
            assertEquals(.5f, table.crop(0).sourceX(1f), .000001f)
            assertEquals(.7f - height / 2, table.crop(0).sourceY(0f), .000001f)
            assertEquals(.5f, table.crop(1).sourceX(0f), .000001f)
            assertEquals(.3f + height / 2, table.crop(1).sourceY(1f), .000001f)
            // Unused square selection still has its own geometry and default crop.
            assertEquals(.5f, table.crop(2).sourceX(.5f), .000001f)
            table.requireTarget(OutputSize(prepared.exportSettings.width, prepared.exportSettings.height))
        }
    }

    // Admission must reject stale visual identity, unsupported modes and mismatched output format.
    @Test fun invalidVisualIdentityModeAndOutputProfileRejectBeforeTouchingOutput() {
        val prepared = fixture()
        val output = temporary.newFile("keep.mp4").apply { writeText("existing output") }
        fun reject(visual: ProjectVisualSettings, project: HybridProject = projects.getValue(prepared)) {
            assertThrows(IllegalArgumentException::class.java) {
                val revision = project.current.copy(visualSettings = visual)
                PreparedHybridExportAdapter.request(prepared, project.copy(original = revision, current = revision), output)
            }
            assertEquals("existing output", output.readText())
        }
        val project = projects.getValue(prepared)
        val stale = project.current.copy(id = 2)
        reject(visual(prepared), project.copy(original = stale, current = stale, nextRevisionId = 3))
        reject(visual(prepared), project.copy(id = "other"))
        reject(ProjectVisualSettings(ProjectAspect.LANDSCAPE_16_9, true))
        for (mode in listOf(FramingMode.SMART_PERSON)) {
            reject(visual(prepared).withFraming(FramingKey(SourceId("unused"), ProjectAspect.PORTRAIT_9_16),
                SourceFramingSettings(mode = mode)))
        }
        val fourK = fixture(shortEdge = 2160)
        assertThrows(IllegalArgumentException::class.java) { request(fourK) }
    }

    @Test fun changedTextAndMusicCannotReuseAnOlderPreparedRevisionPayload() {
        val prepared = fixture()
        val project = projects.getValue(prepared)
        val revision = project.current
        val unsupported = listOf(
            revision.copy(texts = listOf(TextItem("text", "Title", FrameSpan(0, 1), .5f, .5f, .5f,
                .05f, -1, TextItem.Appearance.PLAIN, 0))),
            revision.copy(textState = HybridTextState(layers = listOf(TextLayer("title", "Title", 0, 100_000)))),
            revision.copy(textState = HybridTextState(captions = listOf(CaptionCue("cue", "Caption", 0, 100_000)))),
            revision.copy(music = revision.music.copy(startUs = 100_000)),
            revision.copy(music = revision.music.copy(gain = .5f)),
            revision.copy(music = revision.music.copy(fadeOutUs = 100_000)),
            revision.copy(music = revision.music.copy(repeat = false)))
        for (bad in unsupported) {
            assertThrows(IllegalArgumentException::class.java) {
                PreparedHybridExportAdapter.request(prepared, project.copy(original = bad, current = bad),
                    File(temporary.root, "unsupported.mp4"))
            }
        }
    }

    @Test fun freshPreparedTextAndEditedMusicAreBoundWithoutLosingTheirFields() {
        val legacy = TextItem("legacy", "Legacy", FrameSpan(1, 20), .23f, .68f, .41f, .08f,
            0xffcc99aa.toInt(), TextItem.Appearance.ACCENT, 2)
        val prepared = fixture(revisionChange = { revision -> revision.copy(texts = listOf(legacy),
            textState = HybridTextState(layers = listOf(TextLayer("title", "Заголовок", 0, 100_000)),
                captions = listOf(CaptionCue("cue", "Речь", 100_000, 200_000))),
            music = revision.music.copy(startUs = 100_000, gain = .5f, fadeInUs = 20_000, fadeOutUs = 50_000, repeat = false)) })
        val request = request(prepared)
        assertSame(prepared.renderText, request.textProject)
        assertEquals(listOf(legacy), request.textProject!!.legacyItems)
        assertEquals("Заголовок", request.textProject!!.layers.single().text)
        assertEquals("Речь", request.textProject!!.captions.single().text)
        assertEquals(MediaCodecSpeedRampRenderer.TextBehavior.MONTAGE_OVERLAY, request.textBehavior)
        assertEquals(100_000L, prepared.audio.music.startUs)
        assertEquals(.5f, prepared.audio.music.gain, 0f)
        assertFalse(prepared.audio.music.repeat)
        val visual = visual(prepared).withFraming(FramingKey(SourceId("unused"), ProjectAspect.PORTRAIT_9_16),
            SourceFramingSettings(mode = FramingMode.BLURRED_FIT))
        assertNotNull(request(prepared, visual).preparedExport!!.framing.sample(2, 0).backgroundSource)
    }

    // An internal binding cannot be reused with a different graph/FPS/dimensions/source ordering.
    @Test fun mismatchedRequestAndMalformedFrameBindingsAreRejected() {
        val prepared = fixture()
        val original = request(prepared)
        val binding = original.preparedExport!!
        fun altered(graph: MontageGraph = prepared.graph, fps: Int = 30, width: Int = 720,
            files: List<File> = prepared.sources.map { it.file }) = MediaCodecSpeedRampRenderer.Request(
            graph = graph, sourceFiles = files, outputFile = original.outputFile,
            width = width, height = 1280, fps = fps, bitrate = 5_000_000)
        for (bad in listOf(altered(graph = prepared.graph.copy()), altered(fps = 60),
            altered(width = 1080), altered(files = original.sourceFiles.reversed()))) {
            assertThrows(IllegalArgumentException::class.java) { binding.validate(bad) }
        }
        val plan = prepared.framePlan
        fun reject(frame: HighQualityFramePlan.Frame) {
            assertThrows(IllegalArgumentException::class.java) {
                PreparedHybridExportBinding.requirePlanBindings(prepared.graph,
                    plan.copy(frames = listOf(frame) + plan.frames.drop(1)), prepared.sources)
            }
        }
        reject(plan.frames.first().copy(sourceIndex = 2))
        reject(plan.frames.first().copy(secondarySourceIndex = 20))
        reject(plan.frames.first().copy(outputTimeUs = 1))
        assertThrows(IllegalArgumentException::class.java) {
            PreparedHybridExportBinding.requirePlanBindings(prepared.graph, plan.copy(fps = 60), prepared.sources)
        }
    }

    @Test fun cancelledAdmissionLeavesOutputAndSourcesUntouched() {
        val prepared = fixture()
        val output = temporary.newFile("cancelled.mp4").apply { writeText("keep") }
        assertThrows(IllegalStateException::class.java) {
            PreparedHybridExportAdapter.request(prepared, projects.getValue(prepared), output,
                checkCancelled = { error("cancelled") })
        }
        assertEquals("keep", output.readText())
        assertTrue(prepared.sources.all { it.file.isFile })
    }

    // Both output paths are deleted by the native exporter; neither may alias retained inputs.
    @Test fun outputAndVideoIntermediateCannotReplaceSourceOrLocalMusic() {
        val prepared = fixture()
        val source = prepared.sources.first().file
        val originalBytes = source.readBytes()
        assertThrows(IllegalArgumentException::class.java) {
            PreparedHybridExportAdapter.request(prepared, projects.getValue(prepared),
                File(source.parentFile, "./${source.name}"))
        }
        assertArrayEquals(originalBytes, source.readBytes())
        val music = temporary.newFile("export.video.mp4").apply { writeText("retained music") }
        val output = File(temporary.root, "export.mp4")
        assertThrows(IllegalArgumentException::class.java) {
            PreparedHybridExportAdapter.request(prepared, projects.getValue(prepared), output, audioFile = music)
        }
        assertEquals("retained music", music.readText())
        assertFalse(output.exists())
    }

    private fun visual(prepared: PreparedHybridPreview) = projects.getValue(prepared).current.visualSettings
    private fun request(prepared: PreparedHybridPreview, visual: ProjectVisualSettings = visual(prepared)):
        MediaCodecSpeedRampRenderer.Request {
        val project = projects.getValue(prepared)
        val revision = project.current.copy(visualSettings = visual)
        return PreparedHybridExportAdapter.request(prepared, project.copy(original = revision, current = revision),
            File(temporary.root, "export.mp4"))
    }

    private fun fixture(fps: Int = 30, aspect: ProjectAspect = ProjectAspect.PORTRAIT_9_16,
        shortEdge: Int = 720, temporal: Boolean = false,
        revisionChange: (HybridRevision) -> HybridRevision = { it }): PreparedHybridPreview {
        val base = ManualMontageFixtures.linearProject(fps)
        val store = ProjectAssetStore(temporary.newFolder())
        fun imported(geometry: SourceGeometry, bytes: String): ProjectAsset {
            val file = temporary.newFile().apply { writeText(bytes) }
            return store.import(file, ProjectAsset.Kind.VIDEO, 10_000_000).copy(videoMetadata =
                VideoSourceMetadata(geometry, file.length(), "video/avc", null, false, 0, 10_000_000))
        }
        val first = imported(SourceGeometry(400, 200, 90, 2f), "first fixture")
        val unused = imported(SourceGeometry(200, 200, 0, 1f), "unused fixture")
        val musicFile = temporary.newFile().apply { writeText("owned music fixture") }
        val music = store.import(musicFile, ProjectAsset.Kind.AUDIO, base.music.durationUs).copy(id = base.music.id)
        val selections = listOf(SelectedVideo(SourceId("first"), first.id, SourceOwnership.IMPORTED, displayName = "First"),
            SelectedVideo(SourceId("repeat"), first.id, SourceOwnership.IMPORTED, displayName = "Repeat"),
            SelectedVideo(SourceId("unused"), unused.id, SourceOwnership.IMPORTED, displayName = "Unused"))
        val revision = base.shared.current.copy(clips = base.shared.current.clips.map { it.copy(assetId = first.id) },
            visualSettings = ProjectVisualSettings(aspect, true), graph = base.shared.current.graph.let { graph ->
                if (!temporal) graph else graph.copy(overlays = graph.overlays + MontageGraph.Overlay(
                    "temporal", 0, 1000, "temporal", overlayKind = MontageGraph.OverlayKind.DOUBLE_EXPOSURE,
                    secondaryTimelineStartMs = 2000))
            }).let(revisionChange)
        val project = base.shared.copy(assets = listOf(first, unused, music), original = revision,
            current = revision, selectedVideos = selections)
        val size = aspect.size(shortEdge)
        return HybridPreviewPreparation.prepare(project, base.export.copy(width = size.width, height = size.height), store)
            .also { projects[it] = project }
    }
}
