package com.veycad.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.lifecycle.LiveData
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Process-owned jobs never retain an Activity. Published files and interruption markers are durable. */
internal class EditSession internal constructor(
    context: Context,
    private val renderEngine: RenderEngine = NativeRenderEngine,
    private val sourceInspector: SourceInspector = SourceInspector { id, file, name, checkCancelled ->
        MediaInputInspector(checkCancelled).inspect(id, file, name)
    },
    private val coordinator: MediaWorkCoordinator = MediaWorkCoordinator.process,
    private val executor: java.util.concurrent.ExecutorService = Executors.newSingleThreadExecutor(),
    private val sourceStore: SourceDraftStore = SourceDraftStore(context.applicationContext.filesDir),
    private val selectMusic: (MontageStyleCatalog.Style) -> BuiltInMusicCatalog.Selection = {
        BuiltInMusicCatalog.select(context.applicationContext, it.id)
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
        val passedQualityGate: Boolean, val issues: List<String>, val productSummary: String? = null,
        val projectId: String? = null
    )
    private object NativeRenderEngine : RenderEngine {
        override fun render(request: VeycadAutomaticEditor.Request): RenderSummary {
            if (request.recipe == MontageStyleCatalog.Recipe.GALLERY_MONTAGE) return GalleryMvpRenderer.render(request)
            val result = VeycadAutomaticEditor.render(request)
            val projectId = if (request.recipe == MontageStyleCatalog.Recipe.CUSTOM_MUSIC)
                CustomMusicProjectHandoff.publish(request.context, result.winner.alternative.graph,
                    request.sources, request.musicFile,
                    ProjectExportSettings(request.width, request.height, HighQualityFramePlan.DEFAULT_FPS,
                        request.bitrate, 192_000), request.checkCancelled) else null
            return RenderSummary(result.winner.alternative.graph.clips.size,
                result.winner.acceptance.metrics.beatHitRate, result.passedQualityGate,
                result.winner.acceptance.issues.map { it.toString() }, projectId = projectId)
        }
    }
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val mediaWork = MediaWorkDispatch(coordinator, executor) { main.post(it) }
    private var recovered = false
    private var draftGeneration = 0L
    private var confirmedDraft: Draft? = null
    @Volatile private var sourceSelectionConfirmed = false
    @Volatile private var sourceGcPending = false
    private val sourceGcLock = Any()
    private val previouslyOwnedSources = mutableSetOf<String>()
    private val preferences = app.getSharedPreferences("edit_session", Context.MODE_PRIVATE)
    private val mutableState = ObserverSafeLiveData(State(
        entry = CompletedRenderStore.latest(app.filesDir),
        error = if (preferences.getBoolean("running", false))
            "Работа была прервана системой. Исходники и предыдущий монтаж сохранены; можно повторить." else null
    )) { failure ->
        LocalDiagnostics.record(app, "edit_session_observer_failed", mapOf("error" to (failure.message ?: "observer")))
    }
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
        val operationDetail: String? = null,
        val cleaning: Boolean = false,
        val draftGeneration: Long = 0,
        val cleanupReport: TemporaryFileCleanup.Report? = null,
        val cleanupScope: TemporaryFileCleanup.Scope? = null,
        val sourceSelectionConfirmed: Boolean = false,
        val exportOperationId: String? = null
    )

    data class Draft(val files: List<File>, val names: List<String>, val music: BuiltInMusicCatalog.Selection?)

    fun restoreDraft(style: MontageStyleCatalog.Style) {
        withMediaWork {
            val before = state.value ?: State()
            if (before.busy) {
                if (sourceSelectionConfirmed) publish(before.copy(draft = confirmedDraft))
                return
            }
            try {
                val restored = sourceStore.restore(legacySources())
                confirmedDraft = makeDraft(restored.snapshot, style)
                sourceSelectionConfirmed = confirmedDraft != null
                sourceGcPending = confirmedDraft != null
                publish(before.copy(draft = confirmedDraft, error = restored.warning ?: before.error))
            } catch (failure: Exception) {
                sourceSelectionConfirmed = false
                confirmedDraft = null
                publish(before.copy(draft = null, error = failure.message))
            }
        }
    }

    private fun legacySources(): SourceDraftStore.LegacyState {
        val prefs = app.getSharedPreferences("source_draft", Context.MODE_PRIVATE)
        return SourceDraftStore.LegacyState(prefs.getString("display_name", null),
            prefs.getString("display_name_2", null), prefs.getBoolean("cleanup_incomplete", false))
    }

    private fun makeDraft(snapshot: SourceDraftManifest.Snapshot, style: MontageStyleCatalog.Style): Draft? {
        if (snapshot.entries.isEmpty() ||
            (if (style.recipe == MontageStyleCatalog.Recipe.GALLERY_MONTAGE) snapshot.entries.size !in 1..GalleryImportPolicy.MAX_FILES
             else snapshot.entries.size != style.sourceCount)) return null
        val files = snapshot.entries.map { sourceStore.child(it.fileName) }
        val names = snapshot.entries.map { it.displayName }
        val retained = confirmedDraft
        val music = retained?.takeIf { it.files == files && it.names == names && it.music?.styleId == style.id }?.music
            ?: if (style.recipe == MontageStyleCatalog.Recipe.CUSTOM_MUSIC) null else selectMusic(style)
        return Draft(files, names, music)
    }

    private fun tryCollectSourceObsolete() {
        if (!sourceGcPending) return
        val lease = coordinator.tryBeginCleanup() ?: return
        lease.use {
            val owned = synchronized(sourceGcLock) { previouslyOwnedSources.toSet() }
            val errors = sourceStore.collectObsolete(it, owned)
            if (errors.isEmpty()) synchronized(sourceGcLock) {
                previouslyOwnedSources.removeAll(owned)
                sourceGcPending = false
            }
        }
    }

    /** Capture selection and rendering use the same owned worker and durable result publication. */
    fun prepareCapture(id: String, requestedTake: Int? = null, width: Int = 720, height: Int = 1_280,
        bitrate: Int = 5_000_000) {
        withMediaWork {

            if (state.value?.busy == true) return
            synchronized(cancelLock) { cancelled = false; publishing = false }
            val previous = state.value?.entry
            val previousDraft = state.value?.draft
            publish(State(busy = true, entry = previous, draft = previousDraft, operationDetail = "Подбираю подходящий дубль…"))
            LocalDiagnostics.record(app, "capture_selection_requested", mapOf("requested_take" to (requestedTake ?: 0).toString()))
            preferences.edit().putBoolean("running", true).commit()
            mediaWork.execute {
                var preparationWakeLock: PowerManager.WakeLock? = null
                val result = runCatching {
                    val power = app.getSystemService(PowerManager::class.java)
                    preparationWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${app.packageName}:capture-selection")
                        .apply { acquire(15 * 60 * 1000L) }
                    CaptureTakePreparation.prepare(app, id, requestedTake,
                        checkCancelled = { check(!cancelled) { "Подбор дубля отменён" } },
                        onProgress = { detail -> mediaWork.post {
                            publish(State(busy = true, entry = previous, draft = previousDraft, operationDetail = detail))
                        } })
                }
                preparationWakeLock?.let { if (it.isHeld) it.release() }
                mediaWork.post {
                    preferences.edit().putBoolean("running", false).commit()
                    if (cancelled) { publish(State(entry = previous, error = "Подбор дубля отменён")); return@post }
                    result.onSuccess { prepared ->
                        val style = MontageStyleCatalog.restore(prepared.session.styleId)
                        render(prepared.file, null, prepared.musicFile, style, width, height, bitrate,
                            captureSessionId = id, continuingCapturePreparation = true,
                            captureTakeOrdinal = requireNotNull(prepared.session.selectedTake),
                            captureAutoRecommendation = requestedTake == null)
                    }.onFailure {
                        CaptureDiagnostics.failure(app, CaptureDiagnostics.Stage.TAKE_SELECTION, it)
                        publish(State(entry = previous, error = it.message))
                    }
                }
            }
        }
    }

    fun importVideos(uris: List<android.net.Uri>, style: MontageStyleCatalog.Style) {
        withMediaWork {
            if (state.value?.busy == true) return
            val gallery = style.recipe == MontageStyleCatalog.Recipe.GALLERY_MONTAGE
            val unique = if (gallery) uris.toList() else uris.distinct()
            val original = state.value ?: State()
            if (if (gallery) !BuildConfig.GALLERY_MVP || unique.size !in 1..GalleryImportPolicy.MAX_FILES
                else style.sourceCount !in 1..2 || unique.size != style.sourceCount) {
                publish(original.copy(error = "Выберите ${style.sourceCount} видео"))
                return
            }
            val previousSnapshot = try {
                sourceStore.restore(legacySources()).snapshot
            } catch (failure: Exception) {
                // An invalid existing document can be replaced by a complete new selection.
                // Missing bootstrap (including a failed first write) must stop before staging.
                if (!File(app.filesDir, "source-draft/current.json").isFile) {
                    publish(original.copy(error = failure.message)); return
                }
                sourceSelectionConfirmed = false
                null
            }
            val previousDraft = try { previousSnapshot?.let { makeDraft(it, style) } }
            catch (failure: Exception) { publish(original.copy(error = failure.message)); return }
            confirmedDraft = previousDraft
            sourceSelectionConfirmed = previousDraft != null
            val before = original.copy(draft = previousDraft)
            synchronized(cancelLock) { cancelled = false; publishing = false }
            publish(before.copy(busy = true, importing = true, error = null))
            mediaWork.executeAfterRelease(::tryCollectSourceObsolete) {
                val result = try {
                    val inputs = unique.mapIndexed { index, uri ->
                        val name = app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                            null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Видео ${index + 1}"
                        SourceDraftStore.Input(name) {
                            val input = app.contentResolver.openInputStream(uri)
                                ?: throw java.io.IOException("Не удалось прочитать выбранный файл")
                            object : java.io.FilterInputStream(input) {
                                override fun read(): Int { check(!cancelled) { "Импорт отменён" }; return super.read() }
                                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                                    check(!cancelled) { "Импорт отменён" }
                                    return super.read(buffer, offset, length)
                                }
                            }
                        }
                    }
                    sourceStore.replace(inputs, if (gallery) unique.size else style.sourceCount) { files ->
                        if (gallery) {
                            val inspected = MediaSourceSet(files.mapIndexed { index, file ->
                                sourceInspector.inspect("gallery-$index", file, inputs[index].displayName) {
                                    check(!cancelled) { "Импорт отменён" }
                                }
                            })
                            GalleryImportPolicy.validate(inspected)
                            require(inspected.items.all { it.isBaselineInput }) { "Выберите MP4 H.264 без HDR" }
                        }
                        files.forEach { file ->
                            check(!cancelled) { "Импорт отменён" }
                            val metadata = SequentialBitmapDecoder.metadata(file)
                            require(metadata.durationUs > 0 && metadata.displayWidth > 0 && metadata.displayHeight > 0) {
                                "Выбранный файл не содержит доступного видео"
                            }
                        }
                        val music = if (style.recipe == MontageStyleCatalog.Recipe.CUSTOM_MUSIC) null else selectMusic(style)
                        synchronized(cancelLock) { check(!cancelled) { "Импорт отменён" }; publishing = true }
                        music
                    }
                } catch (failure: Exception) { SourceDraftStore.ReplaceResult.Rejected(failure) }
                if (result is SourceDraftStore.ReplaceResult.Published) {
                    synchronized(sourceGcLock) { previouslyOwnedSources += previousSnapshot?.entries.orEmpty().map { it.fileName } }
                    sourceGcPending = true
                }
                mediaWork.postAfterRelease(::tryCollectSourceObsolete) {
                    val next = when (result) {
                        is SourceDraftStore.ReplaceResult.Published -> {
                            confirmedDraft = Draft(result.snapshot.entries.map { sourceStore.child(it.fileName) },
                                result.snapshot.entries.map { it.displayName }, result.prepared)
                            sourceSelectionConfirmed = true
                            val mirrorFailure = runCatching {
                                val names = result.snapshot.entries.map { it.displayName }
                                check(app.getSharedPreferences("source_draft", Context.MODE_PRIVATE).edit()
                                    .remove("cleanup_incomplete").putString("display_name", names[0])
                                    .putString("display_name_2", names.getOrNull(1)).commit()) { "Не удалось обновить подписи совместимости" }
                            }.exceptionOrNull()
                            before.copy(draft = confirmedDraft, error = (result.warnings + listOfNotNull(mirrorFailure?.message))
                                .takeIf { it.isNotEmpty() }?.joinToString("; "))
                        }
                        is SourceDraftStore.ReplaceResult.Rejected -> before.copy(error = result.failure.message)
                        is SourceDraftStore.ReplaceResult.Uncertain -> {
                            sourceSelectionConfirmed = false
                            confirmedDraft = null
                            before.copy(draft = null, error = "Не удалось подтвердить выбор видео. Откройте экран заново или выберите видео повторно.")
                        }
                    }
                    publish(next)
                    synchronized(cancelLock) { publishing = false }
                }
            }
        }
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
        captureAutoRecommendation: Boolean = false,
        musicStartUs: Long = 0L,
        exportOperationId: String? = null
    ) {
        renderResolved({ checkCancelled ->
            require(secondary == null || secondary.canonicalPath != source.canonicalPath)
            MediaSourceSet(listOfNotNull(source, secondary).mapIndexed { index, file ->
                sourceInspector.inspect("legacy-$index", file, file.name, checkCancelled)
            })
        }, music, style, width, height, bitrate, captureSessionId, continuingCapturePreparation,
            captureTakeOrdinal, captureAutoRecommendation, musicStartUs, exportOperationId)
    }

    fun render(
        files: List<File>,
        music: File,
        style: MontageStyleCatalog.Style,
        width: Int = 720,
        height: Int = 1_280,
        bitrate: Int = 5_000_000,
        exportOperationId: String? = null
    ) {
        val snapshot = files.toList()
        renderResolved({ checkCancelled ->
            MediaSourceSet(snapshot.mapIndexed { index, file ->
                sourceInspector.inspect("gallery-$index", file, file.name, checkCancelled)
            })
        }, music, style, width, height, bitrate, null, false, null, false, exportOperationId = exportOperationId)
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
        captureAutoRecommendation: Boolean = false,
        exportOperationId: String? = null
    ) = renderResolved({ checkCancelled -> checkCancelled(); sources }, music, style, width, height,
        bitrate, captureSessionId, continuingCapturePreparation, captureTakeOrdinal, captureAutoRecommendation,
        exportOperationId = exportOperationId)

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
        captureAutoRecommendation: Boolean,
        musicStartUs: Long = 0L,
        exportOperationId: String? = null
    ) {
        withMediaWork {
            require(captureSessionId == null || captureTakeOrdinal != null)
            if (state.value?.busy == true && !(continuingCapturePreparation && captureSessionId != null &&
                state.value?.operationDetail != null)) return
            synchronized(cancelLock) { cancelled = false; publishing = false }
            val previous = state.value?.entry
            val previousDraft = state.value?.draft
            preferences.edit().putBoolean("running", true).commit()
            publish(State(busy = true, entry = previous, draft = previousDraft,
                progress = VeycadAutomaticEditor.Progress(VeycadAutomaticEditor.Stage.ANALYSING_VIDEO)))
            mediaWork.execute {
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
                    if (captureSessionId == null && sources.items.any { it.file.parentFile?.canonicalFile == sourceStore.root.canonicalFile }) {
                        require(sourceSelectionConfirmed && confirmedDraft?.files == sources.items.map { it.file }) {
                            "Выбор исходников не подтверждён. Выберите видео заново."
                        }
                    }
                    val edit = renderEngine.render(VeycadAutomaticEditor.Request(
                        context = app, sources = sources,
                        musicFile = music, outputFile = output, style = style.directorStyle,
                        recipe = requireNotNull(style.recipe),
                        musicStartUs = musicStartUs,
                        width = width,
                        height = height,
                        bitrate = bitrate,
                        jobLease = lease,
                        checkCancelled = checkCancelled,
                        onProgress = { progress ->
                            check(!cancelled) { "Монтаж отменён" }
                            mediaWork.post { publish(State(busy = true, entry = previous, draft = previousDraft, progress = progress)) }
                        }
                    ))
                    check(!cancelled) { "Монтаж отменён" }
                    val warning = if (edit.passedQualityGate) "проверено" else
                        "готово с предупреждением QA: ${edit.issues.joinToString()}"
                    val summary = edit.productSummary ?: "${style.title} · ${edit.clipCount} фрагментов · " +
                        "бит ${(edit.beatHitRate * 100).toInt()}% · $warning"
                    synchronized(cancelLock) {
                        check(!cancelled) { "Монтаж отменён" }
                        publishing = true
                    }
                    mediaWork.post { publish(State(busy = true, publishing = true,
                        entry = previous, operationDetail = "Сохраняю готовый эдит…")) }
                    val link = captureSessionId?.let { CompletedRenderStore.CaptureLink(it,
                        requireNotNull(captureTakeOrdinal), captureAutoRecommendation) }
                    val published = CompletedRenderStore.publish(app.filesDir, output, summary, link, projectId = edit.projectId)
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
                mediaWork.post {
                    val completed = result.getOrNull()
                    publish(State(entry = completed?.first ?: previous, draft = previousDraft,
                        exportOperationId = if (completed != null) exportOperationId else null,
                        error = result.exceptionOrNull()?.let {
                            MontageFailurePresentation.message(it, style.sourceCount)
                        } ?: completed?.second?.let {
                            "Эдит сохранён. Связь с дублем восстановится при следующем запуске приложения."
                        }))
                }
            }
        }
    }

    /** Cleanup waits for the worker to unwind; no files in use are deleted from the UI. */
    fun cancelRender(): Boolean = synchronized(cancelLock) {
        if (state.value?.busy != true || state.value?.saving == true ||
            publishing || cancelled) return@synchronized false
        cancelled = true
        activeLease?.delete()
        true
    }

    fun clearTemporaryFiles(): Boolean = cleanup(TemporaryFileCleanup.Scope.ALL_TEMPORARY)
    fun clearDraft(): Boolean = cleanup(TemporaryFileCleanup.Scope.SOURCE_DRAFT)

    private fun cleanup(scope: TemporaryFileCleanup.Scope): Boolean {
        val before = state.value ?: State()
        if (before.busy) { publish(before.copy(error = MediaWorkCoordinator.BUSY_MESSAGE)); return false }
        val lease = coordinator.tryBeginCleanup()
            ?: run { publish(before.copy(error = MediaWorkCoordinator.BUSY_MESSAGE)); return false }
        try {
            publish(before.copy(busy = true, cleaning = true, error = null, cleanupReport = null))
            // Cleanup owns exclusive admission, so it must not acquire an ordinary work lease.
            executor.execute {
                var touchedDraft = false
                val result = runCatching {
                    val draftPrefs = app.getSharedPreferences("source_draft", Context.MODE_PRIVATE)
                    sourceStore.invalidate(lease)
                    touchedDraft = true
                    sourceSelectionConfirmed = false
                    confirmedDraft = null
                    // A failed marker write must not resume an older GC on the next restore.
                    sourceGcPending = false
                    synchronized(sourceGcLock) { previouslyOwnedSources.clear() }
                    check(draftPrefs.edit().putBoolean("cleanup_incomplete", true).commit()) {
                        "Не удалось сохранить состояние очистки"
                    }
                    val report = TemporaryFileCleanup.clear(app.filesDir, app.cacheDir, scope, lease)
                    if (report.draft == TemporaryFileCleanup.DraftDisposition.REMOVED)
                        check(draftPrefs.edit().clear().commit()) { "Не удалось завершить очистку черновика" }
                    report
                }
                if (result.exceptionOrNull() is SourceDraftStore.UncertainInvalidation) {
                    sourceSelectionConfirmed = false
                    confirmedDraft = null
                    sourceGcPending = false
                }
                if (!main.post {
                    try {
                        if (touchedDraft) draftGeneration++
                        val report = result.getOrNull()
                        publish(State(entry = before.entry, draft = if (touchedDraft || !sourceSelectionConfirmed) null else before.draft,
                            cleanupReport = report, cleanupScope = scope, error = result.exceptionOrNull()?.message
                                ?: report?.takeIf { it.failures.isNotEmpty() }?.let {
                                    "Часть временных файлов не удалена. Повторите очистку позже."
                                }))
                    } finally { lease.close() }
                }) lease.close()
            }
        } catch (failure: Throwable) {
            lease.close()
            publish(before.copy(error = failure.message ?: "Не удалось начать очистку"))
            return false
        }
        return true
    }

    fun save(entry: CompletedRenderStore.Entry, destination: android.net.Uri? = null) {
        withMediaWork {

            if (state.value?.busy == true || entry.saved) return
            preferences.edit().putBoolean("running", true).commit()
            publish(State(busy = true, saving = true, entry = entry, draft = confirmedDraft))
            mediaWork.execute {
                val result = runCatching {
                    if (destination == null) saveToGallery(entry.file) else {
                        app.contentResolver.openOutputStream(destination)?.use { output ->
                            entry.file.inputStream().use { it.copyTo(output) }
                        } ?: error("Не удалось открыть место сохранения")
                    }
                    CompletedRenderStore.markSaved(entry)
                    entry.copy(saved = true)
                }
                preferences.edit().putBoolean("running", false).commit()
                mediaWork.post { publish(State(entry = result.getOrNull() ?: entry, draft = confirmedDraft,
                    error = result.exceptionOrNull()?.message)) }
            }
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

    private fun publish(value: State) {
        mutableState.value = value.copy(draftGeneration = draftGeneration, sourceSelectionConfirmed = sourceSelectionConfirmed)
    }

    private fun recoverIfIdle() {
        tryCollectSourceObsolete()
        if (recovered) return
        val lease = coordinator.tryBeginCleanup() ?: return
        lease.use {
            TemporaryFileCleanup.clear(app.filesDir, app.cacheDir, TemporaryFileCleanup.Scope.TRANSIENT_RENDER, it)
            if (CompletedRenderStore.recoverCaptureLinks(app.filesDir).isNotEmpty())
                LocalDiagnostics.record(app, "capture_result_link_recovery_failed")
            preferences.edit().putBoolean("running", false).commit()
            recovered = true
        }
    }

    private inline fun withMediaWork(action: () -> Unit) {
        recoverIfIdle()
        val before = state.value ?: State()
        val running = preferences.getBoolean("running", false)
        val lease = coordinator.tryBeginWork()
            ?: run { publish(before.copy(error = MediaWorkCoordinator.CLEANING_MESSAGE)); return }
        try { action() }
        catch (failure: java.util.concurrent.RejectedExecutionException) {
            preferences.edit().putBoolean("running", running).commit()
            publish(before.copy(error = failure.message ?: "Не удалось начать обработку"))
        } catch (failure: Throwable) {
            preferences.edit().putBoolean("running", running).commit()
            runCatching { publish(before) }
            throw failure
        } finally { lease.close(); runCatching { tryCollectSourceObsolete() } }
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
