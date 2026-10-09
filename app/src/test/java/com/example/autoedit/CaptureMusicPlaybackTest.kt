package com.example.autoedit

import org.junit.Assert.*
import org.junit.Test

class CaptureMusicPlaybackTest {
    private class Player : CaptureMusicPlayback.Output {
        val calls = mutableListOf<String>()
        var complete: (() -> Unit)? = null
        var failure: String? = null
        private fun call(name: String) {
            calls += name
            if (failure == name) throw IllegalStateException("Player unavailable")
        }
        override fun start() = call("start")
        override fun pause() = call("pause")
        override fun rewind(onComplete: () -> Unit) { call("seek"); complete = onComplete }
    }
    private class Fixture {
        val player = Player()
        val events = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val playback = CaptureMusicPlayback(player, events::add, failures::add)
        fun at(time: Long) = playback.update(CaptureTakeTimeline.position(CaptureScript.fear, 3, time))
    }

    @Test fun firstPreparedTakeStartsOnceWithoutSeeking() {
        val f = Fixture()
        f.at(0); f.at(100); f.at(26_999)
        assertEquals(listOf("start"), f.player.calls)
        assertEquals(listOf("music-start-request:1", "music-start:1"), f.events)
    }

    @Test fun seekCompletesDuringBreakAndOnlyNextTakeStartsPlayback() {
        val f = Fixture()
        f.at(0); f.at(27_000); f.at(27_100)
        assertEquals(listOf("start", "pause", "seek"), f.player.calls)
        f.player.complete!!.invoke()
        f.at(31_999)
        assertEquals(listOf("start", "pause", "seek"), f.player.calls)
        f.at(32_000); f.at(32_100)
        assertEquals(listOf("start", "pause", "seek", "start"), f.player.calls)
        assertEquals(listOf("music-start-request:1", "music-start:1", "music-pause:1", "music-seek-request:2",
            "music-seek-complete:2", "music-start-request:2", "music-start:2"), f.events)
        assertTrue(f.failures.isEmpty())
    }

    @Test fun stopDuringSeekRejectsLateAndDuplicateCallbacks() {
        val f = Fixture()
        f.at(0); f.at(27_000)
        val callback = f.player.complete!!
        f.playback.close()
        callback(); callback(); f.at(32_000)
        assertEquals(listOf("start", "pause", "seek"), f.player.calls)
        assertFalse(f.events.contains("music-seek-complete:2"))
        assertTrue(f.failures.isEmpty())
    }

    @Test fun oldSeekCallbackCannotAffectTheNextRecovery() {
        val f = Fixture()
        f.at(0); f.at(27_000)
        val previous = f.player.complete!!
        previous(); f.at(32_000); f.at(59_000)
        previous()
        assertFalse(f.events.contains("music-seek-complete:3"))
        f.player.complete!!.invoke(); f.at(64_000)
        assertEquals(3, f.player.calls.count { it == "start" })
        assertEquals(1, f.events.count { it == "music-seek-complete:2" })
        assertTrue(f.failures.isEmpty())
    }

    @Test fun unfinishedSeekStopsInsteadOfStartingAnOffsetTake() {
        val f = Fixture()
        f.at(0); f.at(27_000); f.at(32_000)
        f.player.complete!!.invoke(); f.at(32_100)
        assertEquals(1, f.failures.size)
        assertEquals(1, f.player.calls.count { it == "start" })
        assertFalse(f.events.contains("music-seek-complete:2"))
    }

    @Test fun missingEntireBreakDoesNotResumeOldMusic() {
        val f = Fixture()
        f.at(0); f.at(32_000)
        assertEquals(1, f.failures.size)
        assertEquals(listOf("start"), f.player.calls)
    }

    @Test fun playerErrorsStopPlaybackAndInvalidatePendingWork() {
        for (operation in listOf("start", "pause", "seek")) {
            val f = Fixture()
            if (operation != "start") f.at(0)
            f.player.failure = operation
            f.at(if (operation == "start") 0 else 27_000)
            f.at(32_000)
            assertEquals(operation, 1, f.failures.size)
            assertFalse(f.events.contains("music-start:2"))
        }
    }
}
