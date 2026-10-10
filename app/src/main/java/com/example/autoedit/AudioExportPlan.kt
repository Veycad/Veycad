package com.veycad.app

/** Timestamp contract for AAC muxing: audio is looped/cut to exactly the video graph duration. */
object AudioExportPlan {
    data class Segment(val sourceStartUs: Long, val sourceEndUs: Long, val outputStartUs: Long)

    /**
     * Repeated-track window with global source phase and a local output clock.
     * At most 100,000 segments can be materialized; larger windows are rejected.
     */
    fun window(trackDurationUs: Long, globalStartUs: Long, durationUs: Long): List<Segment> {
        require(trackDurationUs > 0L && globalStartUs >= 0L && durationUs >= 0L)
        require(globalStartUs <= Long.MAX_VALUE - durationUs) { "Global window end overflows" }
        if (durationUs == 0L) return emptyList()

        val sourceStart = globalStartUs % trackDurationUs
        val firstDuration = minOf(durationUs, trackDurationUs - sourceStart)
        val remaining = durationUs - firstDuration
        val additionalSegments = remaining / trackDurationUs +
            if (remaining % trackDurationUs == 0L) 0L else 1L
        require(additionalSegments < MAX_WINDOW_SEGMENTS) { "Window exceeds segment limit" }

        val result = ArrayList<Segment>((additionalSegments + 1L).toInt())
        var source = sourceStart
        var output = 0L
        while (output < durationUs) {
            val size = minOf(trackDurationUs - source, durationUs - output)
            result += Segment(source, source + size, output)
            output += size
            source = 0L
        }
        return result
    }

    private const val MAX_WINDOW_SEGMENTS = 100_000L

    fun loop(trackDurationUs: Long, outputDurationUs: Long): List<Segment> {
        require(trackDurationUs > 0 && outputDurationUs > 0)
        val result = mutableListOf<Segment>(); var output = 0L
        while (output < outputDurationUs) {
            val size = minOf(trackDurationUs, outputDurationUs - output)
            result += Segment(0L, size, output); output += size
        }
        return result
    }

    /** Music always spans the graph's rendered duration, never the raw camera duration. */
    fun forGraph(trackDurationUs: Long, graph: MontageGraph): List<Segment> =
        loop(trackDurationUs, graph.outputDurationMs * 1_000L)

    /** AAC access units are timestamped from sample count, avoiding accumulated millisecond drift. */
    fun presentationTimeUs(encodedSamples: Long, sampleRate: Int): Long {
        require(encodedSamples >= 0 && sampleRate > 0)
        return encodedSamples * 1_000_000L / sampleRate
    }
}
