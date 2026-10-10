package com.veycad.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodedEffectSignatureTest {
    @Test fun black_blocks_require_visible_content_in_the_corresponding_source_region() {
        val source = FloatArray(48 * 72) { .4f }
        val output = source.copyOf()
        for (y in 0 until 36) for (x in 0 until 24) output[y * 48 + x] = 0f
        assertEquals(.25f, DecodedEffectSignature.introducedBlackBlocks(output, source, 48, 72), .000001f)
        assertEquals(0f, DecodedEffectSignature.introducedBlackBlocks(source, source, 48, 72), 0f)
    }

    @Test fun grading_already_dark_clothing_does_not_create_a_black_block() {
        val source = FloatArray(48 * 72) { .04f + it % 7 * .007f }
        val output = source.map { it * .24f }.toFloatArray()
        assertEquals(0f, DecodedEffectSignature.introducedBlackBlocks(output, source, 48, 72), 0f)
        assertEquals(1f, DecodedEffectSignature.introducedBlackBlocks(FloatArray(source.size),
            FloatArray(source.size) { .18f }, 48, 72), 0f)
    }

    @Test fun darkened_background_below_dead_tile_luma_is_not_an_introduced_block() {
        val source = FloatArray(48 * 72) { .14f }
        val graded = FloatArray(source.size) { .021f }
        assertEquals(0f, DecodedEffectSignature.introducedBlackBlocks(graded, source, 48, 72), 0f)
    }

    private val width = 48
    private val height = 72
    private fun texture(seed: Long): FloatArray {
        val random = java.util.Random(seed)
        return FloatArray(width * height) { .1f + random.nextFloat() * .8f }
    }

    @Test fun horizontal_fragments_require_different_row_offsets_relative_to_source() {
        val source = texture(42)
        val fragmented = FloatArray(source.size) { index ->
            val y = index / width
            val offset = intArrayOf(0, 4, -4)[y / 8 % 3]
            source[y * width + (index % width + offset).coerceIn(0, width - 1)]
        }
        assertTrue(DecodedEffectSignature.horizontalFragmentation(fragmented, source, width, height) > .007f)
        val graded = source.map { it * .7f + .1f }.toFloatArray()
        assertEquals(0f, DecodedEffectSignature.horizontalFragmentation(graded, source, width, height), .000001f)
    }

    @Test fun whole_frame_camera_shift_is_not_horizontal_fragmentation() {
        val source = texture(42)
        val shifted = FloatArray(source.size) { index ->
            source[(index / width + 2).coerceAtMost(height - 1) * width +
                (index % width + 4).coerceAtMost(width - 1)]
        }
        assertEquals(0f, DecodedEffectSignature.horizontalFragmentation(shifted, source, width, height), .000001f)
    }

    @Test fun flat_and_repeating_textures_do_not_create_false_fragments() {
        for (source in listOf(FloatArray(width * height) { .5f },
            FloatArray(width * height) { if (it % width % 4 < 2) .2f else .8f })) {
            assertEquals(0f, DecodedEffectSignature.horizontalFragmentation(source, source, width, height), .000001f)
        }
    }

    @Test fun unrelated_texture_cannot_supply_fragment_offsets() {
        assertEquals(0f, DecodedEffectSignature.horizontalFragmentation(texture(99), texture(42), width, height), 0f)
    }

    @Test fun double_exposure_signature_requires_repeated_edge_energy() {
        val width = 24
        val height = 18
        val flat = FloatArray(width * height) { .5f }
        val echoed = FloatArray(width * height) { index ->
            if ((index % width) % 4 < 2) .15f else .85f
        }

        assertEquals(0f, DecodedEffectSignature.doubleExposure(flat, width, height), .001f)
        assertTrue(DecodedEffectSignature.doubleExposure(echoed, width, height) > .2f)
    }

    @Test fun mirror_signature_detects_one_vertical_split_without_rewarding_horizontal_bands() {
        val width = 24
        val height = 72
        val progress = .5f
        val flat = FloatArray(width * height) { .5f }
        val split = FloatArray(width * height) { index ->
            val x = index % width
            if (x < width / 2) .18f else .82f
        }
        val horizontalBands = FloatArray(width * height) { index ->
            val y = index / width
            if ((y / 6) % 2 == 0) .18f else .82f
        }

        assertEquals(0f, DecodedEffectSignature.mirrorSlice(flat, width, height, progress), .001f)
        assertTrue(DecodedEffectSignature.mirrorSlice(split, width, height, progress) > .5f)
        assertEquals(
            0f,
            DecodedEffectSignature.mirrorSlice(horizontalBands, width, height, progress),
            .001f
        )
    }

    @Test fun glitch_signature_detects_chroma_edges_without_matching_luma_edges() {
        val width = 24
        val height = 18
        val luma = FloatArray(width * height) { .5f }
        val neutral = FloatArray(width * height) { .5f }
        val displacedU = FloatArray(width * height) { index ->
            if ((index % width) % 2 == 0) .2f else .8f
        }

        assertEquals(0f, DecodedEffectSignature.glitch(luma, neutral, neutral, width, height), .001f)
        assertTrue(DecodedEffectSignature.glitch(luma, displacedU, neutral, width, height) > .5f)
    }
}
