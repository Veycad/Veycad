package com.veycad.app

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Written T6A GPU regressions. Execution is queued; these are not runtime evidence. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28) // Exact decoded frame indices, without guessed encoder PTS.
class MontageTextRoutingDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun rawOffsetSourceSelectionAndLegacyFinishingRemainDifferentWithText() {
        val directory = File(context.cacheDir, "t6a-offset-routing").apply { mkdirs() }
        val seed = SyntheticVideo.create(File(directory, "seed.mp4"), 2000, lumaBase = 80)
        val source = SourceAudioNativeFixtures.remux(File(directory, "offset.mp4"), listOf(seed),
            shiftVideoUs = 400_000, videoEndUs = 2_000_000)
        val inspected = MediaInputInspector().inspect("offset", source, "Offset")
        val packetPts = videoPts(source)
        assertTrue("Fixture must actually retain a nonzero raw origin", inspected.firstVideoPtsUs > 0)
        assertEquals(inspected.firstVideoPtsUs, packetPts.first())
        assertTrue("Use independent presentation proof, not declared duration",
            inspected.videoEndPtsUs > packetPts.last())
        assertEquals(0L, inspected.firstVideoPtsUs % 1000)
        val originMs = inspected.firstVideoPtsUs / 1000
        assertEquals(0L, videoPts(seed).first())
        // Selection order is [zero-origin, offset], while the montage starts at source 1.
        val graph = MontageGraph(inspected.durationUs / 1000, 2000, clips = listOf(
            clip(originMs, originMs + 1000, 1000).copy(id = "offset", sourceIndex = 1),
            clip(200, 1200, 1000).copy(id = "zero", sourceIndex = 0)))
        val text = TextEditProject(source.path, 2_000_000, 160, 240,
            layers = listOf(TextLayer("title", "Источник", 0, 2_000_000)))

        fun export(name: String, project: TextEditProject?, behavior: MediaCodecSpeedRampRenderer.TextBehavior?) =
            File(directory, "$name.mp4").also { output ->
                // These original positional arguments remain supported, and omitting the new
                // trailing option continues to select the finished-video text workflow.
                val request = if (behavior == null) MediaCodecSpeedRampRenderer.Request(
                    seed, source, graph, output, 160, 240, 30, 2_000_000,
                    context = context, textProject = project)
                else MediaCodecSpeedRampRenderer.Request(
                    seed, source, graph, output, 160, 240, 30, 2_000_000,
                    context = context, textProject = project, textBehavior = behavior)
                assertEquals(60, MediaCodecSpeedRampRenderer.render(request))
            }
        fun witness(file: File) = VeykadRenderInspector.decodedFrameProvenance(
            requireNotNull(VeykadRenderInspector.latest(context, file)))

        val plain = export("plain", null, null)
        val plainWitness = witness(plain)
        val overlay = export("overlay", text, MediaCodecSpeedRampRenderer.TextBehavior.MONTAGE_OVERLAY)
        val overlayWitness = witness(overlay)
        val legacy = export("legacy", text, null)
        val legacyWitness = witness(legacy)
        val explicit = export("explicit", text, MediaCodecSpeedRampRenderer.TextBehavior.FINISHED_VIDEO)
        assertEquals("Explicit finishing must preserve the old selection", legacyWitness, witness(explicit))
        val noTextOverlay = export("no-text-overlay", null, MediaCodecSpeedRampRenderer.TextBehavior.MONTAGE_OVERLAY)
        assertEquals("Text-null requests retain raw source selection", plainWitness, witness(noTextOverlay))

        for ((frameIndex, outputUs) in listOf(0 to 0L, 12 to 400_000L, 27 to 900_000L)) {
            // Independent raw sample table and literal one-speed output instants form the oracle;
            // neither expected target nor selected PTS uses renderer policy/frame-plan helpers.
            val target = inspected.firstVideoPtsUs + outputUs
            val rawSelected = packetPts.first { it >= target }
            val finishingSelected = packetPts.first { it >= target + inspected.firstVideoPtsUs }
            val observed = requireNotNull(overlayWitness[outputUs])
            assertEquals(1, observed.sourceIndex)
            assertEquals(0, observed.clipIndex)
            assertEquals(target, observed.sourceTimeUs)
            assertEquals(rawSelected, observed.decodedSourceTimeUs)
            assertEquals(plainWitness[outputUs], observed)
            assertEquals(finishingSelected, legacyWitness[outputUs]!!.decodedSourceTimeUs)
            assertTrue(finishingSelected > rawSelected)
            withFrames(listOf(plain to frameIndex, overlay to frameIndex, legacy to frameIndex,
                explicit to frameIndex, noTextOverlay to frameIndex,
                source to packetPts.indexOf(rawSelected), source to packetPts.indexOf(finishingSelected))) { images ->
                val band = Band(20, 110, 140, 160)
                assertTrue("Raw montage pixels survive adding text", difference(images[0], images[1], band) < 4)
                assertTrue("No-text mode changes no pixels", difference(images[0], images[4], band) < 4)
                assertTrue("Default and explicit finishing keep identical pixels", difference(images[2], images[3], band) < 4)
                assertTrue("Montage decodes the actual raw target image", difference(images[0], images[5], band) < 8)
                assertTrue("Finishing still decodes its origin-relative image", difference(images[2], images[6], band) < 8)
                assertTrue("Offset fixture distinguishes wrong source selection", difference(images[5], images[6], band) > 8)
            }
        }
        val secondSource = requireNotNull(overlayWitness[1_200_000])
        assertEquals(0, secondSource.sourceIndex)
        assertEquals(1, secondSource.clipIndex)
        assertEquals(400_000L, secondSource.sourceTimeUs)
        assertEquals(videoPts(seed).first { it >= 400_000 }, secondSource.decodedSourceTimeUs)
        assertEquals(plainWitness[1_200_000], secondSource)
        assertEquals(legacyWitness[1_200_000], secondSource)
    }

    @Test fun montageTextKeepsGradePostAndAuthorTitleOnDirectAndOffscreenPaths() {
        val directory = File(context.cacheDir, "t6a-effects-routing").apply { mkdirs() }
        val source = SyntheticVideo.create(File(directory, "source.mp4"), 2000,
            width = 240, height = 240, lumaBase = 90)
        val graph = MontageGraph(2000, 6000, clips = listOf(clip(0, 2000, 6000).copy(
            exposureBias = .25f, redBias = .60f, greenBias = -.30f, blueBias = -.30f)),
            metadata = NleProjectMetadata(generator = FearStrobeProfile.ID),
            effectGraph = GpuEffectGraph(listOf(GpuEffectGraph.Node("authored-glow", GpuEffectGraph.Kind.GLOW,
                4_000_000, 5_200_000, 1f))))
        val text = TextEditProject(source.path, 6_000_000, 240, 240,
            layers = listOf(TextLayer("user-title", "Заголовок", 4_600_000, 4_800_000,
                TextStyle(color = Color.GREEN, sizeRatio = .065f, plate = TextPlate.ACCENT), TextLayerKind.TITLE)),
            captions = listOf(CaptionCue("subtitle", "Субтитры", 4_600_000, 4_800_000)),
            captionStyle = TextStyle(position = TextPosition.BOTTOM, color = Color.CYAN,
                sizeRatio = .065f, plate = TextPlate.DARK))
        val direct = RenderPassPlanner.Plan(RenderPassPlanner.Quality.COMPATIBILITY, listOf(
            RenderPassPlanner.Pass("source", RenderPassPlanner.PassKind.SOURCE_AND_CHEAP_EFFECTS,
                emptyList(), 1f, setOf("grade", "glow")),
            RenderPassPlanner.Pass("encoder", RenderPassPlanner.PassKind.ENCODER_SURFACE, listOf("source"), 1f)))
        val window = MediaCodecSpeedRampRenderer.OutputWindow(4_500_000, 5_000_000)
        val top = Band(24, 24, 216, 70)
        val middle = Band(24, 85, 216, 155)
        val bottom = Band(24, 170, 216, 204)
        val outside = Band(25, 72, 215, 80)
        for ((name, plan) in listOf("direct" to direct, "offscreen" to RenderPassPlanner.plan(
            graph, RenderPassPlanner.DeviceCapabilities.conservative()))) {
            fun export(suffix: String, recipe: MontageGraph, project: TextEditProject?): Pair<File, Map<RenderPassPlanner.PassKind, Int>> {
                val output = File(directory, "$name-$suffix.mp4")
                var passes = emptyMap<RenderPassPlanner.PassKind, Int>()
                assertEquals(15, MediaCodecSpeedRampRenderer.render(MediaCodecSpeedRampRenderer.Request(
                    sourceFiles = listOf(source), graph = recipe, outputFile = output,
                    width = 240, height = 240, bitrate = 2_000_000, context = context,
                    outputWindow = window, renderPlan = plan, textProject = project,
                    textBehavior = MediaCodecSpeedRampRenderer.TextBehavior.MONTAGE_OVERLAY,
                    onPassesExecuted = { passes = it })))
                return output to passes
            }
            val (base, basePasses) = export("base", graph, null)
            val baseWitness = VeykadRenderInspector.decodedFrameProvenance(
                requireNotNull(VeykadRenderInspector.latest(context, base)))
            val (overlay, overlayPasses) = export("overlay", graph, text)
            assertEquals(basePasses, overlayPasses)
            assertEquals(baseWitness, VeykadRenderInspector.decodedFrameProvenance(
                requireNotNull(VeykadRenderInspector.latest(context, overlay))))
            if (name == "offscreen") assertTrue(overlayPasses.getValue(RenderPassPlanner.PassKind.GLOW_EXTRACT) > 0)
            val noGlow = export("no-glow", graph.copy(effectGraph = GpuEffectGraph()), null).first
            val noGrade = export("no-grade", graph.copy(clips = graph.clips.map {
                it.copy(exposureBias = 0f, redBias = 0f, greenBias = 0f, blueBias = 0f)
            }), null).first
            withFrames(listOf(base to 6, noGlow to 6, noGrade to 6)) { images ->
                assertTrue("Fixture proves a visible authored post effect", difference(images[0], images[1], outside) > 4)
                assertTrue("Fixture proves a visible authored grade", difference(images[0], images[2], outside) > 15)
            }
            // Global instants 4.566667 (before), 4.600000 (first), 4.766667 (last),
            // 4.800000 (end-exclusive). Decode actual encoded frame indices, not guessed PTS.
            assertEquals(15, videoPts(overlay).size)
            for ((index, visible) in listOf(2 to false, 3 to true, 8 to true, 9 to false)) {
                withFrames(listOf(base to index, overlay to index)) { images ->
                    val baseline = images[0]; val actual = images[1]
                    assertTrue("Author FEAR lettering is present in the actual base export",
                        count(baseline, middle) { r, g, b -> r > 195 && g > 195 && b > 195 } > 40)
                    assertTrue("Adding text keeps the existing author title", difference(baseline, actual, middle) < 4)
                    assertTrue("Adding text keeps grade/post outside glyphs", difference(baseline, actual, outside) < 4)
                    val green = count(actual, top) { r, g, b -> g > 180 && r < 100 && b < 130 }
                    val cyan = count(actual, bottom) { r, g, b -> g > 180 && b > 180 && r < 100 }
                    assertEquals("Title half-open interval on $name index $index", visible, green > 15)
                    assertEquals("Subtitle half-open interval and upright orientation on $name index $index", visible, cyan > 15)
                    assertEquals(0, count(actual, bottom) { r, g, b -> g > 180 && r < 100 && b < 130 })
                    if (visible) {
                        // Literal accent colour must survive glow without being graded again.
                        val accent = actual.getPixel(35, 28)
                        assertTrue(kotlin.math.abs(Color.red(accent) - 84) < 18)
                        assertTrue(kotlin.math.abs(Color.green(accent) - 39) < 18)
                        assertTrue(kotlin.math.abs(Color.blue(accent) - 164) < 18)
                        // The known 201/255 black matte leaves 54/255 of the already rendered
                        // background. This checks alpha compositing against decoded base pixels,
                        // independently of layout/shader calculations under test.
                        val matte = actual.getPixel(35, 199)
                        val background = baseline.getPixel(35, 199)
                        assertTrue(kotlin.math.abs(Color.red(matte) - Color.red(background) * 54.0 / 255) < 10)
                        assertTrue(kotlin.math.abs(Color.green(matte) - Color.green(background) * 54.0 / 255) < 10)
                        assertTrue(kotlin.math.abs(Color.blue(matte) - Color.blue(background) * 54.0 / 255) < 10)
                    } else {
                        assertTrue(difference(baseline, actual, top) < 4)
                        assertTrue(difference(baseline, actual, bottom) < 4)
                    }
                }
            }
        }
    }

    private fun clip(startMs: Long, endMs: Long, durationMs: Long) = MontageGraph.Clip(
        "clip", startMs, endMs, durationMs, MontageGraph.ShotRole.OPENING,
        MontageGraph.Transition.HARD_CUT, MontageGraph.Motion.HOLD, 1f, 0)

    private data class Band(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun difference(left: Bitmap, right: Bitmap, band: Band): Double {
        var total = 0L
        for (y in band.top until band.bottom) for (x in band.left until band.right) {
            val a = left.getPixel(x, y); val b = right.getPixel(x, y)
            total += kotlin.math.abs(Color.red(a) - Color.red(b)) +
                kotlin.math.abs(Color.green(a) - Color.green(b)) + kotlin.math.abs(Color.blue(a) - Color.blue(b))
        }
        return total.toDouble() / ((band.right - band.left) * (band.bottom - band.top) * 3)
    }

    private fun count(bitmap: Bitmap, band: Band, predicate: (Int, Int, Int) -> Boolean): Int {
        var result = 0
        for (y in band.top until band.bottom) for (x in band.left until band.right) {
            val pixel = bitmap.getPixel(x, y)
            if (predicate(Color.red(pixel), Color.green(pixel), Color.blue(pixel))) result++
        }
        return result
    }

    private fun withFrames(inputs: List<Pair<File, Int>>, assertions: (List<Bitmap>) -> Unit) {
        val images = mutableListOf<Bitmap>()
        try {
            inputs.forEach { (file, index) -> MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(file.path)
                images += requireNotNull(retriever.getFrameAtIndex(index))
            } }
            assertions(images)
        } finally { images.forEach(Bitmap::recycle) }
    }

    private fun videoPts(file: File): List<Long> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            extractor.selectTrack((0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/")
            })
            val pts = mutableListOf<Long>()
            while (extractor.sampleTime >= 0) { pts += extractor.sampleTime; extractor.advance() }
            assertTrue("Fixture requires strictly ordered presentation samples", pts.zipWithNext().all { (a, b) -> a < b })
            return pts
        } finally { extractor.release() }
    }
}
