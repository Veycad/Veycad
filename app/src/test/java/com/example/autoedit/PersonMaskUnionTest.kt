package com.example.autoedit

import org.junit.Assert.*
import org.junit.Test

class PersonMaskUnionTest {
    @Test fun keepsEnclosedAccessoryButRejectsCurtainTouchingOnlyOneEdge() {
        val w = 20
        val c = List(6) { FloatArray(w * w) }
        for (y in 10..18) for (x in 4..15) c[3][y*w+x] = 1f
        // Eyewear enclosed by the face must not become a hole.
        for (y in 12..14) for (x in 7..11) { c[3][y*w+x] = 0f; c[5][y*w+x] = 1f }
        // Tall curtain touches the top of the face but is mostly bounded by background.
        for (y in 0..9) for (x in 4..6) c[5][y*w+x] = 1f
        val result = PersonMaskUnion.combine(c, w, w)
        assertEquals(1f, result[13*w+9], 0f)
        assertEquals(0f, result[4*w+5], 0f)
        assertEquals(1f, result[16*w+10], 0f)
    }
    @Test fun preservesSoftHairWithoutAccessories() {
        val c = List(6) { FloatArray(4) }
        c[1][1] = .42f
        assertEquals(.42f, PersonMaskUnion.combine(c, 2, 2)[1], 0f)
    }

    @Test fun rejectsWideBorderCurtainDespiteQuarterBoundarySupport() {
        val w = 40
        val c = List(6) { FloatArray(w * w) }
        for (y in 10..35) for (x in 3..36) c[3][y*w+x] = 1f
        // 34 of 88 boundary edges touch the person: old 25% rule accepted it.
        for (y in 0..9) for (x in 3..36) c[5][y*w+x] = 1f
        val result = PersonMaskUnion.combine(c, w, w)
        assertEquals(0f, result[4*w+20], 0f)
        assertEquals(1f, result[20*w+20], 0f)
    }

    @Test fun retainsBorderAccessoryWhenMostOfBoundaryIsPersonSupported() {
        val w = 40
        val c = List(6) { FloatArray(w * w) }
        for (y in 0..35) for (x in 2..37) c[1][y*w+x] = 1f
        // Synthetic cropped accessory, enclosed by person on its three visible sides.
        for (y in 0..10) for (x in 15..25) {
            c[1][y*w+x] = 0f
            c[5][y*w+x] = .8f
        }
        val result = PersonMaskUnion.combine(c, w, w)
        assertEquals(.8f, result[4*w+20], 0f)
        assertEquals(1f, result[20*w+20], 0f)
    }
}
