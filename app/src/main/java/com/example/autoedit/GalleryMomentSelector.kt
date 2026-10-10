package com.veycad.app

/** Deterministic greedy selection over the immutable selection table, not a deduplicated asset list. */
internal object GalleryMomentSelector {
    private const val MIN_WINDOW_US = 500_000L
    private const val MAX_WINDOW_US = 4_000_000L

    fun select(moments: List<GalleryMoment>, sources: MediaSourceSet, budgetUs: Long): List<GalleryMoment> =
        selections(moments, sources, budgetUs).toList()

    /** The director stops consuming as soon as the actual beat-trimmed graph reaches its budget. */
    internal fun selections(moments: List<GalleryMoment>, sources: MediaSourceSet,
        budgetUs: Long): Sequence<GalleryMoment> = sequence {
        GalleryImportPolicy.validate(sources)
        require(budgetUs >= 0)
        val indices = sources.items.mapIndexed { index, source -> source.id to index }.toMap()
        fun source(moment: GalleryMoment) = sources.items[indices.getValue(moment.sourceId)]
        moments.forEach { moment ->
            require(moment.sourceId in indices) { "Unknown gallery source: ${moment.sourceId}" }
            val descriptor = source(moment)
            require(moment.startUs >= descriptor.firstVideoPtsUs && moment.endUs <= descriptor.videoEndPtsUs &&
                moment.endUs > moment.startUs) { "Gallery window outside inspected source PTS" }
            val f = moment.features
            require(listOf(f.quality, f.localMotion, f.composition, f.cameraInstability, f.sceneChange)
                .all { it.isFinite() && it in 0f..1f }) { "Gallery features must be normalized" }
            require(f.faceConfidence == null || f.faceConfidence.isFinite() && f.faceConfidence in 0f..1f)
            require(f.timeUs in descriptor.firstVideoPtsUs until descriptor.videoEndPtsUs)
        }
        val order = compareBy<GalleryMoment>({ indices.getValue(it.sourceId) }, { it.startUs }, { it.endUs })
        // Additional stable evidence keys make malformed duplicate windows deterministic too.
            .thenBy { it.keyHash }.thenBy { it.features.timeUs }.thenByDescending { score(it) }
            .thenBy { it.features.quality }.thenBy { it.features.localMotion }
            .thenBy { it.features.composition }.thenBy { it.features.cameraInstability }
            .thenBy { it.features.sceneChange }.thenBy { it.features.faceConfidence ?: -1f }
        val remaining = moments.filter { it.endUs - it.startUs >= MIN_WINDOW_US &&
            it.features.quality >= .15f && score(it) > 0f }.sortedWith(order).distinct().toMutableList()
        val selected = mutableListOf<GalleryMoment>()
        var availableUs = budgetUs
        while (availableUs >= MIN_WINDOW_US && remaining.isNotEmpty()) {
            fun sameContent(a: GalleryMoment, b: GalleryMoment) = source(a).fingerprint == source(b).fingerprint
            fun contentStart(m: GalleryMoment) = m.startUs - source(m).firstVideoPtsUs
            fun contentEnd(m: GalleryMoment) = m.endUs - source(m).firstVideoPtsUs
            // Repeating the same actual footage under another selection ID cannot fill a shortfall.
            remaining.removeAll { candidate -> selected.any { prior -> sameContent(candidate, prior) &&
                contentStart(candidate) < contentEnd(prior) && contentStart(prior) < contentEnd(candidate) } }
            if (remaining.isEmpty()) break
            fun adjusted(candidate: GalleryMoment): Float {
                val used = selected.count { sameContent(candidate, it) }
                val adjacent = selected.any { prior -> sameContent(candidate, prior) &&
                    (contentStart(candidate) - contentEnd(prior) in 0..1_000_000L ||
                        contentStart(prior) - contentEnd(candidate) in 0..1_000_000L) }
                val visualRepeat = selected.any { java.lang.Long.bitCount(it.keyHash xor candidate.keyHash) <= 6 }
                return score(candidate) / (1f + used) * (if (adjacent) .5f else 1f) *
                    (if (visualRepeat) .15f else 1f)
            }
            // Evaluate the penalties once per candidate, rather than inside every sort comparison.
            val next = remaining.map { it to adjusted(it) }.sortedWith(
                compareByDescending<Pair<GalleryMoment, Float>> { it.second }
                    .thenComparator { a, b -> order.compare(a.first, b.first) }).first().first
            val lengthUs = minOf(next.endUs - next.startUs, MAX_WINDOW_US, availableUs)
            selected += next.copy(endUs = next.startUs + lengthUs)
            availableUs -= lengthUs
            remaining.remove(next)
            yield(selected.last())
        }
    }

    internal fun score(moment: GalleryMoment): Float = with(moment.features) {
        quality * (.60f + .25f * localMotion + .15f * composition) * (1f - cameraInstability)
    }
}
