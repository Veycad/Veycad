package com.veycad.app

import android.graphics.ImageFormat
import android.graphics.Bitmap
import android.graphics.Color
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Sequentially decodes the encoded MP4 and measures the decoder output itself.
 *
 * Random-access retriever seeks are deliberately avoided: around short transitions they may
 * return a neighbouring keyframe or transient empty frame and produce a false artifact report.
 */
internal object RenderedVisualSampler {
    private const val DEQUEUE_TIMEOUT_US = 10_000L
    private const val SAMPLE_WIDTH = 48
    private const val SAMPLE_HEIGHT = 72

    fun sample(
        file: File,
        graph: MontageGraph,
        sourceVisualMap: VisualEventMap? = null,
        sourceFile: File? = null,
        intervalUs: Long = 100_000L,
        decodedSourceTimes: Map<Long, Long> = emptyMap(),
        renderFps: Int = HighQualityFramePlan.DEFAULT_FPS,
        secondarySourceVisualMap: VisualEventMap? = null,
        sourceFiles: List<File> = listOfNotNull(sourceFile),
        checkCancelled: () -> Unit = {}
    ): List<RenderedMp4Acceptance.VisualSample> {
        require(file.isFile && intervalUs > 0L)
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        val faceDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .build()
        )
        val segmenter = Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .enableRawSizeMask()
                .build()
        )
        val sourceRetrievers = sourceFiles.map { source ->
            source.takeIf(File::isFile)?.let { MediaMetadataRetriever().apply { setDataSource(it.absolutePath) } }
        }
        try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("Rendered MP4 has no video track")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val framings = sourceFiles.map { source -> VideoDisplayOrientation.cropForFile(source,
                format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT)) }
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            decoder = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }
            return decode(
                extractor,
                decoder,
                graph,
                sourceVisualMap,
                faceDetector,
                segmenter,
                sourceRetrievers,
                intervalUs,
                decodedSourceTimes,
                renderFps,
                secondarySourceVisualMap,
                framings,
                format.getInteger(MediaFormat.KEY_WIDTH).toFloat() / format.getInteger(MediaFormat.KEY_HEIGHT),
                checkCancelled
            )
        } finally {
            runCatching { decoder?.stop() }
            decoder?.release()
            extractor.release()
            faceDetector.close()
            segmenter.close()
            sourceRetrievers.forEach { it?.release() }
        }
    }

    internal fun withDecodedSourceTime(
        frame: HighQualityFramePlan.Frame,
        timeline: FrameAttachmentTimeline,
        decodedUs: Long?,
        sources: List<SourceAttachments> = emptyList(),
        refreshAllTransitions: Boolean = false
    ): HighQualityFramePlan.Frame {
        if (decodedUs == null) return frame
        require(decodedUs >= 0L)
        val attachments = if (((frame.clipIndex == 0 || sources.isNotEmpty() || refreshAllTransitions) &&
            frame.transitionIn == MontageGraph.Transition.FOREGROUND_REENTRY) ||
            frame.layer.kind == MontageGraph.OverlayKind.SUBJECT_STAGE ||
            frame.layer.kind == MontageGraph.OverlayKind.MIRROR_SLICE) {
            MediaCodecSpeedRampRenderer.decodedAttachments(frame, decodedUs, timeline, sources)
        } else frame.attachments
        return frame.copy(sourceTimeUs = decodedUs, attachments = attachments)
    }

    /** A rounded 60 fps target can be 1 us above a decoder's truncated PTS. */
    internal fun reachedSampleTarget(decodedUs: Long, targetUs: Long): Boolean =
        decodedUs >= targetUs || (targetUs > 0L && decodedUs == targetUs - 1L)

    /** Once Heartbeat's boundary evidence begins, every actual decoder image is required. */
    internal fun shouldMeasureDecodedFrame(
        decodedUs: Long,
        nextTargetUs: Long?,
        heartbeatTailStartUs: Long?
    ): Boolean = (heartbeatTailStartUs != null && decodedUs >= heartbeatTailStartUs) ||
        (nextTargetUs != null && reachedSampleTarget(decodedUs, nextTargetUs))

    internal fun expectedFaceConfidence(
        frame: HighQualityFramePlan.Frame,
        primary: VisualEventMap?,
        secondary: VisualEventMap?
    ): Float {
        val source = when (frame.sourceIndex) { 0 -> primary; 1 -> secondary; else -> null }
        return source?.observations
            ?.minByOrNull { abs(it.sourceTimeUs - frame.sourceTimeUs) }
            ?.takeIf { abs(it.sourceTimeUs - frame.sourceTimeUs) <= FACE_EXPECTATION_TOLERANCE_US }
            ?.face?.confidence ?: 0f
    }

    private fun decode(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        graph: MontageGraph,
        sourceVisualMap: VisualEventMap?,
        faceDetector: FaceDetector,
        segmenter: Segmenter,
        sourceRetrievers: List<MediaMetadataRetriever?>,
        intervalUs: Long,
        decodedSourceTimes: Map<Long, Long>,
        renderFps: Int,
        secondarySourceVisualMap: VisualEventMap?,
        framings: List<SourceFraming.Crop>,
        outputAspect: Float,
        checkCancelled: () -> Unit
    ): List<RenderedMp4Acceptance.VisualSample> {
        val output = ArrayList<RenderedMp4Acceptance.VisualSample>()
        val bufferInfo = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        val plan = HighQualityFramePlan.build(graph, renderFps)
        val sampleTargetsUs = samplingTargets(graph, intervalUs, renderFps, plan)
        val heartbeatTailStartUs = if (graph.manualOverrides == null && HeartbeatMontageProfile.appliesTo(graph))
            HeartbeatPulseAudit.tailSamplingTargetsUs().first() - 1L else null
        var nextSampleIndex = 0
        var previous: Features? = null
        var previousOutputMask: FloatArray? = null
        var previousEntranceTravel: Float? = null
        var previousMaskSource: Int? = null
        val framePlan = plan.frames.map { frame ->
            withDecodedSourceTime(frame, graph.frameAttachments, decodedSourceTimes[frame.outputTimeUs], graph.sourceAttachments,
                refreshAllTransitions = graph.manualOverrides != null)
        }
        val frameIndex = RenderQaFrameIndex(framePlan)
        var idleIterations = 0
        while (!outputEnded) {
            checkCancelled()
            var progressed = false
            if (!inputEnded) {
                val inputIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val input = requireNotNull(decoder.getInputBuffer(inputIndex)).apply { clear() }
                    val size = extractor.readSampleData(input, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputEnded = true
                    } else {
                        decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                    progressed = true
                }
            }

            when (val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> progressed = true
                else -> if (outputIndex >= 0) {
                    val ptsUs = bufferInfo.presentationTimeUs.coerceAtLeast(0L)
                    if (bufferInfo.size > 0 && shouldMeasureDecodedFrame(ptsUs,
                        sampleTargetsUs.getOrNull(nextSampleIndex), heartbeatTailStartUs)) {
                        val image = decoder.getOutputImage(outputIndex)
                            ?: error("Decoder did not expose a YUV image at $ptsUs us")
                        val scheduledFrame = framePlan[frameIndex.nearest(ptsUs)]
                        val authoredTransition = transitionAt(scheduledFrame)
                        val sourceRetriever = scheduledFrame?.sourceIndex?.let { sourceRetrievers.getOrNull(it) }
                        val framing = scheduledFrame?.sourceIndex?.let { framings.getOrNull(it) } ?: SourceFraming.Crop(1f, 1f)
                        if (previousMaskSource != scheduledFrame?.sourceIndex) {
                            previousOutputMask = null
                            previousEntranceTravel = null
                        }
                        previousMaskSource = scheduledFrame?.sourceIndex
                        val reentryProgress = scheduledFrame?.transitionProgress ?: -1f
                        val incomingMask = scheduledFrame?.attachments?.mask?.takeIf {
                            authoredTransition == MontageGraph.Transition.FOREGROUND_REENTRY &&
                                it.confidence >= MINIMUM_MASK_CONFIDENCE
                        }
                        val subjectStageMask = scheduledFrame?.attachments?.mask?.takeIf {
                            scheduledFrame.layer.kind == MontageGraph.OverlayKind.SUBJECT_STAGE &&
                                scheduledFrame.layer.opacity >= SUBJECT_STAGE_QA_OPACITY &&
                                it.confidence >= MINIMUM_MASK_CONFIDENCE
                        }
                        val authoredBlackFade = scheduledFrame?.layer?.let {
                            (it.kind == MontageGraph.OverlayKind.BLACK_FADE && it.opacity > .01f) ||
                                it.finalFadeOpacity > .01f
                        } == true
                        val authoredFlash = isIntentionalFlash(graph, ptsUs, renderFps, scheduledFrame?.layer)
                        val authoredFearTitle = FearStrobeProfile.appliesTo(graph) &&
                            (FearStrobeProfile.titleAt(ptsUs)?.opacity ?: 0f) >= .45f
                        val authoredDefocus = (scheduledFrame?.effects?.defocus ?: 0f) >= .25f
                        val faceCanBeJudged = authoredTransition !in FACE_TRANSITION_EXCLUSIONS &&
                            !authoredBlackFade && !authoredFlash && !authoredFearTitle && !authoredDefocus
                        val faceExpected = scheduledFrame?.let { scheduled ->
                            expectedFaceConfidence(scheduled, sourceVisualMap, secondarySourceVisualMap) >= .65f
                        } == true && faceCanBeJudged
                        // Every face-expected QA sample is measured on THIS decoded image. Extra
                        // FEAR/Heartbeat samples and post-cut frames cannot borrow earlier faces.
                        val shouldDetectFace = faceExpected
                        val bitmap = if (shouldDetectFace || incomingMask != null || subjectStageMask != null) {
                            faceBitmap(image)
                        } else null
                        val shouldMeasureMatte = reentryProgress in
                            FOREGROUND_COMPLETE_START..FOREGROUND_BACKGROUND_START
                        val expectedMasks = if (incomingMask == null || bitmap == null || !shouldMeasureMatte) emptyList() else listOf(
                            (exactSourceMask(
                                segmenter,
                                sourceRetriever,
                                scheduledFrame.sourceTimeUs,
                                bitmap.width,
                                bitmap.height,
                                incomingMask
                            ) ?: incomingMask).let { exact ->
                                // Use exactly the same entrance motion as the renderer. Sigma
                                // follows its measured timeline; other profiles retain the envelope.
                                val subjectEnvelope = TransitionTimeline.blendFor(scheduledFrame)
                                    ?.foregroundReentry ?: 0f
                                val entranceTravel = if (ReferenceMontageProfile.appliesTo(graph))
                                    SigmaComposition.entranceTravel(scheduledFrame.originalOutputTimeUs)
                                else ForegroundReentryMotion.verticalTravel(subjectEnvelope)
                                val sigma = ReferenceMontageProfile.appliesTo(graph)
                                exact.copy(values = SemanticMaskMetrics.transform(
                                    if (sigma) SigmaComposition.openingMask(exact.values, exact.width, exact.height,
                                        framing, entranceTravel,
                                        (TransitionTimeline.blendFor(scheduledFrame)?.outlineStrength ?: 0f) > .001f)
                                    else SourceFraming.cropPlane(exact.values, exact.width, exact.height, framing),
                                    exact.width,
                                    exact.height,
                                    scheduledFrame.transform.scale,
                                    scheduledFrame.transform.translateX,
                                    -scheduledFrame.transform.translateY,
                                    scheduledFrame.transform.rotationDegrees,
                                    outputAspect,
                                    preTranslateY = if (sigma) 0f else entranceTravel
                                ))
                            }
                        )
                        val expectedStageMask = if (subjectStageMask == null || bitmap == null) null else {
                            (exactSourceMask(
                                segmenter,
                                sourceRetriever,
                                scheduledFrame.sourceTimeUs,
                                bitmap.width,
                                bitmap.height,
                                subjectStageMask
                            ) ?: subjectStageMask).let { exact ->
                                SemanticMaskMetrics.transform(
                                    SourceFraming.cropPlane(exact.values, exact.width, exact.height, framing),
                                    exact.width,
                                    exact.height,
                                    scheduledFrame.transform.scale,
                                    scheduledFrame.transform.translateX,
                                    -scheduledFrame.transform.translateY,
                                    scheduledFrame.transform.rotationDegrees,
                                    outputAspect
                                ).let { transformed ->
                                    SemanticMaskMetrics.downsample(
                                        transformed,
                                        exact.width,
                                        exact.height,
                                        SAMPLE_WIDTH,
                                        SAMPLE_HEIGHT
                                    )
                                }
                            }
                        }
                        var edgeLeakRatio = 0f
                        var maskEvidence = RenderedMp4Acceptance.MaskEvidence.NOT_REQUESTED
                        var maskTemporalIou: Float? = null
                        val current: Features
                        val faceEvidence = if (shouldDetectFace) {
                            val confidence = detectFaceConfidenceAtQaScales(
                                faceDetector,
                                requireNotNull(bitmap)
                            )
                            DecodedFaceEvidence.fromAttempts(ptsUs, requireNotNull(scheduledFrame).sourceIndex,
                                scheduledFrame.clipIndex, listOf(confidence))
                        } else null
                        if (expectedMasks.isNotEmpty()) {
                            val measurement = outputMaskMeasurement(
                                segmenter,
                                requireNotNull(bitmap),
                                expectedMasks,
                                blackStage = ReferenceMontageProfile.appliesTo(graph) && ptsUs < 2_200_000L
                            )
                            edgeLeakRatio = measurement.edgeLeakRatio
                            maskEvidence = measurement.evidence
                            val currentTravel = if (ReferenceMontageProfile.appliesTo(graph))
                                SigmaComposition.entranceTravel(scheduledFrame?.originalOutputTimeUs ?: ptsUs) * (scheduledFrame?.transform?.scale ?: 1f)
                                else null
                            maskTemporalIou = previousOutputMask?.takeIf {
                                measurement.evidence == RenderedMp4Acceptance.MaskEvidence.MEASURED &&
                                it.size == measurement.mask.size
                            }?.let { previousMask ->
                                SemanticMaskMetrics.registeredTemporalIou(
                                    if (currentTravel != null && previousEntranceTravel != null)
                                        SemanticMaskMetrics.transform(previousMask, measurement.width, measurement.height,
                                            1f, 0f, 0f, preTranslateY = currentTravel - previousEntranceTravel!!)
                                    else previousMask,
                                    measurement.mask,
                                    measurement.width,
                                    measurement.height
                                )
                            }
                            previousOutputMask = measurement.mask.takeIf {
                                measurement.evidence == RenderedMp4Acceptance.MaskEvidence.MEASURED }
                            previousEntranceTravel = currentTravel.takeIf { previousOutputMask != null }
                        } else if (authoredTransition != MontageGraph.Transition.FOREGROUND_REENTRY) {
                            // A low-confidence sparse sample must not erase the last valid decoded
                            // matte inside the same live entrance. Keep it until the next valid mask
                            // so temporal QA can compare the moving silhouette across sparse ML PTS.
                            previousOutputMask = null
                            previousEntranceTravel = null
                        }
                        bitmap?.recycle()
                        current = image.use(::features)
                        val subjectStageBackgroundLuma = expectedStageMask?.let { mask ->
                            subjectStageBackgroundLuma(current.pixels, mask)
                        }
                        val subjectStageBackgroundLoss = if (ReferenceMontageProfile.appliesTo(graph) &&
                            expectedStageMask != null && scheduledFrame.layer.finalFadeOpacity < .01f) {
                            sourceLumaAtCamera(sourceRetriever, scheduledFrame, framing, outputAspect)?.let { source ->
                                SigmaComposition.backgroundLoss(current.pixels, source, expectedStageMask)
                            }
                        } else null
                        val difference = previous?.let { frameDifference(it, current) } ?: 0f
                        val expectedEffect = when {
                            (scheduledFrame?.effects?.glitch ?: 0f) >= .04f ->
                                RenderedMp4Acceptance.DecodedEffect.GLITCH
                            scheduledFrame?.layer?.kind == MontageGraph.OverlayKind.MIRROR_SLICE &&
                                scheduledFrame.layer.opacity >= .08f ->
                                RenderedMp4Acceptance.DecodedEffect.MIRROR_SLICE
                            scheduledFrame?.layer?.kind == MontageGraph.OverlayKind.DOUBLE_EXPOSURE &&
                                scheduledFrame.layer.opacity >= .08f ->
                                RenderedMp4Acceptance.DecodedEffect.DOUBLE_EXPOSURE
                            else -> null
                        }
                        val effectSignature = when (expectedEffect) {
                            RenderedMp4Acceptance.DecodedEffect.DOUBLE_EXPOSURE -> maxOf(
                                difference,
                                DecodedEffectSignature.doubleExposure(
                                    current.pixels,
                                    SAMPLE_WIDTH,
                                    SAMPLE_HEIGHT
                                )
                            )
                            RenderedMp4Acceptance.DecodedEffect.MIRROR_SLICE ->
                                maxOf(
                                    difference,
                                    DecodedEffectSignature.mirrorSlice(
                                        current.pixels,
                                        SAMPLE_WIDTH,
                                        SAMPLE_HEIGHT,
                                        scheduledFrame?.layer?.progress ?: 0f
                                    )
                                )
                            RenderedMp4Acceptance.DecodedEffect.GLITCH ->
                                (if (ReferenceMontageProfile.appliesTo(graph)) {
                                    sourceLumaAtCamera(sourceRetriever, requireNotNull(scheduledFrame), framing, outputAspect)
                                } else null)?.let { source ->
                                    DecodedEffectSignature.horizontalFragmentation(
                                        current.pixels, source, SAMPLE_WIDTH, SAMPLE_HEIGHT
                                    )
                                } ?: DecodedEffectSignature.glitch(
                                    current.pixels,
                                    current.chromaPixelsU,
                                    current.chromaPixelsV,
                                    SAMPLE_WIDTH,
                                    SAMPLE_HEIGHT
                                )
                            null -> 0f
                        }
                        val foregroundDarkStage = isIntentionalForegroundDarkStage(
                            authoredTransition,
                            scheduledFrame?.transitionProgress
                        )
                        val authoredSubjectStage = scheduledFrame?.layer?.let {
                            it.kind == MontageGraph.OverlayKind.SUBJECT_STAGE && it.opacity > .01f &&
                                !ReferenceMontageProfile.appliesTo(graph)
                        } == true
                        val intentionalDarkFrame = authoredTransition == MontageGraph.Transition.BLACKOUT ||
                            foregroundDarkStage || authoredBlackFade || authoredSubjectStage
                        val blackArtifact = if (!intentionalDarkFrame && current.luma < .035f && current.variance < .003f) .85f else 0f
                        val clippingArtifact = clippingArtifact(
                            current.clippedRatio,
                            intentionalDarkFrame || authoredFlash
                        )
                        val blockArtifact = if (!foregroundDarkStage && !authoredBlackFade &&
                            !authoredSubjectStage && current.luma >= .12f) {
                            // FEAR's contrast grade can make clothing and hair legitimately near-black.
                            // Count only dead tiles introduced relative to the corresponding source frame.
                            if ((ReferenceMontageProfile.appliesTo(graph) || FearStrobeProfile.appliesTo(graph)) &&
                                current.blackBlockScore > .08f) {
                                sourceLumaAtCamera(sourceRetriever, requireNotNull(scheduledFrame), framing, outputAspect)?.let {
                                    DecodedEffectSignature.introducedBlackBlocks(current.pixels, it,
                                        SAMPLE_WIDTH, SAMPLE_HEIGHT)
                                } ?: current.blackBlockScore
                            } else current.blackBlockScore
                        } else {
                            0f
                        }
                        output += RenderedMp4Acceptance.VisualSample(
                            outputTimeUs = ptsUs,
                            transitionStrength = difference,
                            luma = current.luma,
                            chromaU = current.chromaU,
                            chromaV = current.chromaV,
                            artifactScore = maxOf(blackArtifact, clippingArtifact, blockArtifact),
                            faceExpected = faceExpected,
                            // Zero is only a conservative numeric placeholder when confidence is
                            // unknown. The separate evidence gate never treats it as measured.
                            faceConfidence = faceEvidence?.confidence ?: 0f,
                            decodedSourceIndex = scheduledFrame?.sourceIndex,
                            decodedClipIndex = scheduledFrame?.clipIndex,
                            decodedFaceEvidence = faceEvidence,
                            maskExpected = incomingMask != null,
                            edgeLeakRatio = edgeLeakRatio,
                            maskEvidence = maskEvidence,
                            blackBlockScore = blockArtifact,
                            maskTemporalIou = maskTemporalIou,
                            expectedEffect = expectedEffect,
                            effectSignatureStrength = effectSignature,
                            subjectStageBackgroundLuma = subjectStageBackgroundLuma,
                            subjectStageBackgroundLoss = subjectStageBackgroundLoss
                        )
                        previous = current
                        while (nextSampleIndex < sampleTargetsUs.size &&
                            reachedSampleTarget(ptsUs, sampleTargetsUs[nextSampleIndex])) nextSampleIndex++
                    }
                    outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(outputIndex, false)
                    progressed = true
                }
            }
            idleIterations = if (progressed) 0 else idleIterations + 1
            check(idleIterations < 500) { "Video decoder stalled before end of stream" }
        }
        check(output.isNotEmpty()) { "Video decoder produced no measurable YUV frames" }
        // Duplicate decoder images must remain visible to Heartbeat's one-witness-per-slot audit.
        val seenTimesUs = HashSet<Long>()
        return output.sortedBy { it.outputTimeUs }.filter {
            (heartbeatTailStartUs != null && it.outputTimeUs >= heartbeatTailStartUs) ||
                seenTimesUs.add(it.outputTimeUs)
        }
    }

    private data class Features(
        val pixels: FloatArray,
        val chromaPixelsU: FloatArray,
        val chromaPixelsV: FloatArray,
        val luma: Float,
        val variance: Float,
        val chromaU: Float,
        val chromaV: Float,
        val clippedRatio: Float,
        val blackBlockScore: Float
    )

    private fun features(image: Image): Features {
        check(image.format == ImageFormat.YUV_420_888) {
            "Expected YUV_420_888, got ${image.format}"
        }
        val crop = image.cropRect
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val pixels = FloatArray(SAMPLE_WIDTH * SAMPLE_HEIGHT)
        val chromaPixelsU = FloatArray(pixels.size)
        val chromaPixelsV = FloatArray(pixels.size)
        var sumLuma = 0f
        var sumU = 0f
        var sumV = 0f
        var clipped = 0
        var index = 0
        repeat(SAMPLE_HEIGHT) { sampleY ->
            val y = crop.top + sampleY * crop.height() / SAMPLE_HEIGHT
            repeat(SAMPLE_WIDTH) { sampleX ->
                val x = crop.left + sampleX * crop.width() / SAMPLE_WIDTH
                val luma = limitedRangeLuma(readPlane(yPlane, x, y))
                val u = readChroma(uPlane, x, y)
                val v = readChroma(vPlane, x, y)
                pixels[index] = luma
                chromaPixelsU[index] = u
                chromaPixelsV[index] = v
                index++
                sumLuma += luma
                sumU += u
                sumV += v
                if (luma < .015f || luma > .985f) clipped++
            }
        }
        val count = pixels.size
        val mean = sumLuma / count
        val variance = pixels.sumOf { value ->
            val delta = value - mean
            (delta * delta).toDouble()
        }.toFloat() / count
        return Features(
            pixels,
            chromaPixelsU,
            chromaPixelsV,
            mean,
            variance,
            sumU / count,
            sumV / count,
            clipped.toFloat() / count,
            blackBlockScore(pixels)
        )
    }

    private fun blackBlockScore(pixels: FloatArray): Float {
        val tileWidth = 6
        val tileHeight = 6
        var deadTiles = 0
        var tiles = 0
        for (top in 0 until SAMPLE_HEIGHT step tileHeight) {
            for (left in 0 until SAMPLE_WIDTH step tileWidth) {
                val values = ArrayList<Float>(tileWidth * tileHeight)
                for (y in top until minOf(top + tileHeight, SAMPLE_HEIGHT)) {
                    for (x in left until minOf(left + tileWidth, SAMPLE_WIDTH)) {
                        values += pixels[y * SAMPLE_WIDTH + x]
                    }
                }
                val mean = values.average().toFloat()
                val variance = values.sumOf { value ->
                    val delta = value - mean
                    (delta * delta).toDouble()
                }.toFloat() / values.size.coerceAtLeast(1)
                if (mean < .025f && variance < .0008f) deadTiles++
                tiles++
            }
        }
        return (deadTiles.toFloat() / tiles.coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    private fun detectFaceConfidence(detector: FaceDetector, bitmap: Bitmap): Float? = runCatching {
        val faces = Tasks.await(
            detector.process(InputImage.fromBitmap(bitmap, 0)),
            FACE_TIMEOUT_MS,
            TimeUnit.MILLISECONDS
        )
        val largestArea = faces.maxOfOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return@runCatching 0f
        val areaRatio = largestArea.toFloat() / (bitmap.width * bitmap.height).coerceAtLeast(1)
        (.58f + areaRatio * 3.2f).coerceIn(.58f, .96f)
    }.getOrNull()

    private fun detectFaceConfidenceAtQaScales(detector: FaceDetector, bitmap: Bitmap): Float? {
        val large = detectFaceConfidence(detector, bitmap)
        val longestSide = maxOf(bitmap.width, bitmap.height)
        if (longestSide <= LEGACY_FACE_QA_SIDE) return large
        val scale = LEGACY_FACE_QA_SIDE.toFloat() / longestSide
        val compact = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(64),
            (bitmap.height * scale).roundToInt().coerceAtLeast(64),
            true
        )
        return try {
            val small = detectFaceConfidence(detector, compact)
            if (large == null || small == null) null else maxOf(large, small)
        } finally {
            if (compact !== bitmap) compact.recycle()
        }
    }

    private data class MaskMeasurement(
        val edgeLeakRatio: Float,
        val mask: FloatArray,
        val width: Int,
        val height: Int,
        val evidence: RenderedMp4Acceptance.MaskEvidence
    )

    private fun outputMaskMeasurement(
        segmenter: Segmenter,
        bitmap: Bitmap,
        expected: List<FrameAttachments.Plane>,
        blackStage: Boolean = false
    ): MaskMeasurement = runCatching {
        val mask = Tasks.await(
            segmenter.process(InputImage.fromBitmap(bitmap, 0)),
            MASK_TIMEOUT_MS,
            TimeUnit.MILLISECONDS
        )
        val floats = mask.buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val values = FloatArray(floats.remaining()).also(floats::get)
        expected.map { candidate ->
            val detected = SemanticMaskMetrics.downsample(
                values,
                mask.width,
                mask.height,
                candidate.width,
                candidate.height
            )
            // Segmentation can hallucinate an off-screen torso in the empty black stage.
            // Black pixels outside the expected silhouette cannot be visible matte leakage.
            val actual = if (blackStage) {
                val luma = FloatArray(detected.size) { i ->
                    val x = ((i % candidate.width + .5f) * bitmap.width / candidate.width).toInt().coerceAtMost(bitmap.width - 1)
                    val y = ((i / candidate.width + .5f) * bitmap.height / candidate.height).toInt().coerceAtMost(bitmap.height - 1)
                    val pixel = bitmap.getPixel(x, y)
                    (Color.red(pixel) * .299f + Color.green(pixel) * .587f + Color.blue(pixel) * .114f) / 255f
                }
                SigmaComposition.visibleOpeningMask(detected, candidate.values, luma)
            } else detected
            MaskMeasurement(
                edgeLeakRatio = SemanticMaskMetrics.registeredEdgeLeakRatio(
                    candidate.values,
                    actual,
                    candidate.width,
                    candidate.height
                ),
                mask = actual,
                width = candidate.width,
                height = candidate.height,
                evidence = DecodedMaskEvidence.classify(candidate.values, actual)
            )
        }.minBy { it.edgeLeakRatio }
    }.getOrElse {
        val target = expected.first()
        MaskMeasurement(1f, FloatArray(target.width * target.height), target.width, target.height,
            RenderedMp4Acceptance.MaskEvidence.INFERENCE_FAILED)
    }

    /** Source pixels on the decoded clock, with the export's camera transform already applied. */
    private fun sourceLumaAtCamera(
        retriever: MediaMetadataRetriever?, frame: HighQualityFramePlan.Frame, framing: SourceFraming.Crop,
        outputAspect: Float
    ): FloatArray? {
        if (retriever == null || frame.sourceIndex != 0) return null
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(frame.sourceTimeUs, MediaMetadataRetriever.OPTION_CLOSEST, 480, 480)
        } else {
            retriever.getFrameAtTime(frame.sourceTimeUs, MediaMetadataRetriever.OPTION_CLOSEST)
        } ?: return null
        return try {
            val angle = Math.toRadians(frame.transform.rotationDegrees.toDouble())
            val cosine = kotlin.math.cos(angle).toFloat()
            val sine = kotlin.math.sin(angle).toFloat()
            FloatArray(SAMPLE_WIDTH * SAMPLE_HEIGHT) { index ->
                val outputX = (index % SAMPLE_WIDTH).toFloat() / SAMPLE_WIDTH
                val outputY = (index / SAMPLE_WIDTH).toFloat() / SAMPLE_HEIGHT
                // GL positive Y moves the image upward in the bitmap's top-down coordinates.
                val dx = outputX - .5f - frame.transform.translateX
                val dy = outputY - .5f + frame.transform.translateY
                val sourceX = framing.sourceX((dx * cosine - dy * sine / outputAspect) / frame.transform.scale + .5f)
                val sourceY = framing.sourceY((dx * outputAspect * sine + dy * cosine) / frame.transform.scale + .5f)
                if (sourceX !in 0f..1f || sourceY !in 0f..1f) 0f else {
                    val colour = bitmap.getPixel(
                        (sourceX * bitmap.width).toInt().coerceIn(0, bitmap.width - 1),
                        (sourceY * bitmap.height).toInt().coerceIn(0, bitmap.height - 1))
                    (Color.red(colour) * .299f + Color.green(colour) * .587f + Color.blue(colour) * .114f) / 255f
                }
            }
        } finally { bitmap.recycle() }
    }

    private fun exactSourceMask(
        segmenter: Segmenter,
        retriever: MediaMetadataRetriever?,
        sourceTimeUs: Long,
        bitmapWidth: Int,
        bitmapHeight: Int,
        target: FrameAttachments.Plane
    ): FrameAttachments.Plane? {
        if (retriever == null) return null
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(
                sourceTimeUs,
                MediaMetadataRetriever.OPTION_CLOSEST,
                bitmapWidth,
                bitmapHeight
            )
        } else {
            retriever.getFrameAtTime(sourceTimeUs, MediaMetadataRetriever.OPTION_CLOSEST)?.let { original ->
                Bitmap.createScaledBitmap(original, bitmapWidth, bitmapHeight, true).also { scaled ->
                    if (scaled !== original) original.recycle()
                }
            }
        } ?: return null
        return try {
            val mask = Tasks.await(
                segmenter.process(InputImage.fromBitmap(bitmap, 0)),
                MASK_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )
            val floats = mask.buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
            val values = FloatArray(floats.remaining()).also(floats::get)
            val compact = SemanticMaskMetrics.downsample(
                values,
                mask.width,
                mask.height,
                target.width,
                target.height
            )
            target.copy(values = compact, confidence = 1f)
        } finally {
            bitmap.recycle()
        }
    }

    private fun faceBitmap(image: Image): Bitmap {
        val crop = image.cropRect
        val scale = FACE_MAX_SIDE.toFloat() / maxOf(crop.width(), crop.height())
        val width = (crop.width() * scale).roundToInt().coerceAtLeast(64)
        val height = (crop.height() * scale).roundToInt().coerceAtLeast(64)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val pixels = IntArray(width * height)
        repeat(height) { targetY ->
            val sourceY = crop.top + targetY * crop.height() / height
            repeat(width) { targetX ->
                val sourceX = crop.left + targetX * crop.width() / width
                val y = readPlane(yPlane, sourceX, sourceY).toFloat()
                val u = readPlane(uPlane, sourceX / 2, sourceY / 2).toFloat() - 128f
                val v = readPlane(vPlane, sourceX / 2, sourceY / 2).toFloat() - 128f
                val luma = 1.164f * (y - 16f)
                val red = (luma + 1.596f * v).roundToInt().coerceIn(0, 255)
                val green = (luma - .392f * u - .813f * v).roundToInt().coerceIn(0, 255)
                val blue = (luma + 2.017f * u).roundToInt().coerceIn(0, 255)
                pixels[targetY * width + targetX] = Color.rgb(red, green, blue)
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun readPlane(plane: Image.Plane, x: Int, y: Int): Int =
        unsigned(plane.buffer, y * plane.rowStride + x * plane.pixelStride)

    private fun readChroma(plane: Image.Plane, x: Int, y: Int): Float =
        unsigned(plane.buffer, y / 2 * plane.rowStride + x / 2 * plane.pixelStride) / 255f

    private fun unsigned(buffer: ByteBuffer, index: Int): Int = buffer.get(index).toInt() and 0xff

    private fun limitedRangeLuma(value: Int): Float = ((value - 16f) / 219f).coerceIn(0f, 1f)

    private fun frameDifference(previous: Features, current: Features): Float =
        previous.pixels.indices.sumOf { index ->
            abs(previous.pixels[index] - current.pixels[index]).toDouble()
        }.toFloat().div(previous.pixels.size).times(2.2f).coerceIn(0f, 1f)

    internal fun subjectStageBackgroundLuma(luma: FloatArray, subjectMask: FloatArray): Float {
        require(luma.isNotEmpty() && luma.size == subjectMask.size)
        var sum = 0f
        var count = 0
        luma.indices.forEach { index ->
            if (subjectMask[index] <= SUBJECT_STAGE_BACKGROUND_MASK_MAX) {
                sum += luma[index]
                count++
            }
        }
        // A matte that claims almost the complete frame cannot prove foreground isolation.
        if (count < luma.size * SUBJECT_STAGE_MINIMUM_BACKGROUND_RATIO) return 1f
        return (sum / count).coerceIn(0f, 1f)
    }

    internal fun clippingArtifact(clippedRatio: Float, intentionalDarkFrame: Boolean): Float =
        if (intentionalDarkFrame) 0f else ((clippedRatio - .82f) / .18f)
            .coerceIn(0f, 1f) * .35f

    internal fun isIntentionalFlash(layer: LayerCompositorModel.Sample?): Boolean =
        layer?.let { it.kind == MontageGraph.OverlayKind.FLASH && it.opacity > .5f } == true

    internal fun isIntentionalFlash(
        graph: MontageGraph,
        timeUs: Long,
        renderFps: Int,
        layer: LayerCompositorModel.Sample?
    ): Boolean {
        require(timeUs >= 0L && renderFps > 0)
        if (isIntentionalFlash(layer)) return true
        if (graph.manualOverrides != null) return false
        if (!HeartbeatMontageProfile.appliesTo(graph)) return false
        val frameDurationUs = (1_000_000L + renderFps - 1L) / renderFps
        return HeartbeatMontageProfile.pulses.any { pulse ->
            pulse.kind == HeartbeatMontageProfile.PulseKind.WHITE &&
                timeUs >= pulse.startUs && timeUs < pulse.endUs + frameDurationUs
        }
    }

    internal fun isIntentionalForegroundDarkStage(
        transition: MontageGraph.Transition,
        progress: Float?
    ): Boolean = transition == MontageGraph.Transition.FOREGROUND_REENTRY &&
        progress?.let { it in 0f..FOREGROUND_DARK_STAGE_END } == true

    internal fun samplingTargets(graph: MontageGraph, intervalUs: Long,
        renderFps: Int = graph.editableTiming?.fps ?: HighQualityFramePlan.DEFAULT_FPS,
        plan: HighQualityFramePlan.Plan? = null): List<Long> {
        require(intervalUs > 0L)
        if (graph.manualOverrides != null || graph.editableTiming != null) {
            return ManualQaSampling.targets(graph, plan ?: HighQualityFramePlan.build(graph, renderFps), intervalUs)
        }
        val durationUs = graph.outputDurationMs * 1_000L
        val uniform = generateSequence(0L) { previous ->
            (previous + intervalUs).takeIf { it <= durationUs }
        }
        val accents = if (ReferenceMontageProfile.appliesTo(graph)) {
            ReferenceMontageProfile.ACCENT_BEATS_US
        } else {
            emptyList()
        }
        val effectCheckpoints = if (ReferenceMontageProfile.appliesTo(graph)) {
            graph.overlays.asSequence().filter {
                it.overlayKind == MontageGraph.OverlayKind.DOUBLE_EXPOSURE ||
                    it.overlayKind == MontageGraph.OverlayKind.MIRROR_SLICE
            }.flatMap { overlay ->
                sequenceOf(
                    overlay.startMs * 1_000L,
                    (overlay.startMs + overlay.endMs) * 500L
                )
            } + graph.effectGraph.nodes.asSequence()
                .filter { it.kind == GpuEffectGraph.Kind.GLITCH }
                .flatMap { node -> sequenceOf(node.startUs, (node.startUs + node.endUs) / 2L) }
        } else {
            emptySequence()
        }
        val heartbeatCheckpoints = if (graph.metadata.generator.startsWith(HeartbeatMontageProfile.ID)) {
            HeartbeatMontageProfile.pulses.asSequence().flatMap { pulse ->
                sequenceOf((pulse.startUs - 16_667L).coerceAtLeast(0), pulse.startUs, pulse.endUs)
            } + graph.effectGraph.nodes.asSequence().flatMap { node ->
                sequenceOf(node.startUs, (node.startUs + node.endUs) / 2L, node.endUs)
            } + HeartbeatPulseAudit.tailSamplingTargetsUs().asSequence()
        } else emptySequence()
        val fearCheckpoints = if (FearStrobeProfile.appliesTo(graph)) {
            (plan?.takeIf { it.fps == FearStrobeProfile.REFERENCE_FPS }
                ?: HighQualityFramePlan.build(graph, FearStrobeProfile.REFERENCE_FPS)).frames.asSequence()
                .map { it.outputTimeUs } + FearStrobeProfile.pulses.asSequence().flatMap { pulse ->
                sequenceOf(pulse.startUs, (pulse.endUs - 1L).coerceAtLeast(pulse.startUs))
            } + FearStrobeProfile.scenes.asSequence().map { it.startUs }
        } else emptySequence()
        return (uniform + accents.asSequence() + effectCheckpoints + heartbeatCheckpoints + fearCheckpoints)
            .filter { it <= durationUs }
            .distinct()
            .sorted()
            .toList()
    }

    internal fun transitionAt(frame: HighQualityFramePlan.Frame?): MontageGraph.Transition =
        if (frame != null && TransitionTimeline.blendFor(frame) != null) frame.transitionIn
        else MontageGraph.Transition.HARD_CUT

    private const val FACE_EXPECTATION_TOLERANCE_US = 400_000L
    private const val FACE_TIMEOUT_MS = 900L
    private const val MASK_TIMEOUT_MS = 1_200L
    // Evaluate both this source-analysis scale and the legacy 320 px scale. ML Kit detects small
    // static faces better at 480, while motion-softened selfie faces are more stable at 320.
    private const val FACE_MAX_SIDE = 480
    private const val LEGACY_FACE_QA_SIDE = 320
    private const val FOREGROUND_COMPLETE_START = .72f
    private const val FOREGROUND_BACKGROUND_START = .80f
    private const val FOREGROUND_DARK_STAGE_END = .88f
    private val FACE_TRANSITION_EXCLUSIONS = setOf(
        MontageGraph.Transition.WHIP,
        MontageGraph.Transition.OCCLUSION,
        MontageGraph.Transition.FOREGROUND_REENTRY,
        MontageGraph.Transition.BLACKOUT
    )
    private const val MINIMUM_MASK_CONFIDENCE = .80f
    private const val SUBJECT_STAGE_QA_OPACITY = .80f
    private const val SUBJECT_STAGE_BACKGROUND_MASK_MAX = .15f
    private const val SUBJECT_STAGE_MINIMUM_BACKGROUND_RATIO = .05f
}
