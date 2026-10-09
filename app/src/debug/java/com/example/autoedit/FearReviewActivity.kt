package com.example.autoedit

import android.app.Activity
import android.graphics.Color
import android.media.PlaybackParams
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.widget.VideoView
import java.io.File

/** Debug-only real-time playback check for rendered FEAR output. */
class FearReviewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val source = File(requireNotNull(intent.getStringExtra("source")))
        val speed = intent.getFloatExtra("speed", 1f)
        require(source.isFile) { "Review source does not exist: ${source.absolutePath}" }
        require(speed == 1f || speed == .5f) { "Review speed must be 1x or 0.5x" }

        val marker = File(filesDir, "fear-review-${speed}x.result")
        marker.delete()
        val startedAtMs = SystemClock.elapsedRealtime()
        marker.writeText("status=preparing\nspeed=$speed\nsource=${source.name}\n")

        val video = VideoView(this).apply {
            setBackgroundColor(Color.BLACK)
            setVideoPath(source.absolutePath)
            setOnPreparedListener { player ->
                player.playbackParams = PlaybackParams().setSpeed(speed)
                marker.writeText(
                    "status=playing\nspeed=$speed\nsource=${source.name}\n" +
                        "duration_ms=${player.duration}\n"
                )
                start()
            }
            setOnCompletionListener { player ->
                marker.writeText(
                    "status=completed\nspeed=$speed\nsource=${source.name}\n" +
                        "duration_ms=${player.duration}\n" +
                        "wall_time_ms=${SystemClock.elapsedRealtime() - startedAtMs}\n"
                )
                finish()
            }
            setOnErrorListener { _, what, extra ->
                marker.writeText("status=failed\nspeed=$speed\nwhat=$what\nextra=$extra\n")
                finish()
                true
            }
        }
        setContentView(video)
    }
}
