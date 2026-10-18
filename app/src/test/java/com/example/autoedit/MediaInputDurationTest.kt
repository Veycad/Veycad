package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class MediaInputDurationTest {
    @Test fun declaredLongFinalHoldKeepsAUsableSixTenthsSecondSource() {
        val duration = mediaInputDurationUs(0L, 480_000L, 470_000L, 600_000L, 100)
        assertEquals(600_000L, duration)
        GalleryImportPolicy.validate(MediaSourceSet(listOf(mediaSource(durationUs = duration))))
    }

    @Test fun smallInitialPtsDoesNotShortenDeclaredHalfSecondContent() {
        val duration = mediaInputDurationUs(5_000L, 495_000L, 485_000L, 500_000L, 100)
        assertEquals(500_000L, duration)
        GalleryImportPolicy.validate(MediaSourceSet(listOf(mediaSource(durationUs = duration))))
    }

    @Test fun longFinalHoldIsCountedAgainstTheThirtyMinuteBudget() {
        val duration = mediaInputDurationUs(0L, 1_799_880_000L, 1_799_870_000L, 1_800_000_001L, 100)
        assertThrows(IllegalArgumentException::class.java) {
            GalleryImportPolicy.validate(MediaSourceSet(listOf(mediaSource(durationUs = duration))))
        }
    }

    @Test fun largeInitialPtsDoesNotChangeDeclaredContentDuration() {
        assertEquals(600_000L, mediaInputDurationUs(2_000_000L, 2_480_000L, 2_470_000L, 600_000L, 100))
    }

    @Test fun aSingleFrameCanHoldForItsWholeDeclaredDuration() {
        assertEquals(600_000L, mediaInputDurationUs(5_000L, 5_000L, null, 600_000L, 30))
    }

    @Test fun missingDurationUsesObservedIntervalsWithoutAddingTimestampOffset() {
        assertEquals(490_000L, mediaInputDurationUs(2_000_000L, 2_480_000L, 2_470_000L, null, 100))
    }

    @Test fun unknownDurationCannotOverflowWhenEstimatingTheFinalInterval() {
        assertThrows(IllegalArgumentException::class.java) {
            mediaInputDurationUs(0L, Long.MAX_VALUE, Long.MAX_VALUE - 1L, null, 100)
        }
    }
}
