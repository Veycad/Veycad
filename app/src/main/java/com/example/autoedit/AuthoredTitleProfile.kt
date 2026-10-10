package com.veycad.app

/** Renderer-facing title description shared by fixed choreography profiles. */
internal object AuthoredTitleProfile {
    data class Sample(val atlasRow: Int, val opacity: Float)
    data class Spec(
        val texts: List<String>,
        val textSizePx: Float,
        val letterSpacing: Float,
        val condensedBold: Boolean,
        val bandCenter: Float,
        val bandHeight: Float,
        val baseOpacity: Float,
        val sampleAt: (Long) -> Sample?
    )

    fun forGraph(graph: MontageGraph): Spec? = when {
        HeartbeatMontageProfile.appliesTo(graph) -> Spec(
            texts = HeartbeatMontageProfile.OpeningTitle.entries.map { it.text },
            textSizePx = 22f,
            letterSpacing = .055f,
            condensedBold = false,
            bandCenter = .5f,
            bandHeight = .05f,
            baseOpacity = .82f,
            sampleAt = { timeUs -> HeartbeatMontageProfile.openingTitleAt(timeUs)?.let {
                Sample(it.atlasRow, 1f)
            } }
        )
        FearStrobeProfile.appliesTo(graph) -> Spec(
            texts = listOf("FEAR"),
            textSizePx = 176f,
            letterSpacing = .015f,
            condensedBold = true,
            bandCenter = .5f,
            bandHeight = .32f,
            baseOpacity = .90f,
            sampleAt = { timeUs -> FearStrobeProfile.titleAt(timeUs)?.let {
                Sample(it.atlasRow, it.opacity)
            } }
        )
        else -> null
    }
}
