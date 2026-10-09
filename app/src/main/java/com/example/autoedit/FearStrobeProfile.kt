package com.example.autoedit

import java.io.File
import java.security.MessageDigest

/** Frame-measured reproduction of the user's author-owned FEAR edit. */
internal object FearStrobeProfile {
    const val ID = "FEAR_STROBE_V1"
    const val AUTHOR_TRACK_ID = "fear_strobe_author"
    const val REFERENCE_FPS = 30
    const val OUTPUT_DURATION_US = 18_300_000L
    const val REFERENCE_CONTAINER_DURATION_US = 18_319_000L
    const val LAST_VISUAL_END_US = 17_000_000L
    const val TITLE_START_US = 3_600_000L
    const val TITLE_END_US = 5_100_000L

    enum class SceneRole { OPENER, TITLE_CLOSE_UP, CASCADE, BLACK_TAIL }
    enum class PulseKind { BLACK, WHITE }

    data class Scene(val startUs: Long, val endUs: Long, val role: SceneRole) {
        init { require(startUs >= 0L && endUs > startUs) }
    }

    data class Pulse(
        val startUs: Long,
        val endUs: Long,
        val kind: PulseKind,
        val shutterFrame: Boolean = false
    ) {
        init { require(startUs >= 0L && endUs > startUs) }
        fun contains(timeUs: Long): Boolean = timeUs in startUs until endUs
    }

    data class TitleSample(val atlasRow: Int, val opacity: Float)

    private val sceneStartsUs = listOf(
        0L, 3_600_000L, 5_100_000L, 5_566_667L, 6_000_000L, 6_566_667L,
        7_066_667L, 7_533_333L, 8_033_333L, 8_533_333L, 9_033_333L,
        9_533_333L, 10_000_000L, 10_300_000L, 10_666_667L, 10_966_667L,
        11_433_333L, 11_866_667L, 12_433_333L, 12_933_333L, 13_400_000L,
        13_900_000L, 14_400_000L, 14_900_000L, 15_400_000L, 15_866_667L,
        16_200_000L, 16_533_333L, 16_833_333L, 17_000_000L
    )

    val scenes: List<Scene> = sceneStartsUs.mapIndexed { index, startUs ->
        Scene(
            startUs,
            sceneStartsUs.getOrElse(index + 1) { OUTPUT_DURATION_US },
            when (index) {
                0 -> SceneRole.OPENER
                1 -> SceneRole.TITLE_CLOSE_UP
                sceneStartsUs.lastIndex -> SceneRole.BLACK_TAIL
                else -> SceneRole.CASCADE
            }
        )
    }

    private fun framePtsUs(frameIndex: Int): Long =
        kotlin.math.round(frameIndex * 1_000_000.0 / REFERENCE_FPS).toLong()

    private fun framePulse(frameIndex: Int, kind: PulseKind, shutter: Boolean = false) = Pulse(
        framePtsUs(frameIndex), framePtsUs(frameIndex + 1), kind, shutter
    )

    /** The two one-second shutter phrases contain 15 alternating black frames each. */
    val shutterPulses: List<Pulse> = buildList {
        (300..328 step 2).forEach { add(framePulse(it, PulseKind.BLACK, shutter = true)) }
        (476..504 step 2).forEach { add(framePulse(it, PulseKind.BLACK, shutter = true)) }
    }

    /** Frames 505..509 are white, white, black, white, white; the remaining tail is black. */
    val finalePulses: List<Pulse> = listOf(
        Pulse(framePtsUs(505), framePtsUs(507), PulseKind.WHITE),
        framePulse(507, PulseKind.BLACK),
        Pulse(framePtsUs(508), framePtsUs(510), PulseKind.WHITE),
        Pulse(framePtsUs(510), OUTPUT_DURATION_US, PulseKind.BLACK)
    )

    val pulses: List<Pulse> = shutterPulses + finalePulses

    fun pulseAt(timeUs: Long): Pulse? = pulses.firstOrNull { it.contains(timeUs) }

    fun titleAt(timeUs: Long): TitleSample? {
        if (timeUs !in TITLE_START_US until TITLE_END_US) return null
        val opacity = when {
            timeUs < 4_000_000L -> .18f + .27f *
                ((timeUs - TITLE_START_US).toFloat() / 400_000f).coerceIn(0f, 1f)
            timeUs < 4_500_000L -> .45f + .55f *
                ((timeUs - 4_000_000L).toFloat() / 500_000f).coerceIn(0f, 1f)
            else -> 1f
        }
        return TitleSample(0, opacity)
    }

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
        outputDurationMs == OUTPUT_DURATION_US / 1_000L && sourceDurationMs >= 6_000L

    private const val AUTHOR_TRACK_SHA256 =
        "2fc09a0cb163ad9eb6cdda3095fcf103ab4aab7b9dc52ec3a3e5564b085bce37"
}
