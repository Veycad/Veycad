package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class MediaSourceSetTest {
    @Test fun selectionOrderIsImmutableEvenWhenCallerChangesItsList() {
        val selected = mutableListOf(mediaSource("second"), mediaSource("first"))
        val sources = MediaSourceSet(selected)
        selected.reverse()
        selected.clear()
        assertEquals(listOf("second", "first"), sources.items.map { it.id })
        assertThrows(UnsupportedOperationException::class.java) {
            (sources.items as MutableList<MediaSource>).clear()
        }
        assertEquals(2, sources.items.size)
    }

    @Test fun identicalBytesHaveDistinctSelectionIdsButDuplicateIdsAreRejected() {
        val sources = sourceSet(2)
        assertEquals(sources.items[0].fingerprint, sources.items[1].fingerprint)
        assertEquals(listOf("source-0", "source-1"), sources.items.map { it.id })
        assertThrows(IllegalArgumentException::class.java) {
            MediaSourceSet(listOf(mediaSource("same"), mediaSource("same")))
        }
    }

    @Test fun emptyDraftIsAllowedButUnknownSchemaIsRejected() {
        assertTrue(MediaSourceSet(emptyList()).items.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { MediaSourceSet(emptyList(), 2) }
    }

    @Test fun invalidMetadataCannotEnterASelection() {
        val source = mediaSource()
        listOf<() -> MediaSource>(
            { source.copy(id = "") }, { source.copy(durationUs = 0) },
            { source.copy(sizeBytes = 0) }, { source.copy(rotationDegrees = 45) },
            { source.copy(width = 0) }, { source.copy(height = -1) },
            { source.copy(mime = "audio/aac") }, { source.copy(fingerprint = "") }
        ).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { invalid() } }
    }

    @Test fun baselineInputRequiresAvcAndSdr() {
        val source = mediaSource()
        assertTrue(source.isBaselineInput)
        assertTrue(source.copy(colorTransfer = 3).isBaselineInput)
        assertFalse(source.copy(mime = "video/hevc").isBaselineInput)
        assertFalse(source.copy(colorTransfer = 6).isBaselineInput)
        assertFalse(source.copy(colorTransfer = 7).isBaselineInput)
    }
}
