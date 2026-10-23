package com.veycad.app

import android.app.Application
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Exists only in the isolated .uitest APK. File import/publication/save remain production code. */
internal class UiTestApplication : Application(), EditSession.Owner, TextEditSession.Owner {
    val renderer = CapturingRenderEngine()
    private var ownedSession: EditSession? = null
    private var ownedText: TextEditSession? = null
    var textExportEngine = TextEditSession.NATIVE_EXPORT_ENGINE
    var speechTranscriber: SpeechTranscriber = WhisperSpeechTranscriber()
    var sourceIo: SourceDraftStore.Io = SourceDraftStore.DiskIo
    var musicSelector: ((MontageStyleCatalog.Style) -> BuiltInMusicCatalog.Selection)? = null
    var failSourcePreferenceCommit = false
    override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
        val actual = super.getSharedPreferences(name, mode)
        if (name != "source_draft") return actual
        return object : android.content.SharedPreferences by actual {
            override fun edit(): android.content.SharedPreferences.Editor {
                val editor = actual.edit()
                return object : android.content.SharedPreferences.Editor by editor {
                    override fun putString(key: String, value: String?) = apply { editor.putString(key, value) }
                    override fun putBoolean(key: String, value: Boolean) = apply { editor.putBoolean(key, value) }
                    override fun remove(key: String) = apply { editor.remove(key) }
                    override fun clear() = apply { editor.clear() }
                    override fun commit(): Boolean = if (failSourcePreferenceCommit) false else editor.commit()
                }
            }
        }
    }
    private val forwardingIo = object : SourceDraftStore.Io {
        override fun copyAndSync(input: java.io.InputStream, target: File) = sourceIo.copyAndSync(input, target)
        override fun replaceManifest(target: File, bytes: ByteArray) = sourceIo.replaceManifest(target, bytes)
        override fun readManifest(target: File) = sourceIo.readManifest(target)
        override fun checkpoint(phase: SourceDraftStore.Phase) = sourceIo.checkpoint(phase)
    }
    override val textEditSession: TextEditSession
        get() = ownedText ?: TextEditSession(this, transcriber = speechTranscriber,
            exportEngine = textExportEngine).also { ownedText = it }
    override val editSession: EditSession
        get() = ownedSession ?: EditSession(this, renderer, sourceStore = SourceDraftStore(filesDir, forwardingIo),
            selectMusic = { musicSelector?.invoke(it) ?: BuiltInMusicCatalog.select(this, it.id) }).also { ownedSession = it }

    fun resetSession() {
        check(ownedSession?.state?.value?.busy != true) { "Cannot reset a running job" }
        check(ownedText?.state?.value?.busy != true) { "Cannot reset a running text job" }
        check(MediaWorkCoordinator.process.snapshot() == MediaWorkCoordinator.Snapshot(0, false)) { "Active media lease" }
        ownedText?.close()
        ownedText = null
        textExportEngine = TextEditSession.NATIVE_EXPORT_ENGINE
        speechTranscriber = WhisperSpeechTranscriber()
        ownedSession?.close()
        ownedSession = null
        sourceIo = SourceDraftStore.DiskIo
        musicSelector = null
        failSourcePreferenceCommit = false
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

    fun fail(message: String) = fail(IllegalStateException(message))
    fun fail(error: Throwable) { commands.put { throw error } }
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
