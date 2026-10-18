package com.veycad.app

/** Post-export contract, independent of Media3/MediaCodec implementation details. */
object ExportContract {
    data class Probe(
        val encodedWidth: Int,
        val encodedHeight: Int,
        val rotationDegrees: Int,
        val durationMs: Long,
        val audioMime: String? = null,
        val videoMime: String? = null,
        val videoFps: Int? = null
    )
    data class Verdict(val accepted: Boolean, val issues: List<String>)

    /** Geometry and codec gate. Canonical duration/PTS acceptance belongs to RenderedMp4Acceptance. */
    @Suppress("UNUSED_PARAMETER")
    fun validateExact(probe: Probe, profile: ExportProfile, targetDurationMs: Long): Verdict {
        val issues = buildList {
            if (probe.encodedWidth != profile.size.width || probe.encodedHeight != profile.size.height)
                add("encoded_dimensions_do_not_match_export")
            if (probe.rotationDegrees != 0) add("encoded_rotation_not_baked")
            if (probe.videoMime != "video/avc") add("encoded_video_is_not_avc")
            if (probe.audioMime != "audio/mp4a-latm") add("encoded_audio_is_not_aac")
            if (probe.videoFps != profile.fps) add("encoded_fps_do_not_match_export")
        }
        return Verdict(issues.isEmpty(), issues)
    }

    fun validate(probe: Probe, targetDurationMs: Long, requireFullHd: Boolean): Verdict {
        val portrait = if (probe.rotationDegrees % 180 != 0) {
            probe.encodedHeight to probe.encodedWidth
        } else probe.encodedWidth to probe.encodedHeight
        val (minWidth, minHeight) = if (requireFullHd) 1_000 to 1_700 else 650 to 1_100
        val issues = buildList {
            if (portrait.first < minWidth || portrait.second < minHeight) {
                add("Разрешение ${portrait.first}x${portrait.second} ниже контракта ${minWidth}x${minHeight}")
            }
            if (kotlin.math.abs(probe.durationMs - targetDurationMs) > 850L) {
                add("Длительность ${probe.durationMs} мс не совпадает с задачей $targetDurationMs мс")
            }
        }
        return Verdict(issues.isEmpty(), issues)
    }

    /**
     * VME owns the final pixels and AAC muxing, so its output must be portable without a
     * player-specific rotation workaround. A later Transformer pass can silently turn this
     * into landscape pixels and strip AAC, therefore this is deliberately stricter than the
     * legacy Media3 compatibility contract.
     */
    fun validateVmeFinal(probe: Probe, targetDurationMs: Long, requireFullHd: Boolean): Verdict {
        val issues = validate(probe, targetDurationMs, requireFullHd).issues.toMutableList()
        if (probe.encodedWidth >= probe.encodedHeight) issues += "VME-файл закодирован не вертикально"
        if (probe.rotationDegrees != 0) issues += "VME-файл требует rotation metadata"
        if (probe.audioMime != "audio/mp4a-latm") issues += "В VME-файле нет AAC-дорожки"
        return Verdict(issues.isEmpty(), issues)
    }
}
