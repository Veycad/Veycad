package com.veycad.app

import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreparedHybridExportDeviceTest {
    // Losing the MANUAL table yields a dark quarter pixel; rescheduling changes encoded PTS/count.
    @Test fun preparedManualPixelsAndEncodedTimingMatchKnownSplitSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "prepared-manual-export").apply { mkdirs() }
        val source = SyntheticVideo.create(File(directory, "split.mp4"), 1000, 320, 160,
            lumaAt = { x, _, _ -> if (x < 160) 40 else 160 })
        val inspected = MediaInputInspector().inspect("selection", source, "Split")
        val store = ProjectAssetStore(File(directory, "project"))
        val asset = store.importVideo(inspected, 1f)
        val selection = SelectedVideo.fromInspected(inspected, asset, SourceOwnership.IMPORTED)
        val graph = MontageGraph(1000, 1000, clips = listOf(MontageGraph.Clip("clip", 0, 1000, 1000,
            MontageGraph.ShotRole.ACTION, MontageGraph.Transition.HARD_CUT, MontageGraph.Motion.HOLD, 1f, 0)),
            metadata = NleProjectMetadata(generator = "prepared-manual-fixture"))
        val export = ProjectExportSettings(720, 720, 30, 2_000_000, 128_000)
        val music = ProjectAsset("silent-fixture", "unused.wav", ProjectAsset.Kind.AUDIO, 1_000_000, "fixture")
        val imported = EditableMontageImporter.create("native-prepared", graph, MontageStyleCatalog.Recipe.DUALITY_LOOP,
            export, listOf(asset), music, null, listOf(selection)).shared
        val visual = ProjectVisualSettings(ProjectAspect.SQUARE_1_1, true, mapOf(
            FramingKey(selection.id, ProjectAspect.SQUARE_1_1) to SourceFramingSettings(
                manual = FramingSettings(FramingMode.MANUAL, .75f, .5f, 1f))))
        val revision = imported.current.copy(visualSettings = visual)
        val project = imported.copy(original = revision, current = revision)
        val prepared = HybridPreviewPreparation.prepare(project, export, store)
        val output = File(directory, "manual.mp4")
        val request = PreparedHybridExportAdapter.request(prepared, project, output, context = context)
        assertSame(prepared.framePlan, request.resolvedFramePlan())
        assertEquals(30, MediaCodecSpeedRampRenderer.render(request))
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(output.path)
            val video = (0 until extractor.trackCount).single {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            assertEquals(720, extractor.getTrackFormat(video).getInteger(MediaFormat.KEY_WIDTH))
            assertEquals(720, extractor.getTrackFormat(video).getInteger(MediaFormat.KEY_HEIGHT))
            extractor.selectTrack(video)
            val times = mutableListOf<Long>()
            while (extractor.sampleTime >= 0) { times += extractor.sampleTime; extractor.advance() }
            assertEquals(30, times.size)
            for (index in times.indices) assertEquals(kotlin.math.round(index * 1_000_000.0 / 30).toLong(), times[index])
        } finally { extractor.release() }
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(output.path)
            val pixels = requireNotNull(retriever.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST))
            try {
                assertTrue("MANUAL right half must be bright", Color.red(pixels.getPixel(pixels.width / 4, pixels.height / 2)) > 120)
                assertTrue("Crop must fill output", Color.red(pixels.getPixel(pixels.width * 3 / 4, pixels.height / 2)) > 120)
            } finally { pixels.recycle() }
        }
        // Cancel a second operation after admission, while the native renderer owns partial output.
        val cancelled = File(directory, "cancelled.mp4")
        var armed = false
        var checks = 0
        val cancelledRequest = PreparedHybridExportAdapter.request(prepared, project, cancelled,
            checkCancelled = { if (armed && ++checks >= 20) error("cancelled native fixture") })
        armed = true
        assertThrows(IllegalStateException::class.java) { MediaCodecSpeedRampRenderer.render(cancelledRequest) }
        assertFalse(cancelled.exists())
        assertFalse(File(directory, "cancelled.video.mp4").exists())
        assertTrue(prepared.sources.all { it.file.isFile })
    }
}
