package com.veycad.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectSourceImportTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun source(): MediaSource {
        val file = temporary.newFile().apply { writeText("synthetic source bytes") }
        return MediaSource("selection", file, "Provider label", 1_234_567, file.length(), 270,
            1440, 1080, "video/avc", 3, true, contentHash(file.readBytes()))
    }

    @Test fun inspectedImportsKeepPhysicalFactsAndIndependentSelectionLabels() {
        val source = source()
        val second = source.copy(id = "second", displayName = "Capture label")
        val store = ProjectAssetStore(temporary.newFolder())
        val firstAsset = store.importVideo(source, 4f / 3f)
        val secondAsset = store.importVideo(second, 4f / 3f)
        assertEquals(firstAsset, secondAsset)
        assertEquals(1_234_567L, firstAsset.durationUs)
        assertEquals(source.fingerprint, firstAsset.contentHash)
        assertEquals(source.sizeBytes, firstAsset.videoMetadata!!.sizeBytes)
        assertEquals(SourceGeometry(1440, 1080, 270, 4f / 3f), firstAsset.videoMetadata!!.geometry)
        assertEquals("synthetic source bytes", store.resolve(firstAsset).readText())
        val first = SelectedVideo.fromInspected(source, firstAsset, SourceOwnership.IMPORTED)
        val capture = SelectedVideo.fromInspected(second, secondAsset, SourceOwnership.CAPTURE, CaptureOrigin("session", 1, false))
        assertEquals("Provider label", first.displayName)
        assertEquals("Capture label", capture.displayName)
        assertNotEquals(first.id, capture.id)
        assertEquals(first.assetId, capture.assetId)
        assertEquals(SourceOwnership.CAPTURE, capture.ownership)
        assertFalse(capture.captureOrigin!!.recommended)
    }

    @Test fun staleInspectionAndWrongAssetBindingAreRejected() {
        val source = source()
        val store = ProjectAssetStore(temporary.newFolder())
        assertThrows(IllegalArgumentException::class.java) { store.importVideo(source.copy(sizeBytes = source.sizeBytes + 1), 1f) }
        assertThrows(IllegalArgumentException::class.java) { store.importVideo(source.copy(fingerprint = "a".repeat(64)), 1f) }
        assertThrows(IllegalArgumentException::class.java) { store.importVideo(source, Float.NaN) }
        val asset = store.importVideo(source, 1f)
        assertThrows(IllegalArgumentException::class.java) {
            SelectedVideo.fromInspected(source.copy(durationUs = source.durationUs + 1), asset, SourceOwnership.IMPORTED)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SelectedVideo.fromInspected(source.copy(width = 1920), asset, SourceOwnership.IMPORTED)
        }
    }

    @Test fun mediaSourceDtoRetainsOwnerValidationAndSelectionSnapshot() {
        val source = source()
        assertTrue(source.isBaselineInput)
        assertFalse(source.copy(colorTransfer = 6).isBaselineInput)
        for (rotation in listOf(0, 90, 180, 270)) assertEquals(rotation, source.copy(rotationDegrees = rotation).rotationDegrees)
        assertThrows(IllegalArgumentException::class.java) { source.copy(fingerprint = "not-a-hash") }
        assertThrows(IllegalArgumentException::class.java) { source.copy(sizeBytes = 0) }
        assertThrows(IllegalArgumentException::class.java) { source.copy(width = 0) }
        assertThrows(IllegalArgumentException::class.java) { source.copy(rotationDegrees = 45) }
        assertThrows(IllegalArgumentException::class.java) { source.copy(mime = "audio/aac") }
        val caller = mutableListOf(source, source.copy(id = "another"))
        val set = MediaSourceSet(caller)
        caller.clear()
        assertEquals(listOf("selection", "another"), set.items.map { it.id })
        assertThrows(UnsupportedOperationException::class.java) { (set.items as MutableList).clear() }
        assertThrows(IllegalArgumentException::class.java) { MediaSourceSet(listOf(source, source)) }
    }
}
