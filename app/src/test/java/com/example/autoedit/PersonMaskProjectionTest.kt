package com.example.autoedit

import org.junit.Assert.*
import org.junit.Test

class PersonMaskProjectionTest {
    @Test fun background_center_does_not_hide_a_person_at_the_patch_edge() {
        val mask = FrameAttachments.Plane(13, 9, FloatArray(13 * 9) { i ->
            if (i % 13 == 9 && i / 13 == 6) 1f else 0f
        }, 1f)
        assertEquals(0f, PersonMaskProjection.sample(mask, 13, 9, 6, 4), 0f)
        assertEquals(1f, PersonMaskProjection.patchMaximum(mask, 13, 9, 6, 4, 3, 2), 0f)
        assertEquals(0f, PersonMaskProjection.patchMaximum(mask, 13, 9, 6, 4, 2, 2), 0f)
    }

    @Test fun zero_radius_patch_is_the_exact_center_even_when_mask_resolution_differs() {
        val mask = FrameAttachments.Plane(12, 12, FloatArray(144) { it / 144f }, 1f)
        // Native center (4.5, 6.5) in the affine mask: (6.5 * 12 + 4.5) / 144.
        assertEquals(82.5f / 144f, PersonMaskProjection.sample(mask, 6, 6, 2, 3), .000001f)
        assertEquals(82.5f / 144f, PersonMaskProjection.patchMaximum(mask, 6, 6, 2, 3, 0, 0), .000001f)
        assertEquals(82.5f / 144f, PersonMaskProjection.patchMinimum(mask, 6, 6, 2, 3, 0, 0), .000001f)
    }

    @Test fun equal_resolution_samples_exactly_the_same_texel() {
        val mask = FrameAttachments.Plane(7, 9, FloatArray(63) { it / 63f }, 1f)
        for (y in 0 until 9) for (x in 0 until 7)
            assertEquals(mask.values[y * 7 + x], PersonMaskProjection.sample(mask, 7, 9, x, y), 0f)
    }

    @Test fun normalized_pixel_centers_reproduce_an_affine_probability_field() {
        val mask = FrameAttachments.Plane(12, 16, FloatArray(12 * 16) { i ->
            .5f * (i % 12 / 11f + i / 12 / 15f)
        }, 1f)
        val value = PersonMaskProjection.sample(mask, 6, 8, 2, 3)
        assertEquals(.5f * (4.5f / 11f + 6.5f / 15f), value, .000001f)
        val legacy = mask.values[(3 * 16 / 8) * 12 + 2 * 12 / 6]
        assertNotEquals("Edge-index projection samples a different physical location", legacy, value, .000001f)
    }

    @Test fun a_half_person_boundary_must_not_be_falsely_labelled_clean_background() {
        val mask = FrameAttachments.Plane(12, 12, FloatArray(144) { i -> if (i % 12 >= 5) 1f else 0f }, 1f)
        val legacy = mask.values[(2 * 12 / 6) * 12 + 2 * 12 / 6]
        val centered = PersonMaskProjection.sample(mask, 6, 6, 2, 2)
        assertEquals(0f, legacy, 0f)
        assertEquals(.5f, centered, 0f)
        assertTrue("Camera background cutoff is .15, so this boundary is not clean background", centered > .15f)
    }

    @Test fun anisotropic_fractional_resize_clamps_edges_and_preserves_constants() {
        val mask = FrameAttachments.Plane(3, 5, FloatArray(15) { .83f }, 1f)
        for (y in 0 until 17) for (x in 0 until 11)
            assertEquals(.83f, PersonMaskProjection.sample(mask, 11, 17, x, y), .000001f)
    }

    @Test fun patch_minimum_includes_a_background_edge_and_rejects_out_of_frame_queries() {
        val mask = FrameAttachments.Plane(7, 7, FloatArray(49) { if (it == 4 * 7 + 4) 0f else 1f }, 1f)
        assertEquals(1f, PersonMaskProjection.patchMinimum(mask, 7, 7, 3, 3, 0, 0), 0f)
        assertEquals(0f, PersonMaskProjection.patchMinimum(mask, 7, 7, 3, 3, 1, 1), 0f)
        assertEquals(1f, PersonMaskProjection.patchMaximum(mask, 7, 7, 3, 3, 1, 1), 0f)
        assertThrows(IllegalArgumentException::class.java) { PersonMaskProjection.patchMinimum(mask, 7, 7, 0, 3, 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { PersonMaskProjection.patchMaximum(mask, 7, 7, 3, 6, 1, 1) }
    }
}
