package com.example.autoedit

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
    private val renderEngine: RenderEngine = NativeRenderEngine
) {
    internal interface Owner { val editSession: EditSession }
    internal fun interface RenderEngine {
        fun render(request: VeycadAutomaticEditor.Request): RenderSummary
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
    @Volatile private var cancelled = false
    @Volatile private var activeLease: File? = null

    data class State(
        val busy: Boolean = false,
        val saving: Boolean = false,
        val importing: Boolean = false,
        val progress: VeycadAutomaticEditor.Progress? = null,
        val entry: CompletedRenderStore.Entry? = null,
        val draft: Draft? = null,
        val error: String? = null
    )

    data class Draft(val files: List<File>, val names: List<String>, val music: BuiltInMusicCatalog.Selection)

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
        preferences.edit().putBoolean("running", false).commit()
    }

    fun render(
        source: File,
        secondary: File?,
        music: File,
        style: MontageStyleCatalog.Style,
        width: Int = 720,
        height: Int = 1_280,
        bitrate: Int = 5_000_000
    ) {
        if (state.value?.busy == true) return
        cancelled = false
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
                val edit = renderEngine.render(VeycadAutomaticEditor.Request(
                    context = app, sourceFile = source, secondarySourceFile = secondary,
                    musicFile = music, outputFile = output, style = style.directorStyle,
                    recipe = requireNotNull(style.recipe),
                    width = width,
                    height = height,
                    bitrate = bitrate,
                    jobLease = lease,
                    checkCancelled = { check(!cancelled) { "Монтаж отменён" } },
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
                CompletedRenderStore.publish(app.filesDir, output, summary)
            }
            wakeLock?.let { if (it.isHeld) it.release() }
            directory.deleteRecursively()
            activeLease = null
            preferences.edit().putBoolean("running", false).commit()
            main.post {
                mutableState.value = State(entry = result.getOrNull() ?: previous,
                    error = result.exceptionOrNull()?.message)
            }
        }
    }

    /** Cleanup waits for the worker to unwind; no files in use are deleted from the UI. */
    fun cancelRender() {
        if (state.value?.busy == true && state.value?.saving != true) {
            cancelled = true
            activeLease?.delete()
        }
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
