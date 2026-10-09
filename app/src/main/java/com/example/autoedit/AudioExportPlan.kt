package com.example.autoedit

/** Timestamp contract for AAC muxing: audio is looped/cut to exactly the video graph duration. */
object AudioExportPlan {
    data class Segment(val sourceStartUs: Long, val sourceEndUs: Long, val outputStartUs: Long)
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
