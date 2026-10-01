package com.example.autoedit

import java.io.File
import java.security.MessageDigest

/** Measured two-source call-and-response grammar from the user's author-owned reference. */
internal object DualityLoopProfile {
    const val ID = "DUALITY_LOOP_V1"
    const val AUTHOR_TRACK_ID = "duality_loop_author"
    const val OUTPUT_DURATION_US = 18_300_000L
    const val MINIMUM_SOURCE_DURATION_MS = 6_000L
    // Container duration can extend past the last decodable frame. Keep two 30 fps frames
    // of margin so a retimed range doesn't drain reordered tail buffers at physical EOS.
    const val SOURCE_END_GUARD_MS = 70L

    /** Long setup, two near-identical rhythmic phrases, then an original unbranded release. */
    val boundariesMs: List<Long> = listOf(
        0L, 3_700L,
        4_200L, 4_800L, 5_333L, 5_933L, 6_467L, 7_033L, 7_567L,
        8_167L, 8_733L, 9_267L, 9_800L, 10_367L,
        10_867L, 11_467L, 12_000L, 12_600L, 13_133L, 13_700L, 14_233L,
        14_833L, 15_400L, 15_933L, 16_467L,
        OUTPUT_DURATION_US / 1_000L
    )

    fun matchesAudio(file: File): Boolean {
        if (!file.isFile) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        } == AUTHOR_TRACK_SHA256
    }

    fun appliesTo(graph: MontageGraph): Boolean = graph.metadata.generator.startsWith(ID)

    fun canExtendSourceToTimeline(sourceDurationMs: Long, outputDurationMs: Long): Boolean =
        outputDurationMs == OUTPUT_DURATION_US / 1_000L &&
            sourceDurationMs >= MINIMUM_SOURCE_DURATION_MS

    private const val AUTHOR_TRACK_SHA256 =
        "ff759c051b806564e6012423d237df57f0798ce52825f7b24179dc20072cb3de"
}
