package com.example.autoedit

import java.io.File
import java.security.MessageDigest

/** Measured production Heartbeat recipe; deliberately separate from Sigma. */
internal object HeartbeatMontageProfile {
    const val ID = "HEARTBEAT_V1"
    const val AUTHOR_TRACK_ID = "heartbeat_author"
    const val REFERENCE_FPS = 60
    const val LAST_VISUAL_END_US = 19_800_000L
    const val OUTPUT_DURATION_US = 21_166_667L
    // Exact decoded interval after the final dark beat and before the terminal white pulse.
    // The author reference restores the hero as several horizontally separated live copies.
    const val FINALE_ECHO_START_US = 19_666_667L
    const val FINALE_ECHO_END_US = 19_766_667L
    enum class PulseKind { DARK, WHITE }
    enum class EchoEnvelope { RESOLVE, PERSIST, BUILD }
    enum class OpeningTitle(val atlasRow: Int, val text: String) {
        HEART(0, "HEART"),
        HEARTBEAT(1, "HEARTBEAT"),
        MY(2, "MY"),
        MY_HEARTBEAT(3, "MY HEARTBEAT")
    }
    data class OpeningTitleCue(val startUs: Long, val endUs: Long, val title: OpeningTitle) {
        init { require(startUs >= 0L && endUs > startUs) }
        fun contains(timeUs: Long) = timeUs >= startUs && timeUs < endUs
    }
    data class Pulse(val startUs: Long, val endUs: Long, val kind: PulseKind) {
        init { require(startUs >= 0 && endUs > startUs) }
        fun contains(timeUs: Long) = timeUs >= startUs && timeUs < endUs
    }
    data class LightAccent(
        val startUs: Long,
        val endUs: Long,
        /** Measured plateau luminance in the decoded author reference. */
        val targetLuma: Float,
        /** Rendering hypotheses derived from the current Samsung candidate, not source evidence. */
        val entryExposure: Float,
        val plateauExposure: Float,
        val exitExposure: Float
    ) {
        init {
            require(startUs >= 0L && endUs > startUs)
            require(targetLuma in 0f..1f)
            require(entryExposure >= 0f && plateauExposure >= entryExposure && exitExposure >= 0f)
        }
    }
    // Decoded luminance runs; pulse existence is measured, musical intent is not yet inferred.
    val pulses = listOf(
        Pulse(100_000,133_333,PulseKind.DARK),
        Pulse(650_000,666_667,PulseKind.WHITE),
        Pulse(1_766_667,1_783_333,PulseKind.WHITE),
        Pulse(2_700_000,2_716_667,PulseKind.WHITE),
        Pulse(3_033_333,3_066_667,PulseKind.DARK),
        Pulse(4_800_000,4_833_333,PulseKind.DARK),
        Pulse(6_833_333,6_866_667,PulseKind.DARK),
        Pulse(8_850_000,8_883_333,PulseKind.DARK),
        Pulse(9_733_333,9_766_667,PulseKind.DARK),
        Pulse(10_050_000,10_066_667,PulseKind.DARK),
        Pulse(10_550_000,10_566_667,PulseKind.DARK),
        Pulse(10_816_667,10_833_333,PulseKind.DARK),
        Pulse(11_033_333,11_050_000,PulseKind.DARK),
        Pulse(11_316_667,11_333_333,PulseKind.DARK),
        Pulse(11_633_333,11_650_000,PulseKind.DARK),
        Pulse(12_816_667,12_850_000,PulseKind.DARK),
        Pulse(14_850_000,14_883_333,PulseKind.DARK),
        Pulse(16_866_667,16_900_000,PulseKind.DARK),
        Pulse(17_750_000,17_783_333,PulseKind.DARK),
        Pulse(18_066_667,18_083_333,PulseKind.DARK),
        Pulse(18_566_667,18_583_333,PulseKind.DARK),
        Pulse(18_833_333,18_850_000,PulseKind.DARK),
        Pulse(19_050_000,19_066_667,PulseKind.DARK),
        Pulse(19_333_333,19_350_000,PulseKind.DARK),
        Pulse(19_650_000,19_666_667,PulseKind.DARK),
        Pulse(19_766_667,LAST_VISUAL_END_US,PulseKind.WHITE)
    )
    fun pulseAt(timeUs: Long): Pulse? = pulses.firstOrNull { it.contains(timeUs) }

    /** Synchronized first/reprise frame pairs show three distinct echo phrases. The first four
     * reprise shots settle from separated contours, the 14.183 s hero keeps a readable residue,
     * and the six final shots build their displaced copies toward the next musical punctuation. */
    fun echoEnvelope(index: Int): EchoEnvelope = when (index) {
        in 0..3 -> EchoEnvelope.RESOLVE
        4 -> EchoEnvelope.PERSIST
        in 5..10 -> EchoEnvelope.BUILD
        else -> error("No Heartbeat echo envelope for index $index")
    }

    fun echoOpacityFactor(index: Int, progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        val eased = p * p * (3f - 2f * p)
        return when (echoEnvelope(index)) {
            EchoEnvelope.RESOLVE -> 1f - eased
            EchoEnvelope.PERSIST -> maxOf(.22f, 1f - eased)
            EchoEnvelope.BUILD -> .18f + .82f * eased
        }
    }

    /** Text itself and its order are visible in the decoded author reference. The first two
     * switch points are midpoint estimates inside the 500 ms contact-sheet intervals; the last
     * is narrowed by the independently decoded 1.733 s cut-pair. Keep that timing uncertainty
     * explicit instead of presenting lyric alignment as a measured beat map. */
    val openingTitles = listOf(
        OpeningTitleCue(0L, 300_000L, OpeningTitle.HEART),
        OpeningTitleCue(300_000L, 1_266_667L, OpeningTitle.HEARTBEAT),
        OpeningTitleCue(1_266_667L, 1_650_000L, OpeningTitle.MY),
        OpeningTitleCue(1_650_000L, 2_683_333L, OpeningTitle.MY_HEARTBEAT)
    )
    fun openingTitleAt(timeUs: Long): OpeningTitle? =
        openingTitles.firstOrNull { it.contains(timeUs) }?.title

    /** The same bright role appears in both phrases. Reference frame luma rises above .90
     * through the middle of each 500 ms window and remains bright until the following cut. */
    val lightAccents = listOf(
        LightAccent(7_283_333L, 7_783_333L, targetLuma = .89f,
            entryExposure = .75f, plateauExposure = 3f, exitExposure = 1.5f),
        LightAccent(15_300_000L, 15_800_000L, targetLuma = .94f,
            entryExposure = .75f, plateauExposure = 3f, exitExposure = 1.5f)
    )

    // Verified Samsung source: role 2 is the cleanest centred head-and-shoulders portrait
    // (quality .895, zero occlusion) for the two measured light peaks. The separate role-8
    // scene must not be collapsed into it merely because it also contains the subject.
    // Role 7 remains a valid animal shot, but its human matte also includes the couch/animal.
    const val SUBJECT_PORTRAIT_ROLE = 2
    const val SUBJECT_GESTURE_ROLE = 3
    const val FINALE_PORTRAIT_ROLE = 4
    // Current author reference opens at decoded luma ~.09 while the verified source opening is
    // ~.38. The first -.72 render decoded at .033, below the .087 reference checkpoint;
    // interpolation between those real renders gives -.61 for the opening-only correction.
    const val OPENING_EXPOSURE_BIAS = -.61f

    /** Guards the product route against silently rendering this fixed choreography with Sigma's
     * score. The file is the audio extracted from Leonid's author-owned Heartbeat reference. */
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

    /** Boundary candidates checked against adjacent decoded images. White pulses conceal
     * the exact cut for the first two boundaries; retain that uncertainty explicitly. */
    data class Scene(val startUs: Long, val endUs: Long, val sourceRole: Int,
        val echo: Boolean, val targetLuma: Float, val boundaryUncertaintyUs: Long = 0) {
        init { require(targetLuma in .05f.. .95f) }
    }
    private val startsUs = listOf(0L,650_000L,1_766_667L,2_683_333L,
        3_733_333L,4_233_333L,5_200_000L,5_716_667L,6_166_667L,
        7_283_333L,7_783_333L,8_300_000L,8_700_000L,9_633_333L,10_650_000L,
        11_750_000L,12_250_000L,13_216_667L,13_733_333L,14_183_333L,
        15_300_000L,15_800_000L,16_316_667L,16_716_667L,17_650_000L,18_666_667L)
    // Median decoded luma inside each author-reference scene, excluding 50 ms at both cut edges.
    // These are style measurements, not assumptions about the user's source exposure.
    private val targetLumas = listOf(
        .092f, .336f, .340f, .413f, .665f, .310f, .278f, .389f, .488f,
        .882f, .528f, .326f, .265f, .555f, .666f, .677f, .295f, .275f,
        .391f, .508f, .914f, .527f, .360f, .250f, .556f, .693f
    )
    // Median face-region width for each authored role. Reprised roles use the median of both
    // measured appearances. Roles 0 and 4 deliberately remain unknown: the detector loses the
    // dark opening face and the fast full-body action respectively, so fabricating their scale
    // would turn missing evidence into a source-selection rule.
    private val targetFaceWidthsByRole = listOf<Float?>(
        null, .400f, .510f, .254f, null, .521f, .316f, .255f, .285f,
        .400f, .450f, .150f, .334f, .363f, .450f
    )
    fun targetFaceWidthForRole(role: Int): Float? = targetFaceWidthsByRole.getOrNull(role)

    // Median within-shot camera + subject motion after excluding the first 200 ms at each cut.
    // Reprised roles combine both appearances. These values select live source motion; they do not
    // synthesize shake or change source PTS.
    private val targetMotionByRole = listOf(
        .145f, .143f, .588f, .547f, .288f, .232f, .418f, .059f, .433f,
        .381f, .645f, .416f, .322f, .079f, .535f
    )
    fun targetMotionForRole(role: Int): Float? = targetMotionByRole.getOrNull(role)

    val scenes: List<Scene> = startsUs.mapIndexed { index, start ->
        Scene(start, startsUs.getOrElse(index + 1) { LAST_VISUAL_END_US },
            if (index < 15) index else index - 11,
            echo = index >= 15,
            targetLuma = targetLumas[index],
            boundaryUncertaintyUs = when (index) { 0 -> 0L; 1, 2 -> 16_667L; else -> 33_333L })
    }
    fun sceneAt(timeUs: Long): Scene? = scenes.firstOrNull { timeUs >= it.startUs && timeUs < it.endUs }

    private const val AUTHOR_TRACK_SHA256 =
        "cc98cef76d1aaf27e38acaf1bc4f8e28a604d6fe6a9b63a329bf0f0669e2ef48"
}
