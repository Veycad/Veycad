package com.example.autoedit

/** A material limitation, distinct from a codec crash or a failed encoded-MP4 quality gate. */
internal open class MaterialRejectedException(
    val code: String,
    message: String
) : IllegalArgumentException(message)

internal object MaterialSuitability {
    fun checkDuration(recipe: MontageStyleCatalog.Recipe, durationUs: List<Long>) {
        val minimumMs = if (recipe == MontageStyleCatalog.Recipe.DUALITY_LOOP) {
            DualityLoopProfile.MINIMUM_SOURCE_DURATION_MS
        } else EditDurationPolicy.MINIMUM_MS
        if (durationUs.any { it / 1_000L < minimumMs }) {
            throw MaterialRejectedException("insufficient_duration",
                "Each source needs at least $minimumMs ms for ${recipe.name}")
        }
    }

    fun checkHumanEvidence(
        recipe: MontageStyleCatalog.Recipe,
        visualMaps: List<VisualEventMap>
    ) {
        val hasEvidence = visualMaps.map { map -> map.observations.count {
            it.humanPresenceConfidence >= .55f || (it.face?.confidence ?: 0f) >= .65f
        } >= 2 }
        val usable = if (recipe == MontageStyleCatalog.Recipe.DUALITY_LOOP) {
            hasEvidence.any { it }
        } else hasEvidence.size == 1 && hasEvidence.first()
        if (!usable) {
            throw MaterialRejectedException("insufficient_human_evidence",
                "Not enough verified human-subject evidence for ${recipe.name}")
        }
    }
}
