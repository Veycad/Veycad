package com.veycad.app

import java.io.File

/** Production entry point joining decoded evidence to reproducible montage alternatives. */
object VeycadEventPipeline {
    /** Shared observations only. A product director owns the edit, not this evidence step. */
    data class Evidence(val audioMap: AudioBeatMap, val visualMap: VisualEventMap)

    data class Result(
        val audioMap: AudioBeatMap,
        val visualMap: VisualEventMap,
        val alternatives: List<EventMatchingDirector.Alternative>
    )

    fun evidenceFromAudioFile(
        audioFile: File,
        sourceDurationUs: Long,
        outputDurationUs: Long,
        observations: List<VisualEventMap.Observation>
    ): Evidence {
        require(sourceDurationUs > 0L && outputDurationUs > 0L)
        val decoded = MediaCodecAudioDecoder.decode(audioFile, maxOf(sourceDurationUs, outputDurationUs))
        val analysed = AudioBeatMapAnalyzer.analyze(decoded.mono(), decoded.sampleRate)
        val targetSamples = outputDurationUs * analysed.sampleRate / 1_000_000L
        val audioMap = if (analysed.durationSamples < targetSamples) {
            analysed.loopedTo(targetSamples)
        } else analysed
        return Evidence(audioMap, VisualEventMapAnalyzer.analyze(sourceDurationUs, observations))
    }

    fun fromAudioFile(
        sourceId: String,
        audioFile: File,
        sourceDurationUs: Long,
        observations: List<VisualEventMap.Observation>,
        frameAttachments: FrameAttachmentTimeline = FrameAttachmentTimeline(),
        requestedOutputDurationMs: Long? = null,
        requestedStyle: EventMatchingDirector.Style? = null
    ): Result {
        val fearProfile = FearStrobeProfile.matchesAudio(audioFile)
        val dualityProfile = DualityLoopProfile.matchesAudio(audioFile)
        val decodeDurationUs = when {
            fearProfile -> maxOf(sourceDurationUs, FearStrobeProfile.OUTPUT_DURATION_US)
            dualityProfile -> maxOf(sourceDurationUs, DualityLoopProfile.OUTPUT_DURATION_US)
            else -> sourceDurationUs
        }
        val decoded = MediaCodecAudioDecoder.decode(audioFile, decodeDurationUs)
        val analysedAudio = AudioBeatMapAnalyzer.analyze(decoded.mono(), decoded.sampleRate)
        val sourceDurationMs = sourceDurationUs / 1_000L
        val referenceProfile = ReferenceMontageProfile.matchesAudio(audioFile)
        val referenceDurationMs: Long? = if (referenceProfile) {
            ReferenceMontageProfile.outputDurationMs(audioFile, sourceDurationMs)
        } else {
            null
        }
        val outputDurationMs: Long = requestedOutputDurationMs
            ?: referenceDurationMs
            ?: FearStrobeProfile.OUTPUT_DURATION_US.div(1_000L).takeIf { fearProfile }
            ?: DualityLoopProfile.OUTPUT_DURATION_US.div(1_000L).takeIf { dualityProfile }
            ?: EditDurationPolicy.choose(sourceDurationMs, analysedAudio).durationMs
        return fromAnalysedAudio(
            sourceId,
            audioFile.absolutePath,
            sourceDurationUs,
            outputDurationMs,
            analysedAudio,
            observations,
            frameAttachments,
            when {
                referenceProfile -> ReferenceMontageProfile.ID
                fearProfile -> FearStrobeProfile.ID
                dualityProfile -> DualityLoopProfile.ID
                else -> null
            },
            requestedStyle
        )
    }

    fun fromPcm(
        sourceId: String,
        audioSourceId: String,
        monoPcm: FloatArray,
        sampleRate: Int,
        sourceDurationUs: Long,
        outputDurationMs: Long,
        observations: List<VisualEventMap.Observation>,
        frameAttachments: FrameAttachmentTimeline = FrameAttachmentTimeline()
    ): Result {
        require(sourceId.isNotBlank() && audioSourceId.isNotBlank())
        require(sourceDurationUs >= outputDurationMs * 1_000L)
        val analysedAudio = AudioBeatMapAnalyzer.analyze(monoPcm, sampleRate)
        return fromAnalysedAudio(
            sourceId,
            audioSourceId,
            sourceDurationUs,
            outputDurationMs,
            analysedAudio,
            observations,
            frameAttachments,
            null
        )
    }

    private fun fromAnalysedAudio(
        sourceId: String,
        audioSourceId: String,
        sourceDurationUs: Long,
        outputDurationMs: Long,
        analysedAudio: AudioBeatMap,
        observations: List<VisualEventMap.Observation>,
        frameAttachments: FrameAttachmentTimeline,
        referenceProfileId: String?,
        requestedStyle: EventMatchingDirector.Style? = null
    ): Result {
        require(sourceId.isNotBlank() && audioSourceId.isNotBlank())
        val sourceDurationMs = sourceDurationUs / 1_000L
        require(
            sourceDurationUs >= outputDurationMs * 1_000L ||
                referenceProfileId == ReferenceMontageProfile.ID &&
                ReferenceMontageProfile.canExtendSourceToTimeline(sourceDurationMs, outputDurationMs) ||
                referenceProfileId == FearStrobeProfile.ID &&
                FearStrobeProfile.canExtendSourceToTimeline(sourceDurationMs, outputDurationMs) ||
                referenceProfileId == DualityLoopProfile.ID &&
                DualityLoopProfile.canExtendSourceToTimeline(sourceDurationMs, outputDurationMs)
        ) {
            "Source duration $sourceDurationMs ms cannot fill output timeline $outputDurationMs ms"
        }
        val sampleRate = analysedAudio.sampleRate
        val targetSamples = outputDurationMs * sampleRate / 1_000L
        val audioMap = if (analysedAudio.durationSamples < targetSamples) {
            analysedAudio.loopedTo(targetSamples)
        } else analysedAudio
        val visualMap = VisualEventMapAnalyzer.analyze(sourceDurationUs, observations)
        val alternatives = EventMatchingDirector.direct(EventMatchingDirector.Request(
            sourceId,
            audioSourceId,
            outputDurationMs,
            audioMap,
            visualMap,
            frameAttachments,
            referenceProfileId
        ), requestedStyle)
        return Result(audioMap, visualMap, alternatives)
    }
}
