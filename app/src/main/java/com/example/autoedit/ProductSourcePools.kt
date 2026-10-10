package com.veycad.app

import kotlin.math.abs

/** A bounded planning search is not a finding that the source lacks suitable material. */
internal class HeartbeatCandidateSearchIncompleteException(
    val trace: HeartbeatSourcePool.SelectionTrace
) : IllegalStateException("Не удалось завершить подбор измеренных фрагментов; это не означает, " +
    "что исходник непригоден. visits=${trace.candidateVisits}/${trace.maxCandidateVisits}, " +
    "validations=${trace.directorValidations}/${trace.maxDirectorValidations}")

internal class HeartbeatCandidateDomainExhaustedException(
    val trace: HeartbeatSourcePool.SelectionTrace
) : MaterialRejectedException("insufficient_distinct_moments",
    "Heartbeat exhausted its finite measured-anchor candidate pools, not all physical source moments. " +
        "generated=${trace.generatedCandidates} candidates=${trace.candidateCount} " +
        "visits=${trace.candidateVisits} validations=${trace.directorValidations} exhausted=true")

/** Evidence windows only; the product selectors below own their distinct rankings and roles. */
private object SourceMomentWindows {
    class CoverageShortfall(message: String) :
        MaterialRejectedException("insufficient_distinct_moments", message)

    data class Window(
        val startMs: Long,
        val endMs: Long,
        val observations: List<VisualEventMap.Observation>
    ) {
        val quality get() = observations.map { it.visualQuality }.average().toFloat()
        val face get() = observations.map { it.face?.confidence ?: 0f }.average().toFloat()
        val human get() = observations.map { it.humanPresenceConfidence }.average().toFloat()
        val occlusion get() = observations.map { it.occlusionConfidence }.average().toFloat()
        val motion get() = observations.map {
            it.cameraMotion.magnitude + it.subjectMotion.magnitude
        }.average().toFloat()
        val hasGestureEvidence get() = observations.any { it.gestureEvidenceAvailable }
        val gesture get() = observations.filter { it.gestureEvidenceAvailable }
            .map { it.gestureConfidence }.average().toFloat().takeIf { it.isFinite() } ?: 0f
        val scale get() = observations.mapNotNull { it.composition?.subjectScale }
            .average().toFloat().takeIf { it.isFinite() } ?: .5f
        val luma get() = observations.map { it.meanLuma }.average().toFloat()
        val sceneChange get() = observations.maxOf { it.sceneChangeConfidence }
        fun overlapRatio(other: Window): Float =
            (minOf(endMs, other.endMs) - maxOf(startMs, other.startMs)).coerceAtLeast(0L)
                .toFloat() / (endMs - startMs)
    }

    fun candidates(visual: VisualEventMap, durationMs: Long,
        checkCancelled: () -> Unit = {}): List<Window> {
        val sourceMs = visual.durationUs / 1_000L
        require(durationMs in 1..sourceMs)
        val latest = sourceMs - durationMs
        val starts = buildList {
            add(0L)
            add(latest)
            visual.observations.forEach { checkCancelled(); add(it.sourceTimeUs / 1_000L - durationMs / 2L) }
            visual.events.forEach { checkCancelled(); add(it.peakTimeUs / 1_000L - durationMs / 2L) }
        }.map { it.coerceIn(0L, latest) }.distinct()
        return starts.mapNotNull { start ->
            checkCancelled()
            val selected = visual.observations.filterIndexed { index, observation ->
                if (index % 128 == 0) checkCancelled()
                observation.sourceTimeUs / 1_000L in start until start + durationMs
            }
            if (selected.size < 2) null else Window(start, start + durationMs, selected)
        }
    }

    fun best(
        candidates: List<Window>,
        selected: List<Window>,
        score: (Window) -> Float
    ): Window {
        if (candidates.isEmpty()) throw CoverageShortfall(
            "No sampled source window can fill the product role")
        // A good-looking duplicate is still the same material. Require a distinct source range
        // rather than turning an authored reprise into accidental repetition.
        return candidates.filter { candidate -> selected.none { candidate.overlapRatio(it) > .04f } }
            .maxByOrNull(score)
            ?: throw CoverageShortfall("Not enough distinct source windows for this product")
    }

    fun graph(
        visual: VisualEventMap,
        windows: List<Window>,
        roles: List<MontageGraph.ShotRole>,
        attachments: FrameAttachmentTimeline,
        generator: String
    ): MontageGraph {
        var cursor = 0L
        val clips = windows.mapIndexed { index, window ->
            val length = window.endMs - window.startMs
            val clip = MontageGraph.Clip(
                id = "$generator-$index",
                sourceStartMs = window.startMs,
                sourceEndMs = window.endMs,
                outputDurationMs = length,
                role = roles[index],
                transitionIn = if (index == 0) MontageGraph.Transition.OPEN
                    else MontageGraph.Transition.HARD_CUT,
                motion = MontageGraph.Motion.HOLD,
                confidence = window.quality.coerceIn(.05f, 1f),
                beatAnchorMs = cursor
            )
            cursor += length
            clip
        }
        return MontageGraph(visual.durationUs / 1_000L, cursor, clips = clips,
            frameAttachments = attachments,
            metadata = NleProjectMetadata(generator = generator))
    }
}

/** Heartbeat reserves a dark entrance, readable hero, light portrait and wide action. */
internal object HeartbeatSourcePool {
    data class SearchLimits(val maxCandidateVisits: Long = 2_000_000L,
        val maxDirectorValidations: Int = 64) {
        init { require(maxCandidateVisits >= 0L && maxDirectorValidations >= 1) }
    }
    data class SelectionTrace(val method: String, val generatedCandidates: Int,
        val candidateCount: Int, val candidateVisits: Long, val directorValidations: Int,
        val finiteDomainExhausted: Boolean, val maxCandidateVisits: Long = 2_000_000L,
        val maxDirectorValidations: Int = 64)
    data class Selection(val pool: MontageGraph, val graph: MontageGraph, val trace: SelectionTrace)

    /** Keep the successful production selection EXACTLY as it was. Only a typed planning
     * shortfall opens the finite, measured alternate domain; codecs and cancellation propagate. */
    fun direct(visual: VisualEventMap, attachments: FrameAttachmentTimeline, audioSourceId: String,
        echoOffsetMs: Long = -67L, allowShiftedHandles: Boolean = true,
        allowProfileGap: Boolean = true, limits: SearchLimits = SearchLimits(),
        checkCancelled: () -> Unit = {}, director: ((MontageGraph) -> MontageGraph)? = null): Selection {
        checkCancelled()
        val validate = director ?: { pool: MontageGraph -> HeartbeatDirector.build(pool, audioSourceId,
            echoOffsetMs, allowShiftedHandles, allowProfileGap, visual, checkCancelled) }
        var validations = 0
        var preferred: MontageGraph? = null
        try {
            val pool = select(visual, attachments, checkCancelled)
            preferred = pool
            checkCancelled()
            validations++
            val graph = validate(pool)
            return Selection(pool, graph, SelectionTrace("preferred-pool", 0, 0, 0L,
                validations, false, limits.maxCandidateVisits, limits.maxDirectorValidations))
        } catch (error: MaterialRejectedException) {
            if (error.code != "insufficient_distinct_moments") throw error
            // An offset outside the source is source-independent. Do not consume alternate
            // material-search budget attempting to resolve it.
            if (echoOffsetMs !in -(visual.durationUs / 1_000L)..(visual.durationUs / 1_000L)) throw error
        }
        val lengths = HeartbeatDirector.liveRoleLengths()
        val roles = poolRoles()
        val index = MeasuredIndex(visual, checkCancelled)
        val targetScales = scaleTargets(visual)
        var generated = 0
        var candidatesCount = 0
        var visits = 0L
        fun trace(exhausted: Boolean = false) = SelectionTrace("measured-pair-joint-v1", generated,
            candidatesCount, visits, validations, exhausted,
            limits.maxCandidateVisits, limits.maxDirectorValidations)
        fun visit() {
            checkCancelled()
            if (visits >= limits.maxCandidateVisits) throw HeartbeatCandidateSearchIncompleteException(trace())
            visits++
        }
        val sourceMs = visual.durationUs / 1_000L
        // Geometry/evidence is shared across roles with the same duration. The union over ALL
        // fifteen destination uses is necessary only, never a pre-composition role assignment.
        val windowsByLength = mutableMapOf<Long, List<SourceMomentWindows.Window>>()
        for (length in lengths.distinct()) {
            checkCancelled()
            val windows = if (length <= sourceMs) index.windows(length,
                preferred?.clips?.filter { it.sourceEndMs - it.sourceStartMs == length }
                    ?.map { it.sourceStartMs } ?: emptyList()) else emptyList()
            generated += windows.size
            windowsByLength[length] = windows.filter { window ->
                checkCancelled()
                HeartbeatDirector.hasAnyLiveUse(sourceMs, attachments, windowClip(window), echoOffsetMs,
                    allowShiftedHandles, allowProfileGap, checkCancelled)
            }
        }
        data class Ranked(val window: SourceMomentWindows.Window, val score: Float,
            val preferred: Boolean, val rank: Int = 0)
        val domains = lengths.mapIndexed { role, length ->
            val original = preferred?.clips?.getOrNull(role)
            windowsByLength.getValue(length).map { window ->
                checkCancelled()
                Ranked(window, score(role, window, targetScales), original != null &&
                    original.sourceStartMs == window.startMs && original.sourceEndMs == window.endMs)
            }.sortedWith(compareByDescending<Ranked> { it.preferred }
                .thenByDescending { it.score }.thenBy { it.window.startMs }.thenBy { it.window.endMs })
                .mapIndexed { rank, candidate -> candidate.copy(rank = rank) }
        }
        candidatesCount = domains.sumOf { it.size }
        val selected = arrayOfNulls<SourceMomentWindows.Window>(lengths.size)
        val attempted = HashSet<List<Pair<Long, Long>>>()
        preferred?.let { attempted.add(it.clips.map { clip -> clip.sourceStartMs to clip.sourceEndMs }) }
        fun compatible(window: SourceMomentWindows.Window): Boolean {
            visit() // includes every available-domain scan, not only recursive selections
            return selected.filterNotNull().none {
                window.startMs < it.endMs && it.startMs < window.endMs
            }
        }
        fun search(remaining: Int, remainingCost: Int): Selection? {
            checkCancelled()
            if (remaining == 0) {
                if (remainingCost != 0) return null
                val windows = selected.map { requireNotNull(it) }
                val key = windows.map { it.startMs to it.endMs }
                if (!attempted.add(key)) return null
                if (validations >= limits.maxDirectorValidations)
                    throw HeartbeatCandidateSearchIncompleteException(trace())
                val pool = SourceMomentWindows.graph(visual, windows, roles, attachments, "heartbeat-source-pool")
                validations++
                return try { Selection(pool, validate(pool), trace()) }
                catch (error: MaterialRejectedException) {
                    if (error.code != "insufficient_distinct_moments") throw error
                    null
                }
            }
            val available = selected.indices.filter { selected[it] == null }.map { role ->
                // ORIGINAL ranks are immutable: filtering conflicting ranges must not make a
                // low-quality distant alternative masquerade as the first-ranked replacement.
                role to domains[role].take(remainingCost + 1).filter { compatible(it.window) }
            }
            if (available.any { it.second.isEmpty() }) return null
            if (available.sumOf { (_, choices) -> choices.minOf { it.rank } } > remainingCost) return null
            // Shared temporal union is a necessary capacity condition; shifted choices are not
            // counted as independent source time. No score-based pruning claims impossibility.
            val ranges = available.flatMap { it.second }.map { it.window }
                .sortedWith(compareBy<SourceMomentWindows.Window> { it.startMs }.thenBy { it.endMs })
            var union = 0L
            var end = -1L
            for (range in ranges) {
                visit()
                union += (range.endMs - maxOf(range.startMs, end)).coerceAtLeast(0L)
                end = maxOf(end, range.endMs)
            }
            if (union < available.sumOf { lengths[it.first] }) return null
            val priority = listOf(4, 9, 11, 0) + selected.indices.filterNot { it in setOf(4, 9, 11, 0) }
            val (role, choices) = available.minWith(compareBy<Pair<Int, List<Ranked>>> { it.second.size }
                .thenBy { priority.indexOf(it.first) })
            for (choice in choices) {
                visit()
                selected[role] = choice.window
                val result = search(remaining - 1, remainingCost - choice.rank)
                if (result != null) return result
                selected[role] = null
            }
            return null
        }
        // Progressive TOTAL original-rank cost covers every first singleton alternate before
        // rank-one pairs/rank-two singletons. It is NOT a truncated domain: only exhaustion of
        // the entire finite rank-sum family is a domain proof. Low-cost layer failures are not.
        // Budgets and ordered complete-pool deduplication remain cumulative across all layers.
        val finalCost = domains.sumOf { (it.size - 1).coerceAtLeast(0) }
        for (cost in 0..finalCost) {
            checkCancelled()
            search(lengths.size, cost)?.let { return it }
        }
        throw HeartbeatCandidateDomainExhaustedException(trace(true))
    }

    private fun poolRoles() = (0 until 15).map { role -> when (role) {
        0 -> MontageGraph.ShotRole.OPENING
        4, 9 -> MontageGraph.ShotRole.CLOSE
        11 -> MontageGraph.ShotRole.ACTION
        else -> MontageGraph.ShotRole.DETAIL
    } }

    private fun windowClip(window: SourceMomentWindows.Window) = MontageGraph.Clip(
        "measured-window", window.startMs, window.endMs, window.endMs - window.startMs,
        MontageGraph.ShotRole.DETAIL, MontageGraph.Transition.HARD_CUT, MontageGraph.Motion.HOLD,
        window.quality.coerceIn(.05f, 1f), 0L)

    /** Exposed to pure sparse-clock tests: these are measured windows, not inferred face handles. */
    internal fun measuredFallbackCandidates(visual: VisualEventMap, durationMs: Long,
        checkCancelled: () -> Unit = {}): List<MontageGraph.Clip> =
        MeasuredIndex(visual, checkCancelled).windows(durationMs).map(::windowClip)

    private class MeasuredIndex(val visual: VisualEventMap, val checkCancelled: () -> Unit) {
        private val timesMs = LongArray(visual.observations.size) { index ->
            checkCancelled(); visual.observations[index].sourceTimeUs / 1_000L
        }
        private fun lowerBound(timeMs: Long): Int {
            var low = 0
            var high = timesMs.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (timesMs[middle] < timeMs) low = middle + 1 else high = middle
            }
            return low
        }
        fun windows(durationMs: Long, retainedStarts: List<Long> = emptyList()): List<SourceMomentWindows.Window> {
            val sourceMs = visual.durationUs / 1_000L
            if (durationMs !in 1..sourceMs) return emptyList()
            val latest = sourceMs - durationMs
            val starts = LinkedHashSet<Long>()
            fun add(start: Long) { checkCancelled(); starts.add(start.coerceIn(0L, latest)) }
            add(0L); add(latest)
            timesMs.forEach { add(it - durationMs / 2L) }
            visual.events.forEach { add(it.peakTimeUs / 1_000L - durationMs / 2L) }
            visual.observations.zipWithNext().forEach { (left, right) ->
                // The midpoint is an anchor only. Clamping never excuses missing real samples.
                add((left.sourceTimeUs + (right.sourceTimeUs - left.sourceTimeUs) / 2L) /
                    1_000L - durationMs / 2L)
            }
            // A preferred spaced packing is measured too, even when its start is not an
            // observation/event/pair midpoint. Preserve such parts during joint replacements.
            retainedStarts.forEach(::add)
            return starts.mapNotNull { start ->
                checkCancelled()
                val first = lowerBound(start)
                val after = lowerBound(start + durationMs)
                if (after - first < 2) null else SourceMomentWindows.Window(start, start + durationMs,
                    visual.observations.subList(first, after))
            }
        }
    }

    fun select(visual: VisualEventMap, attachments: FrameAttachmentTimeline,
        checkCancelled: () -> Unit = {}): MontageGraph {
        val count = 15
        val lengths = HeartbeatDirector.liveRoleLengths()
        val targetScales = scaleTargets(visual)
        val chosen = arrayOfNulls<SourceMomentWindows.Window>(count)
        val used = mutableListOf<SourceMomentWindows.Window>()
        // Hero and accent material is scarce; reserve those roles before filling ordinary cuts.
        val priority = listOf(4, 9, 11, 0) + (0 until count).filterNot { it in setOf(4, 9, 11, 0) }
        val rankedSelection = try {
            for (role in priority) {
                checkCancelled()
                val candidates = SourceMomentWindows.candidates(visual, lengths[role], checkCancelled)
                checkCancelled()
                // Heartbeat's sum-of-overlaps gate cannot be guaranteed by a 4% per-pair
                // allowance. Keep strict distinctness here; the director subsequently reserves
                // the actual composition-mapped, length-aware live handles together.
                val best = candidates.filter { candidate -> used.none { previous ->
                    candidate.startMs < previous.endMs && previous.startMs < candidate.endMs
                } }.maxByOrNull {
                    score(role, it, targetScales)
                } ?: throw SourceMomentWindows.CoverageShortfall(
                    "Not enough distinct Heartbeat source windows")
                chosen[role] = best
                used += best
            }
            chosen.map { requireNotNull(it) }
        } catch (_: SourceMomentWindows.CoverageShortfall) {
            // A greedy reservation can fragment an otherwise long enough source. Re-pack
            // measured windows without overlap before declaring material unsuitable.
            spacedSelection(visual, lengths, targetScales, checkCancelled)
        }
        val roles = (0 until count).map { role -> when (role) {
            0 -> MontageGraph.ShotRole.OPENING
            4, 9 -> MontageGraph.ShotRole.CLOSE
            11 -> MontageGraph.ShotRole.ACTION
            else -> MontageGraph.ShotRole.DETAIL
        } }
        return SourceMomentWindows.graph(visual, rankedSelection, roles, attachments,
            "heartbeat-source-pool")
    }

    private fun scaleTargets(visual: VisualEventMap): Map<Int, Float> {
        // The reference specifies face width, while source analysis reports body occupancy.
        // Match their relative ranks within this source, never their incompatible raw units.
        val widths = (0 until 15).mapNotNull(HeartbeatMontageProfile::targetFaceWidthForRole)
        val scales = visual.observations.mapNotNull { it.composition?.subjectScale }
        if (scales.isEmpty()) return emptyMap()
        val widthSpan = (widths.max() - widths.min()).coerceAtLeast(.001f)
        val sourceSpan = scales.max() - scales.min()
        return (0 until 15).mapNotNull { role ->
            HeartbeatMontageProfile.targetFaceWidthForRole(role)?.let { width ->
                role to (scales.min() + (width - widths.min()) / widthSpan * sourceSpan)
            }
        }.toMap()
    }

    private fun score(
        role: Int,
        moment: SourceMomentWindows.Window,
        targetScales: Map<Int, Float>
    ): Float {
        val targetScale = targetScales[role]
        val targetMotion = HeartbeatMontageProfile.targetMotionForRole(role) ?: .3f
        val common = moment.quality * .30f + moment.human * .16f +
            moment.face * .14f + (1f - moment.occlusion) * .12f +
            moment.gesture * .08f - moment.sceneChange * .30f
        val scale = targetScale?.let { 1f - abs(moment.scale - it).coerceIn(0f, 1f) } ?: .5f
        val movement = 1f - abs(moment.motion - targetMotion).coerceIn(0f, 1f)
        return common + scale * .10f + movement * .10f + when (role) {
            0 -> (1f - abs(moment.luma - .20f)) * .12f
            4 -> moment.face * .12f + moment.quality * .08f
            9 -> moment.face * .12f + moment.luma * .08f
            11 -> moment.motion.coerceIn(0f, 1f) * .12f +
                (1f - moment.scale) * .08f
            else -> 0f
        }
    }

    private fun spacedSelection(
        visual: VisualEventMap,
        lengths: List<Long>,
        targetScales: Map<Int, Float>,
        checkCancelled: () -> Unit = {}
    ): List<SourceMomentWindows.Window> {
        val sourceMs = visual.durationUs / 1_000L
        if (lengths.sum() > sourceMs) throw SourceMomentWindows.CoverageShortfall(
            "Heartbeat lacks distinct source duration")
        var freeMs = sourceMs - lengths.sum()
        var cursor = 0L
        return lengths.mapIndexed { role, length ->
            checkCancelled()
            val allowance = freeMs / (lengths.size - role + 1)
            val starts = listOf(cursor, cursor + allowance / 2L, cursor + allowance).distinct()
            val options = starts.mapNotNull { start ->
                val observations = visual.observations.filter {
                    it.sourceTimeUs / 1_000L in start until start + length
                }
                if (observations.size < 2) null else
                    SourceMomentWindows.Window(start, start + length, observations)
            }
            val best = options.maxByOrNull { score(role, it, targetScales) }
                ?: throw SourceMomentWindows.CoverageShortfall(
                    "Heartbeat source has unsampled role windows")
            freeMs -= best.startMs - cursor
            cursor = best.endMs
            best
        }
    }
}

/** FEAR first reserves a continuous moving opener and a clean close-up, then varied cascade. */
internal object FearSourcePool {
    fun select(visual: VisualEventMap, attachments: FrameAttachmentTimeline): MontageGraph {
        val openerCandidates = SourceMomentWindows.candidates(visual, 3_600L)
        if (openerCandidates.isEmpty()) throw SourceMomentWindows.CoverageShortfall(
            "FEAR has no measured opening windows")
        if (openerCandidates.none { FearOpeningEvidence.evaluate(it.observations).supported }) {
            val unavailable = openerCandidates.all {
                FearOpeningEvidence.evaluate(it.observations).conclusiveSamples < 3
            }
            throw MaterialRejectedException(if (unavailable) "insufficient_motion_evidence" else "insufficient_motion",
                "FEAR needs reliable sustained measured movement; unknown samples are not static evidence")
        }
        val windows = try { rankedSelection(visual) }
            catch (_: SourceMomentWindows.CoverageShortfall) { spacedSelection(visual) }
        val roles = listOf(MontageGraph.ShotRole.OPENING, MontageGraph.ShotRole.CLOSE) +
            List(14) { MontageGraph.ShotRole.ACTION }
        return SourceMomentWindows.graph(visual, windows, roles, attachments,
            "fear-source-pool")
    }

    private fun rankedSelection(visual: VisualEventMap): List<SourceMomentWindows.Window> {
        val used = mutableListOf<SourceMomentWindows.Window>()
        val opener = SourceMomentWindows.best(SourceMomentWindows.candidates(visual, 3_600L)
            .filter { FearOpeningEvidence.evaluate(it.observations).supported }, used) {
            it.quality * .25f + it.human * .22f + it.motion.coerceIn(0f, 1f) * .25f +
                (1f - it.occlusion) * .18f - it.sceneChange * .40f
        }
        used += opener
        val title = SourceMomentWindows.best(SourceMomentWindows.candidates(visual, 1_500L), used) {
            it.face * .36f + it.scale * .24f + it.quality * .24f +
                (1f - it.occlusion) * .16f - it.sceneChange * .40f
        }
        used += title
        repeat(14) { index ->
            val duration = if (index % 3 == 0) 650L else 550L
            val candidates = SourceMomentWindows.candidates(visual, duration)
            val last = used.last()
            val best = SourceMomentWindows.best(candidates, used) { moment ->
                val contrast = abs(moment.scale - last.scale) * .34f +
                    abs(moment.motion - last.motion).coerceIn(0f, 1f) * .18f +
                    (if (moment.hasGestureEvidence && last.hasGestureEvidence)
                        abs(moment.gesture - last.gesture) * .13f else 0f)
                moment.quality * .25f + moment.face * .15f + moment.human * .13f +
                    (1f - moment.occlusion) * .10f + contrast - moment.sceneChange * .35f
            }
            used += best
        }
        return used
    }

    private fun spacedSelection(visual: VisualEventMap): List<SourceMomentWindows.Window> {
        val lengths = listOf(3_600L, 1_500L) + List(14) { index ->
            if (index % 3 == 0) 650L else 550L
        }
        val sourceMs = visual.durationUs / 1_000L
        if (lengths.sum() > sourceMs) throw SourceMomentWindows.CoverageShortfall(
            "FEAR lacks distinct source duration")
        var freeMs = sourceMs - lengths.sum()
        var cursor = 0L
        val chosen = mutableListOf<SourceMomentWindows.Window>()
        lengths.forEachIndexed { role, length ->
            val allowance = freeMs / (lengths.size - role + 1)
            val options = listOf(cursor, cursor + allowance / 2L, cursor + allowance)
                .distinct().mapNotNull { start ->
                    val observations = visual.observations.filter {
                        it.sourceTimeUs / 1_000L in start until start + length
                    }
                    if (observations.size < 2) null else
                        SourceMomentWindows.Window(start, start + length, observations)
                }
            val previous = chosen.lastOrNull()
            val best = options.filter { role != 0 || FearOpeningEvidence.evaluate(it.observations).supported }
                .maxByOrNull { moment -> when (role) {
                0 -> moment.quality * .25f + moment.human * .22f +
                    moment.motion.coerceIn(0f, 1f) * .25f - moment.sceneChange * .40f
                1 -> moment.face * .36f + moment.scale * .24f +
                    moment.quality * .24f - moment.sceneChange * .40f
                else -> moment.quality * .30f + moment.face * .18f +
                    moment.gesture * .10f +
                    (previous?.let { abs(moment.scale - it.scale) } ?: 0f) * .20f -
                    moment.sceneChange * .35f
            } } ?: throw SourceMomentWindows.CoverageShortfall(
                "FEAR source has unsampled role windows")
            freeMs -= best.startMs - cursor
            cursor = best.endMs
            chosen += best
        }
        return chosen
    }
}
