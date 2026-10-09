package com.example.autoedit

/** Shared CPU description of the authored live cutout entrance used by GLES and decoded QA. */
internal object ForegroundReentryMotion {
    const val TRAVEL = .78f

    fun verticalTravel(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        val eased = p * p * (3f - 2f * p)
        return (1f - eased) * TRAVEL
    }
}
