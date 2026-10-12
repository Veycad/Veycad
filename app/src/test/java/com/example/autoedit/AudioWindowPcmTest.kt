package com.veycad.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioWindowPcmTest {
    private val stereo = pcm(10, 11, 20, 21, 30, 31, 40, 41)

    @Test fun stereo_wrap_preserves_whole_channel_frames() {
        assertArrayEquals(pcm(30, 31, 40, 41, 10, 11),
            AudioWindowPcm.slice(stereo, 4, 2, 500_000L, 750_000L))
    }

    @Test fun mono_can_wrap_several_times() {
        assertArrayEquals(pcm(40, 10, 20, 30, 40, 10, 20, 30, 40, 10),
            AudioWindowPcm.slice(pcm(10, 20, 30, 40), 4, 1, 750_000L, 2_500_000L))
    }

    @Test fun stereo_can_wrap_several_times() {
        assertArrayEquals(pcm(40, 41, 10, 11, 20, 21, 30, 31, 40, 41, 10, 11),
            AudioWindowPcm.slice(stereo, 4, 2, 750_000L, 1_500_000L))
    }

    @Test fun fractional_48khz_window_uses_absolute_end_to_include_boundary_sample() {
        // floor(21us * 48kHz) = 1, floor(42us * 48kHz) = 2.
        assertArrayEquals(pcm(20, 21), AudioWindowPcm.slice(stereo, 48_000, 2, 21L, 21L))
        assertArrayEquals(pcm(10, 11), AudioWindowPcm.slice(stereo, 48_000, 2, 20L, 1L))
    }

    @Test fun adjacent_fractional_windows_concatenate_to_identical_absolute_samples() {
        val left = AudioWindowPcm.slice(stereo, 48_000, 2, 5L, 16L)
        val right = AudioWindowPcm.slice(stereo, 48_000, 2, 21L, 21L)
        val whole = AudioWindowPcm.slice(stereo, 48_000, 2, 5L, 37L)
        assertArrayEquals(pcm(10, 11, 20, 21), whole)
        assertArrayEquals(whole, left + right)
    }

    @Test fun large_absolute_offset_is_exact_despite_intermediate_product_overflow() {
        // Indices are 432000000000000 and 432000000000002; track phase is zero.
        assertArrayEquals(pcm(10, 11, 20, 21),
            AudioWindowPcm.slice(stereo, 48_000, 2, 9_000_000_000_000_005L, 37L))
    }

    @Test fun copied_prepared_pcm_keeps_amplitude_sign_balance_and_source_bytes() {
        val source = pcm(1, -2, Short.MAX_VALUE.toInt(), Short.MIN_VALUE.toInt())
        val original = source.copyOf()
        val result = AudioWindowPcm.slice(source, 2, 2, 500_000L, 1_000_000L)
        assertArrayEquals(pcm(Short.MAX_VALUE.toInt(), Short.MIN_VALUE.toInt(), 1, -2), result)
        assertArrayEquals(original, source)
        assertNotSame(source, result)
    }

    @Test fun a_window_shorter_than_sample_boundary_is_empty() {
        assertArrayEquals(byteArrayOf(), AudioWindowPcm.slice(stereo, 48_000, 2, 5L, 5L))
    }

    @Test fun zero_duration_is_empty() {
        assertArrayEquals(byteArrayOf(), AudioWindowPcm.slice(stereo, 4, 2, 750_000L, 0L))
    }

    @Test fun empty_or_partial_pcm_frame_is_rejected_even_for_zero_duration() {
        for ((bytes, channels) in listOf(
            byteArrayOf() to 1, byteArrayOf(0) to 1, byteArrayOf(0, 1) to 2,
            byteArrayOf(0, 1, 2, 3, 4, 5) to 2
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                AudioWindowPcm.slice(bytes, 4, channels, 0L, 0L)
            }
        }
    }

    @Test fun invalid_rate_channels_or_negative_times_are_rejected() {
        for ((rate, channels) in listOf(0 to 2, -1 to 2, 4 to 0, 4 to -1, 4 to Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                AudioWindowPcm.slice(stereo, rate, channels, 0L, 0L)
            }
        }
        for ((start, duration) in listOf(-1L to 0L, 0L to -1L)) {
            assertThrows(IllegalArgumentException::class.java) {
                AudioWindowPcm.slice(stereo, 4, 2, start, duration)
            }
        }
    }

    @Test fun overflowing_global_end_is_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AudioWindowPcm.slice(stereo, 4, 2, Long.MAX_VALUE, 1L)
        }
    }

    @Test fun unrepresentable_absolute_frame_index_is_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AudioWindowPcm.slice(stereo, Int.MAX_VALUE, 2, Long.MAX_VALUE - 1L, 1L)
        }
    }

    @Test fun unrepresentable_output_byte_count_is_rejected_before_allocation() {
        assertThrows(IllegalArgumentException::class.java) {
            AudioWindowPcm.slice(stereo, 48_000, 2, 0L, Long.MAX_VALUE)
        }
    }

    @Test fun exact_output_byte_limit_is_supported_without_truncation() {
        val result = AudioWindowPcm.slice(stereo, 1_000_000, 2, 0L, 8_388_608L)
        assertEquals(32 * 1024 * 1024, result.size)
        assertArrayEquals(pcm(10, 11), result.copyOfRange(0, 4))
        assertArrayEquals(pcm(40, 41), result.copyOfRange(result.size - 4, result.size))
    }

    @Test fun one_frame_over_output_byte_limit_is_rejected_before_allocation() {
        assertThrows(IllegalArgumentException::class.java) {
            AudioWindowPcm.slice(stereo, 1_000_000, 2, 0L, 8_388_609L)
        }
    }

    private fun pcm(vararg samples: Int): ByteArray =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            samples.forEach { putShort(it.toShort()) }
        }.array()
}
