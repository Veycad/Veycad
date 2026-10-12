package com.veycad.app

data class ExportProfile(val aspect: ProjectAspect, val quality: ExportQuality,
    val size: OutputSize, val fps: Int, val bitrate: Int, val encoderName: String)

/** A declared 4K candidate is not ready for export until requiresSurfaceVerification is false. */
data class ExportAvailability(val profile: ExportProfile?, val reason: String?,
    val requiresSurfaceVerification: Boolean = false)

object ExportProfileResolver {
    fun resolve(aspect: ProjectAspect, quality: ExportQuality, fps: Int,
                capabilities: EncoderCapabilityProbe.Capabilities): ExportAvailability {
        if (fps <= 0) return ExportAvailability(null, "Некорректная частота кадров")
        if (capabilities.mime != "video/avc") return ExportAvailability(null, "Требуется H.264 Surface encoder")
        val size = aspect.size(quality.shortEdge)
        if (!capabilities.supports(size, fps)) {
            return ExportAvailability(null, "Кодек не поддерживает точный размер ${size.width}x${size.height} при $fps FPS")
        }
        val area = size.width.toDouble() * size.height
        val referenceArea = if (quality == ExportQuality.K4) 1080.0 * 1920
            else quality.shortEdge.toDouble() * quality.shortEdge * 16 / 9
        val base = if (quality == ExportQuality.P720) 5_000_000.0 else 8_000_000.0
        // Starting setting, not a promise of visual quality: MP4 acceptance remains necessary.
        val bitrate = (base * area / referenceArea * fps / 30)
            .coerceIn(capabilities.bitrateMin.toDouble(), capabilities.bitrateMax.toDouble()).toInt()
        val profile = ExportProfile(aspect, quality, size, fps, bitrate, capabilities.codecName)
        val verified = capabilities.surfaceStartResult(profile)
        if (verified == false) return ExportAvailability(null, "Кодек не смог запустить этот профиль")
        return ExportAvailability(profile, null, quality == ExportQuality.K4 && verified != true)
    }
}
