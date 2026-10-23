package com.veycad.app

import android.graphics.Color
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real decoder -> framing shader -> encoder evidence, independent of ML fixture quality. */
@RunWith(AndroidJUnit4::class)
class SmartFramingDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun indexedSmartAnalysisIncludesRealNonzeroOriginTailBeyondDeclaredDuration() {
        val file = File(context.cacheDir, "smart-raw-origin-${UUID.randomUUID()}.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("media/raw-origin-400ms.mp4").use { input ->
            // The existing authentic 60-frame encoder fixture preserves ctts400ms. Only its
            // declared duration fields become2s; sample tables, chunk offsets and mdat stay exact.
            file.writeBytes(withShortDeclaredDuration(input.readBytes()))
        }
        try {
            val source = MediaInputInspector().inspect("offset", file, "Versioned raw PTS fixture")
            assertEquals(400_000L, source.requiredFirstVideoPtsUs)
            assertEquals(2_400_000L, source.requiredVideoEndPtsUs)
            assertTrue("Authentic fixture must expose independent raw tail", source.requiredVideoEndPtsUs > source.durationUs)
            val key = SmartFramingKey(source.fingerprint, source.durationUs, source.requiredFirstVideoPtsUs,
                source.requiredVideoEndPtsUs, listOf(VisualEventMap.UsableWindow(
                    source.requiredFirstVideoPtsUs, source.requiredVideoEndPtsUs)))
            val targets = SmartFramingSourceAnalysis.targets(file, key)
            assertTrue(targets.last() > source.durationUs)
            val analysis = SmartFramingSourceAnalysis.analyze(context, file, key) {}
            assertEquals(targets.toList(), analysis.observations.map { it.sourceTimeUs })
            val track = SmartFramingTrack.build(analysis, key.windows)
            assertEquals(source.requiredVideoEndPtsUs, track.sourceWindows.single().endUs)
            assertTrue(track.points.any { it.sourceTimeUs > source.durationUs })
            assertEquals(source.requiredVideoEndPtsUs, analysis.durationUs)
        } finally { file.delete() }
    }

    private fun withShortDeclaredDuration(original: ByteArray): ByteArray {
        val bytes = original.clone()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val changed = mutableSetOf<Int>()
        fun visit(start: Int, end: Int) {
            var at = start
            while (at < end) {
                require(end - at >= 8)
                val size = buffer.getInt(at)
                require(size >= 8 && size <= end - at)
                val kind = String(bytes, at + 4, 4, Charsets.US_ASCII)
                if (kind in listOf("moov", "trak", "mdia", "minf", "stbl")) visit(at + 8, at + size)
                if (kind in listOf("mvhd", "mdhd", "tkhd")) {
                    require(bytes[at + 8] == 0.toByte())
                    val offset = at + 8 + if (kind == "tkhd") 20 else 16
                    val scale = if (kind == "tkhd") 1000 else buffer.getInt(at + 8 + 12)
                    buffer.putInt(offset, Math.multiplyExact(scale, 2))
                    repeat(4) { changed += offset + it }
                }
                at += size
            }
            require(at == end)
        }
        visit(0, bytes.size)
        assertEquals("Three version-0 duration fields only", 12, changed.size)
        assertTrue(original.indices.all { it in changed || original[it] == bytes[it] })
        return bytes
    }

    @Test fun sourcePtsTrackingAndWholeForegroundBlurEncodeInEveryAspect() {
        val directory = File(context.cacheDir, "smart-native-${UUID.randomUUID()}").apply { mkdirs() }
        val source = SyntheticVideo.create(File(directory, "split.mp4"), 1000, 320, 160,
            lumaAt = { x, _, _ -> when { x < 24 -> 220; x < 160 -> 40; else -> 180 } })
        val inspected = MediaInputInspector().inspect("selection", source, "Framing fixture")
        val assets = ProjectAssetStore(File(directory, "project"))
        val video = assets.importVideo(inspected, 1f)
        val selection = SelectedVideo.fromInspected(inspected, video, SourceOwnership.IMPORTED)
        val musicFile = SourceAudioNativeFixtures.wav(File(directory, "silent.wav")) { 0 }
        val music = assets.import(musicFile, ProjectAsset.Kind.AUDIO, 1_000_000)
        val graph = MontageGraph(1000, 1000, clips = listOf(MontageGraph.Clip("clip", 0, 1000, 1000,
            MontageGraph.ShotRole.ACTION, MontageGraph.Transition.HARD_CUT, MontageGraph.Motion.HOLD, 1f, 0)),
            metadata = NleProjectMetadata(generator = "smart-framing-native-fixture"))
        val originalBytes = source.readBytes()
        val track = SmartFramingTrack.fromPoints(listOf(
            SmartFramingTrack.Point(0, .25f, .5f, SmartFramingStatus.TRACKING),
            SmartFramingTrack.Point(200_000, .25f, .5f, SmartFramingStatus.TRACKING),
            SmartFramingTrack.Point(500_000, .75f, .5f, SmartFramingStatus.TRACKING),
            SmartFramingTrack.Point(999_999, .75f, .5f, SmartFramingStatus.TRACKING)),
            listOf(VisualEventMap.UsableWindow(0, 1_000_000)))
        for (aspect in ProjectAspect.entries) for (mode in listOf(FramingMode.SMART_PERSON, FramingMode.BLURRED_FIT)) {
            val size = aspect.size(720)
            val settings = ProjectExportSettings(size.width, size.height, 30, 2_000_000, 128_000)
            val imported = EditableMontageImporter.create("smart-native", graph,
                MontageStyleCatalog.Recipe.DUALITY_LOOP, settings, listOf(video), music, null,
                listOf(selection)).shared
            val visual = ProjectVisualSettings(aspect, true, mapOf(FramingKey(selection.id, aspect) to
                SourceFramingSettings(mode = mode)))
            val revision = imported.current.copy(visualSettings = visual, music = imported.current.music.copy(gain = 0f))
            val project = imported.copy(original = revision, current = revision)
            val prepared = HybridPreviewPreparation.prepare(project, settings, assets)
            val output = File(directory, "$aspect-$mode.mp4")
            try {
                val request = PreparedHybridExportAdapter.request(prepared, project, output, context = context,
                    smartTracks = mapOf(selection.id to track))
                assertSame(prepared.framePlan, request.resolvedFramePlan())
                assertEquals("$aspect/$mode encoded count", 30, MediaCodecSpeedRampRenderer.render(request))
                MediaMetadataRetriever().use { reader ->
                    reader.setDataSource(output.path)
                    fun pixel(time: Long, x: Float, y: Float): Int {
                        val frame = requireNotNull(reader.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST))
                        try {
                            assertEquals(size.width, frame.width); assertEquals(size.height, frame.height)
                            return Color.red(frame.getPixel((frame.width * x).toInt(), (frame.height * y).toInt()))
                        } finally { frame.recycle() }
                    }
                    if (mode == FramingMode.SMART_PERSON) {
                        assertTrue("$aspect starts on left subject", pixel(0, .5f, .5f) < 80)
                        assertTrue("$aspect follows own source PTS to right subject", pixel(750_000, .5f, .5f) > 130)
                    } else {
                        assertTrue("$aspect retains full-source edge marker", pixel(500_000, .03f, .5f) > 190)
                        assertTrue("$aspect retains left foreground", pixel(500_000, .25f, .5f) < 80)
                        val background = pixel(500_000, .03f, .03f)
                        assertTrue("$aspect fills top bar with enlarged blurred source: $background", background in 20..110)
                    }
                }
            } finally { prepared.close() }
        }
        assertArrayEquals("Rendering cannot mutate selected input", originalBytes, source.readBytes())
        directory.deleteRecursively()
    }

    @Test fun compactCacheUsesExactPhysicalIdentityAndCorruptionDoesNotReachReady() {
        val directory = File(context.cacheDir, "smart-cache-${UUID.randomUUID()}").apply { mkdirs() }
        val source = SyntheticVideo.create(File(directory, "source.mp4"), 1000)
        val inspected = MediaInputInspector().inspect("selection", source, "Cache fixture")
        val assets = ProjectAssetStore(File(directory, "project"))
        val video = assets.importVideo(inspected, 1f)
        val selection = SelectedVideo.fromInspected(inspected, video, SourceOwnership.IMPORTED)
        val metadata = requireNotNull(video.videoMetadata)
        val first = metadata.firstVideoPtsUs ?: 0L
        val end = metadata.videoEndPtsUs ?: video.durationUs
        val key = SmartFramingKey(video.contentHash, video.durationUs, first, end,
            listOf(VisualEventMap.UsableWindow(first, minOf(end, video.durationUs))))
        val track = SmartFramingTrack.fromPoints(listOf(SmartFramingTrack.Point(first, .23f, .61f,
            SmartFramingStatus.TRACKING)), key.windows)
        val actual = File(File(context.cacheDir, "full-video-analysis"), key.fileName)
        val previousCache = actual.takeIf { it.isFile }?.readBytes()
        try {
        MediaFrameAnalysisCache.storeSmart(context, key, track)
        assertEquals(track.points, requireNotNull(MediaFrameAnalysisCache.loadSmart(context, key)).points)
        // Use the real cache location, not a mirror parser, and preserve the payload length.
        assertTrue(actual.isFile)
        val bytes = actual.readBytes(); bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
        actual.writeBytes(bytes)
        assertNull(MediaFrameAnalysisCache.loadSmart(context, key))
        assertFalse("Bad compact state must be discarded before preparation can use it", actual.exists())
        MediaFrameAnalysisCache.storeSmart(context, key, track)
        assertEquals(.23f, requireNotNull(MediaFrameAnalysisCache.loadSmart(context, key)).sample(first).centerX, 0f)
        val music = assets.import(SourceAudioNativeFixtures.wav(File(directory, "silent.wav")) { 0 },
            ProjectAsset.Kind.AUDIO, 1_000_000)
        val graph = MontageGraph(1000, 1000, clips = listOf(MontageGraph.Clip("clip", 0, 1000, 1000,
            MontageGraph.ShotRole.ACTION, MontageGraph.Transition.HARD_CUT, MontageGraph.Motion.HOLD, 1f, 0)))
        val project = EditableMontageImporter.create("cached-smart", graph, MontageStyleCatalog.Recipe.HEARTBEAT,
            ProjectExportSettings(720, 1280, 30, 2_000_000, 128_000), listOf(video), music, null,
            listOf(selection)).shared.let { imported ->
            imported.copy(current = imported.current.copy(visualSettings = ProjectVisualSettings(
                ProjectAspect.PORTRAIT_9_16, true, mapOf(FramingKey(selection.id, ProjectAspect.PORTRAIT_9_16) to
                    SourceFramingSettings(mode = FramingMode.SMART_PERSON)))))
        }
        assertEquals("The production preparation path must consume the exact cached compact track",
            track.points, SmartFramingPreparation.prepare(context, project, assets).getValue(selection.id).points)
        assertTrue(assets.resolve(video).isFile)
        assertEquals(selection.assetId, video.id)
        } finally {
            if (previousCache == null) actual.delete() else actual.writeBytes(previousCache)
            directory.deleteRecursively()
        }
    }

    @Test fun automaticCaptureWinnerReopensWithExactCommonIdentityAndFramingHistory() {
        val directory = File(context.cacheDir, "capture-handoff-${UUID.randomUUID()}").apply { mkdirs() }
        val source = SyntheticVideo.create(File(directory, "take.mp4"), 1000)
        val sourceBytes = source.readBytes()
        val inspected = MediaInputInspector().inspect("take-selection", source, "Camera take")
        val music = SourceAudioNativeFixtures.wav(File(directory, "music.wav")) { 0 }
        val graph = MontageGraph(1000, 1000, clips = listOf(MontageGraph.Clip("winner", 0, 1000, 1000,
            MontageGraph.ShotRole.ACTION, MontageGraph.Transition.HARD_CUT, MontageGraph.Motion.HOLD, 1f, 0)),
            audioTrack = MontageGraph.AudioTrack("original-music"))
        val capture = CaptureOrigin("session-${UUID.randomUUID()}", 2, true)
        val settings = ProjectExportSettings(720, 720, 30, 2_000_000, 128_000)
        val id = CustomMusicProjectHandoff.publish(context, graph, MediaSourceSet(listOf(inspected)), music,
            settings, recipe = MontageStyleCatalog.Recipe.FEAR_STROBE, captureOrigin = capture)
        val store = HybridProjectStore(context.filesDir)
        try {
            val loaded = store.load(id)
            assertEquals(id, loaded.id)
            val selection = requireNotNull(loaded.selectedVideos).single()
            assertEquals(SourceOwnership.CAPTURE, selection.ownership)
            assertEquals(capture, selection.captureOrigin)
            assertEquals(ProjectAspect.SQUARE_1_1, loaded.current.visualSettings.aspect)
            assertFalse(loaded.current.visualSettings.explicitlySelected)
            assertEquals(MontageStyleCatalog.all.single { it.recipe == MontageStyleCatalog.Recipe.FEAR_STROBE }.id,
                loaded.current.style.recipeId)
            val assets = ProjectAssetStore(store.directory(id))
            val video = loaded.assets.single { it.id == selection.assetId }
            assertEquals(inspected.fingerprint, video.contentHash)
            assertArrayEquals(sourceBytes, assets.resolve(video).readBytes())
            val key = FramingKey(selection.id, ProjectAspect.SQUARE_1_1)
            val framed = loaded.current.visualSettings.withFraming(key, SourceFramingSettings(
                mode = FramingMode.SMART_PERSON, manual = FramingSettings(FramingMode.MANUAL, .35f, .6f, 1.4f)))
            val edited = HybridEditCommands.apply(loaded,
                ProjectCommand.CommitRevision(loaded.current.copy(visualSettings = framed)))
            store.save(edited, loaded.current.id)
            val reopened = store.load(id)
            assertEquals(FramingMode.SMART_PERSON, reopened.current.visualSettings.framings.getValue(key).mode)
            assertEquals(.35f, reopened.current.visualSettings.framings.getValue(key).manual.centerX, 0f)
            val undone = HybridEditCommands.apply(reopened, ProjectCommand.Undo)
            store.save(undone, reopened.current.id)
            assertEquals(loaded.current.visualSettings, store.load(id).current.visualSettings)
            assertEquals(capture, store.load(id).selectedVideos!!.single().captureOrigin)
            assertArrayEquals(sourceBytes, source.readBytes())
        } finally { store.delete(id); directory.deleteRecursively() }
    }
}
