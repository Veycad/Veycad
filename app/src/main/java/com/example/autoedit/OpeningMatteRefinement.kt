package com.veycad.app

/** Bounded exact-source sampling for selected live entrances, not the entire video. */
internal object OpeningMatteRefinement {
    const val MAX_FRAMES = 90
    const val MAX_PIXELS = 12_000_000L // <=48 MB of retained FloatArray mask payload

    /** Matches DecoderCursor's first decoded PTS >= request, including its EOS hold. */
    fun alignToDecodedFrames(targets: LongArray, sampleTimes: LongArray): LongArray {
        require(sampleTimes.isNotEmpty() && sampleTimes.first() >= 0L)
        require(sampleTimes.toList().zipWithNext().all { (a, b) -> a < b })
        return targets.map { target ->
            val found = sampleTimes.binarySearch(target)
            val index = if (found >= 0) found else (-found - 1).coerceAtMost(sampleTimes.lastIndex)
            sampleTimes[index]
        }.distinct().sorted().toLongArray()
    }

    fun targets(graph: MontageGraph): LongArray {
        val targets = HighQualityFramePlan.build(graph).frames
            .filter { (it.transitionProgress?.let { progress -> progress >= 0f } == true &&
                it.transitionIn == MontageGraph.Transition.FOREGROUND_REENTRY) ||
                (ReferenceMontageProfile.appliesTo(graph) &&
                    it.layer.kind in setOf(MontageGraph.OverlayKind.SUBJECT_STAGE,
                        MontageGraph.OverlayKind.MIRROR_SLICE) &&
                    it.layer.finalFadeOpacity < 1f) }
            .map { it.sourceTimeUs }.sorted().distinctBy { it / 1_000L }
        if (targets.size <= MAX_FRAMES) return targets.toLongArray()
        // Cover both retained takes within the existing payload bound. Taking the first 90
        // timestamps silently discarded the finale whenever the entrance consumed the budget.
        return LongArray(MAX_FRAMES) { index ->
            targets[index * targets.lastIndex / (MAX_FRAMES - 1)]
        }
    }

    fun dimensions(width: Int, height: Int, frames: Int): Pair<Int, Int> {
        require(width > 0 && height > 0 && frames in 1..MAX_FRAMES)
        val scale = minOf(1.0, kotlin.math.sqrt(MAX_PIXELS.toDouble() / (width.toLong() * height * frames)))
        return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
    }

    fun attach(graph: MontageGraph, targets: LongArray,
               masks: Map<Long, FrameAttachments.Plane>): MontageGraph {
        require(targets.isNotEmpty() && targets.size <= MAX_FRAMES)
        require(targets.toSet() == masks.keys && targets.toList().zipWithNext().all { (a, b) -> a < b })
        require(masks.values.sumOf { it.values.size.toLong() } <= MAX_PIXELS)
        val refined = targets.map { time -> FrameAttachments(time, mask = preserveFaceInterior(
            masks.getValue(time), graph.frameAttachments.interpolated(time))) }
        return graph.copy(frameAttachments = graph.frameAttachments.copy(maskRefinements = refined))
    }

    /** Exact-frame segmentation must not punch holes through a previously supported face.
     * Only mask confidence is retained, never RGB; the source decoder continues advancing.
     * The inset ellipse excludes silhouette edges and keeps the new hair boundary intact.
     */
    internal fun preserveFaceInterior(mask: FrameAttachments.Plane, base: FrameAttachments?): FrameAttachments.Plane {
        val face = base?.faceRegion ?: return mask
        val prior = base.mask ?: return mask
        if (face.confidence < .65f || base.subjectOcclusion > .25f) return mask
        // A moving face box can overlap background supported by an older mask. Restore
        // enclosed holes, never background connected to the exact frame's outer edge.
        val exterior = BooleanArray(mask.values.size)
        val queue = IntArray(mask.values.size)
        var head = 0
        var tail = 0
        fun visit(index: Int) {
            if (!exterior[index] && mask.values[index] < .5f) {
                exterior[index] = true
                queue[tail++] = index
            }
        }
        repeat(mask.width) { x ->
            visit(x)
            visit((mask.height - 1) * mask.width + x)
        }
        repeat(mask.height) { y ->
            visit(y * mask.width)
            visit(y * mask.width + mask.width - 1)
        }
        while (head < tail) {
            val index = queue[head++]
            val x = index % mask.width
            if (x > 0) visit(index - 1)
            if (x < mask.width - 1) visit(index + 1)
            if (index >= mask.width) visit(index - mask.width)
            if (index < mask.values.size - mask.width) visit(index + mask.width)
        }
        fun sample(plane: FrameAttachments.Plane, x: Float, y: Float): Float {
            val px = (x * plane.width).toInt().coerceIn(0, plane.width - 1)
            val py = (y * plane.height).toInt().coerceIn(0, plane.height - 1)
            return plane.values[py * plane.width + px]
        }
        val values = mask.values.copyOf()
        for (i in values.indices) {
            if (exterior[i]) continue
            val x = (i % mask.width + .5f) / mask.width
            val y = (i / mask.width + .5f) / mask.height
            val dx = (x - face.centerX) / (face.width * .42f)
            val dy = (y - face.centerY) / (face.height * .42f)
            val radius = dx * dx + dy * dy
            if (radius >= 1f) continue
            val first = sample(prior, x, y)
            val supported = base.maskBlendTarget?.let {
                first + (sample(it, x, y) - first) * base.maskBlendProgress
            } ?: first
            // Fade protection within the inset, avoiding a new hard ellipse edge.
            val weight = ((1f - radius) / .25f).coerceIn(0f, 1f)
            values[i] = maxOf(values[i], values[i] + (supported - values[i]) * weight)
        }
        return mask.copy(values = values)
    }
}
