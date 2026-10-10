package com.veycad.app

import java.io.File
import java.security.MessageDigest

/** Immutable identity and timing contract for Leonid's author-owned montage reference. */
object ReferenceMontageProfile {
    const val ID = "SUBJECT_REENTRY_PULSE_V2"
    const val OUTPUT_DURATION_MS = 18_034L
    const val AUTHOR_TRACK_ID = "leonid_reentry"
    const val GLITCH_START_US = 7_900_000L
    const val GLITCH_END_US = 8_180_000L
    const val GLITCH_AMOUNT = .48f

    /** Measured structural cut anchors; sub-beat shader accents remain separate effect events. */
    val DYNAMIC_BOUNDARIES_MS = listOf(
        0L,
        2_514L,
        3_500L,
        4_493L,
        5_480L,
        6_467L,
        7_465L,
        8_446L,
        9_439L,
        10_432L,
        11_424L,
        12_417L,
        13_404L,
        14_391L,
        15_383L,
        16_376L,
        OUTPUT_DURATION_MS
    )

    /** Nine phrase-defining accents measured from the author-owned reference audio sample clock. */
    val ACCENT_BEATS_US = listOf(
        551_473L,
        2_513_560L,
        4_493_061L,
        6_466_757L,
        8_446_258L,
        10_431_564L,
        12_416_870L,
        14_390_566L,
        16_375_873L
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
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) } ==
            AUTHOR_TRACK_SHA256
    }

    fun outputDurationMs(audioFile: File, sourceDurationMs: Long): Long? {
        if (!matchesAudio(audioFile)) return null
        require(canExtendSourceToTimeline(sourceDurationMs, OUTPUT_DURATION_MS)) {
            "$ID requires at least ${EditDurationPolicy.MINIMUM_MS} ms of source video"
        }
        return OUTPUT_DURATION_MS
    }

    /**
     * The authored music phrase is slightly longer than the product's 15 second capture floor.
     * Reference edits may therefore use slow playback inside unique source windows, but may never
     * manufacture coverage from a source that the capture contract itself considers too short.
     */
    fun canExtendSourceToTimeline(sourceDurationMs: Long, outputDurationMs: Long): Boolean =
        outputDurationMs == OUTPUT_DURATION_MS && sourceDurationMs >= EditDurationPolicy.MINIMUM_MS

    fun boundariesMs(outputDurationMs: Long): List<Long>? =
        DYNAMIC_BOUNDARIES_MS.takeIf { outputDurationMs == OUTPUT_DURATION_MS }

    fun appliesTo(graph: MontageGraph): Boolean = graph.metadata.generator.contains(ID)

    fun authoredOverlays(): List<MontageGraph.Overlay> = listOf(
        MontageGraph.Overlay(
            "reference-first-phrase-impact",
            2_514L,
            2_634L,
            "reference-accent-flash",
            MontageGraph.BlendMode.SCREEN,
            MontageGraph.OverlayKind.FLASH,
            .24f
        ),
        MontageGraph.Overlay(
            "reference-double-exposure-build",
            3_420L,
            4_180L,
            "reference-double-exposure",
            MontageGraph.BlendMode.SCREEN,
            MontageGraph.OverlayKind.DOUBLE_EXPOSURE,
            .52f
        ),
        MontageGraph.Overlay(
            "reference-hallway-echo",
            5_280L,
            5_920L,
            "reference-double-exposure",
            MontageGraph.BlendMode.SCREEN,
            MontageGraph.OverlayKind.DOUBLE_EXPOSURE,
            .48f
        ),
        MontageGraph.Overlay(
            "reference-office-echo-build",
            10_280L,
            11_160L,
            "reference-double-exposure",
            MontageGraph.BlendMode.SCREEN,
            MontageGraph.OverlayKind.DOUBLE_EXPOSURE,
            .50f
        ),
        MontageGraph.Overlay(
            "reference-office-echo-peak",
            11_340L,
            12_180L,
            "reference-double-exposure",
            MontageGraph.BlendMode.SCREEN,
            MontageGraph.OverlayKind.DOUBLE_EXPOSURE,
            .55f
        ),
        MontageGraph.Overlay(
            "reference-mirror-slice-peak",
            13_330L,
            14_120L,
            "reference-mirror-slice",
            MontageGraph.BlendMode.OVERLAY,
            MontageGraph.OverlayKind.MIRROR_SLICE,
            .60f
        ),
        MontageGraph.Overlay(
            "reference-double-exposure-release",
            14_850L,
            15_420L,
            "reference-double-exposure",
            MontageGraph.BlendMode.SCREEN,
            MontageGraph.OverlayKind.DOUBLE_EXPOSURE,
            .40f
        ),
        MontageGraph.Overlay(
            "reference-final-subject-stage",
            16_376L,
            OUTPUT_DURATION_MS,
            "reference-subject-stage",
            MontageGraph.BlendMode.OVERLAY,
            MontageGraph.OverlayKind.SUBJECT_STAGE,
            1f,
            red = .12f,
            green = .015f,
            blue = .18f
        ),
        MontageGraph.Overlay(
            "reference-final-black-release",
            17_150L,
            OUTPUT_DURATION_MS,
            "reference-black-fade",
            MontageGraph.BlendMode.MULTIPLY,
            MontageGraph.OverlayKind.BLACK_FADE,
            1f,
            red = 0f,
            green = 0f,
            blue = 0f
        )
    )

    private const val AUTHOR_TRACK_SHA256 =
        "4610d784b15416d5f88e7657358e32be5723ce0288d34436406244a004b1b03d"
}
