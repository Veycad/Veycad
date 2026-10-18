package com.veycad.app

/** Executed against entire immutable music models, never a fake Graph/Frame/Transition. */
fun main() {
    val audio = MontageGraph.AudioTrack("custom-music", 2f, 1_234_567L)
    fun clip(id: String, durationMs: Long, transition: MontageGraph.Transition) = MontageGraph.Clip(
        id, 2_000, 62_000, durationMs, MontageGraph.ShotRole.ACTION, transition,
        MontageGraph.Motion.HOLD, .8f, 0)
    fun steady(durationMs: Long) = MontageGraph(62_000, durationMs,
        clips = listOf(clip("steady", durationMs, MontageGraph.Transition.OPEN)), audioTrack = audio)
    val cuts = listOf(1_500_000L, 5_500_000L, 9_500_000L, 13_500_000L)
    val graph = steady(17_500).copy(clips = listOf(1_500L, 4_000L, 4_000L, 4_000L, 4_000L)
        .mapIndexed { i, duration -> clip("clip-$i", duration,
            if (i == 0) MontageGraph.Transition.OPEN else MontageGraph.Transition.BLACKOUT) })
    fun targets(g: MontageGraph, plan: HighQualityFramePlan.Plan): List<Long> {
        val result = RenderQaSampling.targets(g, plan, 100_000)
        check(result == result.distinct().sorted())
        check(result.all { time -> plan.frames.any { it.outputTimeUs == time } })
        check(g.audioTrack === audio && g.audioTrack?.sourceStartUs == 1_234_567L && g.audioTrack?.gain == 2f)
        return result
    }
    for (fps in listOf(30, 60)) {
        val plan = HighQualityFramePlan.build(graph, fps)
        val requested = targets(graph, plan)
        val offsets = if (fps == 30) listOf(-33_333L, 0L, 33_333L, 66_667L, 100_000L, 133_333L)
            else listOf(-16_667L, 0L, 16_667L, 33_333L, 50_000L, 66_667L, 83_333L, 100_000L, 116_667L)
        for (cut in cuts) check(requested.containsAll(offsets.map { cut + it }))
        if (fps == 30) check(requested.containsAll(listOf(1_533_333L, 1_566_667L, 5_533_333L,
            5_566_667L, 9_533_333L, 9_566_667L, 13_533_333L, 13_566_667L)))
        // Real plan equality covers source/output/transition/effects/attachment clocks.
        check(plan == HighQualityFramePlan.build(graph.copy(audioTrack = audio.copy(sourceStartUs = 0)), fps))
        check(requested == RenderQaSampling.targets(graph.copy(audioTrack = null), plan, 100_000))
        check(plan.frames.first().outputTimeUs == 0L && plan.frames.first().sourceTimeUs == 2_000_000L)
        // A caller-supplied subset exposes accidental plan rebuilding, including saved FPS.
        val supplied = plan.copy(frames = plan.frames.filterIndexed { i, _ -> i % 2 == 0 })
        targets(graph, supplied)
        val time = if (fps == 30) 233_333L else 116_667L
        val neighbors = if (fps == 30) listOf(200_000L, time, 266_667L) else listOf(100_000L, time, 133_333L)
        val base = steady(1_000)
        val overlay = MontageGraph.Overlay("one", time / 1_000, time / 1_000 + 1, "flash")
        val node = GpuEffectGraph.Node("one", GpuEffectGraph.Kind.GLITCH, time, time + 1, .5f)
        for (eventGraph in listOf(base.copy(overlays = listOf(overlay)),
                base.copy(effectGraph = GpuEffectGraph(listOf(node))))) {
            val result = targets(eventGraph, HighQualityFramePlan.build(eventGraph, fps))
            check(result.containsAll(neighbors) && result.size < 20)
        }
        val offscreen = base.copy(overlays = listOf(overlay.copy(startMs = 1_237, endMs = 1_238)),
            effectGraph = GpuEffectGraph(listOf(node.copy(startUs = 1_237_000, endUs = 1_238_000))))
        check(targets(base, HighQualityFramePlan.build(base, fps)) ==
            targets(offscreen, HighQualityFramePlan.build(offscreen, fps)))
        val shortStartMs = if (fps == 30) 231L else 115L
        val short = base.copy(clips = listOf(clip("a", shortStartMs, MontageGraph.Transition.OPEN),
            clip("b", 3, MontageGraph.Transition.HARD_CUT),
            clip("c", 997 - shortStartMs, MontageGraph.Transition.HARD_CUT)))
        check(time in targets(short, HighQualityFramePlan.build(short, fps)))
        val long = steady(60_000).copy(overlays = listOf(overlay.copy(startMs = 1_237, endMs = 50_456)),
            effectGraph = GpuEffectGraph(listOf(node.copy(startUs = 2_237_000, endUs = 51_456_000))))
        val bounded = targets(long, HighQualityFramePlan.build(long, fps))
        check(bounded.size <= 613)
        check(bounded.containsAll((0L until 60_000_000L step 100_000L).toList()))
        if (fps == 60) {
            fun durationGraph(duration: Long) = graph.copy(clips = graph.clips.mapIndexed { i, c ->
                if (i == 1) c.copy(transitionDurationMs = duration) else c })
            val eight = durationGraph(120)
            check(targets(eight, HighQualityFramePlan.build(eight, fps)).containsAll(listOf(
                1_483_333L, 1_500_000L, 1_516_667L, 1_533_333L, 1_550_000L,
                1_566_667L, 1_583_333L, 1_600_000L, 1_616_667L, 1_633_333L)))
            val nine = durationGraph(140)
            check(1_516_667L !in targets(nine, HighQualityFramePlan.build(nine, fps)))
            val foreground = graph.copy(clips = graph.clips.mapIndexed { i, c -> c.copy(
                transitionIn = if (i == 1) MontageGraph.Transition.FOREGROUND_REENTRY else MontageGraph.Transition.HARD_CUT) })
            val matte = targets(foreground, HighQualityFramePlan.build(foreground, fps))
            check(matte.containsAll(listOf(1_483_333L, 1_500_000L, 2_133_333L, 2_750_000L,
                3_300_000L, 3_400_000L, 3_500_000L, 3_700_000L, 4_000_000L, 4_016_667L)))
            check(matte.size < 210 && 1_516_667L !in matte)
            check(RenderQaFrameIndex(plan.frames).nearest(25_000) == 1)
        }
        println("PASS real music model: ${fps}fps, ${requested.size} cut targets; audio offset, supplied plan, effects, offscreen and bounded long windows")
    }
    println("PASS literal eight BLACKOUT interior PTS at 30fps; seven retained frames/cut plus neighbors at 60fps")
}
