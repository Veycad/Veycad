package com.veycad.app

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * Explicit, opt-in planning diagnostic for registered frozen owner-supplied caches. This is not a
 * JUnit test, a source re-analysis, a decoded render inspection, or human/audio acceptance.
 * The production cache reader, event analyzer, selector and full director remain authoritative.
 */
internal object HeartbeatCachedPoolProbe {
    private data class RegisteredCache(val sourceSha: String, val observations: Int, val preferredOutcome: String)
    private val registeredCaches = mapOf(
        "21a2e6d655d46f9588aeff64074cca98bff547cb8a4324cc557c6b86880aa70f" to RegisteredCache(
            "0e2d39036a6eeb520196905a04d6d4fa1385a09e80124d98f3c4a3faf48e9c23", 119, "refusal"),
        "31abb3fcd6f3cf067fd87bee76d0b9449cf83d5c62524219551cf7c4da6f870a" to RegisteredCache(
            "8e65b237e48e8fd4b3d50079c85c64034fb592f5881a389deaa9485dc43aafca", 112, "preferred"),
        // Exact snapshot of the A editorial analysis regenerated for DUALITY on Sep28,
        // then actually reused by native Heartbeat v30. Not a new source or holdout.
        "cc3b9e3d2fa225c06207810b24dec88ec9aa0d0ad9e0cb4d4a2a88d88ea5f19d" to RegisteredCache(
            "8e65b237e48e8fd4b3d50079c85c64034fb592f5881a389deaa9485dc43aafca", 112, "preferred")
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 4..5) {
            "Usage: HeartbeatCachedPoolProbe <frozen-cache> <new-output-json> <expected-cache-sha> <preferred|refusal> [echo-offset-ms]"
        }
        val cacheFile = File(args[0]).canonicalFile
        val output = File(args[1]).canonicalFile
        val expectedSha = args[2].lowercase()
        val registered = requireNotNull(registeredCaches[expectedSha]) { "Cache is not registered for this explicit diagnostic" }
        val preferredOutcome = args[3]
        val echoOffsetMs = args.getOrNull(4)?.toLong() ?: -67L
        require(preferredOutcome == registered.preferredOutcome)
        require(cacheFile.isFile)
        require(!output.exists()) { "Diagnostic output already exists; evidence must not be overwritten" }
        require(output.parentFile?.isDirectory == true) { "Create the intended output directory explicitly first" }
        val digest = MessageDigest.getInstance("SHA-256")
        cacheFile.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
        require(actualSha == expectedSha) { "Frozen cache SHA-256 does not match the registered evidence" }
        val evidence = MediaFrameAnalysisCache.read(cacheFile, SourceAnalysisProfile.EDITORIAL_SEMANTICS)
        evidence.profile.validate(evidence.observations, evidence.correspondenceAssessmentsCompleted)
        require(evidence.observations.size == registered.observations &&
            evidence.attachments.frames.size == registered.observations) { "Unexpected frozen cache population" }
        if (registered.observations == 119) require(evidence.durationUs == 29_700_000L)
        val visual = VisualEventMapAnalyzer.analyze(evidence.durationUs, evidence.observations)
        val audioSourceId = "cache-planning-diagnostic-only-no-audio-render"
        val preferredPool = HeartbeatSourcePool.select(visual, evidence.attachments)
        val startedNs = System.nanoTime()
        var preferredCode: String? = null
        var preferredGraph: MontageGraph? = null
        val preferred = try {
            val graph = HeartbeatDirector.build(preferredPool, audioSourceId, echoOffsetMs,
                visualMap = visual)
            preferredGraph = graph
            mapOf("status" to "accepted_planning", "graph" to graphRecord(graph, echoOffsetMs))
        } catch (failure: MaterialRejectedException) {
            preferredCode = failure.code
            mapOf("status" to "material_rejected", "code" to failure.code,
                "detail" to failure.message)
        }
        var planningPass = false
        val selection = try {
            val selected = HeartbeatSourcePool.direct(visual, evidence.attachments, audioSourceId,
                echoOffsetMs = echoOffsetMs)
            val graph = graphRecord(selected.graph, echoOffsetMs)
            val pool = poolRecord(selected.pool, visual)
            val preferredPreserved = selected.pool == preferredPool && selected.graph == preferredGraph
            val preferredTracePreserved = selected.trace.method == "preferred-pool" &&
                selected.trace.directorValidations == 1 && selected.trace.candidateVisits == 0L &&
                selected.trace.generatedCandidates == 0
            planningPass = graph["planning_contract_pass"] == true &&
                pool.all { it["actual_observation_count"] as Int >= 2 } &&
                when (preferredOutcome) {
                    "preferred" -> preferredPreserved && preferredTracePreserved
                    else -> echoOffsetMs != -67L || preferredCode == "insufficient_distinct_moments"
                }
            mapOf("status" to "accepted_planning", "trace" to traceRecord(selected.trace),
                "pool" to pool, "graph" to graph,
                "exact_preferred_pool_and_graph_preserved" to preferredPreserved,
                "preferred_trace_no_fallback" to preferredTracePreserved)
        } catch (failure: HeartbeatCandidateSearchIncompleteException) {
            mapOf("status" to "search_incomplete_nonmaterial", "detail" to failure.message,
                "trace" to traceRecord(failure.trace))
        } catch (failure: HeartbeatCandidateDomainExhaustedException) {
            mapOf("status" to "finite_measured_anchor_domain_exhausted", "code" to failure.code,
                "detail" to failure.message, "trace" to traceRecord(failure.trace))
        } catch (failure: MaterialRejectedException) {
            mapOf("status" to "material_rejected", "code" to failure.code,
                "detail" to failure.message)
        }
        val report = linkedMapOf<String, Any?>(
            "method" to "frozen-cache-production-planning-diagnostic-v1",
            "scope" to "Cache-derived planning only. No new source analysis, decoded pixels, audio measurement or human acceptance.",
            "source_sha256_from_registered_cache_key" to registered.sourceSha,
            "cache_sha256" to actualSha,
            "cache_bytes" to cacheFile.length(),
            "source_analysis_profile" to evidence.profile.cacheToken,
            "correspondence_state" to evidence.profile.correspondenceState.name,
            "correspondence_assessments_completed" to evidence.correspondenceAssessmentsCompleted,
            "duration_us" to evidence.durationUs,
            "observations" to evidence.observations.size,
            "attachments" to evidence.attachments.frames.size,
            "echo_offset_ms" to echoOffsetMs,
            "expected_preferred_outcome_at_minus_67_ms" to preferredOutcome,
            "preferred_pool" to poolRecord(preferredPool, visual),
            "preferred_director" to preferred,
            "new_selection" to selection,
            "elapsed_planning_ms" to (System.nanoTime() - startedNs) / 1_000_000L,
            "planning_contract_pass" to planningPass,
            "native_render_acceptance" to "NOT_ASSESSED",
            "audio_acceptance" to "NOT_ASSESSED",
            "human_acceptance" to "NOT_ASSESSED"
        )
        Files.write(output.toPath(), (json(report) + "\n").toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        println("Heartbeat frozen-cache planning diagnostic: $output; planning_contract_pass=$planningPass")
        check(planningPass) { "Frozen-cache planning contract did not pass; inspect the immutable diagnostic" }
    }

    private fun traceRecord(trace: HeartbeatSourcePool.SelectionTrace): Map<String, Any?> = linkedMapOf(
        "method" to trace.method,
        "generated_candidates" to trace.generatedCandidates,
        "candidate_count" to trace.candidateCount,
        "candidate_visits" to trace.candidateVisits,
        "director_validations" to trace.directorValidations,
        "finite_domain_exhausted" to trace.finiteDomainExhausted,
        "max_candidate_visits" to trace.maxCandidateVisits,
        "max_director_validations" to trace.maxDirectorValidations
    )

    private fun poolRecord(pool: MontageGraph, visual: VisualEventMap): List<Map<String, Any?>> =
        pool.clips.mapIndexed { index, clip ->
            val observations = visual.observations.filter {
                it.sourceTimeUs / 1_000L in clip.sourceStartMs until clip.sourceEndMs
            }
            linkedMapOf("role" to index, "id" to clip.id, "source_index" to clip.sourceIndex,
                "start_ms" to clip.sourceStartMs, "end_ms" to clip.sourceEndMs,
                "actual_observation_count" to observations.size,
                "actual_observation_pts_us" to observations.map { it.sourceTimeUs })
        }

    private fun graphRecord(graph: MontageGraph, echoOffsetMs: Long): Map<String, Any?> {
        val live = graph.clips.take(HeartbeatMontageProfile.scenes.size)
        val firstPhrase = graph.clips.take(15)
        val overlaps = firstPhrase.flatMapIndexed { index, left ->
            firstPhrase.drop(index + 1).mapNotNull { right ->
                val overlap = minOf(left.sourceEndMs, right.sourceEndMs) -
                    maxOf(left.sourceStartMs, right.sourceStartMs)
                if (left.sourceIndex == right.sourceIndex && overlap > 0L)
                    mapOf("left" to left.id, "right" to right.id, "overlap_ms" to overlap)
                else null
            }
        }
        val plan = HighQualityFramePlan.build(graph, 60)
        val echoFrames = plan.frames.filter { it.layer.heartbeatEcho }
        val requests = echoFrames.map { it.sourceTimeUs + it.layer.secondarySourceOffsetMs * 1_000L }
        val echoOutOfBounds = requests.count { it !in 0L until graph.sourceDurationMs * 1_000L }
        val echoOffsetMismatch = echoFrames.count { it.layer.secondarySourceOffsetMs != echoOffsetMs }
        val sourceOneX = live.all {
            it.sourceEndMs - it.sourceStartMs == it.outputDurationMs &&
                it.speedRamp.keyframes.all { keyframe -> keyframe.speed == 1f } &&
                graph.parameterTracks.none { track -> track.target == ParameterTargets.clip(it.id, "speed") }
        }
        val primaryInBounds = live.all { it.sourceStartMs >= 0L && it.sourceEndMs <= graph.sourceDurationMs }
        val repeatedRatio = RenderedMp4Acceptance.repeatedSourceRatio(graph)
        val pass = plan.frames.size == 1_270 && graph.clips.size == 27 && sourceOneX &&
            primaryInBounds && overlaps.isEmpty() && repeatedRatio == 0f && echoFrames.isNotEmpty() &&
            echoOutOfBounds == 0 && echoOffsetMismatch == 0
        return linkedMapOf(
            "generator" to graph.metadata.generator,
            "output_duration_ms" to graph.outputDurationMs,
            "scheduled_frames" to plan.frames.size,
            "scheduled_fps" to plan.fps,
            "clips" to graph.clips.mapIndexed { index, clip -> linkedMapOf(
                "index" to index, "id" to clip.id, "source_index" to clip.sourceIndex,
                "start_ms" to clip.sourceStartMs, "end_ms" to clip.sourceEndMs,
                "output_duration_ms" to clip.outputDurationMs,
                "source_one_x" to (clip.sourceEndMs - clip.sourceStartMs == clip.outputDurationMs)) },
            "live_source_one_x" to sourceOneX,
            "live_primary_in_bounds" to primaryInBounds,
            "first_15_primary_overlaps" to overlaps,
            "repeated_source_ratio" to repeatedRatio,
            "echo_scheduled_frames" to echoFrames.size,
            "echo_requested_min_us" to requests.minOrNull(),
            "echo_requested_max_us" to requests.maxOrNull(),
            "echo_requests_out_of_bounds" to echoOutOfBounds,
            "echo_offset_mismatches" to echoOffsetMismatch,
            "planning_contract_pass" to pass
        )
    }

    // Keep this JVM-only helper independent from Android's mocked JSONObject implementation.
    private fun json(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"" + buildString {
            value.forEach { character -> when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 32) append("\\u%04x".format(character.code)) else append(character)
            } }
        } + "\""
        is Boolean -> value.toString()
        is Number -> value.toString().also { require(it !in setOf("NaN", "Infinity", "-Infinity")) }
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (key, item) ->
            json(key.toString()) + ":" + json(item)
        }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { json(it) }
        else -> error("Unsupported diagnostic value: ${value.javaClass.name}")
    }
}
