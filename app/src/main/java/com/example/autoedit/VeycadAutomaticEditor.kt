package com.veycad.app

import android.content.Context
import java.io.File

/**
 * End-to-end automatic edit contract used by product UI and debug regression alike.
 * Decoded QA scores every encoded candidate. When none passes the quality gate, the
 * best available MP4 is returned with passedQualityGate=false for an explicit warning.
 */
internal object VeycadAutomaticEditor {
    enum class Stage { ANALYSING_VIDEO, DIRECTING, RENDERING, VERIFYING, COMPLETE }

    data class Progress(
        val stage: Stage,
        val completedCandidates: Int = 0,
        val totalCandidates: Int = 3
    )

    data class Request(
        val context: Context,
        val sources: MediaSourceSet,
        val musicFile: File,
        val outputFile: File,
        val style: EventMatchingDirector.Style? = null,
        val recipe: MontageStyleCatalog.Recipe = MontageStyleCatalog.Recipe.SIGMA,
        val heartbeatEchoOffsetMs: Long = -67L,
        val width: Int = 720,
        val height: Int = 1_280,
        val bitrate: Int = 5_000_000,
        val capabilities: RenderPassPlanner.DeviceCapabilities =
            RenderPassPlanner.DeviceCapabilities.conservative(),
        val onProgress: (Progress) -> Unit = {},
        val checkCancelled: () -> Unit = {},
        val jobLease: File? = null
    ) {
        val sourceFile: File get() = sources.items.first().file
        val secondarySourceFile: File? get() = sources.items.getOrNull(1)?.file

        /** Legacy callers are worker-thread callers; inspection uses real metadata and cancellation. */
        constructor(
            context: Context,
            sourceFile: File,
            secondarySourceFile: File? = null,
            musicFile: File,
            outputFile: File,
            style: EventMatchingDirector.Style? = null,
            recipe: MontageStyleCatalog.Recipe = MontageStyleCatalog.Recipe.SIGMA,
            heartbeatEchoOffsetMs: Long = -67L,
            width: Int = 720,
            height: Int = 1_280,
            bitrate: Int = 5_000_000,
            capabilities: RenderPassPlanner.DeviceCapabilities = RenderPassPlanner.DeviceCapabilities.conservative(),
            onProgress: (Progress) -> Unit = {},
            checkCancelled: () -> Unit = {},
            jobLease: File? = null
        ) : this(context, inspectLegacySources(sourceFile, secondarySourceFile, checkCancelled),
            musicFile, outputFile, style, recipe, heartbeatEchoOffsetMs, width, height, bitrate,
            capabilities, onProgress, checkCancelled, jobLease)

        companion object {
            internal fun validateRecipeSources(sources: MediaSourceSet, recipe: MontageStyleCatalog.Recipe) {
                val expected = if (recipe == MontageStyleCatalog.Recipe.DUALITY_LOOP) 2 else 1
                require(sources.items.size == expected) {
                    "DUALITY requires exactly two video sources and other recipes require one"
                }
            }

            internal fun inspectLegacySources(source: File, secondary: File?, checkCancelled: () -> Unit): MediaSourceSet {
                require(secondary == null || secondary.canonicalPath != source.canonicalPath)
                val inspector = MediaInputInspector(checkCancelled)
                return MediaSourceSet(listOfNotNull(source, secondary).mapIndexed { index, file ->
                    inspector.inspect("legacy-$index", file, file.name)
                })
            }
        }

        init {
            validateRecipeSources(sources, recipe)
            require(sourceFile.isFile && musicFile.isFile)
            require(sourceFile.canonicalPath != musicFile.canonicalPath)
            secondarySourceFile?.let { secondary ->
                require(secondary.isFile)
                require(secondary.canonicalPath != sourceFile.canonicalPath)
                require(secondary.canonicalPath != musicFile.canonicalPath)
            }
            require(recipe != MontageStyleCatalog.Recipe.HEARTBEAT ||
                HeartbeatMontageProfile.matchesAudio(musicFile)) {
                "Heartbeat requires its authored score; refusing a mixed-style render"
            }
            require(fearAudioMatchesRecipe(recipe, musicFile)) {
                "Fear recipe and authored score must be selected together; refusing a mixed-style render"
            }
            require(dualityAudioMatchesRecipe(recipe, musicFile)) {
                "DUALITY recipe and authored score must be selected together; refusing a mixed-style render"
            }
            require(width > 0 && height > 0 && bitrate > 0)
        }
    }

    internal fun fearAudioMatchesRecipe(
        recipe: MontageStyleCatalog.Recipe,
        musicFile: File
    ): Boolean = FearStrobeProfile.matchesAudio(musicFile) ==
        (recipe == MontageStyleCatalog.Recipe.FEAR_STROBE)

    internal fun dualityAudioMatchesRecipe(
        recipe: MontageStyleCatalog.Recipe,
        musicFile: File
    ): Boolean = DualityLoopProfile.matchesAudio(musicFile) ==
        (recipe == MontageStyleCatalog.Recipe.DUALITY_LOOP)

    data class Candidate(
        val alternative: EventMatchingDirector.Alternative,
        val file: File,
        val frames: Int,
        val passPlan: RenderPassPlanner.Plan,
        val executedPasses: Map<RenderPassPlanner.PassKind, Int>,
        val samples: List<RenderedMp4Acceptance.VisualSample>,
        val acceptance: RenderedMp4Acceptance.Report,
        val artifacts: VeykadRenderInspector.Artifacts? = null
    )

    data class Result(
        val analysis: MediaFrameVisualAnalyzer.Result,
        val pipeline: VeycadEventPipeline.Result,
        val candidates: List<Candidate>,
        val winner: Candidate,
        val passedQualityGate: Boolean,
        /** Product-owned source selection, retained for diagnostics and A/B controls. */
        val productSourcePool: MontageGraph? = null,
        val secondaryAnalysis: MediaFrameVisualAnalyzer.Result? = null,
        val heartbeatSelectionTrace: HeartbeatSourcePool.SelectionTrace? = null
    )

    /** Synchronous; product callers must invoke this from their owned background executor. */
    fun render(request: Request): Result {
        MontageStyleCatalog.requireRecipeAvailable(request.recipe)
        request.checkCancelled()
        request.onProgress(Progress(Stage.ANALYSING_VIDEO, 0, candidateCount(request)))
        val requiredAnalysis = SourceAnalysisProfile.requiredFor(request.recipe)
        val analysis = MediaFrameVisualAnalyzer.analyze(request.context, request.sourceFile,
            profile = requiredAnalysis,
            checkCancelled = request.checkCancelled, jobLease = request.jobLease)
        val secondaryAnalysis = request.secondarySourceFile?.let { secondary ->
            MediaFrameVisualAnalyzer.analyze(request.context, secondary,
                profile = requiredAnalysis,
                checkCancelled = request.checkCancelled, jobLease = request.jobLease)
        }
        analysis.requireCapabilities(requiredAnalysis)
        secondaryAnalysis?.requireCapabilities(requiredAnalysis)
        val secondaryVisualMap = secondaryAnalysis?.let {
            VisualEventMapAnalyzer.analyze(it.durationUs, it.observations)
        }
        MaterialSuitability.checkDuration(request.recipe,
            listOfNotNull(analysis, secondaryAnalysis).map { it.durationUs })
        val primaryVisualMap = VisualEventMapAnalyzer.analyze(analysis.durationUs, analysis.observations)
        MaterialSuitability.checkHumanEvidence(request.recipe,
            listOfNotNull(primaryVisualMap, secondaryVisualMap))
        request.onProgress(Progress(Stage.DIRECTING, 0, candidateCount(request)))
        // Only Sigma enters the generic event-matching director. The other three products
        // select their own source windows from neutral evidence before authoring their graph.
        val sigmaPipeline = if (request.recipe == MontageStyleCatalog.Recipe.SIGMA)
            VeycadEventPipeline.fromAudioFile(
                sourceId = request.sourceFile.name,
                audioFile = request.musicFile,
                sourceDurationUs = analysis.durationUs,
                observations = analysis.observations,
                frameAttachments = analysis.attachments,
                requestedStyle = request.style
            ) else null
        var productSourcePool: MontageGraph? = null
        var heartbeatSelectionTrace: HeartbeatSourcePool.SelectionTrace? = null
        val pipeline = if (request.recipe == MontageStyleCatalog.Recipe.DUALITY_LOOP) {
            val evidence = VeycadEventPipeline.evidenceFromAudioFile(
                request.musicFile, analysis.durationUs, DualityLoopProfile.OUTPUT_DURATION_US,
                analysis.observations
            )
            val graph = DualityLoopDirector.build(
                evidence.visualMap, requireNotNull(secondaryVisualMap),
                request.musicFile.absolutePath, evidence.audioMap
            )
            VeycadEventPipeline.Result(evidence.audioMap, evidence.visualMap,
                listOf(EventMatchingDirector.Alternative(
                    EventMatchingDirector.Style.DYNAMIC, graph, 0, 0
                )))
        } else if (request.recipe == MontageStyleCatalog.Recipe.HEARTBEAT) {
            val evidence = VeycadEventPipeline.evidenceFromAudioFile(
                request.musicFile, analysis.durationUs, HeartbeatMontageProfile.OUTPUT_DURATION_US,
                analysis.observations
            )
            val selection = HeartbeatSourcePool.direct(evidence.visualMap, analysis.attachments,
                request.musicFile.absolutePath, request.heartbeatEchoOffsetMs,
                checkCancelled = request.checkCancelled)
            productSourcePool = selection.pool
            heartbeatSelectionTrace = selection.trace
            val graph = selection.graph
            VeycadEventPipeline.Result(evidence.audioMap, evidence.visualMap,
                listOf(EventMatchingDirector.Alternative(
                    EventMatchingDirector.Style.DYNAMIC, graph, 0, 0
                )))
        } else if (request.recipe == MontageStyleCatalog.Recipe.FEAR_STROBE) {
            val evidence = VeycadEventPipeline.evidenceFromAudioFile(
                request.musicFile, analysis.durationUs, FearStrobeProfile.OUTPUT_DURATION_US,
                analysis.observations
            )
            val pool = FearSourcePool.select(evidence.visualMap, analysis.attachments)
            productSourcePool = pool
            val graph = FearDirector.build(pool, request.musicFile.absolutePath, evidence.visualMap)
            FearCascadeEvidence.requireSupported(graph, evidence.visualMap)
            VeycadEventPipeline.Result(evidence.audioMap, evidence.visualMap,
                listOf(EventMatchingDirector.Alternative(
                    EventMatchingDirector.Style.DYNAMIC, graph, 0, 0
                )))
        } else requireNotNull(sigmaPipeline)
        val alternatives = request.style?.let { requested ->
            listOf(pipeline.alternatives.first { it.style == requested })
        } ?: pipeline.alternatives
        // Exact mattes belong to graphs that actually request a foreground transition, not to a
        // product style name. A single known recipe can therefore resolve masks before its only
        // encode; automatic multi-candidate selection still refines only the eventual winner.
        val renderAlternatives = if (alternatives.size == 1) alternatives.map { alternative ->
            prepareExactMattes(request, alternative)
        } else alternatives
        val candidates = renderAlternatives.mapIndexed { index, alternative ->
            request.onProgress(Progress(Stage.RENDERING, index, alternatives.size))
            val file = if (alternatives.size == 1) request.outputFile else File(
                request.outputFile.parentFile,
                "${request.outputFile.nameWithoutExtension}-${alternative.style.name.lowercase()}.mp4"
            )
            val candidate = renderCandidate(request, pipeline, alternative, file, secondaryVisualMap)
            request.onProgress(Progress(Stage.VERIFYING, index + 1, alternatives.size))
            candidate
        }.toMutableList()
        val selection = RenderedCandidateSelector.chooseBestAvailable(candidates.map { candidate ->
            RenderedCandidateSelector.Candidate(candidate, candidate.alternative.graph, candidate.acceptance)
        })
        val selectedIndex = candidates.indexOf(selection.candidate.value)
        request.onProgress(Progress(Stage.VERIFYING, alternatives.size + 1, alternatives.size))
        var winner: Candidate = selection.candidate.value
        if (selectedIndex in candidates.indices &&
            candidates[selectedIndex].alternative.graph.frameAttachments.maskRefinements.isEmpty()) {
            val candidate = candidates[selectedIndex]
            val targets = MulticlassMatteClient.decodedTargets(request.sourceFile,
                OpeningMatteRefinement.targets(candidate.alternative.graph), request.checkCancelled)
            if (targets.isNotEmpty()) {
                val refinedGraph = OpeningMatteRefinement.attach(
                    candidate.alternative.graph,
                    targets,
                    MulticlassMatteClient.analyzeTargets(
                        request.context,
                        request.sourceFile,
                        targets, request.jobLease
                    )
                )
                val refinedCandidate = renderCandidate(
                    request,
                    pipeline,
                    candidate.alternative.copy(graph = refinedGraph),
                    refinedOutputFile(request.outputFile, candidate.file, "refined"),
                    secondaryVisualMap
                )
                val winnerByScore = RenderedCandidateSelector.chooseBestAvailable(
                    listOf(
                        RenderedCandidateSelector.Candidate(candidate, candidate.alternative.graph, candidate.acceptance),
                        RenderedCandidateSelector.Candidate(refinedCandidate, refinedCandidate.alternative.graph, refinedCandidate.acceptance)
                    )
                ).candidate.value
                for (evaluated in listOf(candidate, refinedCandidate)) {
                    val metrics = evaluated.acceptance.metrics
                    LocalDiagnostics.record(request.context, "opening_matte_candidate", mapOf(
                        "file" to evaluated.file.name,
                        "selected" to (evaluated === winnerByScore).toString(),
                        "refinement_frames" to evaluated.alternative.graph.frameAttachments.maskRefinements.size.toString(),
                        "score" to RenderedCandidateSelector.score(RenderedCandidateSelector.Candidate(
                            evaluated, evaluated.alternative.graph, evaluated.acceptance)).toString(),
                        "edge_leak" to metrics.maximumEdgeLeakRatio.toString(),
                        "temporal_iou" to metrics.minimumMaskTemporalIou.toString(),
                        "face_loss" to metrics.faceLossRate.toString(),
                        "issues" to evaluated.acceptance.issues.joinToString(",")
                    ))
                }
                candidates[selectedIndex] = winnerByScore
                winner = winnerByScore
            }
        }
        request.outputFile.parentFile?.mkdirs()
        val selectedIndexValid = selectedIndex in candidates.indices
        if (!winner.file.isFile) {
            val rerenderedToRequestOutput = renderCandidate(
                request,
                pipeline,
                winner.alternative,
                request.outputFile,
                secondaryVisualMap
            )
            winner = rerenderedToRequestOutput
            if (selectedIndexValid) candidates[selectedIndex] = winner
        } else if (winner.file.canonicalPath != request.outputFile.canonicalPath) {
            winner.file.copyTo(request.outputFile, overwrite = true)
        }
        request.onProgress(Progress(Stage.COMPLETE, alternatives.size, alternatives.size))
        val updatedSelection = RenderedCandidateSelector.chooseBestAvailable(
            candidates.map {
                RenderedCandidateSelector.Candidate(it, it.alternative.graph, it.acceptance)
            }
        )
        return Result(analysis, pipeline, candidates, winner, updatedSelection.passedQualityGate,
            productSourcePool, secondaryAnalysis, heartbeatSelectionTrace)
    }

    private fun renderCandidate(
        request: Request,
        pipeline: VeycadEventPipeline.Result,
        alternative: EventMatchingDirector.Alternative,
        file: File,
        secondaryVisualMap: VisualEventMap? = null
    ): Candidate {
        val passPlan = RenderPassPlanner.plan(alternative.graph, request.capabilities)
        val previousInspectorReport = VeykadRenderInspector.latest(request.context)?.report
        var executedPasses: Map<RenderPassPlanner.PassKind, Int> = emptyMap()
        val frames = MediaCodecSpeedRampRenderer.render(MediaCodecSpeedRampRenderer.Request(
            sourceFiles = request.sources.items.map { it.file },
            graph = alternative.graph,
            outputFile = file,
            width = request.width,
            height = if (request.recipe == MontageStyleCatalog.Recipe.FEAR_STROBE) request.width else request.height,
            bitrate = request.bitrate,
            fps = if (request.recipe == MontageStyleCatalog.Recipe.HEARTBEAT) 60
                else HighQualityFramePlan.DEFAULT_FPS,
            audioFile = request.musicFile,
            context = request.context,
            renderPlan = passPlan,
            onPassesExecuted = { executedPasses = it },
            checkCancelled = request.checkCancelled
        ))
        val artifacts = VeykadRenderInspector.latest(request.context, file)?.takeIf {
            hasFreshExecutionReport(previousInspectorReport, it.report)
        }
        val container = VeykadRenderInspector.inspectContainer(file, request.width,
            if (request.recipe == MontageStyleCatalog.Recipe.FEAR_STROBE) request.width else request.height)
        val decodedAudio = runCatching {
            val pcm = MediaCodecAudioDecoder.decode(file,
                maxDecodeUs = alternative.graph.outputDurationMs * 1_000L + 1_000_000L,
                preserveFloatHeadroom = true, checkCancelled = request.checkCancelled)
            val frameUs = if (HeartbeatMontageProfile.appliesTo(alternative.graph)) 16_667L else 33_334L
            DecodedAudioQuality.evaluate(pcm.interleavedSamples, pcm.sampleRate, pcm.channelCount,
                alternative.graph.outputDurationMs * 1_000L,
                durationToleranceUs = frameUs + 1_024_000_000L / pcm.sampleRate,
                unclampedFloatEvidence = pcm.pcmEncoding == android.media.AudioFormat.ENCODING_PCM_FLOAT)
        }.getOrElse {
            request.checkCancelled()
            null // Unknown decoding/format evidence is a failed quality gate, not silent success.
        }
        val samples = RenderedVisualSampler.sample(
            file,
            alternative.graph,
            pipeline.visualMap,
            request.sourceFile.takeUnless { request.recipe == MontageStyleCatalog.Recipe.DUALITY_LOOP },
            decodedSourceTimes = VeykadRenderInspector.decodedSourceClock(artifacts),
            renderFps = if (request.recipe == MontageStyleCatalog.Recipe.HEARTBEAT) 60
                else HighQualityFramePlan.DEFAULT_FPS,
            secondarySourceVisualMap = secondaryVisualMap,
            checkCancelled = request.checkCancelled,
            // DUALITY's authored grade historically excludes source-luma comparisons.
            sourceFiles = if (request.recipe == MontageStyleCatalog.Recipe.DUALITY_LOOP) emptyList()
                else request.sources.items.map { it.file },
            sourceVisualMaps = listOf(pipeline.visualMap, secondaryVisualMap),
            decodedFrameProvenance = VeykadRenderInspector.decodedFrameProvenance(artifacts)
        )
        val decodedAcceptance = RenderedMp4Acceptance.evaluate(
            alternative.graph,
            pipeline.audioMap,
            RenderedMp4Acceptance.ContainerSample(
                container.videoLastPtsUs,
                container.audioLastPtsUs,
                container.videoMime,
                container.audioMime,
                container.videoFirstPtsUs,
                container.audioFirstPtsUs,
                container.issues
            ),
            samples,
            reference = when {
                ReferenceMontageProfile.appliesTo(alternative.graph) ->
                    RenderedMp4Acceptance.ReferenceMontageCard(
                        minimumBeatHitRate = .95f,
                        maximumAvDriftUs = 20_000L,
                        maximumFaceLossRate = .05f
                    )
                HeartbeatMontageProfile.appliesTo(alternative.graph) ->
                    RenderedMp4Acceptance.ReferenceMontageCard(
                        minimumBeatHitRate = .90f,
                        maximumAvDriftUs = 20_000L,
                        maximumFaceLossRate = .05f
                    )
                FearStrobeProfile.appliesTo(alternative.graph) ->
                    RenderedMp4Acceptance.ReferenceMontageCard(
                        minimumBeatHitRate = .85f,
                        maximumAvDriftUs = 33_334L,
                        maximumFaceLossRate = .18f
                    )
                DualityLoopProfile.appliesTo(alternative.graph) ->
                    RenderedMp4Acceptance.ReferenceMontageCard(
                        // The measured author cuts include syncopated picture accents between
                        // detected beat peaks; the fixed boundary list, not beat recall alone,
                        // owns chronology for this profile.
                        minimumBeatHitRate = .60f,
                        maximumRepeatedSourceRatio = .20f,
                        maximumAvDriftUs = 33_334L,
                        maximumArtifactScore = .45f,
                        // DUALITY deliberately admits low-key plates with large deep-black areas.
                        maximumBlackBlockScore = .45f,
                        maximumFaceLossRate = .18f,
                        minimumReferenceGrammarFit = 0f
                    )
                else -> RenderedMp4Acceptance.ReferenceMontageCard()
            },
            requireFaceEvidence = if (request.recipe == MontageStyleCatalog.Recipe.DUALITY_LOOP) {
                listOfNotNull(pipeline.visualMap, secondaryVisualMap).any { source ->
                    source.observations.any { (it.face?.confidence ?: 0f) >= .65f }
                }
            } else pipeline.visualMap.observations.any { it.face != null },
            decodedAudio = decodedAudio,
            requireDecodedAudioEvidence = true
        )
        val acceptance = withExecutionEvidence(decodedAcceptance, artifacts?.summary)
        return Candidate(alternative, file, frames, passPlan, executedPasses, samples, acceptance, artifacts)
    }

    /** A failed artifact write must not reuse a prior same-name, same-length MP4 report. */
    internal fun hasFreshExecutionReport(previousReport: File?, currentReport: File?): Boolean =
        currentReport != null && currentReport.canonicalPath != previousReport?.canonicalPath

    /** Production adds actual shader execution proof; the legacy pure decoded gate is unchanged. */
    internal fun withExecutionEvidence(
        decoded: RenderedMp4Acceptance.Report,
        execution: VeykadRenderInspector.Summary?
    ): RenderedMp4Acceptance.Report {
        val executionIssues = execution?.issues?.map { "render-execution:$it" }
            ?: listOf("render-execution-evidence-missing")
        val issues = (decoded.issues + executionIssues).distinct()
        return decoded.copy(
            accepted = decoded.accepted && execution?.accepted == true && issues.isEmpty(),
            issues = issues
        )
    }

    /** The raw contract uses the selected candidate's captured proof, never global latest state. */
    internal fun executionArtifactFields(execution: VeykadRenderInspector.Summary?): Map<String, String> =
        linkedMapOf(
            "render_execution_method" to "shader-frame-inspector-v1",
            "render_execution_evidence" to (execution != null).toString(),
            "render_execution_accepted" to (execution?.accepted == true).toString(),
            "render_execution_issues" to (execution?.issues?.joinToString(",")
                ?: "execution-evidence-missing")
        )

    private fun candidateCount(request: Request): Int = if (request.style == null) 3 else 1

    private fun prepareExactMattes(
        request: Request,
        alternative: EventMatchingDirector.Alternative
    ): EventMatchingDirector.Alternative {
        if (request.recipe == MontageStyleCatalog.Recipe.DUALITY_LOOP) return alternative
        val plannedTargets = OpeningMatteRefinement.targets(alternative.graph)
        if (plannedTargets.isEmpty()) return alternative
        val startedNs = System.nanoTime()
        val targets = MulticlassMatteClient.decodedTargets(request.sourceFile, plannedTargets, request.checkCancelled)
        val prepared = alternative.copy(graph = OpeningMatteRefinement.attach(
            alternative.graph,
            targets,
            MulticlassMatteClient.analyzeTargets(request.context, request.sourceFile, targets, request.jobLease)
        ))
        LocalDiagnostics.record(request.context, "opening_matte_prepared", mapOf(
            "target_frames" to targets.size.toString(),
            "elapsed_ms" to ((System.nanoTime() - startedNs) / 1_000_000L).toString(),
            "encode_passes" to "1"
        ))
        return prepared
    }

    private fun refinedOutputFile(baseOutput: File, fallbackOutput: File, suffix: String): File =
        if (fallbackOutput == baseOutput) File(
            baseOutput.parentFile,
            "${baseOutput.nameWithoutExtension}-$suffix.mp4"
        ) else File(
            fallbackOutput.parentFile,
            "${fallbackOutput.nameWithoutExtension}-$suffix.mp4"
        )
}
