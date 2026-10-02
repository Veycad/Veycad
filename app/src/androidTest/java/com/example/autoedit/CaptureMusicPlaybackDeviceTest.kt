package com.example.autoedit

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real local decoder/playback clock; does not measure speaker, Bluetooth or camera latency. */
@RunWith(AndroidJUnit4::class)
class CaptureMusicPlaybackDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun bothAuthoredTracksRewindDuringBreakAndWaitForNextTake() {
        check(context.packageName.endsWith(".uitest"))
        val report = JSONArray()
        for (script in listOf(CaptureScript.fear, CaptureScript.heartbeat)) {
            val file = BuiltInMusicCatalog.select(context, script.styleId).file
            lateinit var player: MediaPlayer
            lateinit var playback: CaptureMusicPlayback
            val events = JSONArray()
            val failures = mutableListOf<String>()
            var duration = 0
            instrumentation.runOnMainSync {
                player = MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    setDataSource(file.path)
                    isLooping = true
                    prepare()
                }
                duration = player.duration
                playback = CaptureMusicPlayback.forPlayer(player, { type ->
                    events.put(JSONObject().put("type", type).put("at_ns", SystemClock.elapsedRealtimeNanos())
                        .put("music_ms", player.currentPosition))
                }, failures::add)
            }
            try {
                fun at(time: Long) = instrumentation.runOnMainSync {
                    playback.update(CaptureTakeTimeline.position(script, 3, time))
                }
                at(0)
                await("first take plays") { player.currentPosition >= 300 }
                at(script.durationMs)
                await("seek completed while paused") {
                    (0 until events.length()).any { events.getJSONObject(it).getString("type") == "music-seek-complete:2" }
                }
                var positionAfterSeek = 0
                instrumentation.runOnMainSync { positionAfterSeek = player.currentPosition }
                SystemClock.sleep(250)
                instrumentation.runOnMainSync {
                    assertFalse("Music must remain paused during recovery", player.isPlaying)
                    assertTrue("Paused clock advanced", kotlin.math.abs(player.currentPosition - positionAfterSeek) < 80)
                    assertTrue("Rewind did not reach beginning", player.currentPosition in 0..100)
                }
                at(script.durationMs + CaptureScript.RECOVERY_MS)
                await("second take plays") { player.currentPosition >= 300 }
                instrumentation.runOnMainSync {
                    assertTrue(failures.toString(), failures.isEmpty())
                    val types = (0 until events.length()).map { events.getJSONObject(it).getString("type") }
                    assertEquals(listOf("music-start-request:1", "music-start:1", "music-pause:1", "music-seek-request:2",
                        "music-seek-complete:2", "music-start-request:2", "music-start:2"), types)
                    val preparedPosition = events.getJSONObject(types.indexOf("music-start-request:2")).getInt("music_ms")
                    assertTrue("Player was not at zero before starting: $preparedPosition; events=$events", preparedPosition in 0..100)
                }
            } finally {
                instrumentation.runOnMainSync {
                    report.put(JSONObject().put("style", script.styleId).put("asset_duration_ms", duration)
                        .put("events", events))
                    playback.close(); player.release()
                }
                File(context.getExternalFilesDir("camera-evidence"), "music-player.json").writeText(
                    JSONObject().put("clock", "MediaPlayer position, not acoustic output")
                        .put("tracks", report).toString(2))
            }
        }
    }

    private fun await(description: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 10_000
        while (true) {
            var done = false
            instrumentation.runOnMainSync { done = condition() }
            if (done) return
            if (SystemClock.elapsedRealtime() >= until) fail("Timed out: $description")
            SystemClock.sleep(40)
        }
    }
}
