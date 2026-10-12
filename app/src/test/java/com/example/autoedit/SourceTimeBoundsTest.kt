package com.veycad.app

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceTimeBoundsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun originAloneNeverTurnsDeclaredContainerDurationIntoMeasuredEnd() {
        val unmeasuredEnd = SourceTimeBounds(1294300, 120000)
        assertEquals(1294300L, unmeasuredEnd.requestEndUs)
        assertNull(unmeasuredEnd.measuredRawEndUs)
        assertThrows(IllegalArgumentException::class.java) { unmeasuredEnd.requireActualPts(120000) }
    }

    @Test fun nonzeroOriginAdmitsRawRequestsButKeepsObservationEvidenceSeparate() {
        val bounds = SourceTimeBounds(500000, 120000, 620000)
        assertEquals(620000L, bounds.requestEndUs)
        bounds.requireRequest(586666)
        bounds.requireRequest(0) // Decoder may return the first frame for an earlier request.
        bounds.requireActualPts(120000)
        bounds.requireActualPts(619999)
        assertThrows(IllegalArgumentException::class.java) { bounds.requireActualPts(0) }
        assertThrows(IllegalArgumentException::class.java) { bounds.requireActualPts(620000) }
        assertThrows(IllegalArgumentException::class.java) { bounds.requireRequest(620000) }
        bounds.requireRequest(620000, exclusiveBoundary = true)
        assertThrows(IllegalArgumentException::class.java) { SourceTimeBounds(500000, Long.MAX_VALUE, Long.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { SourceTimeBounds(500000, -1) }
    }

    @Test fun independentlyMeasuredEndKeepsContainerEditOffsetAndLegitimateFinalHold() {
        val bounds = SourceTimeBounds(1294300, 120000, 1294334)
        assertEquals(1294300L, bounds.durationUs)
        assertEquals(1294334L, bounds.measuredRawEndUs)
        assertEquals(1174334L, bounds.measuredContentSpanUs)
        bounds.requireActualPts(1261000)
        bounds.requireRequest(1294333)
        assertThrows(IllegalArgumentException::class.java) { bounds.requireRequest(1414300) }
        val base = editProject()
        val video = base.assets.first().copy(durationUs = 1294300, videoMetadata = VideoSourceMetadata(
            SourceGeometry(1920, 1080, 0, 1f), 123, "video/avc", 3, true, 120000, 1294334))
        val held = SourceTimeMap(listOf(SourceTimeMap.Point(0, 120000), SourceTimeMap.Point(20, 1261000),
            SourceTimeMap.Point(30, 1261000)))
        val revision = base.current.copy(clips = base.current.clips.map { it.copy(sourceMap = held) })
        val project = base.copy(assets = listOf(video, base.assets.last()), original = revision, current = revision)
        val codec = HybridProjectCodec(AnalysisSidecarStore(temporary.newFolder()))
        val loaded = codec.decode(codec.encode(project))
        assertEquals(1294300L, loaded.assets.first().durationUs)
        assertEquals(1294334L, loaded.assets.first().timeBounds.requestEndUs)
        assertEquals(1261000L, loaded.current.clips[1].sourceMap.sample(29))
        assertEquals(1261000L, loaded.current.clips[1].sourceMap.sample(30))
    }

    @Test fun partialMeasurementsStayUnknownAndIndependentEndpointsCannotOverflow() {
        val endOnly = SourceTimeBounds(500000, null, 600000)
        assertNull(endOnly.measuredContentSpanUs)
        assertEquals(600000L, endOnly.requestEndUs)
        assertThrows(IllegalArgumentException::class.java) { endOnly.requireActualPts(586666) }
        for (declared in listOf(500000L, 600000L)) {
            val unknown = SourceTimeBounds(declared, null)
            assertEquals(declared, unknown.requestEndUs)
            unknown.requireRequest(declared - 1)
            unknown.requireRequest(declared, exclusiveBoundary = true)
            assertNull(unknown.measuredContentSpanUs)
        }
        for (end in listOf(-1L, 0L, 119999L, 120000L))
            assertThrows(IllegalArgumentException::class.java) { SourceTimeBounds(500000, 120000, end) }
        // No addition of declared duration to origin, even near the numeric limit.
        assertEquals(600000L, SourceTimeBounds(Long.MAX_VALUE, 120000, 600000).requestEndUs)
        val large = SourceTimeBounds(500000, 1, Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE - 1, large.measuredContentSpanUs)
        large.requireActualPts(Long.MAX_VALUE - 1)
        assertThrows(IllegalArgumentException::class.java) { large.requireActualPts(Long.MAX_VALUE) }
    }

    @Test fun unknownOriginKeepsLegacyTargetsWithoutFabricatingActualBounds() {
        val legacy = SourceTimeBounds(500000, null)
        assertNull(legacy.measuredRawEndUs)
        assertEquals(500000L, legacy.requestEndUs)
        legacy.requireRequest(0)
        assertThrows(IllegalArgumentException::class.java) { legacy.requireActualPts(0) }
        val codec = HybridProjectCodec(AnalysisSidecarStore(temporary.newFolder()))
        val project = codec.decode(javaClass.getResourceAsStream("/hybrid-v4/project.bin")!!.readBytes())
        assertNull(project.assets.first().videoMetadata!!.firstVideoPtsUs)
        assertNull(project.assets.first().videoMetadata!!.videoEndPtsUs)
        assertEquals(500000L, project.original.clips[1].sourceMap.sample(0))
        assertEquals(433333L, project.undo[1].clips[1].sourceMap.sample(0))
    }

    @Test fun measuredImportSurvivesCodecStoreAndImmutableAssetBindings() {
        val files = temporary.newFolder()
        val store = HybridProjectStore(files)
        val source = temporary.newFile("video.mp4").apply { writeText("inspected bytes") }
        val inspected = MediaSource("selection", source, "Video", 500000, source.length(), 0, 1920, 1080,
            "video/avc", 3, true, contentHash(source.readBytes()), firstVideoPtsUs = 120000, videoEndPtsUs = 620000)
        val asset = ProjectAssetStore(store.directory("project")).importVideo(inspected, 1f)
        assertEquals(120000L, asset.videoMetadata!!.firstVideoPtsUs)
        val base = editProject()
        val clips = base.current.clips.map { it.copy(assetId = asset.id, sourceMap = SourceTimeMap(listOf(
            SourceTimeMap.Point(0, 0), SourceTimeMap.Point(29, 586666), SourceTimeMap.Point(30, 620000)))) }
        val revision = base.current.copy(clips = clips)
        val project = base.copy(assets = listOf(asset, base.assets.last()), original = revision, current = revision)
        File(File(store.directory(project.id), "sources"), "music.wav").writeText("music")
        store.create(project)
        val loaded = HybridProjectStore(files).load(project.id)
        assertEquals(project, loaded)
        assertEquals(586666L, loaded.current.clips[1].sourceMap.sample(29))
        assertEquals(120000L, loaded.assets.first().videoMetadata!!.firstVideoPtsUs)
        assertEquals(620000L, loaded.assets.first().videoMetadata!!.videoEndPtsUs)
        val modified = loaded.copy(assets = loaded.assets.map { if (it.id == asset.id)
            it.copy(videoMetadata = it.videoMetadata!!.copy(firstVideoPtsUs = 130000)) else it })
        val pointer = File(store.directory(project.id), "CURRENT").readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.save(modified, 0) }
        assertArrayEquals(pointer, File(store.directory(project.id), "CURRENT").readBytes())
        val changedEnd = loaded.copy(assets = loaded.assets.map { if (it.id == asset.id)
            it.copy(videoMetadata = it.videoMetadata!!.copy(videoEndPtsUs = 630000)) else it })
        assertThrows(IllegalArgumentException::class.java) { store.save(changedEnd, 0) }
        assertThrows(HybridEditRejected::class.java) { HybridEditCommands.slipClip(loaded, "right", 1) }
    }
}
