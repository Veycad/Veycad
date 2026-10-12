package com.veycad.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Process-owned jobs never retain an Activity. Published files and interruption markers are durable. */
internal class EditSession internal constructor(
    context: Context,
    private val renderEngine: RenderEngine = NativeRenderEngine,
    private val sourceInspector: SourceInspector = SourceInspector { id, file, name, checkCancelled ->
        MediaInputInspector(checkCancelled).inspect(id, file, name)
    }
) {
    internal interface Owner { val editSession: EditSession }
    internal fun interface RenderEngine {
        fun render(request: VeycadAutomaticEditor.Request): RenderSummary
    }
    internal fun interface SourceInspector {
        fun inspect(id: String, file: File, name: String, checkCancelled: () -> Unit): MediaSource
    }
    internal data class RenderSummary(
        val clipCount: Int, val beatHitRate: Float,
        val passedQualityGate: Boolean, val issues: List<String>
    )
    private object NativeRenderEngine : RenderEngine {
        override fun render(request: VeycadAutomaticEditor.Request): RenderSummary {
            val result = VeycadAutomaticEditor.render(request)
            return RenderSummary(result.winner.alternative.graph.clips.size,
                result.winner.acceptance.metrics.beatHitRate, result.passedQualityGate,
                result.winner.acceptance.issues.map { it.toString() })
        }
    }
    private val app = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val preferences = app.getSharedPreferences("edit_session", Context.MODE_PRIVATE)
    private val mutableState = MutableLiveData(State(
        entry = CompletedRenderStore.latest(app.filesDir),
        error = if (preferences.getBoolean("running", false))
            "Работа была прервана системой. Исходники и предыдущий монтаж сохранены; можно повторить." else null
    ))
    val state: LiveData<State> = mutableState
    private val cancelLock = Any()
    @Volatile private var cancelled = false
    @Volatile private var publishing = false
    @Volatile private var activeLease: File? = null

    data class State(
        val busy: Boolean = false,
        val saving: Boolean = false,
        val importing: Boolean = false,
        val publishing: Boolean = false,
        val progress: VeycadAutomaticEditor.Progress? = null,
        val entry: CompletedRenderStore.Entry? = null,
        val draft: Draft? = null,
        val error: String? = null,
        val operationDetail: String? = null
    )

    data class Draft(val files: List<File>, val names: List<String>, val music: BuiltInMusicCatalog.Selection)

    /** Capture selection and rendering use the same owned worker and durable result publication. */
    fun prepareCapture(id: String, requestedTake: Int? = null, width: Int = 720, height: Int = 1_280,
        bitrate: Int = 5_000_000) {
        if (state.value?.busy == true) return
        synchronized(cancelLock) { cancelled = false; publishing = false }
        val previous = state.value?.entry
        mutableState.value = State(busy = true, entry = previous, operationDetail = "Подбираю подходящий дубль…")
        LocalDiagnostics.record(app, "capture_selection_requested", mapOf("requested_take" to (requestedTake ?: 0).toString()))
        preferences.edit().putBoolean("running", true).commit()
        executor.execute {
            var preparationWakeLock: PowerManager.WakeLock? = null
            val result = runCatching {
                val power = app.getSystemService(PowerManager::class.java)
                preparationWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${app.packageName}:capture-selection")
                    .apply { acquire(15 * 60 * 1000L) }
                CaptureTakePreparation.prepare(app, id, requestedTake,
                    checkCancelled = { check(!cancelled) { "Подбор дубля отменён" } },
                    onProgress = { detail -> main.post {
                        mutableState.value = State(busy = true, entry = previous, operationDetail = detail)
                    } })
            }
            preparationWakeLock?.let { if (it.isHeld) it.release() }
            main.post {
                preferences.edit().putBoolean("running", false).commit()
                if (cancelled) { mutableState.value = State(entry = previous, error = "Подбор дубля отменён"); return@post }
                result.onSuccess { prepared ->
                    val style = MontageStyleCatalog.restore(prepared.session.styleId)
                    render(prepared.file, null, prepared.musicFile, style, width, height, bitrate,
                        captureSessionId = id, continuingCapturePreparation = true,
                        captureTakeOrdinal = requireNotNull(prepared.session.selectedTake),
                        captureAutoRecommendation = requestedTake == null)
                }.onFailure {
                    CaptureDiagnostics.failure(app, CaptureDiagnostics.Stage.TAKE_SELECTION, it)
                    mutableState.value = State(entry = previous, error = it.message)
                }
            }
        }
    }

    fun importVideos(uris: List<android.net.Uri>, style: MontageStyleCatalog.Style) {
        if (state.value?.busy == true) return
        val previous = state.value?.entry
        mutableState.value = State(busy = true, importing = true, entry = previous)
        executor.execute {
            val directory = RenderWorkspace.sourceDraftDirectory(app.filesDir)
            val stamp = UUID.randomUUID().toString()
            val targets = uris.indices.map { File(directory, "source-$it-$stamp.mp4") }
            val result = runCatching {
                val names = uris.mapIndexed { index, uri ->
                    app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                        null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: "Видео ${index + 1}"
                }
                uris.zip(targets).forEach { (uri, target) ->
                    val temporary = File(target.path + ".partial")
                    try {
                        app.contentResolver.openInputStream(uri)?.use { input ->
                            temporary.outputStream().use { input.copyTo(it) }
                        } ?: error("Не удалось прочитать выбранный файл")
                        check(temporary.length() > 0) { "Выбранный файл пуст" }
                        check(temporary.renameTo(target)) { "Не удалось импортировать видео" }
                    } finally { temporary.delete() }
                }
                val music = BuiltInMusicCatalog.select(app, style.id)
                app.getSharedPreferences("source_draft", Context.MODE_PRIVATE).edit()
                    .putString("display_name", names[0])
                    .putString("display_name_2", names.getOrNull(1)).commit()
                directory.listFiles()?.filter { it !in targets }?.forEach(File::delete)
                Draft(targets, names, music)
            }
            if (result.isFailure) targets.forEach(File::delete)
            main.post { mutableState.value = State(entry = previous, draft = result.getOrNull(),
                error = result.exceptionOrNull()?.message) }
        }
    }

    init {
        // A new session means the old process is gone; no old worker can still use its scratch.
        RenderWorkspace.pendingRendersDirectory(app.filesDir).deleteRecursively()
        if (CompletedRenderStore.recoverCaptureLinks(app.filesDir).isNotEmpty())
            LocalDiagnostics.record(app, "capture_result_link_recovery_failed")
        preferences.edit().putBoolean("running", false).commit()
    }

    fun render(
        source: File,
        secondary: File?,
        music: File,
        style: MontageStyleCatalog.Style,
        width: Int = 720,
        height: Int = 1_280,
        bitrate: Int = 5_000_000,
        captureSessionId: String? = null,
        continuingCapturePreparation: Boolean = false,
        captureTakeOrdinal: Int? = null,
        captureAutoRecommendation: Boolean = false
    ) {
        renderResolved({ checkCancelled ->
            require(secondary == null || secondary.canonicalPath != source.canonicalPath)
            MediaSourceSet(listOfNotNull(source, secondary).mapIndexed { index, file ->
                sourceInspector.inspect("legacy-$index", file, file.name, checkCancelled)
            })
        }, music, style, width, height, bitrate, captureSessionId, continuingCapturePreparation,
            captureTakeOrdinal, captureAutoRecommendation)
    }

    fun render(
        sources: MediaSourceSet,
        music: File,
        style: MontageStyleCatalog.Style,
        width: Int = 720,
        height: Int = 1_280,
        bitrate: Int = 5_000_000,
        captureSessionId: String? = null,
        continuingCapturePreparation: Boolean = false,
        captureTakeOrdinal: Int? = null,
        captureAutoRecommendation: Boolean = false
    ) = renderResolved({ checkCancelled -> checkCancelled(); sources }, music, style, width, height,
        bitrate, captureSessionId, continuingCapturePreparation, captureTakeOrdinal, captureAutoRecommendation)

    private fun renderResolved(
        resolveSources: (() -> Unit) -> MediaSourceSet,
        music: File,
        style: MontageStyleCatalog.Style,
        width: Int,
        height: Int,
        bitrate: Int,
        captureSessionId: String?,
        continuingCapturePreparation: Boolean,
        captureTakeOrdinal: Int?,
        captureAutoRecommendation: Boolean
    ) {
        require(captureSessionId == null || captureTakeOrdinal != null)
        if (state.value?.busy == true && !(continuingCapturePreparation && captureSessionId != null &&
            state.value?.operationDetail != null)) return
        synchronized(cancelLock) { cancelled = false; publishing = false }
        val previous = state.value?.entry
        preferences.edit().putBoolean("running", true).commit()
        mutableState.value = State(busy = true, entry = previous,
            progress = VeycadAutomaticEditor.Progress(VeycadAutomaticEditor.Stage.ANALYSING_VIDEO))
        executor.execute {
            val directory = File(RenderWorkspace.pendingRendersDirectory(app.filesDir), UUID.randomUUID().toString())
                .apply { mkdirs() }
            val output = File(directory, "Veycad-${UUID.randomUUID()}.mp4")
            var wakeLock: PowerManager.WakeLock? = null
            val result = runCatching {
                val lease = File(directory, "active.lease").apply { writeText("active") }
                activeLease = lease
                if (cancelled) lease.delete()
                val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${app.packageName}:edit-session")
                    .apply { acquire(15 * 60 * 1000L) }
                val checkCancelled = { check(!cancelled) { "Монтаж отменён" } }
                val sources = resolveSources(checkCancelled)
                val edit = renderEngine.render(VeycadAutomaticEditor.Request(
                    context = app, sources = sources,
                    musicFile = music, outputFile = output, style = style.directorStyle,
                    recipe = requireNotNull(style.recipe),
                    width = width,
                    height = height,
                    bitrate = bitrate,
                    jobLease = lease,
                    checkCancelled = checkCancelled,
                    onProgress = { progress ->
                        check(!cancelled) { "Монтаж отменён" }
                        main.post { mutableState.value = State(busy = true, entry = previous, progress = progress) }
                    }
                ))
                check(!cancelled) { "Монтаж отменён" }
                val warning = if (edit.passedQualityGate) "проверено" else
                    "готово с предупреждением QA: ${edit.issues.joinToString()}"
                val summary = "${style.title} · ${edit.clipCount} фрагментов · " +
                    "бит ${(edit.beatHitRate * 100).toInt()}% · $warning"
                synchronized(cancelLock) {
                    check(!cancelled) { "Монтаж отменён" }
                    publishing = true
                }
                main.post { mutableState.value = State(busy = true, publishing = true,
                    entry = previous, operationDetail = "Сохраняю готовый эдит…") }
                val link = captureSessionId?.let { CompletedRenderStore.CaptureLink(it,
                    requireNotNull(captureTakeOrdinal), captureAutoRecommendation) }
                val published = CompletedRenderStore.publish(app.filesDir, output, summary, link)
                val linkError = link?.let {
                    runCatching { CaptureSessionStore(app.filesDir).attachResult(it.sessionId,
                        published.file.name, it.takeOrdinal, it.recommended) }.exceptionOrNull()
                }
                if (linkError != null) LocalDiagnostics.record(app, "capture_result_link_failed")
                published to linkError
            }
            wakeLock?.let { if (it.isHeld) it.release() }
            if (captureSessionId != null) result.exceptionOrNull()?.let {
                CaptureDiagnostics.failure(app, CaptureDiagnostics.Stage.RENDER, it, mapOf("style" to style.id))
            }
            directory.deleteRecursively()
            activeLease = null
            preferences.edit().putBoolean("running", false).commit()
            main.post {
                val completed = result.getOrNull()
                mutableState.value = State(entry = completed?.first ?: previous,
                    error = result.exceptionOrNull()?.message ?: completed?.second?.let {
                        "Эдит сохранён. Связь с дублем восстановится при следующем запуске приложения."
                    })
            }
        }
    }

    /** Cleanup waits for the worker to unwind; no files in use are deleted from the UI. */
    fun cancelRender(): Boolean = synchronized(cancelLock) {
        if (state.value?.busy != true || state.value?.saving == true ||
            state.value?.importing == true || publishing || cancelled) return@synchronized false
        cancelled = true
        activeLease?.delete()
        true
    }

    fun clearDraft() {
        if (state.value?.busy == true) return
        RenderWorkspace.clearSourceDraft(app.filesDir)
        mutableState.value = state.value?.copy(draft = null)
    }

    fun save(entry: CompletedRenderStore.Entry, destination: android.net.Uri? = null) {
        if (state.value?.busy == true || entry.saved) return
        preferences.edit().putBoolean("running", true).commit()
        mutableState.value = State(busy = true, saving = true, entry = entry)
        executor.execute {
            val result = runCatching {
                val local = File(File(app.filesDir, "my-edits").apply { mkdirs() }, entry.file.name)
                val temporary = File(local.path + ".partial")
                try {
                    entry.file.copyTo(temporary, overwrite = true)
                    if (destination == null) saveToGallery(entry.file) else {
                        app.contentResolver.openOutputStream(destination)?.use { output ->
                            entry.file.inputStream().use { it.copyTo(output) }
                        } ?: error("Не удалось открыть место сохранения")
                    }
                    check(temporary.renameTo(local)) { "Не удалось сохранить монтаж в библиотеку" }
                    CompletedRenderStore.markSaved(entry)
                    entry.copy(saved = true)
                } finally { temporary.delete() }
            }
            preferences.edit().putBoolean("running", false).commit()
            main.post { mutableState.value = State(entry = result.getOrNull() ?: entry,
                error = result.exceptionOrNull()?.message) }
        }
    }

    private fun saveToGallery(source: File) {
        check(android.os.Build.VERSION.SDK_INT >= 29)
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, source.name)
            put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/Veykad")
            put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = android.provider.MediaStore.Video.Media.getContentUri(
            android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = app.contentResolver.insert(collection, values) ?: error("Галерея недоступна")
        try {
            app.contentResolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { it.copyTo(output) }
            } ?: error("Не удалось сохранить файл")
            values.clear()
            values.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
            check(app.contentResolver.update(uri, values, null, null) == 1) { "Не удалось завершить сохранение" }
        } catch (error: Throwable) {
            app.contentResolver.delete(uri, null, null)
            throw error
        }
    }

    internal fun close() {
        check(state.value?.busy != true) { "Cannot close a running session" }
        executor.shutdown()
    }

    companion object {
        private var instance: EditSession? = null
        @Synchronized fun get(context: Context): EditSession =
            (context.applicationContext as? Owner)?.editSession
                ?: (instance ?: EditSession(context).also { instance = it })
    }
}
