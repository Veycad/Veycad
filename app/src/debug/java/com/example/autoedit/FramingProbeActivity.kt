package com.example.autoedit

import android.app.Activity
import android.os.Bundle
import java.io.File

/** Local synthetic geometry regression: no perception, recipe effects or private reference pixels. */
class FramingProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = File(requireNotNull(intent.getStringExtra("source")))
        val output = File(requireNotNull(intent.getStringExtra("output")))
        val width = intent.getIntExtra("width", 360)
        val height = intent.getIntExtra("height", 640)
        val rotation = intent.getFloatExtra("cameraRotation", 0f)
        val secondary = intent.getStringExtra("secondary")?.let(::File)
        val transform = MontageGraph.ClipTransform(listOf(
            MontageGraph.ClipTransform.Keyframe(0f, 1f, rotationDegrees = rotation),
            MontageGraph.ClipTransform.Keyframe(1f, 1f, rotationDegrees = rotation)))
        Thread {
            val marker = File(output.path + ".result")
            runCatching {
                val clips = mutableListOf(MontageGraph.Clip(
                    "geometry", 0, 1_000, 1_000, MontageGraph.ShotRole.CLOSE,
                    MontageGraph.Transition.OPEN, MontageGraph.Motion.HOLD, 1f, 0,
                    transform = transform))
                secondary?.let { clips += clips.first().copy(id = "second-source", sourceIndex = 1,
                    transitionIn = MontageGraph.Transition.HARD_CUT) }
                val graph = MontageGraph(2_000, if (secondary == null) 1_000 else 2_000, clips = clips)
                MediaCodecSpeedRampRenderer.render(MediaCodecSpeedRampRenderer.Request(
                    masterFile = source, secondaryFile = secondary, graph = graph, outputFile = output,
                    width = width, height = height, bitrate = 1_000_000))
                marker.writeText("status=ok")
            }.onFailure { marker.writeText("status=failed\n${it.stackTraceToString()}") }
            runOnUiThread { finish() }
        }.start()
    }
}
