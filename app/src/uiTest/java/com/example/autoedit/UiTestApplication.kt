package com.example.autoedit

import android.app.Application
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Exists only in the isolated .uitest APK. File import/publication/save remain production code. */
internal class UiTestApplication : Application(), EditSession.Owner {
    val renderer = CapturingRenderEngine()
    private var ownedSession: EditSession? = null
    override val editSession: EditSession
        get() = ownedSession ?: EditSession(this, renderer).also { ownedSession = it }

    fun resetSession() {
        check(ownedSession?.state?.value?.busy != true) { "Cannot reset a running job" }
        ownedSession?.close()
        ownedSession = null
        renderer.reset()
    }
}

internal class CapturingRenderEngine : EditSession.RenderEngine {
    @Volatile var currentRequest: VeycadAutomaticEditor.Request? = null
        private set
    var outputFixture: File? = null
    var passedQualityGate = true
    @Volatile var requestCount = 0
        private set
    private val commands = LinkedBlockingQueue<() -> EditSession.RenderSummary?>()

    override fun render(request: VeycadAutomaticEditor.Request): EditSession.RenderSummary {
        requestCount++
        currentRequest = request
        while (true) {
            request.checkCancelled()
            val summary = commands.poll(25, TimeUnit.MILLISECONDS)?.invoke()
            if (summary != null) return summary
        }
    }

    fun complete() {
        commands.put {
            requireNotNull(outputFixture).copyTo(requireNotNull(currentRequest).outputFile, overwrite = true)
            EditSession.RenderSummary(3, 0.93f, passedQualityGate,
                if (passedQualityGate) emptyList() else listOf("test quality warning"))
        }
    }

    fun fail(message: String) { commands.put { error(message) } }
    fun progress(stage: VeycadAutomaticEditor.Stage, completed: Int, total: Int) {
        commands.put {
            requireNotNull(currentRequest).onProgress(VeycadAutomaticEditor.Progress(stage, completed, total))
            null
        }
    }

    fun reset() {
        commands.clear()
        currentRequest = null
        requestCount = 0
        passedQualityGate = true
    }
}
