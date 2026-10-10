package com.veycad.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** Sample-clock musical structure consumed by the montage director and render QA. */
data class AudioBeatMap(
    val sampleRate: Int,
    val durationSamples: Long,
    val estimatedTempoBpm: Float?,
    val beats: List<Beat>,
    val onsets: List<Onset>,
    val drops: List<Drop> = emptyList()
) {
    init {
        require(sampleRate > 0 && durationSamples >= 0L)
        require(beats.zipWithNext().all { (left, right) -> right.sampleIndex > left.sampleIndex })
        require(onsets.zipWithNext().all { (left, right) -> right.sampleIndex > left.sampleIndex })
        require(beats.all { it.sampleIndex in 0 until max(1L, durationSamples) })
        require(onsets.all { it.sampleIndex in 0 until max(1L, durationSamples) })
        require(drops.zipWithNext().all { (left, right) -> right.sampleIndex > left.sampleIndex })
        require(drops.all { it.sampleIndex in 0 until max(1L, durationSamples) })
    }

    enum class FrequencyBand { LOW, MID, HIGH, BROADBAND }

    /** Heuristic sustained bass/energy entrance, not a guaranteed semantic chorus label. */
    data class Drop(val sampleIndex: Long, val strength: Float) {
        init { require(sampleIndex >= 0L && strength in 0f..1f) }
    }

    data class Beat(
        val sampleIndex: Long,
        val strength: Float,
        val dominantBand: FrequencyBand,
        val isDownbeat: Boolean
    ) {
        init { require(sampleIndex >= 0L && strength in 0f..1f) }
    }

    data class Onset(
        val sampleIndex: Long,
        val strength: Float,
        val dominantBand: FrequencyBand
    ) {
        init { require(sampleIndex >= 0L && strength in 0f..1f) }
    }

    fun timestampUs(sampleIndex: Long): Long {
        require(sampleIndex >= 0L)
        return sampleIndex * 1_000_000L / sampleRate
    }

    /** Repeats musical evidence on the same sample clock used by audio export. */
    fun loopedTo(targetDurationSamples: Long): AudioBeatMap {
        require(targetDurationSamples > 0L && durationSamples > 0L)
        fun <T> repeat(events: List<T>, sampleOf: (T) -> Long, shifted: (T, Long) -> T): List<T> = buildList {
            var offset = 0L
            while (offset < targetDurationSamples) {
                events.forEach { event ->
                    val sample = sampleOf(event) + offset
                    if (sample < targetDurationSamples) add(shifted(event, sample))
                }
                offset += durationSamples
            }
        }
        return AudioBeatMap(
            sampleRate,
            targetDurationSamples,
            estimatedTempoBpm,
            repeat(beats, { it.sampleIndex }) { event, sample -> event.copy(sampleIndex = sample) }
                .distinctBy { it.sampleIndex },
            repeat(onsets, { it.sampleIndex }) { event, sample -> event.copy(sampleIndex = sample) }
                .distinctBy { it.sampleIndex },
            repeat(drops, { it.sampleIndex }) { event, sample -> event.copy(sampleIndex = sample) }
                .distinctBy { it.sampleIndex }
        )
    }
}

/**
 * Dependency-free PCM analyser. It intentionally exposes musical evidence rather than edit
 * decisions: effect choice and source-window selection belong to later engine stages.
 */
object AudioBeatMapAnalyzer {
    data class Config(
        val frameSize: Int = 1024,
        val hopSize: Int = 256,
        val minimumOnsetSpacingMs: Int = 75,
        val thresholdWindowFrames: Int = 16,
        val thresholdMultiplier: Float = 1.35f,
        val minimumTempoBpm: Int = 65,
        val maximumTempoBpm: Int = 190
    ) {
        init {
            require(frameSize >= 64 && frameSize.countOneBits() == 1)
            require(hopSize in 1..frameSize)
            require(minimumOnsetSpacingMs > 0 && thresholdWindowFrames >= 2)
            require(thresholdMultiplier > 0f)
            require(minimumTempoBpm in 30 until maximumTempoBpm)
        }
    }

    /** A short selected tail repeats in export; measure that same waveform for enough beat intervals. */
    fun analyzeLoopingFragment(monoPcm: FloatArray, sampleRate: Int, config: Config = Config(),
        checkCancelled: () -> Unit = {}): AudioBeatMap {
        require(sampleRate > 0)
        if (monoPcm.isEmpty() || monoPcm.size.toLong() >= sampleRate * 8L)
            return analyze(monoPcm, sampleRate, config, checkCancelled)
        val repeats = ((sampleRate * 8L + monoPcm.size - 1) / monoPcm.size).toInt()
        val repeated = FloatArray(Math.multiplyExact(monoPcm.size, repeats))
        repeat(repeats) { index ->
            checkCancelled()
            monoPcm.copyInto(repeated, index * monoPcm.size)
        }
        val measured = analyze(repeated, sampleRate, config, checkCancelled)
        // Repeated decay tails can produce tiny local flux peaks. They must not pull the
        // beat grid away from the actual attacks when selecting the nearest onset.
        val attacks = measured.onsets.filter { it.strength >= .08f }
        val period = estimateBeatPeriod(attacks, sampleRate, config)
        return measured.copy(onsets = attacks,
            estimatedTempoBpm = period?.let { 60f * sampleRate / it },
            beats = period?.let { buildBeatGrid(attacks, it, sampleRate, repeated.size.toLong()) }.orEmpty())
    }

    fun analyze(monoPcm: FloatArray, sampleRate: Int, config: Config = Config(),
        checkCancelled: () -> Unit = {}): AudioBeatMap {
        checkCancelled()
        require(sampleRate > 0)
        require(monoPcm.all { it.isFinite() })
        if (monoPcm.isEmpty()) return AudioBeatMap(sampleRate, 0L, null, emptyList(), emptyList())

        val frames = spectra(monoPcm, sampleRate, config, checkCancelled)
        val onsetFrames = pickOnsets(frames, sampleRate, config)
        val onsets = normalizeOnsets(onsetFrames, frames, monoPcm.size.toLong())
        val periodSamples = estimateBeatPeriod(onsets, sampleRate, config)
        val beats = periodSamples?.let {
            buildBeatGrid(onsets, it, sampleRate, monoPcm.size.toLong())
        }.orEmpty()

        return AudioBeatMap(
            sampleRate = sampleRate,
            durationSamples = monoPcm.size.toLong(),
            estimatedTempoBpm = periodSamples?.let { 60f * sampleRate / it },
            beats = beats,
            onsets = onsets,
            drops = detectDrops(frames, onsetFrames, sampleRate, config)
        )
    }

    private data class FluxFrame(
        val centerSample: Long,
        val total: Float,
        val low: Float,
        val mid: Float,
        val high: Float,
        val energy: Double,
        val bassEnergy: Double
    ) {
        fun dominantBand(): AudioBeatMap.FrequencyBand {
            val maximum = max(low, max(mid, high))
            if (maximum <= 0f) return AudioBeatMap.FrequencyBand.BROADBAND
            val totalBands = low + mid + high
            if (totalBands > 0f && maximum / totalBands < .52f) return AudioBeatMap.FrequencyBand.BROADBAND
            return when (maximum) {
                low -> AudioBeatMap.FrequencyBand.LOW
                mid -> AudioBeatMap.FrequencyBand.MID
                else -> AudioBeatMap.FrequencyBand.HIGH
            }
        }
    }

    private fun spectra(pcm: FloatArray, sampleRate: Int, config: Config,
        checkCancelled: () -> Unit): List<FluxFrame> {
        val frameCount = if (pcm.size <= config.frameSize) 1
        else 1 + (pcm.size - config.frameSize + config.hopSize - 1) / config.hopSize
        val previous = FloatArray(config.frameSize / 2 + 1)
        val result = ArrayList<FluxFrame>(frameCount)
        val real = DoubleArray(config.frameSize)
        val imaginary = DoubleArray(config.frameSize)

        repeat(frameCount) { frameIndex ->
            if (frameIndex % 64 == 0) checkCancelled()
            val start = frameIndex * config.hopSize
            for (index in 0 until config.frameSize) {
                val window = .5 - .5 * cos(2.0 * PI * index / (config.frameSize - 1))
                real[index] = (pcm.getOrElse(start + index) { 0f } * window).toDouble()
                imaginary[index] = 0.0
            }
            fft(real, imaginary)

            var low = 0f
            var mid = 0f
            var high = 0f
            var energy = 0.0
            var bassEnergy = 0.0
            for (bin in previous.indices) {
                val power = real[bin] * real[bin] + imaginary[bin] * imaginary[bin]
                val magnitude = ln(1.0 + sqrt(power)).toFloat()
                val positiveDelta = max(0f, magnitude - previous[bin])
                previous[bin] = magnitude
                val frequency = bin.toFloat() * sampleRate / config.frameSize
                energy += power
                if (frequency in 35f..250f) bassEnergy += power
                when {
                    frequency < 250f -> low += positiveDelta
                    frequency < 2_500f -> mid += positiveDelta
                    else -> high += positiveDelta
                }
            }
            result += FluxFrame(
                centerSample = (start + config.frameSize / 2L).coerceAtMost(pcm.lastIndex.toLong()),
                total = low + mid + high,
                low = low,
                mid = mid,
                high = high,
                energy = energy,
                bassEnergy = bassEnergy
            )
        }
        return result
    }

    private fun detectDrops(frames: List<FluxFrame>, onsets: List<Int>, sampleRate: Int,
        config: Config): List<AudioBeatMap.Drop> {
        // Compare sustained energy, not one-frame flux: ordinary periodic kicks should not
        // become drops. This remains a heuristic for bass entrances in electronic music.
        val window = max(1, sampleRate / config.hopSize)
        if (frames.size < window * 3) return emptyList()
        val energy = DoubleArray(frames.size + 1)
        val bass = DoubleArray(frames.size + 1)
        frames.forEachIndexed { index, frame ->
            energy[index + 1] = energy[index] + frame.energy
            bass[index + 1] = bass[index] + frame.bassEnergy
        }
        fun average(prefix: DoubleArray, from: Int, to: Int): Double =
            (prefix[to] - prefix[from]) / (to - from)
        val candidates = onsets.mapNotNull { index ->
            val before = index - window * 2
            val after = index + window
            if (before < 0 || after > frames.size) return@mapNotNull null
            val previous = average(energy, before, index).coerceAtLeast(1e-6)
            val next = average(energy, index, after)
            val previousBass = average(bass, before, index).coerceAtLeast(1e-6)
            val nextBass = average(bass, index, after)
            if (next < 1.0 || next < previous * 2.5 || nextBass < previousBass * 3.0 ||
                nextBass < next * .25 || frames[index].bassEnergy < previousBass * 3.0)
                return@mapNotNull null
            AudioBeatMap.Drop(frames[index].centerSample,
                ((nextBass / previousBass - 3.0) / 10.0).toFloat().coerceIn(.3f, 1f))
        }
        val result = ArrayList<AudioBeatMap.Drop>()
        for (drop in candidates) {
            if (result.lastOrNull()?.let { drop.sampleIndex - it.sampleIndex < sampleRate * 2L } == true) continue
            result += drop
        }
        return result
    }

    private fun pickOnsets(frames: List<FluxFrame>, sampleRate: Int, config: Config): List<Int> {
        if (frames.size < 3) return emptyList()
        val minimumFrameGap = max(1, sampleRate * config.minimumOnsetSpacingMs / 1000 / config.hopSize)
        val candidates = ArrayList<Int>()
        for (index in 1 until frames.lastIndex) {
            val from = max(0, index - config.thresholdWindowFrames)
            val neighborhood = (from until index).map { frames[it].total }.sorted()
            val median = neighborhood[neighborhood.size / 2]
            val deviations = neighborhood.map { abs(it - median) }.sorted()
            val mad = deviations[deviations.size / 2]
            val threshold = median + config.thresholdMultiplier * max(mad, median * .08f) + 1e-5f
            val value = frames[index].total
            if (value >= threshold && value >= frames[index - 1].total && value > frames[index + 1].total) {
                val previousIndex = candidates.lastOrNull()
                if (previousIndex == null || index - previousIndex >= minimumFrameGap) {
                    candidates += index
                } else if (value > frames[previousIndex].total) {
                    candidates[candidates.lastIndex] = index
                }
            }
        }
        return candidates
    }

    private fun normalizeOnsets(
        indices: List<Int>,
        frames: List<FluxFrame>,
        durationSamples: Long
    ): List<AudioBeatMap.Onset> {
        val maximum = indices.maxOfOrNull { frames[it].total }?.coerceAtLeast(1e-6f) ?: return emptyList()
        return indices.map { index ->
            val frame = frames[index]
            AudioBeatMap.Onset(
                sampleIndex = frame.centerSample.coerceIn(0L, durationSamples - 1L),
                strength = (frame.total / maximum).coerceIn(0f, 1f),
                dominantBand = frame.dominantBand()
            )
        }.distinctBy { it.sampleIndex }
    }

    private fun estimateBeatPeriod(
        onsets: List<AudioBeatMap.Onset>,
        sampleRate: Int,
        config: Config
    ): Long? {
        if (onsets.size < 3) return null
        val minimum = 60L * sampleRate / config.maximumTempoBpm
        val maximum = 60L * sampleRate / config.minimumTempoBpm
        val binSize = max(1L, sampleRate / 200L)
        val votes = HashMap<Long, Float>()

        for (left in onsets.indices) {
            for (right in left + 1..minOf(onsets.lastIndex, left + 8)) {
                var delta = onsets[right].sampleIndex - onsets[left].sampleIndex
                while (delta < minimum) delta *= 2L
                while (delta > maximum) delta /= 2L
                if (delta in minimum..maximum) {
                    val bucket = ((delta + binSize / 2L) / binSize) * binSize
                    val distancePenalty = 1f / (right - left)
                    votes[bucket] = (votes[bucket] ?: 0f) +
                        distancePenalty * onsets[left].strength * onsets[right].strength
                }
            }
        }
        val winningBucket = votes.maxByOrNull { it.value }?.key ?: return null
        val nearby = ArrayList<Pair<Long, Float>>()
        for (left in onsets.indices) {
            for (right in left + 1..minOf(onsets.lastIndex, left + 8)) {
                var delta = onsets[right].sampleIndex - onsets[left].sampleIndex
                while (delta < minimum) delta *= 2L
                while (delta > maximum) delta /= 2L
                if (abs(delta - winningBucket) <= binSize * 2L) {
                    nearby += delta to (onsets[left].strength * onsets[right].strength).coerceAtLeast(.01f)
                }
            }
        }
        val weight = nearby.sumOf { it.second.toDouble() }.toFloat().coerceAtLeast(.001f)
        return (nearby.sumOf { (period, strength) -> period * strength.toDouble() } / weight).roundToLong()
    }

    private fun buildBeatGrid(
        onsets: List<AudioBeatMap.Onset>,
        periodSamples: Long,
        sampleRate: Int,
        durationSamples: Long
    ): List<AudioBeatMap.Beat> {
        val tolerance = max(periodSamples / 4L, sampleRate / 50L)
        val phaseCandidates = onsets.take(minOf(12, onsets.size))
        val phase = phaseCandidates.maxByOrNull { candidate ->
            var score = 0f
            var cursor = candidate.sampleIndex
            while (cursor < durationSamples) {
                score += onsets.nearest(cursor, tolerance)?.strength ?: 0f
                cursor += periodSamples
            }
            score
        }?.sampleIndex ?: return emptyList()

        var first = phase
        while (first - periodSamples >= 0L) first -= periodSamples
        val raw = ArrayList<Pair<Long, AudioBeatMap.Onset?>>()
        var cursor = first
        while (cursor < durationSamples) {
            val nearest = onsets.nearest(cursor, tolerance)
            raw += (nearest?.sampleIndex ?: cursor) to nearest
            cursor += periodSamples
        }
        val unique = raw.distinctBy { it.first }.sortedBy { it.first }
        if (unique.isEmpty()) return emptyList()

        val downbeatPhase = (0..3).maxByOrNull { candidatePhase ->
            unique.indices.filter { it % 4 == candidatePhase }.sumOf { index ->
                (unique[index].second?.strength ?: .08f).toDouble()
            }
        } ?: 0
        return unique.mapIndexed { index, (sampleIndex, onset) ->
            AudioBeatMap.Beat(
                sampleIndex = sampleIndex,
                strength = onset?.strength ?: .08f,
                dominantBand = onset?.dominantBand ?: AudioBeatMap.FrequencyBand.BROADBAND,
                isDownbeat = index % 4 == downbeatPhase
            )
        }
    }

    private fun List<AudioBeatMap.Onset>.nearest(sample: Long, tolerance: Long): AudioBeatMap.Onset? =
        minByOrNull { abs(it.sampleIndex - sample) }?.takeIf { abs(it.sampleIndex - sample) <= tolerance }

    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        val size = real.size
        var reversed = 0
        for (index in 1 until size) {
            var bit = size shr 1
            while (reversed and bit != 0) {
                reversed = reversed xor bit
                bit = bit shr 1
            }
            reversed = reversed xor bit
            if (index < reversed) {
                val realValue = real[index]
                real[index] = real[reversed]
                real[reversed] = realValue
                val imaginaryValue = imaginary[index]
                imaginary[index] = imaginary[reversed]
                imaginary[reversed] = imaginaryValue
            }
        }
        var length = 2
        while (length <= size) {
            val angle = -2.0 * PI / length
            val stepReal = cos(angle)
            val stepImaginary = sin(angle)
            var start = 0
            while (start < size) {
                var currentReal = 1.0
                var currentImaginary = 0.0
                for (offset in 0 until length / 2) {
                    val even = start + offset
                    val odd = even + length / 2
                    val oddReal = real[odd] * currentReal - imaginary[odd] * currentImaginary
                    val oddImaginary = real[odd] * currentImaginary + imaginary[odd] * currentReal
                    real[odd] = real[even] - oddReal
                    imaginary[odd] = imaginary[even] - oddImaginary
                    real[even] += oddReal
                    imaginary[even] += oddImaginary
                    val nextReal = currentReal * stepReal - currentImaginary * stepImaginary
                    currentImaginary = currentReal * stepImaginary + currentImaginary * stepReal
                    currentReal = nextReal
                }
                start += length
            }
            length = length shl 1
        }
    }
}
