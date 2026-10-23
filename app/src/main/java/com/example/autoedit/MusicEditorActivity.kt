package com.veycad.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.graphics.Typeface
import android.view.View
import com.google.android.material.button.MaterialButton
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.content.res.ColorStateList
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.UUID
import java.io.File
import java.io.FileOutputStream

/** Small saved-project editor. The store and common commands own every revision and history entry. */
class MusicEditorActivity : AppCompatActivity() {
    companion object { const val EXTRA_PROJECT_ID = "editable_project_id" }
    private val jobs = Executors.newSingleThreadExecutor()
    private lateinit var store: HybridProjectStore
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private var projectId: String? = null
    private var clipId: String? = null
    private var busy = false
    @Volatile private var generation = 0
    private var video: VideoView? = null
    private var previewFile: File? = null
    private var previewProjectId: String? = null
    private var previewRevisionId: Long? = null
    private var playbackGeneration = 0
    private var newProjectFps = 30
    private var captionPage = 0
    private var renderJob: Future<*>? = null
    private var mediaAdmission: ManualEditorAdmission.Request? = null
    @Volatile private var mediaThread: Thread? = null
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
    private var musicPlayer: MediaPlayer? = null
    private val musicPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importMusic(uri)
    }
    private val videoPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importVideos(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        store = HybridProjectStore(filesDir)
        val prefs = getSharedPreferences("manual_mvp", MODE_PRIVATE)
        projectId = savedInstanceState?.getString("project") ?: intent.getStringExtra(EXTRA_PROJECT_ID)
            ?: prefs.getString("project", null)
        clipId = savedInstanceState?.getString("clip") ?: prefs.getString("clip", null)
        newProjectFps = savedInstanceState?.getInt("newProjectFps") ?: prefs.getInt("newProjectFps", 30)

        val scroll = ScrollView(this).apply {
            setFillViewport(true)
            setBackgroundColor(ContextCompat.getColor(this@MusicEditorActivity, R.color.background))
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }
        scroll.addView(content)
        // ScrollView's rectangle-on-screen calculation uses its full measured
        // height, so system-bar padding must shrink it from an outer container.
        val viewport = FrameLayout(this).apply { addView(scroll) }
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
        mediaAdmission?.cancel(); mediaAdmission = null
        mediaThread?.interrupt()
        jobs.shutdown()
        stopPlayback()
        previewFile?.delete()
        super.onDestroy()
    }

    override fun onStop() {
        playbackHandler.removeCallbacks(playbackTicker)
        stopMusic()
        if (renderJob != null || mediaAdmission != null) {
            mediaAdmission?.cancel(); mediaAdmission = null
            mediaThread?.interrupt()
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
                val projects = store.listSummaries()
                val selected = requested?.takeIf { id -> projects.any { it.id == id } }?.let(store::loadWithAnalysisStatus)
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

    private fun showProjects(projects: List<ProjectSummary>, selectedState: ProjectLoadResult?, message: String?) {
        val selected = selectedState?.project
        stopPlayback()
        content.removeAllViews()
        val custom = selected?.let(::isCustomMusic) == true
        header(message ?: if (selected != null) "Правки сохраняются автоматически." else
            "Выберите сохранённый монтаж или добавьте свои видео.")
        if (selected != null && selectedState.analysisRegenerationRequired) {
            val savedId = selected.id
            panel("Восстановление проекта").apply {
                addView(label("Сохранённый анализ отсутствует или повреждён. Восстановите проверенную копию проекта. Правки и исходники сохранены.", 14))
                addView(button("Резервные копии и восстановление") {
                    startActivity(Intent(this@MusicEditorActivity, BackupRecoveryActivity::class.java)
                        .putExtra(BackupRecoveryActivity.EXTRA_PROJECT_ID, savedId))
                })
            }
        } else if (selected != null) showEditor(selected) else {
            projectId = null
            clipId = null
            rememberSelection()
        }
        if (!custom) panel("Новый монтаж").apply {
            addView(label("1–2 ваших видео · базовая сборка без анализа ИИ", 14))
            addView(row(
                button("30 кадр/с") { chooseFps(30) }.apply { isSelected = newProjectFps == 30 },
                button("60 кадр/с") { chooseFps(60) }.apply { isSelected = newProjectFps == 60 }
            ))
            addView(button("Выбрать видео для базового монтажа", primary = selected == null) {
                videoPicker.launch(arrayOf("video/*"))
            })
        }
        panel("Сохранённые проекты").apply {
            if (projects.isEmpty()) addView(label("Пока нет проектов ручной доводки.", 14))
            projects.forEachIndexed { index, project ->
                val savedId = project.id
                val name = if (project.recipeId == MontageStyleCatalog.customMusic.id) "♫ ${project.musicName.orEmpty()}"
                    else project.displayName
                addView(button("${index + 1}. $name · ${project.fps} кадр/с") {
                    projectId = savedId
                    clipId = null
                    rememberSelection()
                    refresh()
                }.apply {
                    isSelected = project.id == selected?.id
                    contentDescription = "Открыть проект ${index + 1}: $name"
                })
            }
        }
    }

    private fun showEditor(project: HybridProject) {
        val savedId = project.id
        val export = projectSettings(project, false)
        val editable = runCatching { EditableMontageProject(project, export) }.getOrElse { error ->
            content.addView(label("Этот проект нельзя довести: ${error.message.orEmpty()}", 14))
            return
        }
        val clips = editable.current.clips
        showMusic(project)
        showAudioAndText(project)
        val custom = isCustomMusic(project)
        val selected = clips.firstOrNull { it.id == clipId } ?: clips.first()
        if (clipId != selected.id) {
            clipId = selected.id
            rememberSelection()
        }
        val previewPanel = panel(projectName(project))
        previewPanel.addView(button("Прямой предпросмотр и кадрирование") {
            startActivity(Intent(this, ManualMvpActivity::class.java)
                .putExtra(MusicEditorActivity.EXTRA_PROJECT_ID, savedId))
        })
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
            timeline.addView(button(if (custom) "$marker${index + 1}. Фрагмент · ${musicTime(frameTimeUs(clip.visible.count, project.fps))}"
                else "$marker${index + 1}. ${clip.origin.id} · ${clip.visible.count} кадров") {
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

    private fun isCustomMusic(project: HybridProject) = project.current.style.recipeId == MontageStyleCatalog.customMusic.id

    private fun loadEditableProject(id: String): HybridProject {
        val loaded = store.loadWithAnalysisStatus(id)
        require(!loaded.analysisRegenerationRequired) { "Сохранённый анализ недоступен. Восстановите проверенную копию проекта" }
        return loaded.project
    }

    private fun musicTime(us: Long): String = "%d:%02d.%d".format(us / 60_000_000L,
        (us / 1_000_000L) % 60L, (us / 100_000L) % 10L)

    private fun showMusic(project: HybridProject) {
        val savedId = project.id
        val music = project.current.music
        val asset = project.assets.single { it.id == music.assetId }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
            setBackgroundResource(R.drawable.panel_surface)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) }
        }
        panel.addView(label("Под свою музыку", 20))
        panel.addView(label(asset.displayName, 14))
        val start = label("Начало трека: ${musicTime(music.startUs)}", 14)
        panel.addView(start)
        panel.addView(SeekBar(this).apply {
            contentDescription = "Начало музыкального фрагмента"
            progressTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MusicEditorActivity, R.color.neon))
            thumbTintList = progressTintList
            max = ((asset.durationUs - 1_000_000L).coerceAtLeast(0L) / 100_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            progress = (music.startUs / 100_000L).toInt()
            minimumHeight = dp(48)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) { stopMusic() }
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) start.text = "Начало трека: ${musicTime(value * 100_000L)}"
                }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    val value = bar.progress * 100_000L
                    if (!busy && value != music.startUs) common(ProjectCommand.ReplaceMusic(music.copy(startUs = value)))
                }
            })
        })
        panel.addView(button("Прослушать фрагмент") {
            if (musicPlayer != null) stopMusic() else {
                video?.pause()
                val file = ProjectAssetStore(store.directory(savedId)).resolve(asset)
                val player = MediaPlayer()
                musicPlayer = player
                try {
                    player.setDataSource(file.path)
                    player.setOnPreparedListener {
                        if (musicPlayer === player) player.seekTo(music.startUs / 1_000L, MediaPlayer.SEEK_CLOSEST)
                    }
                    player.setOnSeekCompleteListener { if (musicPlayer === player) player.start() }
                    player.setOnCompletionListener { stopMusic() }
                    player.setOnErrorListener { _, _, _ -> stopMusic(); status.text = "Не удалось прослушать трек"; true }
                    player.prepareAsync()
                } catch (error: Exception) { stopMusic(); status.text = "Не удалось прослушать трек" }
            }
        })
        panel.addView(button("Заменить трек") { stopMusic(); musicPicker.launch(arrayOf("audio/*", "application/ogg")) })
        panel.addView(gainControl("Громкость музыки", music.gain, 2f) { value ->
            common(ProjectCommand.ReplaceMusic(music.copy(gain = value)))
        })
        panel.addView(button(if (music.repeat) "Повтор трека: включён" else "Повтор трека: выключен") {
            common(ProjectCommand.ReplaceMusic(music.copy(repeat = !music.repeat)))
        })
        panel.addView(button("Плавное начало: ${musicTime(music.fadeInUs)}") {
            editSeconds("Плавное начало музыки", music.fadeInUs) { value -> common(ProjectCommand.ReplaceMusic(music.copy(fadeInUs = value))) }
        })
        panel.addView(button("Плавное окончание: ${musicTime(music.fadeOutUs)}") {
            editSeconds("Плавное окончание музыки", music.fadeOutUs) { value -> common(ProjectCommand.ReplaceMusic(music.copy(fadeOutUs = value))) }
        })
        panel.addView(label("Клипам сохранятся твои правки. Для новых склеек по ритму создай автосборку с другим треком.", 12))
        content.addView(panel)
    }

    private fun gainControl(name: String, value: Float, limit: Float, commit: (Float) -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val caption = label("$name: ${(value * 100).toInt()}%", 14)
            addView(caption)
            addView(SeekBar(this@MusicEditorActivity).apply {
                contentDescription = name; max = (limit * 100).toInt(); progress = (value * 100).toInt()
                minimumHeight = dp(48)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onStartTrackingTouch(bar: SeekBar) { stopMusic() }
                    override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (fromUser) caption.text = "$name: $progress%"
                    }
                    override fun onStopTrackingTouch(bar: SeekBar) { if (!busy) commit(bar.progress / 100f) }
                })
            })
        }

    private fun editSeconds(title: String, value: Long, commit: (Long) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("%.3f".format(java.util.Locale.ROOT, value / 1000000.0)); contentDescription = "$title, секунды"
        }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(input)
            .setNegativeButton("Отмена", null).setPositiveButton("Сохранить", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val seconds = input.text.toString().replace(',', '.').toDoubleOrNull()
            if (seconds == null || !seconds.isFinite() || seconds !in 0.0..3600.0) input.error = "Введите от 0 до 3600 секунд"
            else { commit((seconds * 1000000).toLong()); dialog.dismiss() }
        } }
        dialog.show()
    }

    private fun saveText(expected: DraftVersion, text: HybridTextState) = change { latest ->
        require(latest.id == expected.projectId && latest.current.id == expected.revision) { "Проект изменился; откройте правку заново" }
        HybridEditCommands.apply(latest, ProjectCommand.SetTextState(text))
    }

    private fun showAudioAndText(project: HybridProject) {
        val version = DraftVersion(project.id, project.current.id)
        val audio = project.current.audioMix
        panel("Звук монтажа").apply {
            for ((mode, title) in listOf(AudioMixMode.MUSIC to "Музыка", AudioMixMode.SOURCE to "Исходный звук",
                AudioMixMode.SPEECH_AND_MUSIC to "Речь + музыка")) addView(button(title) {
                common(ProjectCommand.SetAudioMix(audio.copy(mode = mode, musicGain = minOf(audio.musicGain, 1f))))
            }.apply { isSelected = audio.mode == mode; contentDescription = "Режим звука: $title" })
            addView(gainControl("Громкость исходного звука", audio.sourceGain, 1f) { value ->
                common(ProjectCommand.SetAudioMix(audio.copy(sourceGain = value)))
            })
            addView(label("Скорость исходной речи следует монтажу. При смешивании громкость ограничивается общим запасом от перегруза.", 12))
        }
        val text = project.current.textState
        val duration = project.current.graph.editableTiming?.let { frameTimeUs(it.frameCount, project.fps) }
            ?: project.current.graph.outputDurationMs * 1000L
        val textDuration = ProjectClock(project.fps).timeUs(project.current.clips.last().span.endExclusive)
        val showAuthored = project.current.style.showAuthoredText
        panel("Текст и субтитры").apply {
            addView(button(if (showAuthored) "Авторские титры: включены" else "Авторские титры: выключены") {
                common(ProjectCommand.SetAuthoredText(!showAuthored))
            })
            addView(button("Добавить надпись") {
                editTextLayer(version, text, textDuration, TextLayer(UUID.randomUUID().toString(), "", 0, minOf(duration, 3000000), kind = TextLayerKind.TITLE))
            })
            text.layers.take(12).forEach { layer -> addView(button("Надпись: ${layer.text.take(60)}") { editTextLayer(version, text, textDuration, layer) }) }
            if (text.layers.size > 12) addView(button("Все надписи (${text.layers.size})") {
                AlertDialog.Builder(this@MusicEditorActivity).setTitle("Надписи")
                    .setItems(text.layers.map { it.text.take(60) }.toTypedArray()) { _, index -> editTextLayer(version, text, textDuration, text.layers[index]) }.show()
            })
            addView(button("Язык речи: ${text.language}") {
                val languages = arrayOf("auto", "ru", "en")
                AlertDialog.Builder(this@MusicEditorActivity).setTitle("Язык речи").setItems(arrayOf("Авто", "Русский", "English")) { _, index ->
                    saveText(version, text.copy(language = languages[index]))
                }.show()
            })
            addView(button("Распознать исходную речь") {
                if (text.captionsEdited) AlertDialog.Builder(this@MusicEditorActivity).setTitle("Заменить субтитры?")
                    .setMessage("Ручные правки останутся в предыдущей версии. Новое распознавание заменит текущие субтитры.")
                    .setNegativeButton("Отмена", null).setPositiveButton("Распознать") { _, _ -> transcribe(version) }.show()
                else transcribe(version)
            }.apply { isEnabled = audio.mode != AudioMixMode.MUSIC && audio.sourceGain > 0f })
            addView(label("Субтитры распознаются локально из исходной речи до музыки. Проверьте слова и интервалы.", 12))
            addView(button("Добавить субтитр вручную") {
                editCaption(version, text, textDuration, CaptionCue(UUID.randomUUID().toString(), "", 0, minOf(duration, 3000000)))
            })
            val pages = maxOf(1, (text.captions.size + 7) / 8)
            captionPage = captionPage.coerceIn(0, pages - 1)
            text.captions.drop(captionPage * 8).take(8).forEach { cue ->
                addView(button("${musicTime(cue.startUs)} · ${if (cue.needsReview) "Проверить: " else ""}${cue.text.take(70)}") { editCaption(version, text, textDuration, cue) })
            }
            if (pages > 1) addView(row(button("‹ Фразы") { captionPage--; refresh() }.apply { isEnabled = captionPage > 0 },
                button("Фразы ›") { captionPage++; refresh() }.apply { isEnabled = captionPage < pages - 1 }))
        }
    }

    private fun textFields(title: String, text: String, startUs: Long, endUs: Long, durationUs: Long,
        onSave: (String, Long, Long) -> Unit, onDelete: (() -> Unit)? = null, styleView: View? = null) {
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0) }
        val words = EditText(this).apply { setText(text); hint = "Текст"; minLines = 2; contentDescription = "Текст" }
        fun seconds(name: String, value: Long) = EditText(this).apply {
            hint = name; contentDescription = name
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("%.6f".format(java.util.Locale.ROOT, value / 1000000.0))
        }
        val start = seconds("Начало, секунды", startUs); val end = seconds("Конец, секунды", endUs)
        fields.addView(words); fields.addView(start); fields.addView(end); styleView?.let(fields::addView)
        val builder = AlertDialog.Builder(this).setTitle(title).setView(fields).setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить", null)
        if (onDelete != null) builder.setNeutralButton("Удалить") { _, _ -> onDelete() }
        val dialog = builder.create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            fun time(field: EditText): Long? = field.text.toString().replace(',', '.').toDoubleOrNull()
                ?.takeIf { it.isFinite() && it >= 0 && it <= durationUs / 1000000.0 }?.let { (it * 1000000).toLong() }
            val from = time(start); val until = time(end)
            if (from == null || until == null || until <= from) end.error = "Интервал должен быть внутри ролика"
            else runCatching { onSave(words.text.toString(), from, until) }.fold(
                onSuccess = { dialog.dismiss() }, onFailure = { words.error = it.message ?: "Проверьте текст" })
        } }
        dialog.show()
    }

    private fun editTextLayer(version: DraftVersion, state: HybridTextState, durationUs: Long, layer: TextLayer) {
        val position = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MusicEditorActivity, android.R.layout.simple_spinner_dropdown_item,
                arrayOf("Сверху", "По центру", "Снизу")); setSelection(layer.style.position.ordinal); contentDescription = "Положение надписи"
        }
        textFields("Надпись", layer.text, layer.startUs, layer.endUs,
            durationUs, { words, from, until ->
                val updated = layer.copy(text = words, startUs = from, endUs = until,
                    style = layer.style.copy(position = TextPosition.entries[position.selectedItemPosition]))
                saveText(version, state.copy(layers = (state.layers.filterNot { it.id == layer.id } + updated).sortedBy { it.startUs }))
            }, { saveText(version, state.copy(layers = state.layers.filterNot { it.id == layer.id })) }, position)
    }

    private fun editCaption(version: DraftVersion, state: HybridTextState, durationUs: Long, cue: CaptionCue) {
        textFields("Субтитр", cue.text, cue.startUs, cue.endUs,
            durationUs, { words, from, until ->
                val updated = cue.copy(text = words, startUs = from, endUs = until, manuallyReviewed = true)
                saveText(version, state.copy(captions = (state.captions.filterNot { it.id == cue.id } + updated)
                    .sortedBy { it.startUs }, captionsEdited = true))
            }, { saveText(version, state.copy(captions = state.captions.filterNot { it.id == cue.id }, captionsEdited = true)) })
    }

    private fun transcribe(expected: DraftVersion) {
        if (busy) return
        invalidatePreview(); setBusy(true, "Распознаю исходную речь…")
        val sequence = ++generation
        submitMediaJob(sequence) {
            val result = runCatching {
                val lease = MediaWorkCoordinator.process.tryBeginWork() ?: error(MediaWorkCoordinator.CLEANING_MESSAGE)
                lease.use {
                    val checkCancelled = { if (Thread.currentThread().isInterrupted || sequence != generation) throw InterruptedException("Распознавание отменено") }
                    val project = loadEditableProject(expected.projectId)
                    require(project.current.id == expected.revision) { "Проект изменился; повторите распознавание" }
                    val snapshot = PreparedProjectAudio.from(project, ProjectAssetStore(store.directory(project.id)), checkCancelled)
                    snapshot.compose(File(cacheDir, "project-speech-work"), checkCancelled).use { audio ->
                        val outcome = ComposedSpeechTranscriber(WhisperSpeechTranscriber()).transcribeOutcome(applicationContext,
                            audio, snapshot.settings, project.current.textState.language, checkCancelled) { progress ->
                            runOnUiThread { if (sequence == generation) status.text = "Распознаю исходную речь… $progress%" }
                        }
                        val cues = outcome.cuesOrThrow()
                        require(cues.all { it.endUs <= snapshot.durationUs }) { "Субтитры вышли за длительность монтажа" }
                        checkCancelled()
                        val updated = HybridEditCommands.apply(project,
                            ProjectCommand.SetTextState(project.current.textState.copy(captions = cues, captionsEdited = false)))
                        store.save(updated, project.current.id)
                        if (cues.isEmpty()) "Речь не найдена. Черновик сохранён; можно добавить субтитры вручную."
                        else "Субтитры сохранены. Проверьте распознанный текст и интервалы."
                    }
                }
            }
            runOnUiThread {
                if (isDestroyed || sequence != generation) return@runOnUiThread
                renderJob = null; setBusy(false)
                refresh(result.getOrElse { "Распознавание не завершилось: ${it.message.orEmpty()}" })
            }
        }
    }

    /** A queued body must run its finally; Future.cancel/shutdownNow can strand its native lease. */
    private fun submitMediaJob(sequence: Int, action: () -> Unit) {
        mediaAdmission = ManualEditorAdmission.acquire { lease ->
            mediaAdmission = null
            if (isDestroyed || sequence != generation) { lease.release(); return@acquire }
            try {
                renderJob = jobs.submit {
                    mediaThread = Thread.currentThread()
                    try { action() }
                    finally { mediaThread = null; runOnUiThread { lease.release() } }
                }
            } catch (failure: Throwable) { lease.release(); throw failure }
        }
    }

    private fun stopMusic() {
        musicPlayer?.release()
        musicPlayer = null
    }

    private fun importMusic(uri: Uri) {
        if (busy) return
        val id = projectId ?: return
        invalidatePreview()
        setBusy(true, "Анализирую новый трек…")
        val sequence = ++generation
        jobs.execute {
            var imported: CustomMusicStore.Selection? = null
            val result = runCatching {
                val checkCancelled = { if (Thread.currentThread().isInterrupted) throw InterruptedException() }
                val musicStore = CustomMusicStore(applicationContext)
                val selected = musicStore.importFile(uri, checkCancelled).also { imported = it }
                musicStore.analyze(selected, checkCancelled)
                val previous = loadEditableProject(id)
                val asset = ProjectAssetStore(store.directory(id)).import(selected.file,
                    ProjectAsset.Kind.AUDIO, selected.durationUs).copy(displayName = selected.title)
                checkCancelled()
                val updated = HybridEditCommands.replaceMusic(previous,
                    ProjectMusic(asset.id, 0L, 1f, 0L, 0L, true),
                    // An identical file keeps its canonical saved metadata.
                    previous.assets.firstOrNull { it.id == asset.id } ?: asset)
                store.save(updated, previous.current.id)
            }
            imported?.file?.delete()
            runOnUiThread {
                if (isDestroyed || sequence != generation) return@runOnUiThread
                setBusy(false)
                refresh(result.fold(onSuccess = { "Трек заменён. Правки клипов сохранены." },
                    onFailure = { "Трек не заменён: ${it.message.orEmpty()}" }))
            }
        }
    }

    private fun renderVersion(publish: Boolean) {
        if (busy) return
        val id = projectId ?: return
        invalidatePreview()
        setBusy(true, if (publish) "Рендерю и сохраняю новую версию…" else
            "Обновляю предпросмотр… Полный рендер может занять время.")
        val sequence = ++generation
        submitMediaJob(sequence) {
            var output: File? = null
            var linked = false
            var published: CompletedRenderStore.Entry? = null
            val result = runCatching {
                val installed = loadEditableProject(id)
                val settings = projectSettings(installed, publish)
                unsupportedReason(installed, settings)?.let { throw IllegalArgumentException(it) }
                val assets = ProjectAssetStore(store.directory(id))
                val musicAsset = installed.assets.single { it.id == installed.current.music.assetId }
                val musicFile = assets.resolve(musicAsset)
                require(installed.current.audioMix.mode == AudioMixMode.SOURCE || musicFile.isFile) { "Сохранённый музыкальный файл отсутствует" }
                val checkCancelled = {
                    if (Thread.currentThread().isInterrupted || sequence != generation) throw InterruptedException("Рендер отменён")
                }
                checkCancelled()
                val directory = File(cacheDir, "manual-renders").apply { mkdirs() }
                val target = File(directory, "manual-${UUID.randomUUID()}.mp4")
                output = target
                val smart = SmartFramingPreparation.prepare(applicationContext, installed, assets, checkCancelled)
                val budget = PreviewMemoryBudget(128L * 1024 * 1024)
                val frames = try {
                    HybridPreviewPreparation.prepare(installed, settings, assets, budget,
                        scratchDirectory = cacheDir, checkCancelled = checkCancelled).use { prepared ->
                        val request = PreparedHybridExportAdapter.request(prepared, installed, target,
                            audioFile = musicFile.takeIf { installed.current.audioMix.mode != AudioMixMode.SOURCE },
                            context = applicationContext, checkCancelled = checkCancelled, smartTracks = smart)
                        MediaCodecSpeedRampRenderer.render(request)
                    }
                } finally { budget.clear() }
                checkCancelled()
                require(frames > 0 && target.isFile && target.length() > 0L) {
                    "Рендер не сохранил все кадры текущей версии"
                }
                requireVideoShape(target, settings)
                if (publish) {
                    val latest = loadEditableProject(id)
                    require(latest.current.id == installed.current.id) { "Проект изменился во время экспорта" }
                    checkCancelled()
                    val entry = CompletedRenderStore.publish(filesDir, target,
                        "Ручная версия ${installed.current.id} · ${if (isCustomMusic(installed)) "Под свою музыку" else "базовый монтаж"}",
                        projectId = installed.id)
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
                if (!busy && duration > 0) movie.seekTo((duration.toLong() * seek.progress / seek.max).toInt())
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
        stopMusic()
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
                val previous = loadEditableProject(id)
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
        if (project.selectedVideos == null) return "у проекта нет проверенного порядка видео"
        if (project.fps != settings.fps) return "частота кадров не совпадает с проектом"
        return null
    }

    private fun projectSettings(project: HybridProject, publish: Boolean): ProjectExportSettings {
        val size = project.current.visualSettings.aspect.size(if (publish) 1080 else 720)
        return ProjectExportSettings(size.width, size.height, project.fps, if (publish) 8000000 else 5000000, 192000)
    }
}
