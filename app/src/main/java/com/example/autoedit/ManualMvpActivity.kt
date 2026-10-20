package com.veycad.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import android.widget.FrameLayout
import com.google.android.material.button.MaterialButton
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.FileProvider
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.UUID
import java.io.File
import java.io.FileOutputStream

/** Small saved-project editor. The store and common commands own every revision and history entry. */
class ManualMvpActivity : AppCompatActivity() {
    private val jobs = Executors.newSingleThreadExecutor()
    private lateinit var store: HybridProjectStore
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private var projectId: String? = null
    private var clipId: String? = null
    private var busy = false
    private var generation = 0
    private var video: VideoView? = null
    private var previewFile: File? = null
    private var previewProjectId: String? = null
    private var previewRevisionId: Long? = null
    private var playbackGeneration = 0
    private var newProjectFps = 30
    private var renderJob: Future<*>? = null
    private var refreshAfterStop = false
    private val disabledControls = java.util.IdentityHashMap<View, Boolean>()
    private val playbackHandler = Handler(Looper.getMainLooper())
    private var videoSeek: SeekBar? = null
    private var playbackButton: Button? = null
    private val playbackTicker = object : Runnable {
        override fun run() {
            val movie = video ?: return
            val seek = videoSeek ?: return
            if (!seek.isPressed && movie.duration > 0) {
                seek.progress = (movie.currentPosition.toLong() * seek.max / movie.duration).toInt()
            }
            playbackButton?.text = if (movie.isPlaying) "Пауза" else "Воспроизвести"
            playbackHandler.postDelayed(this, 250)
        }
    }
    private val videoPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importVideos(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        store = HybridProjectStore(filesDir)
        val prefs = getSharedPreferences("manual_mvp", MODE_PRIVATE)
        projectId = savedInstanceState?.getString("project") ?: prefs.getString("project", null)
        clipId = savedInstanceState?.getString("clip") ?: prefs.getString("clip", null)
        newProjectFps = savedInstanceState?.getInt("newProjectFps") ?: prefs.getInt("newProjectFps", 30)

        val scroll = ScrollView(this).apply {
            setFillViewport(true)
            clipToPadding = false
            setBackgroundColor(getColor(R.color.background))
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(32))
        }
        scroll.addView(content)
        // Insets belong outside the scrolling viewport, otherwise scrollTo can leave
        // controls hidden under its bottom padding (including gesture navigation).
        val viewport = FrameLayout(this).apply {
            setBackgroundColor(getColor(R.color.background))
            addView(scroll, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(viewport)
        ViewCompat.setOnApplyWindowInsetsListener(viewport) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(viewport)
        title = "Довести монтаж"
        status = label("Открываю проекты…", 14)
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("project", projectId)
        outState.putString("clip", clipId)
        outState.putInt("newProjectFps", newProjectFps)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        generation++
        renderJob?.cancel(true)
        jobs.shutdownNow()
        stopPlayback()
        previewFile?.delete()
        super.onDestroy()
    }

    override fun onStop() {
        playbackHandler.removeCallbacks(playbackTicker)
        if (renderJob != null) {
            renderJob?.cancel(true)
            renderJob = null
            generation++
            setBusy(false, "Рендер отменён. Черновик сохранён.")
            refreshAfterStop = true
        }
        video?.pause()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        if (video != null) playbackHandler.post(playbackTicker)
        if (refreshAfterStop) {
            refreshAfterStop = false
            refresh("Рендер остановлен. Черновик сохранён.")
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    private fun label(text: String, size: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = size.toFloat()
        setPadding(0, dp(8), 0, dp(8))
        setTextColor(getColor(if (size >= 20) R.color.text_primary else R.color.text_secondary))
        if (size >= 20) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    private fun button(text: String, primary: Boolean = false, action: () -> Unit): Button = MaterialButton(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        minHeight = dp(52)
        minimumHeight = dp(52)
        cornerRadius = dp(18)
        insetTop = 0
        insetBottom = 0
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(getColor(R.color.stroke))
        val states = arrayOf(intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_selected), intArrayOf())
        backgroundTintList = ColorStateList(states, intArrayOf(getColor(R.color.surface_high),
            getColor(R.color.accent_soft), getColor(if (primary) R.color.neon else R.color.surface)))
        setTextColor(ColorStateList(states, intArrayOf(getColor(R.color.text_muted),
            getColor(R.color.text_primary), getColor(R.color.text_primary))))
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        setOnClickListener { if (!busy) action() }
    }

    private fun row(vararg buttons: Button): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        buttons.forEachIndexed { index, button ->
            addView(button, LinearLayout.LayoutParams(0, -1, 1f).apply {
                if (index > 0) marginStart = dp(8)
            })
        }
        // Both controls grow together when labels wrap or system font size increases.
        minimumHeight = dp(52)
    }

    private fun panel(title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(16))
        setBackgroundResource(R.drawable.panel_surface)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }
        addView(label(title, 20))
        content.addView(this)
    }

    private fun header(message: String) {
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("VEYKAD  •", 13).apply {
                setTextColor(getColor(R.color.text_primary))
                letterSpacing = .20f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(button("‹ Назад") { finish() }, LinearLayout.LayoutParams(-2, -2))
        })
        content.addView(label("Довести монтаж", 30))
        status = label(message, 14).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        content.addView(status)
    }

    private fun projectName(project: HybridProject): String =
        project.selectedVideos?.firstOrNull()?.displayName
            ?.substringBeforeLast('.')?.take(48)?.takeIf { it.isNotBlank() } ?: "Мой монтаж"

    private fun rememberSelection() {
        getSharedPreferences("manual_mvp", MODE_PRIVATE).edit()
            .putString("project", projectId).putString("clip", clipId).apply()
    }

    private fun setBusy(value: Boolean, message: String? = null) {
        busy = value
        if (value) {
            fun disable(view: View) {
                if (view is Button || view is SeekBar) {
                    disabledControls.putIfAbsent(view, view.isEnabled)
                    view.isEnabled = false
                }
                if (view is ViewGroup) repeat(view.childCount) { disable(view.getChildAt(it)) }
            }
            disable(content)
        } else {
            disabledControls.forEach { (view, enabled) -> view.isEnabled = enabled }
            disabledControls.clear()
        }
        content.descendantFocusability = if (value) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
        content.isEnabled = !value
        if (message != null) status.text = message
    }

    private fun refresh(message: String? = null) {
        if (busy) return
        setBusy(true, "Открываю сохранённый монтаж…")
        val requested = projectId
        val sequence = ++generation
        jobs.execute {
            val result = runCatching {
                val projects = store.list()
                val selected = requested?.let { id -> projects.firstOrNull { it.id == id } }
                projects to selected
            }
            runOnUiThread {
                if (isDestroyed || sequence != generation) return@runOnUiThread
                setBusy(false)
                result.fold(
                    onSuccess = { (projects, selected) -> showProjects(projects, selected, message) },
                    onFailure = { error -> showError("Не удалось открыть проекты: ${error.message.orEmpty()}") }
                )
            }
        }
    }

    private fun showError(message: String) {
        stopPlayback()
        content.removeAllViews()
        header(message)
        content.addView(button("Повторить") { refresh() })
    }

    private fun showProjects(projects: List<HybridProject>, selected: HybridProject?, message: String?) {
        stopPlayback()
        content.removeAllViews()
        header(message ?: if (selected != null) "Правки сохраняются автоматически." else
            "Выберите сохранённый монтаж или добавьте свои видео.")
        if (selected != null) showEditor(selected) else {
            projectId = null
            clipId = null
            rememberSelection()
        }
        panel("Новый монтаж").apply {
            addView(label("1–2 ваших видео · базовая сборка без анализа ИИ", 14))
            addView(row(
                button("30 кадр/с") { chooseFps(30) }.apply {
                    isSelected = newProjectFps == 30
                },
                button("60 кадр/с") { chooseFps(60) }.apply {
                    isSelected = newProjectFps == 60
                }
            ))
            addView(button("Выбрать видео для базового монтажа", primary = selected == null) {
                videoPicker.launch(arrayOf("video/*"))
            })
        }
        panel("Сохранённые проекты").apply {
            if (projects.isEmpty()) addView(label("Пока нет проектов ручной доводки.", 14))
            projects.forEachIndexed { index, project ->
                addView(button("${index + 1}. ${projectName(project)} · ${project.fps} кадр/с") {
                    projectId = project.id
                    clipId = null
                    rememberSelection()
                    refresh()
                }.apply {
                    isSelected = project.id == selected?.id
                    contentDescription = "Открыть проект ${index + 1}: ${projectName(project)}"
                })
            }
        }
    }

    private fun showEditor(project: HybridProject) {
        val export = previewSettings(project.fps)
        val editable = runCatching { EditableMontageProject(project, export) }.getOrElse { error ->
            content.addView(label("Этот проект нельзя довести: ${error.message.orEmpty()}", 14))
            return
        }
        val clips = editable.current.clips
        val selected = clips.firstOrNull { it.id == clipId } ?: clips.first()
        if (clipId != selected.id) {
            clipId = selected.id
            rememberSelection()
        }
        val previewPanel = panel(projectName(project))
        previewPanel.addView(label("Версия ${project.current.id} · ${project.fps} кадр/с · черновик сохранён", 14))
        val currentPreview = previewFile?.takeIf { previewProjectId == project.id &&
            previewRevisionId == project.current.id && it.isFile && it.length() > 0L }
        val exported = project.exports.lastOrNull { it.revisionId == project.current.id }?.let {
            File(File(filesDir, "completed-renders"), it.fileName).takeIf { file -> file.isFile && file.length() > 0L }
        }
        val playable = currentPreview ?: exported
        if (playable != null) {
            previewPanel.addView(label(if (currentPreview != null) "Предпросмотр этой версии · полный рендер 720p" else
                "Сохранённая версия · воспроизведение и перемотка", 14))
            showVideo(playable, previewPanel)
        } else previewPanel.addView(label("Обновите предпросмотр, чтобы увидеть текущие правки.", 14))
        if (exported != null) previewPanel.addView(button("Поделиться сохранённой версией") { share(exported) })
        if (unsupportedReason(project, export) == null) {
            previewPanel.addView(button("Просмотреть · полный рендер 720p") { renderVersion(false) })
            previewPanel.addView(button("Сохранить новую версию · 1080p", primary = true) { renderVersion(true) })
        }
        unsupportedReason(project, export)?.let { reason ->
            previewPanel.addView(label("Просмотр и экспорт недоступны: $reason", 14))
        }
        val timeline = panel("Порядок клипов")
        clips.forEachIndexed { index, clip ->
            val marker = if (clip.id == selected.id) "● " else "○ "
            timeline.addView(button("$marker${index + 1}. ${clip.origin.id} · ${clip.visible.count} кадров") {
                clipId = clip.id
                rememberSelection()
                refresh()
            }.apply { isSelected = clip.id == selected.id })
        }
        val controls = panel("Правки клипа ${selected.origin.id}")
        controls.addView(label("Подрезка по одному кадру · ${project.fps} кадр/с", 14))
        controls.addView(row(
            button("Начало −1") { edit(TimelineCommand.Trim(selected.id, ClipEdge.START, selected.visible.start - 1)) },
            button("Начало +1") { edit(TimelineCommand.Trim(selected.id, ClipEdge.START, selected.visible.start + 1)) }
        ))
        controls.addView(row(
            button("Конец −1") { edit(TimelineCommand.Trim(selected.id, ClipEdge.END, selected.visible.endExclusive - 1)) },
            button("Конец +1") { edit(TimelineCommand.Trim(selected.id, ClipEdge.END, selected.visible.endExclusive + 1)) }
        ))
        val index = clips.indexOf(selected)
        controls.addView(row(
            button("Выше") { edit(TimelineCommand.Move(selected.id, index - 1)) }.apply { isEnabled = index > 0 },
            button("Ниже") { edit(TimelineCommand.Move(selected.id, index + 1)) }.apply { isEnabled = index < clips.lastIndex }
        ))
        if (index > 0) {
            controls.addView(button("Обычная склейка перед клипом") {
                edit(TimelineCommand.SetTransition(selected.id, MontageGraph.Transition.HARD_CUT))
            })
        }
        val flashes = editable.current.effects.filter {
            it.originalOverlay?.overlayKind == MontageGraph.OverlayKind.FLASH
        }
        if (flashes.isEmpty()) controls.addView(label("В этом монтаже нет вспышки для переноса.", 14))
        flashes.forEach { flash ->
            controls.addView(label("Вспышка на стыке", 14))
            val boundary = flash.anchor as? EffectAnchor.Boundary
            val boundaryIndex = clips.indexOfFirst { it.id == boundary?.incomingClipId }
            val nextValid = boundaryIndex >= 0 && boundaryIndex < clips.lastIndex
            controls.addView(row(
                button(if (flash.enabled) "Отключить вспышку" else "Включить вспышку") {
                    edit(TimelineCommand.SetFlashEnabled(flash.id, !flash.enabled))
                },
                button("На следующий стык") { edit(TimelineCommand.MoveFlashToNext(flash.id)) }
                    .apply { isEnabled = nextValid }
            ))
        }
        val history = panel("История правок")
        history.addView(row(
            button("Отменить") { common(ProjectCommand.Undo) }.apply { isEnabled = project.undo.isNotEmpty() },
            button("Повторить") { common(ProjectCommand.Redo) }.apply { isEnabled = project.redo.isNotEmpty() }
        ))
        history.addView(button(if (project.original.graph.metadata.generator == "manual-basic-test")
            "Вернуть базовый монтаж" else "Вернуть автосборку") { edit(TimelineCommand.RestoreBaseline) })
    }

    private fun edit(command: TimelineCommand) = change { project ->
        val editable = EditableMontageProject(project, previewSettings(project.fps))
        when (val prepared = MontageTimelineEditor.prepare(editable, command)) {
            is TimelinePreparation.Prepared -> HybridEditCommands.apply(project,
                ProjectCommand.CommitRevision(prepared.candidate))
            is TimelinePreparation.CoreRestoreMontage -> HybridEditCommands.apply(project, prepared.command)
            TimelinePreparation.Unchanged -> project
            is TimelinePreparation.Rejected -> throw HybridEditRejected(prepared.reason)
        }
    }

    private fun common(command: ProjectCommand) = change { HybridEditCommands.apply(it, command) }

    private fun renderVersion(publish: Boolean) {
        if (busy) return
        val id = projectId ?: return
        invalidatePreview()
        setBusy(true, if (publish) "Рендерю и сохраняю новую версию…" else
            "Обновляю предпросмотр… Полный рендер может занять время.")
        val sequence = ++generation
        renderJob = jobs.submit {
            var output: File? = null
            var linked = false
            var published: CompletedRenderStore.Entry? = null
            val result = runCatching {
                val installed = store.load(id)
                val settings = if (publish) exportSettings(installed.fps) else previewSettings(installed.fps)
                unsupportedReason(installed, settings)?.let { throw IllegalArgumentException(it) }
                val assets = ProjectAssetStore(store.directory(id))
                val musicAsset = installed.assets.single { it.id == installed.current.music.assetId }
                val musicFile = assets.resolve(musicAsset)
                require(musicFile.isFile) { "Сохранённый музыкальный файл отсутствует" }
                val checkCancelled = {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("Рендер отменён")
                }
                checkCancelled()
                val prepared = HybridPreviewPreparation.prepare(installed, settings, assets)
                require(prepared.graph.audioTrack?.sourceId == musicAsset.id &&
                    prepared.graph.audioTrack?.gain == 1f) {
                    "Сохранённая музыка не совпадает с планом рендера"
                }
                val directory = File(cacheDir, "manual-renders").apply { mkdirs() }
                val target = File(directory, "manual-${UUID.randomUUID()}.mp4")
                output = target
                val request = PreparedHybridExportAdapter.request(prepared, installed, target,
                    audioFile = musicFile, context = applicationContext, checkCancelled = checkCancelled)
                val frames = MediaCodecSpeedRampRenderer.render(request)
                checkCancelled()
                require(frames == prepared.framePlan.frames.size && target.isFile && target.length() > 0L) {
                    "Рендер не сохранил все кадры текущей версии"
                }
                requireVideoShape(target, settings)
                if (publish) {
                    val latest = store.load(id)
                    require(latest.current.id == installed.current.id) { "Проект изменился во время экспорта" }
                    checkCancelled()
                    val entry = CompletedRenderStore.publish(filesDir, target,
                        "Ручная версия ${installed.current.id} · базовый монтаж")
                    published = entry
                    checkCancelled()
                    store.save(latest.copy(exports = latest.exports +
                        ProjectExportRef(installed.current.id, entry.file.name, settings)),
                        expectedRevisionId = latest.current.id)
                    linked = true
                    target.delete()
                    null to installed.current.id
                } else target to installed.current.id
            }
            if (result.isFailure || publish) output?.delete()
            if (!linked) published?.let { entry ->
                entry.file.delete()
                File(entry.file.parentFile, "${entry.file.name}.properties").delete()
            }
            runOnUiThread {
                if (isDestroyed || sequence != generation) {
                    if (!publish) result.getOrNull()?.first?.delete()
                    return@runOnUiThread
                }
                renderJob = null
                setBusy(false)
                result.fold(onSuccess = { (file, revisionId) ->
                    if (file != null) {
                        previewFile = file
                        previewProjectId = id
                        previewRevisionId = revisionId
                    }
                    refresh(if (publish) "Новая версия сохранена. Её можно воспроизвести и отправить." else
                        "Предпросмотр готов для текущей версии.")
                }, onFailure = { error ->
                    refresh("Рендер не завершился: ${error.message ?: "повторите попытку"}")
                })
            }
        }
    }

    private fun requireVideoShape(file: File, settings: ProjectExportSettings) {
        val metadata = MediaMetadataRetriever()
        try {
            metadata.setDataSource(file.absolutePath)
            val width = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            require(width == settings.width && height == settings.height) {
                "Размер готового видео не совпадает с выбранным пресетом"
            }
        } finally { metadata.release() }
    }

    private fun showVideo(file: File, parent: LinearLayout) {
        val frame = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            contentDescription = "Предпросмотр монтажа"
        }
        val movie = VideoView(this).apply {
            isFocusable = false
            isFocusableInTouchMode = false
        }
        video = movie
        // VideoView measures to its video aspect ratio. Center it inside a full-width
        // container rather than leaving portrait playback against the left edge.
        frame.addView(movie, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        parent.addView(frame, LinearLayout.LayoutParams(-1, dp(300)).apply { topMargin = dp(8) })
        val seek = SeekBar(this).apply {
            max = 1_000
            contentDescription = "Позиция в предпросмотре"
            progressTintList = ColorStateList.valueOf(getColor(R.color.neon))
            thumbTintList = progressTintList
            minimumHeight = dp(48)
        }
        videoSeek = seek
        parent.addView(seek, LinearLayout.LayoutParams(-1, dp(48)))
        val playback = button("Воспроизвести") {
            if (movie.isPlaying) movie.pause() else movie.start()
        }
        playbackButton = playback
        parent.addView(playback)
        val playbackToken = ++playbackGeneration
        movie.setOnPreparedListener { player ->
            if (playbackToken != playbackGeneration) return@setOnPreparedListener
            player.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            seek.progress = 0
            movie.seekTo(1)
            movie.start()
            playbackHandler.removeCallbacks(playbackTicker)
            playbackHandler.post(playbackTicker)
        }
        movie.setOnErrorListener { _, _, _ ->
            if (playbackToken == playbackGeneration) status.text = "Видео не открылось. Повторите экспорт."
            true
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) = Unit
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) {
                val duration = movie.duration
                if (duration > 0) movie.seekTo((duration.toLong() * seek.progress / seek.max).toInt())
            }
        })
        movie.setVideoPath(file.absolutePath)
    }

    private fun chooseFps(fps: Int) {
        if (newProjectFps == fps) return
        newProjectFps = fps
        getSharedPreferences("manual_mvp", MODE_PRIVATE).edit().putInt("newProjectFps", fps).apply()
        refresh()
    }

    private fun importVideos(uris: List<Uri>) {
        if (busy) return
        if (uris.size !in 1..2) {
            status.text = "Выберите одно или два видео для базового монтажа."
            return
        }
        setBusy(true, "Копирую и проверяю выбранные видео…")
        val sequence = ++generation
        val fps = newProjectFps
        jobs.execute {
            val copies = ArrayList<File>()
            val result = runCatching {
                val inspector = MediaInputInspector {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                }
                val inputs = uris.mapIndexed { index, uri ->
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val name = videoName(uri, index)
                    val copy = File.createTempFile("manual-input-", ".tmp", cacheDir)
                    copies += copy
                    requireNotNull(contentResolver.openInputStream(uri)) { "Не удалось открыть $name" }.use { input ->
                        FileOutputStream(copy).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                        }
                    }
                    val (source, metadata) = inspector.inspectForProject("manual-selected-$index", copy, name)
                    source to metadata.geometry.pixelAspectRatio
                }
                createBasicProject(inputs, fps)
            }
            copies.forEach { it.delete() }
            runOnUiThread {
                if (isDestroyed || sequence != generation) return@runOnUiThread
                setBusy(false)
                result.fold(onSuccess = { id ->
                    projectId = id
                    clipId = null
                    rememberSelection()
                    refresh("Базовый монтаж создан. Можно довести его и сохранить новую версию.")
                }, onFailure = { error ->
                    status.text = "Не удалось создать базовый монтаж: ${error.message.orEmpty()}"
                })
            }
        }
    }

    private fun videoName(uri: Uri, index: Int): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Видео ${index + 1}"

    private fun stopPlayback() {
        playbackHandler.removeCallbacks(playbackTicker)
        videoSeek = null
        playbackButton = null
        playbackGeneration++
        video?.apply {
            setOnPreparedListener(null)
            setOnErrorListener(null)
            stopPlayback()
        }
        video = null
    }

    private fun share(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Поделиться монтажом"))
    }

    private fun invalidatePreview() {
        stopPlayback()
        previewFile?.delete()
        previewFile = null
        previewProjectId = null
        previewRevisionId = null
    }

    /** The inspector supplies physical facts; this only publishes through existing asset/import APIs. */
    private fun createBasicProject(inspected: List<Pair<MediaSource, Float>>, fps: Int): String {
        require(inspected.size in 1..2)
        ManualMvpBasicGraph.validateSources(inspected.map { it.first })
        val id = "basic-${UUID.randomUUID()}"
        val assets = ProjectAssetStore(store.directory(id))
        val sources = inspected.map { (source, aspect) ->
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            assets.importVideo(source, aspect)
        }
        val selections = inspected.mapIndexed { index, (source, _) ->
            SelectedVideo.fromInspected(source.copy(id = "$id-$index"), sources[index], SourceOwnership.IMPORTED)
        }
        val style = if (sources.size == 1) MontageStyleCatalog.heartbeat else MontageStyleCatalog.dualityLoop
        val musicFile = BuiltInMusicCatalog.select(applicationContext, style.id).file
        val retriever = MediaMetadataRetriever()
        val musicDurationUs = try {
            retriever.setDataSource(musicFile.absolutePath)
            Math.multiplyExact(requireNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION))
                .toLong(), 1_000L)
        } finally { retriever.release() }
        val music = assets.import(musicFile, ProjectAsset.Kind.AUDIO, musicDurationUs)
        val graph = ManualMvpBasicGraph.create(inspected.map { it.first }, music.id)
        val project = EditableMontageImporter.create(id, graph, requireNotNull(style.recipe),
            previewSettings(fps), sources, music, null, selections).shared
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        store.create(project)
        return id
    }

    private fun change(apply: (HybridProject) -> HybridProject) {
        if (busy) return
        val id = projectId ?: return
        invalidatePreview()
        setBusy(true, "Сохраняю правку…")
        val sequence = ++generation
        jobs.execute {
            val result = runCatching {
                val previous = store.load(id)
                val updated = apply(previous)
                if (updated === previous || updated == previous) "Без изменений" else {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    store.save(updated, expectedRevisionId = previous.current.id)
                    "Правка сохранена · версия ${updated.current.id}"
                }
            }
            runOnUiThread {
                if (isDestroyed || sequence != generation) return@runOnUiThread
                setBusy(false)
                result.fold(onSuccess = { refresh(it) }, onFailure = { error ->
                    status.text = if (error is HybridEditRejected || error is IllegalArgumentException)
                        "Правка отклонена: ${error.message.orEmpty()}" else
                        "Не удалось сохранить правку: ${error.message.orEmpty()}"
                })
            }
        }
    }

    private fun previewSettings(fps: Int) = ProjectExportSettings(720, 1280, fps, 5_000_000, 192_000)

    private fun unsupportedReason(project: HybridProject, settings: ProjectExportSettings): String? {
        val revision = project.current
        val graph = revision.graph
        if (project.selectedVideos == null) return "у проекта нет проверенного порядка видео"
        if (graph.frameAttachments.frames.isNotEmpty() || graph.frameAttachments.maskRefinements.isNotEmpty() ||
            graph.sourceAttachments.any { it.timeline.frames.isNotEmpty() || it.timeline.maskRefinements.isNotEmpty() })
            return "этому автопроекту нужен анализ кадров, который пока не передаётся в рендер"
        if (revision.texts.isNotEmpty() || revision.textState.layers.isNotEmpty() ||
            revision.textState.captions.isNotEmpty() || revision.style.showAuthoredText)
            return "сохранённый текст пока не поддерживается"
        val visual = revision.visualSettings
        if (visual.aspect != ProjectAspect.PORTRAIT_9_16 || visual.explicitlySelected || visual.framings.isNotEmpty() ||
            (settings.width to settings.height) !in setOf(720 to 1280, 1080 to 1920))
            return "нестандартное кадрирование пока не поддерживается"
        val music = revision.music
        if (music != project.original.music || music.gain != 1f || music.startUs != 0L ||
            music.fadeInUs != 0L || music.fadeOutUs != 0L || !music.repeat ||
            graph.audioTrack?.sourceId != music.assetId || graph.audioTrack?.gain != music.gain)
            return "этот вариант настройки музыки пока не поддерживается"
        return null
    }

    private fun exportSettings(fps: Int) = ProjectExportSettings(1080, 1920, fps, 8_000_000, 192_000)
}
