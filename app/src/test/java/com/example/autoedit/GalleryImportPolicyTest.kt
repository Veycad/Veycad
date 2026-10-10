package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class GalleryImportPolicyTest {
    @Test fun independentUsableSpanControlsPolicyWhileDeclaredMetadataRemainsUnchanged() {
        val offset = mediaSource(durationUs = 505_000, firstVideoPtsUs = 5_000, endPtsUs = 505_000)
        GalleryImportPolicy.validate(MediaSourceSet(listOf(offset)))
        assertEquals(505_000L, offset.durationUs)
        assertEquals(500_000L, offset.videoContentDurationUs)
        val held = mediaSource(durationUs = 600_000, firstVideoPtsUs = 2_000_000, endPtsUs = 2_600_000)
        GalleryImportPolicy.validate(MediaSourceSet(listOf(held)))
        assertEquals(600_000L, held.durationUs)
        assertEquals(2_600_000L, held.videoEndPtsUs)
        assertThrows(IllegalArgumentException::class.java) {
            GalleryImportPolicy.validate(MediaSourceSet(listOf(offset.copy(
                videoPresentationBounds = VideoPresentationBounds(5_000, 504_999)))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GalleryImportPolicy.validate(MediaSourceSet(listOf(offset.copy(videoPresentationBounds = null))))
        }
    }
    @Test fun enforcesCountAndTotalDuration() {
        GalleryImportPolicy.validate(sourceSet(1))
        GalleryImportPolicy.validate(sourceSet(20, 90_000_000L))
        assertThrows(IllegalArgumentException::class.java) { GalleryImportPolicy.validate(sourceSet(0)) }
        assertThrows(IllegalArgumentException::class.java) { GalleryImportPolicy.validate(sourceSet(21)) }
        assertThrows(IllegalArgumentException::class.java) {
            GalleryImportPolicy.validate(sourceSet(20, 90_000_001L))
        }
    }

    @Test fun exactHalfSecondSourceIsUsableButShorterSourceIsRejected() {
        GalleryImportPolicy.validate(sourceSet(1, 500_000L))
        assertThrows(IllegalArgumentException::class.java) {
            GalleryImportPolicy.validate(sourceSet(1, 499_999L))
        }
    }

    @Test fun exactThirtyMinutesIsAcceptedButOneMicrosecondOverIsRejected() {
        GalleryImportPolicy.validate(sourceSet(1, 1_800_000_000L))
        assertThrows(IllegalArgumentException::class.java) {
            GalleryImportPolicy.validate(sourceSet(1, 1_800_000_001L))
        }
    }

    @Test fun overflowingDurationCannotWrapIntoAnAllowedTotal() {
        assertThrows(IllegalArgumentException::class.java) {
            GalleryImportPolicy.validate(sourceSet(2, Long.MAX_VALUE))
        }
    }
}
