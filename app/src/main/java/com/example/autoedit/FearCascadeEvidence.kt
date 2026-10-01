package com.example.autoedit

import kotlin.math.abs

/** Source-feature contrast witnesses in each authored FEAR phrase, not semantic scene recognition. */
internal object FearCascadeEvidence {
    data class Phrase(val sampledClips: Int, val witnessClipIds: List<String>) {
        val supported get() = witnessClipIds.size == 3
    }
    data class Report(val first: Phrase, val second: Phrase) {
        val supported get() = first.supported && second.supported
    }
    private data class Moment(val id: String, val yaw: Float?, val pitch: Float?,
        val scale: Float?, val gesture: Float?)

    fun evaluate(graph: MontageGraph, visual: VisualEventMap): Report {
        require(FearStrobeProfile.appliesTo(graph))
        require(graph.clips.size == FearStrobeProfile.scenes.size)
        val phrases = listOf(mutableListOf<Moment>(), mutableListOf<Moment>())
        graph.clips.forEachIndexed { index, clip ->
            if (FearStrobeProfile.scenes[index].role != FearStrobeProfile.SceneRole.CASCADE) return@forEachIndexed
            val samples = visual.observations.filter {
                it.sourceTimeUs in clip.sourceStartMs * 1_000L until clip.sourceEndMs * 1_000L &&
                    (it.humanPresenceConfidence >= .55f || (it.face?.confidence ?: 0f) >= .65f)
            }
            if (samples.isEmpty()) return@forEachIndexed
            val faces = samples.mapNotNull { it.face?.takeIf { face -> face.confidence >= .65f } }
            phrases[if (index < 15) 0 else 1] += Moment(clip.id,
                median(faces.map { it.yawDegrees }), median(faces.map { it.pitchDegrees }),
                median(samples.mapNotNull { it.composition?.subjectScale }),
                median(samples.mapNotNull { observation ->
                    observation.gestureConfidence.takeIf { observation.gestureEvidenceAvailable }
                }))
        }
        return Report(witness(phrases[0]), witness(phrases[1]))
    }

    fun requireSupported(graph: MontageGraph, visual: VisualEventMap): Report = evaluate(graph, visual).also {
        if (!it.supported) throw MaterialRejectedException("insufficient_distinct_moments",
            "FEAR needs three pairwise contrasting source moments in each cascade phrase")
    }

    private fun witness(moments: List<Moment>): Phrase {
        // An A-B-C chain is insufficient if A and C are the same pose. All three pairs must contrast.
        for (a in moments.indices) for (b in a + 1 until moments.size) {
            if (!different(moments[a], moments[b])) continue
            for (c in b + 1 until moments.size) {
                if (different(moments[a], moments[c]) && different(moments[b], moments[c])) {
                    return Phrase(moments.size, listOf(moments[a].id, moments[b].id, moments[c].id))
                }
            }
        }
        return Phrase(moments.size, emptyList())
    }

    private fun different(a: Moment, b: Moment): Boolean {
        fun delta(left: Float?, right: Float?, threshold: Float) =
            left != null && right != null && abs(left - right) >= threshold
        val config = VisualEventMapAnalyzer.Config()
        return delta(a.yaw, b.yaw, config.faceTurnDegrees) ||
            delta(a.pitch, b.pitch, config.faceTurnDegrees) ||
            delta(a.scale, b.scale, .10f) || delta(a.gesture, b.gesture, config.gestureThreshold)
    }

    private fun median(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        val ordered = values.sorted()
        val middle = ordered.size / 2
        return if (ordered.size % 2 == 0) (ordered[middle - 1] + ordered[middle]) / 2f else ordered[middle]
    }
}
