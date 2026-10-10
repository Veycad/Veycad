package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class GalleryImportPolicyTest {
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
