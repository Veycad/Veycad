package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class SourceFramingTest {
    @Test fun cropped_source_has_output_aspect_for_every_orientation_and_format() {
        for ((width, height) in listOf(1920 to 1080, 1440 to 1080, 1080 to 1080, 1080 to 1920)) {
            for (rotation in listOf(0, 90, 180, 270)) {
                for ((outputWidth, outputHeight) in listOf(719 to 1277, 1277 to 719, 719 to 719, 719 to 899)) {
                    val crop = SourceFraming.crop(width, height, rotation, outputWidth, outputHeight)
                    val aspect = if (rotation % 180 == 0) width.toFloat() / height else height.toFloat() / width
                    assertEquals(outputWidth.toFloat() / outputHeight, aspect * crop.x / crop.y, .00001f)
                    assertEquals(.5f, crop.sourceX(.5f), 0f)
                    assertEquals(.5f, crop.sourceY(.5f), 0f)
                    assertTrue(crop.x in 0f..1f && crop.y in 0f..1f)
                    assertTrue(crop.x == 1f || crop.y == 1f)
                }
            }
        }
    }

    @Test fun non_square_pixels_and_mixed_sources_have_independent_crops() {
        val anamorphic = SourceFraming.crop(1440, 1080, 0, 720, 1280, 4f / 3f)
        val wide = SourceFraming.crop(1920, 1080, 0, 720, 1280)
        val portrait = SourceFraming.crop(1080, 1920, 0, 720, 1280)
        assertEquals(wide.x, anamorphic.x, .00001f)
        assertEquals(SourceFraming.Crop(1f, 1f), portrait)
        assertTrue(wide.x < portrait.x)
    }

    @Test fun source_mask_is_projected_through_the_same_center_crop_as_video() {
        val values = FloatArray(8 * 8) { index -> if (index % 8 in 3..4) 1f else 0f }
        val cropped = SourceFraming.cropPlane(values, 8, 8, SourceFraming.Crop(.5f, 1f))
        for (y in 0 until 8) for (x in 0 until 8) {
            assertEquals(if (x in 2..5) 1f else 0f, cropped[y * 8 + x], 0f)
        }
        assertSame(values, SourceFraming.cropPlane(values, 8, 8, SourceFraming.Crop(1f, 1f)))
    }

    @Test fun rotated_mask_keeps_physical_aspect_and_matches_pixel_space_camera_position() {
        val width = 360
        val height = 640
        val circle = FloatArray(width * height) { index ->
            val dx = index % width - 140
            val dy = index / width - 260
            if (dx * dx + dy * dy <= 900) 1f else 0f
        }
        val rotated = SemanticMaskMetrics.transform(circle, width, height, 1f, 0f, 0f,
            rotationDegrees = 30f, outputAspect = width.toFloat() / height)
        val occupied = rotated.indices.filter { rotated[it] > .5f }
        val minX = occupied.minOf { it % width }
        val maxX = occupied.maxOf { it % width }
        val minY = occupied.minOf { it / width }
        val maxY = occupied.maxOf { it / width }
        assertEquals(1f, (maxX - minX).toFloat() / (maxY - minY), .035f)
        assertEquals(115.36f, (minX + maxX) / 2f, 1f)
        assertEquals(288.04f, (minY + maxY) / 2f, 1f)
    }
}
