package com.veycad.app

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PreviewMetricsTest {
    private var now = 1_000L
    private val generation = PreviewGeneration(1, 1, 1)
    private fun context(sources: List<PreviewMetrics.Source> = listOf(source())) = PreviewMetrics.Context(
        "synthetic-kotlin", PreviewMetrics.Metadata("synthetic\"\n\\\t", "a".repeat(40), "anonymous", 1,
            "synthetic-arm64", "36", "arm64-v8a", "synthetic-decoder"),
        PreviewMetrics.Scenario("FEAR", "1:1", "manual", "synthetic", 30), sources)
    private fun source() = PreviewMetrics.Source("b".repeat(64), "h264", 1920, 1080, 30, "SDR", 2_000_000, 0, 1, 1)
    private fun metrics(limits: PreviewMetrics.Limits = PreviewMetrics.Limits()) =
        PreviewMetrics(context(), limits) { now }
    private fun completed(epoch: PreviewGeneration = generation, exact: Boolean = true) =
        PreviewMetrics.Terminal(PreviewMetrics.Status.COMPLETED, null, 0, epoch, true, exact, exact)
    private fun expectInvalid(block: () -> Unit) {
        try { block(); fail("Expected explicit rejection") } catch (_: IllegalArgumentException) { }
    }

    // Catch dropping older valid completion, dropping bad actual evidence, or inventing completion.
    @Test fun retains_historical_bad_and_unresolved_requests_without_reclassifying() {
        val m = metrics()
        val old = m.begin(PreviewMetrics.Event.EXACT_SEEK, generation, 0)
        now = 2_000
        m.end(old, completed())
        val newer = generation.copy(seek = 2)
        val stale = m.begin(PreviewMetrics.Event.EXACT_SEEK, generation, 0)
        m.end(stale, completed(newer))
        val thumbnail = m.begin(PreviewMetrics.Event.EXACT_SEEK, newer, 0)
        m.end(thumbnail, completed(newer, exact = false))
        m.begin(PreviewMetrics.Event.PLAY_START, newer, 0)
        val samples = m.snapshot().events
        assertEquals(4, samples.size)
        assertEquals(generation, samples[0].terminal.completionGeneration)
        assertEquals(PreviewMetrics.Status.COMPLETED, samples[1].terminal.status)
        assertEquals(newer, samples[1].terminal.completionGeneration)
        assertFalse(samples[2].terminal.exact)
        assertEquals(PreviewMetrics.Status.INCOMPLETE, samples[3].terminal.status)
        assertNull(samples[3].endNs)
    }

    // Catch replacing request origins with work starts and including preparation in first frame.
    @Test fun writer_keeps_four_request_origins_and_first_frame_ready_boundary() {
        val m = metrics()
        for (event in listOf(PreviewMetrics.Event.CACHED_SCRUB, PreviewMetrics.Event.EXACT_SEEK,
            PreviewMetrics.Event.FORMAT_CHANGE, PreviewMetrics.Event.PLAY_START)) {
            val token = m.begin(event, generation, 0, requestNs = 1_000, startNs = 900_001_000)
            m.end(token, completed(), endNs = 1_000_001_000)
        }
        val first = m.begin(PreviewMetrics.Event.FIRST_FRAME, generation, 0, requestNs = 1_000,
            startNs = 2_000_001_000, temperature = PreviewMetrics.Temperature.COLD, prepareNs = 2_000_000_000)
        m.end(first, completed(), endNs = 2_100_001_000)
        assertEquals(listOf(1_000L, 1_000L, 1_000L, 1_000L, 1_000L), m.snapshot().events.map { it.requestNs })
        assertEquals(2_000_001_000L, m.snapshot().events.last().startNs)
        val file = Files.createTempDirectory("metrics-origins").resolve("raw.json").toFile()
        m.writeReport(file)
        assertTrue(file.readText().contains("\"request_ns\":1000,\"start_ns\":900001000"))
        assertTrue(file.readText().contains("\"prepare_ns\":2000000000"))
    }

    @Test fun invalid_terminal_is_rejected_before_mutation_and_duplicate_terminal_fails() {
        val m = metrics()
        val token = m.begin(PreviewMetrics.Event.EXACT_SEEK, generation, 0)
        expectInvalid { m.end(token, completed(), endNs = 999) }
        expectInvalid { m.end(token, completed().copy(shownOutputUs = -1)) }
        assertNull(m.snapshot().events.single().endNs)
        now = 2_000
        m.end(token, completed())
        expectInvalid { m.end(token, completed()) }
        expectInvalid { m.end(99, completed()) }
        assertEquals(2_000L, m.snapshot().events.single().endNs)
        expectInvalid { m.begin(PreviewMetrics.Event.PLAY_START, generation, 0, requestNs = 4, startNs = 3) }
        expectInvalid { m.begin(PreviewMetrics.Event.PLAY_START, generation, -1) }
    }

    @Test fun failed_missing_and_safe_superseded_requests_remain_visible() {
        val m = metrics()
        for (status in listOf(PreviewMetrics.Status.FAILED, PreviewMetrics.Status.MISSING,
            PreviewMetrics.Status.OBSOLETE, PreviewMetrics.Status.SUPERSEDED)) {
            val token = m.begin(PreviewMetrics.Event.EXACT_SEEK, generation, 0)
            m.end(token, PreviewMetrics.Terminal(status, "observed reason"), endNs = null)
        }
        assertEquals(4, m.snapshot().events.size)
        assertFalse(m.snapshot().events.last().terminal.presented)
        val unsafe = m.begin(PreviewMetrics.Event.EXACT_SEEK, generation, 0)
        m.end(unsafe, completed().copy(status = PreviewMetrics.Status.SUPERSEDED, reason = "unsafe actual callback"))
        assertTrue(m.snapshot().events.last().terminal.presented)
    }

    @Test fun playback_keeps_grid_slots_and_unknown_clocks_rejecting_duplicate_draws() {
        val m = metrics()
        val frames = mutableListOf(PreviewMetrics.Frame(0, PreviewMetrics.FrameStatus.SHOWN, 0, null),
            PreviewMetrics.Frame(1, PreviewMetrics.FrameStatus.DROPPED))
        m.recordPlayback(PreviewMetrics.Playback("p", 100_000, 100_000, false, frames))
        frames.clear()
        val pass = m.snapshot().playback.single()
        assertEquals(2, pass.frames.size)
        assertNull(pass.frames[0].audioUs)
        expectInvalid { m.recordPlayback(pass) }
        expectInvalid { m.recordPlayback(pass.copy(id = "duplicate-index", frames = listOf(pass.frames[0], pass.frames[0]))) }
        expectInvalid { m.recordPlayback(pass.copy(id = "duplicate-pts", frames = listOf(pass.frames[0], pass.frames[0].copy(index = 1)))) }
        expectInvalid { m.recordPlayback(pass.copy(id = "outside", frames = listOf(PreviewMetrics.Frame(3, PreviewMetrics.FrameStatus.MISSING)))) }
        expectInvalid { m.recordPlayback(pass.copy(id = "nonshown", frames = listOf(PreviewMetrics.Frame(0, PreviewMetrics.FrameStatus.DROPPED, 0)))) }
        assertEquals(1, m.snapshot().playback.size)
    }

    @Test fun memory_is_concurrent_unknown_is_null_and_duplicate_timestamps_fail() {
        val m = metrics()
        m.recordMemory(PreviewMetrics.Memory(1, PreviewMetrics.AppBytes(cache = 20, fbo = 1), PreviewMetrics.ProcessBytes(codec = 90)))
        m.recordMemory(PreviewMetrics.Memory(2, PreviewMetrics.AppBytes(cache = 1, fbo = 20)))
        expectInvalid { m.recordMemory(PreviewMetrics.Memory(2)) }
        expectInvalid { m.recordMemory(PreviewMetrics.Memory(3, PreviewMetrics.AppBytes(cache = -1))) }
        assertEquals(20L, m.snapshot().memory[0].appBytes.cache)
        assertEquals(1L, m.snapshot().memory[0].appBytes.fbo)
        assertNull(m.snapshot().memory[0].appBytes.pages)
        assertEquals(90L, m.snapshot().memory[0].processBytes.codec)
    }

    @Test fun scrubbing_validates_real_intervals_and_duplicate_ids() {
        val m = metrics()
        val session = PreviewMetrics.Scrubbing("s", 1_000, 101_000, 100, false)
        m.recordScrubbing(session)
        expectInvalid { m.recordScrubbing(session) }
        expectInvalid { m.recordScrubbing(session.copy(id = "overlap", startNs = 2_000)) }
        expectInvalid { m.recordScrubbing(session.copy(id = "duration", startNs = 101_000, endNs = 102_000)) }
        assertEquals(listOf(session), m.snapshot().scrubbing)
    }

    @Test fun defensive_context_and_snapshots_cannot_change_later_report() {
        val sources = mutableListOf(source())
        val m = PreviewMetrics(context(sources)) { now }
        sources.clear()
        val snapshot = m.snapshot()
        try { (snapshot.context.sources as MutableList).clear(); fail("Must be immutable") }
        catch (_: UnsupportedOperationException) { }
        m.begin(PreviewMetrics.Event.EXACT_SEEK, generation, 0)
        assertTrue(snapshot.events.isEmpty())
        assertEquals(1, m.snapshot().context.sources.size)
    }

    @Test fun overflow_refuses_publication_preserving_destination_and_retained_evidence() {
        val m = metrics(PreviewMetrics.Limits(events = 1))
        m.begin(PreviewMetrics.Event.EXACT_SEEK, generation, 0)
        try { m.begin(PreviewMetrics.Event.PLAY_START, generation, 0); fail("Must overflow") }
        catch (_: IllegalStateException) { }
        val file = Files.createTempDirectory("metrics-overflow").resolve("raw.json").toFile()
        file.writeText("previous")
        try { m.writeReport(file); fail("Lost evidence must not publish") } catch (_: IllegalStateException) { }
        assertEquals("previous", file.readText())
        assertEquals(1, m.snapshot().events.size)
        assertNotNull(m.snapshot().writeFailure)
    }

    @Test fun all_raw_collection_limits_are_strict_and_sticky() {
        val m = metrics(PreviewMetrics.Limits(playbackPasses = 1, frames = 1, scrubSessions = 1, memorySamples = 1))
        val frame = PreviewMetrics.Frame(0, PreviewMetrics.FrameStatus.SHOWN, 0, 0)
        m.recordPlayback(PreviewMetrics.Playback("p", 33_334, 33_334, true, listOf(frame)))
        try { m.recordPlayback(PreviewMetrics.Playback("p2", 100_000, 100_000, false, listOf(frame))); fail("Overflow") }
        catch (_: IllegalStateException) { }
        m.recordMemory(PreviewMetrics.Memory(1))
        try { m.recordMemory(PreviewMetrics.Memory(2)); fail("Overflow") } catch (_: IllegalStateException) { }
        m.recordScrubbing(PreviewMetrics.Scrubbing("s", 0, 1000, 1, true))
        try { m.recordScrubbing(PreviewMetrics.Scrubbing("s2", 1000, 2000, 1, true)); fail("Overflow") }
        catch (_: IllegalStateException) { }
        assertEquals(1, m.snapshot().playback.size)
        assertEquals(1, m.snapshot().memory.size)
        assertEquals(1, m.snapshot().scrubbing.size)
        assertNotNull(m.snapshot().writeFailure)
        val frameBound = metrics(PreviewMetrics.Limits(frames = 1))
        try { frameBound.recordPlayback(PreviewMetrics.Playback("many", 100_000, 100_000, false,
            listOf(frame, frame.copy(index = 1, shownOutputUs = 33_333)))); fail("Frame overflow") }
        catch (_: IllegalStateException) { }
        assertTrue(frameBound.snapshot().playback.isEmpty())
        assertNotNull(frameBound.snapshot().writeFailure)
    }

    @Test fun failed_atomic_publication_keeps_owned_temporary_files_out_of_destination() {
        val m = metrics()
        val parent = Files.createTempDirectory("metrics-publish").toFile()
        val destination = File(parent, "existing-directory").apply { mkdir() }
        File(destination, "previous").writeText("previous")
        try { m.writeReport(destination); fail("Directory cannot be replaced") }
        catch (_: java.io.IOException) { }
        assertEquals("previous", File(destination, "previous").readText())
        assertEquals(listOf("existing-directory"), parent.list()!!.toList())
        val file = File(parent, "report.json")
        file.writeText("previous")
        m.writeReport(file)
        assertTrue(file.readText().startsWith("{\"schema_version\":1"))
    }

    @Test fun typed_numeric_and_text_bounds_reject_unrepresentable_context() {
        expectInvalid { PreviewMetrics(context().copy(scenario = context().scenario.copy(exportFps = 29))) }
        expectInvalid { PreviewMetrics(context().copy(metadata = context().metadata.copy(commit = "A".repeat(40)))) }
        expectInvalid { PreviewMetrics(context().copy(metadata = context().metadata.copy(appVersion = "x".repeat(4097)))) }
        expectInvalid { PreviewMetrics(context().copy(metadata = context().metadata.copy(appVersion = "\uD800"))) }
        expectInvalid { metrics(PreviewMetrics.Limits(events = 0)) }
    }

    @Test fun invalid_source_count_is_rejected_before_copying_caller_collection() {
        val oversized = object : AbstractList<PreviewMetrics.Source>() {
            override val size = 1_000_000
            override fun get(index: Int): PreviewMetrics.Source = throw AssertionError("Must not copy invalid collection")
        }
        expectInvalid { PreviewMetrics(context(oversized)) }
    }

    @Test fun writer_emits_synthetic_fixed_schema_fixture_with_escaped_strings_and_nulls() {
        val m = metrics()
        val token = m.begin(PreviewMetrics.Event.EXACT_SEEK, generation, 20_000)
        now = 10_001_000
        m.end(token, completed())
        for (event in listOf(PreviewMetrics.Event.CACHED_SCRUB, PreviewMetrics.Event.EXACT_SEEK,
            PreviewMetrics.Event.FORMAT_CHANGE, PreviewMetrics.Event.PLAY_START)) {
            val queued = m.begin(event, generation, 0, requestNs = 1_000, startNs = 50_001_000)
            m.end(queued, completed(), endNs = 60_001_000)
        }
        val first = m.begin(PreviewMetrics.Event.FIRST_FRAME, generation, 0, requestNs = 1_000,
            startNs = 2_000_001_000, temperature = PreviewMetrics.Temperature.COLD, prepareNs = 2_000_000_000)
        m.end(first, completed(), endNs = 2_100_001_000)
        m.recordPlayback(PreviewMetrics.Playback("synthetic-pass", 100_000, 100_000, false,
            listOf(PreviewMetrics.Frame(0, PreviewMetrics.FrameStatus.SHOWN, 0, null),
                PreviewMetrics.Frame(1, PreviewMetrics.FrameStatus.MISSING))))
        m.recordMemory(PreviewMetrics.Memory(1, PreviewMetrics.AppBytes(cache = 5)))
        m.recordMemory(PreviewMetrics.Memory(2, PreviewMetrics.AppBytes(20, 0, 0, 0, 0, 0, 1),
            PreviewMetrics.ProcessBytes(100, 10, 20, 30, 90)))
        m.recordMemory(PreviewMetrics.Memory(3, PreviewMetrics.AppBytes(1, 0, 0, 0, 0, 0, 20),
            PreviewMetrics.ProcessBytes(100, 10, 20, 30, 90)))
        m.recordScrubbing(PreviewMetrics.Scrubbing("synthetic-scrub", 0, 100_000, 100, false))
        // Explicit command-local opt-in keeps exchange evidence; fresh CI needs no ignored ledger.
        val file = System.getenv("VEYCAD_PREVIEW_METRICS_FIXTURE")?.let(::File)
            ?: Files.createTempDirectory("metrics-synthetic").resolve("raw.json").toFile()
        m.writeReport(file)
        val json = file.readText()
        assertTrue(json.startsWith("{\"schema_version\":1,\"groups\":[{"))
        assertTrue(json.contains("synthetic\\\"\\n\\\\\\t"))
        assertTrue(json.contains("\"audio_us\":null"))
        assertFalse(json.contains("NaN"))
        assertFalse(json.contains("p95"))
    }
}
