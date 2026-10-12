package com.veycad.app

sealed class EffectAnchor {
    data class Clip(val clipId: String, val localStartUs: Long, val localEndUs: Long) : EffectAnchor() {
        init { require(clipId.isNotBlank() && localEndUs > localStartUs) }
    }
    data class Boundary(val incomingClipId: String, val offsetUs: Long, val durationUs: Long) : EffectAnchor() {
        init { require(incomingClipId.isNotBlank() && durationUs > 0L) }
    }
    data class Music(val startUs: Long, val endUs: Long) : EffectAnchor() {
        init { require(startUs >= 0L && endUs > startUs) }
    }
}

/** Segment identity is distinct from the logical cue and its immutable authored payload. */
data class AnchoredMontageEffect(
    val id: String,
    val logicalId: String,
    val originId: String,
    val anchor: EffectAnchor,
    val enabled: Boolean,
    val originalOverlay: MontageGraph.Overlay? = null,
    val originalNode: GpuEffectGraph.Node? = null,
    val phaseStart: Float = 0f,
    val phaseEnd: Float = 1f
) {
    init {
        require(id.isNotBlank() && logicalId.isNotBlank() && originId.isNotBlank())
        require((originalOverlay == null) != (originalNode == null)) { "Effect requires exactly one authored payload" }
        require(phaseStart in 0f..1f && phaseEnd in 0f..1f && phaseEnd > phaseStart)
    }
}
