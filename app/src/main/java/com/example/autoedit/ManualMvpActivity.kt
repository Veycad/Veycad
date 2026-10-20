package com.veycad.app

import android.os.Bundle
import android.media.MediaMetadataRetriever
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.FileProvider
import java.util.concurrent.Executors
import java.util.UUID
import java.io.File
import java.io.FileOutputStream

/** Published manual editor; the common store owns revisions and the prepared plan owns output time. */
class ManualMvpActivity : AppCompatActivity() {
    private val jobs = Executors.newSingleThreadExecutor { Thread(it, "Veycad-manual-jobs") }
    private var admission: ManualEditorAdmission.Request? = null
    private var operationLease: ManualEditorAdmission.Lease? = null
    private var retiringLease: ManualEditorAdmission.Lease? = null
    private var retirementScheduled = false
    private var leaving = false
    private lateinit var store: HybridProjectStore
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private var projectId: String? = null
    private var clipId: String? = null
    private var busy = false
    @Volatile private var generation = 0
    private var newProjectFps = 30
    @Volatile private var exportThread: Thread? = null
    private var started = false
    private var destroyed = false
    private var lastShownUs = 0L
    private var controller: DraftPreviewController? = null
    private var stopping = false
    private var afterStop: (() -> Unit)? = null
    private var keepPreparedAfterStop = false
    private var binding: Binding? = null
    private var texture: TextureView? = null
    private var playbackState = DraftPreviewController.State()
    private var play: Button? = null
    private var seek: SeekBar? = null
    private var clock: TextView? = null
    private var previewStatus: TextView? = null
    private var retry: Button? = null
    private var export: Button? = null
    private var dragging = false
    private var exporting = false
    private var pendingImports: List<Uri>? = null
    private val surfaces = mutableListOf<BorrowedSurface>()
    private data class BorrowedSurface(val texture: SurfaceTexture, val surface: Surface, var destroyed: Boolean = false)
    private data class Binding(val project: HybridProject, val prepared: PreparedHybridPreview,
        val request: MediaCodecSpeedRampRenderer.Request)
    /** Tick positions come from the same canonical frames that drive seek and export. */
    private class OutputTimeline(context: android.content.Context) : androidx.appcompat.widget.AppCompatSeekBar(context) {
        var cutFrames: List<Int> = emptyList()
            set(value) { field = value; invalidate() }
        private val ticks = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(R.color.text_primary)
            strokeWidth = 2 * resources.displayMetrics.density
        }
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            if (max <= 0) return
            val radius = 5 * resources.displayMetrics.density
            cutFrames.forEach { frame ->
                val x = paddingLeft + (width - paddingLeft - paddingRight) * frame.toFloat() / max
                canvas.drawLine(x, height / 2f - radius, x, height / 2f + radius, ticks)
            }
        }
    }
    private val videoPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            pendingImports = uris
            if (started && !busy && operationLease != null && !leaving) drainImports()
        }
    }
    private fun drainImports() {
        val uris = pendingImports ?: return
        pendingImports = null
        importVideos(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        store = HybridProjectStore(filesDir)
        val prefs = getSharedPreferences("manual_mvp", MODE_PRIVATE)
        projectId = savedInstanceState?.getString("project") ?: prefs.getString("project", null)
        clipId = savedInstanceState?.getString("clip") ?: prefs.getString("clip", null)
        newProjectFps = savedInstanceState?.getInt("newProjectFps") ?: prefs.getInt("newProjectFps", 30)
        lastShownUs = savedInstanceState?.getLong("position") ?: prefs.getLong("position", 0L)
        val scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(getColor(R.color.background)) }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(36))
        }
        scroll.addView(content)
        // Match the text editor: ScrollView reveals rectangles using its full height,
        // so system insets must shrink its viewport rather than clip it with padding.
        val viewport = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.background)) }
        viewport.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(viewport)
        ViewCompat.setOnApplyWindowInsetsListener(viewport) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(viewport)
        title = getString(R.string.manual_title)
        content.addView(label(getString(R.string.manual_title), 28))
        status = label(getString(R.string.manual_opening), 14).apply { tag = "preview-state" }.also(content::addView)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leaveAfterCleanup()
        })
    }

    override fun onStart() { super.onStart(); started = true; busy = false; requestAdmission() }
    private fun requestAdmission() {
        if (!started || destroyed || leaving || stopping || retirementScheduled || admission != null || operationLease != null) return
        admission = ManualEditorAdmission.acquire { lease ->
            admission = null
            if (!started || destroyed) { lease.release(); return@acquire }
            operationLease = lease
            refresh()
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("project", projectId); outState.putString("clip", clipId)
        outState.putInt("newProjectFps", newProjectFps); outState.putLong("position", lastShownUs)
        super.onSaveInstanceState(outState)
    }
    override fun onStop() {
        started = false
        generation++
        rememberSelection()
        stopForLifecycle()
        super.onStop()
    }
    override fun onDestroy() {
        destroyed = true
        generation++
        stopForLifecycle()
        super.onDestroy()
    }
    private fun stopForLifecycle() {
        admission?.cancel(); admission = null
        operationLease?.let { retiringLease = it; operationLease = null }
        // A queued export is never Future.cancel'ed: its body must run its finally cleanup.
        // A running export is interrupted, but admission remains held until the worker drains.
        exportThread?.interrupt()
        stopPreview { finishRetirement() }
    }
    private fun leaveAfterCleanup() {
        if (leaving) return
        leaving = true
        generation++
        rememberSelection()
        setBusy(true, getString(R.string.manual_stopping))
        stopForLifecycle()
    }
    private fun finishRetirement() {
        if (retirementScheduled || jobs.isShutdown) return
        retirementScheduled = true
        val lease = retiringLease
        retiringLease = null
        jobs.execute {
            runOnUiThread {
                // Earlier worker results can post stale-prepared disposal back to jobs.
                // This second fence includes those disposals, not just export completion.
                jobs.execute {
                    runOnUiThread {
                        lease?.release()
                        retirementScheduled = false
                        if (destroyed) jobs.shutdown()
                        else if (leaving) finish()
                        else if (started) requestAdmission()
                    }
                }
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun label(text: String, size: Int): TextView = TextView(this).apply {
        this.text = text; textSize = size.toFloat()
        setTextColor(getColor(if (size >= 16) R.color.text_primary else R.color.text_secondary))
        if (size >= 26) typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        else if (size >= 16) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(0, dp(8), 0, dp(8))
    }
    private fun button(text: String, action: () -> Unit): Button = Button(this).apply {
        this.text = text; isAllCaps = false; minHeight = dp(48)
        setTextColor(getColor(R.color.text_primary))
        backgroundTintList = ColorStateList.valueOf(getColor(R.color.surface_high))
        setOnClickListener { if (!busy && started && !destroyed && !leaving && operationLease != null) action() }
    }
    private fun row(vararg buttons: Button): LinearLayout = LinearLayout(this).apply {
        // At large font sizes stack long edit actions. Aspect controls explicitly use a 2x2 grid.
        orientation = if (resources.configuration.fontScale >= 1.5f) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        buttons.forEach { button -> addView(button, if (orientation == LinearLayout.HORIZONTAL)
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
            else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
    }
    private fun rememberSelection() {
        getSharedPreferences("manual_mvp", MODE_PRIVATE).edit().putString("project", projectId)
            .putString("clip", clipId).putLong("position", lastShownUs).apply()
    }
    private fun setBusy(value: Boolean, message: String? = null) {
        busy = value
        fun update(view: View) {
            if ((view is Button || view is SeekBar) && view.tag != "manual-back") {
                if (value) { view.setTag(R.id.manualPreviouslyEnabled, view.isEnabled); view.isEnabled = false }
                else { (view.getTag(R.id.manualPreviouslyEnabled) as? Boolean)?.let { view.isEnabled = it }
                    view.setTag(R.id.manualPreviouslyEnabled, null) }
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) update(view.getChildAt(index))
        }
        update(content)
        if (message != null) status.text = message
    }
    private fun checkCurrent(sequence: Int) {
        if (Thread.currentThread().isInterrupted || sequence != generation) throw InterruptedException("Операция отменена")
    }

    /** Latest UI job replaces older work; every borrowed Surface survives until the actual stop ack. */
    private fun stopPreview(keepPrepared: Boolean = false, action: () -> Unit) {
        afterStop = action
        keepPreparedAfterStop = keepPrepared
        if (stopping) return
        val player = controller
        stopping = true
        fun released() {
            if (controller === player) controller = null
            player?.close()
            surfaces.forEach { held -> held.surface.release(); if (held.destroyed) held.texture.release() }
            surfaces.clear()
            if (!keepPreparedAfterStop) {
                val previous = binding; binding = null
                if (previous != null) jobs.execute { previous.prepared.close() }
            }
            stopping = false
            val next = afterStop; afterStop = null
            next?.invoke()
        }
        if (player == null) released() else player.stopAndRelease { released() }
    }

    private fun refresh(message: String? = null) {
        if (!started || destroyed || operationLease == null) return
        exporting = false
        val sequence = ++generation
        setBusy(true, getString(R.string.manual_opening))
        stopPreview {
            if (!started || destroyed || sequence != generation) return@stopPreview
            val requested = projectId
            jobs.execute {
                val result = runCatching {
                    checkCurrent(sequence)
                    val projects = store.list()
                    val selected = projects.firstOrNull { it.id == requested }
                    val editable = selected?.let { runCatching { EditableMontageProject(it, exportSettings(it)) }.getOrNull() }
                    checkCurrent(sequence)
                    Triple(projects, selected, editable)
                }
                runOnUiThread {
                    if (destroyed || !started || sequence != generation) return@runOnUiThread
                    setBusy(false)
                    result.fold(onSuccess = { (projects, selected, editable) ->
                        showProjects(projects, selected, editable, message)
                        drainImports()
                    }, onFailure = { showError(getString(R.string.manual_open_failed)) })
                }
            }
        }
    }
    private fun showError(message: String) {
        content.removeAllViews()
        content.addView(label(getString(R.string.manual_title), 28))
        status = label(message, 14).also(content::addView)
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        content.addView(button(getString(R.string.manual_retry)) { refresh() })
    }
    private fun showProjects(projects: List<HybridProject>, selected: HybridProject?, editable: EditableMontageProject?, message: String?) {
        content.removeAllViews()
        texture = null; play = null; seek = null; previewStatus = null; export = null; retry = null
        content.addView(button(getString(R.string.manual_back)) { leaveAfterCleanup() }.apply {
            tag = "manual-back"
            setOnClickListener { leaveAfterCleanup() }
        })
        content.addView(label(getString(R.string.manual_title), 28))
        status = label(message ?: getString(R.string.manual_intro), 14).also(content::addView)
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        if (selected != null) showEditor(selected, editable) else {
            projectId = null; clipId = null; lastShownUs = 0; rememberSelection()
        }
        content.addView(label(getString(R.string.manual_new), 20))
        content.addView(label(getString(R.string.manual_supported), 14))
        content.addView(row(
            button("30 кадр/с") { chooseFps(30) }.apply { isSelected = newProjectFps == 30 },
            button("60 кадр/с") { chooseFps(60) }.apply { isSelected = newProjectFps == 60 }
        ))
        content.addView(button(getString(R.string.manual_import)) { videoPicker.launch(arrayOf("video/*")) }.apply { tag = "import" })
        content.addView(label(getString(R.string.manual_saved), 20))
        if (projects.isEmpty()) content.addView(label(getString(R.string.manual_empty), 14))
        projects.forEachIndexed { index, project ->
            content.addView(button(getString(R.string.manual_project, index + 1, project.fps)) {
                projectId = project.id; clipId = null; lastShownUs = 0; rememberSelection(); refresh()
            })
        }
    }

    private fun showEditor(project: HybridProject, editable: EditableMontageProject?) {
        if (editable == null) {
            content.addView(label(getString(R.string.manual_unsupported), 14)); return
        }
        val clips = editable.current.clips
        val selected = clips.firstOrNull { it.id == clipId } ?: clips.first()
        clipId = selected.id; rememberSelection()
        content.addView(label(getString(R.string.manual_revision, project.current.id, project.fps), 16))
        showPlayer(project)
        showAspects(project)
        export = button(getString(R.string.manual_export, aspectLabel(project.current.visualSettings.aspect))) { renderVersion() }.apply {
            tag = "export"; minHeight = dp(58); isEnabled = false
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.neon))
        }.also(content::addView)
        content.addView(row(
            button(getString(R.string.manual_previous_cut)) { seekCut(false) },
            button(getString(R.string.manual_next_cut)) { seekCut(true) }
        ))
        val exportRef = project.exports.lastOrNull { it.revisionId == project.current.id }
        if (exportRef != null) content.addView(button(getString(R.string.manual_share)) {
            share(File(filesDir, "completed-renders/${exportRef.fileName}"))
        }.apply { tag = "share" })
        clips.forEachIndexed { index, clip ->
            val marker = if (clip.id == selected.id) "● " else "○ "
            content.addView(button("$marker${index + 1}. ${clip.origin.id} · ${clip.visible.count} кадров") {
                clipId = clip.id
                rememberSelection()
                refresh()
            })
        }
        content.addView(label("Выбран: ${selected.id}. Подрезка по одному выходному кадру.", 14).apply { tag = "selected-clip" })
        content.addView(row(
            button("Начало −1") { edit(TimelineCommand.Trim(selected.id, ClipEdge.START, selected.visible.start - 1)) },
            button("Начало +1") { edit(TimelineCommand.Trim(selected.id, ClipEdge.START, selected.visible.start + 1)) }
        ))
        content.addView(row(
            button("Конец −1") { edit(TimelineCommand.Trim(selected.id, ClipEdge.END, selected.visible.endExclusive - 1)) },
            button("Конец +1") { edit(TimelineCommand.Trim(selected.id, ClipEdge.END, selected.visible.endExclusive + 1)) }
        ))
        val index = clips.indexOf(selected)
        content.addView(row(
            button("Выше") { edit(TimelineCommand.Move(selected.id, index - 1)) }.apply { isEnabled = index > 0 },
            button("Ниже") { edit(TimelineCommand.Move(selected.id, index + 1)) }.apply { isEnabled = index < clips.lastIndex }
        ))
        if (index > 0) {
            content.addView(button("Обычная склейка перед клипом") {
                edit(TimelineCommand.SetTransition(selected.id, MontageGraph.Transition.HARD_CUT))
            })
        }
        val flashes = editable.current.effects.filter {
            it.originalOverlay?.overlayKind == MontageGraph.OverlayKind.FLASH
        }
        if (flashes.isEmpty()) content.addView(label("В этом монтаже нет вспышки для переноса.", 14))
        flashes.forEach { flash ->
            content.addView(label("Вспышка ${flash.id}", 14))
            val boundary = flash.anchor as? EffectAnchor.Boundary
            val boundaryIndex = clips.indexOfFirst { it.id == boundary?.incomingClipId }
            val nextValid = boundaryIndex >= 0 && boundaryIndex < clips.lastIndex
            content.addView(row(
                button(if (flash.enabled) "Отключить вспышку" else "Включить вспышку") {
                    edit(TimelineCommand.SetFlashEnabled(flash.id, !flash.enabled))
                },
                button("На следующий стык") { edit(TimelineCommand.MoveFlashToNext(flash.id)) }
                    .apply { isEnabled = nextValid }
            ))
        }
        content.addView(row(
            button("Отменить") { common(ProjectCommand.Undo) }.apply { isEnabled = project.undo.isNotEmpty() },
            button("Повторить") { common(ProjectCommand.Redo) }.apply { isEnabled = project.redo.isNotEmpty() }
        ))
        content.addView(button(if (project.original.graph.metadata.generator == "manual-basic-test")
            "Вернуть базовый монтаж" else "Вернуть автосборку") { edit(TimelineCommand.RestoreBaseline) })
    }

    private fun edit(command: TimelineCommand) = change { project ->
        val editable = EditableMontageProject(project, exportSettings(project))
        when (val prepared = MontageTimelineEditor.prepare(editable, command)) {
            is TimelinePreparation.Prepared -> HybridEditCommands.apply(project,
                ProjectCommand.CommitRevision(prepared.candidate))
            is TimelinePreparation.CoreRestoreMontage -> HybridEditCommands.apply(project, prepared.command)
            TimelinePreparation.Unchanged -> project
            is TimelinePreparation.Rejected -> throw HybridEditRejected(prepared.reason)
        }
    }

    private fun common(command: ProjectCommand) = change { HybridEditCommands.apply(it, command) }

    private fun aspectLabel(aspect: ProjectAspect) = when (aspect) {
        ProjectAspect.PORTRAIT_9_16 -> "9:16"
        ProjectAspect.LANDSCAPE_16_9 -> "16:9"
        ProjectAspect.SQUARE_1_1 -> "1:1"
        ProjectAspect.FEED_4_5 -> "4:5"
    }
    private fun showAspects(project: HybridProject) {
        content.addView(label(getString(R.string.manual_format), 16))
        val compact = resources.configuration.fontScale >= 1.3f || resources.configuration.screenWidthDp < 360
        ProjectAspect.entries.chunked(if (compact) 2 else 4).forEach { aspects ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            aspects.forEach { aspect ->
                val chosen = project.current.visualSettings.aspect == aspect
                val chip = button(aspectLabel(aspect)) {
                    change { installed ->
                        val visual = installed.current.visualSettings.withAspect(aspect)
                        HybridEditCommands.apply(installed, ProjectCommand.CommitRevision(installed.current.copy(visualSettings = visual)))
                    }
                }.apply {
                    tag = "aspect-${aspect.name}"; isSelected = chosen
                    backgroundTintList = ColorStateList.valueOf(getColor(if (chosen) R.color.accent_soft else R.color.surface_high))
                    ViewCompat.setStateDescription(this, getString(if (chosen) R.string.manual_selected else R.string.manual_not_selected))
                    // Checked semantic and a visible checkmark make selection independent of color.
                    if (chosen) setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.manual_check, 0, 0, 0)
                    contentDescription = getString(R.string.manual_aspect_description, aspectLabel(aspect))
                }
                ViewCompat.setAccessibilityDelegate(chip, object : androidx.core.view.AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.isCheckable = true; info.isChecked = chosen
                    }
                })
                line.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4); marginStart = dp(4) })
            }
            content.addView(line)
        }
        content.addView(label(getString(R.string.manual_format_hint), 14))
    }

    private fun showPlayer(project: HybridProject) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundResource(R.drawable.panel_surface)
        }
        content.addView(panel)
        val stage = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.black)) }
        panel.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(280)))
        val movie = TextureView(this).apply {
            tag = "preview-surface"; contentDescription = getString(R.string.manual_frame)
        }
        texture = movie
        stage.addView(movie, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        val aspect = project.current.visualSettings.aspect.size(360)
        stage.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (stage.width > 0 && stage.height > 0) {
                val scale = minOf(stage.width.toFloat() / aspect.width, stage.height.toFloat() / aspect.height)
                val width = (aspect.width * scale).toInt().coerceAtLeast(1)
                val height = (aspect.height * scale).toInt().coerceAtLeast(1)
                if (movie.layoutParams.width != width || movie.layoutParams.height != height)
                    movie.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
            }
        }
        movie.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                if (started && !stopping) attachSurface(surface, width, height)
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                val held = surfaces.firstOrNull { it.texture === surface }
                if (held != null && started && !stopping) controller?.attach(held.surface, OutputSize(width, height))
            }
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                val held = surfaces.firstOrNull { it.texture === surface } ?: return true
                held.destroyed = true
                if (!stopping) {
                    generation++
                    stopPreview { if (started && !destroyed) refresh() }
                }
                return false // The stop acknowledgement owns release of this retained texture.
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        previewStatus = label(getString(R.string.manual_preparing), 14).apply {
            tag = "preview-state"; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }.also(panel::addView)
        clock = label(getString(R.string.manual_clock, "0:00.000", "0:00.000"), 14).also(panel::addView)
        seek = OutputTimeline(this).apply {
            tag = "preview-seek"; minimumHeight = dp(48); isEnabled = false
            contentDescription = getString(R.string.manual_seek)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar?) {
                    if (!busy && !stopping) { dragging = true; controller?.beginScrub() }
                }
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && !busy && !stopping) binding?.prepared?.framePlan?.frames?.getOrNull(progress)?.let {
                        controller?.seekTo(it.outputTimeUs)
                    }
                }
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    dragging = false
                    if (!busy && !stopping) controller?.endScrub()
                }
            })
        }.also(panel::addView)
        play = button(getString(R.string.manual_play)) {
            if (!stopping) { if (playbackState.playing) controller?.pause() else controller?.play() }
        }.apply { tag = "play"; isEnabled = false }.also(panel::addView)
        retry = button(getString(R.string.manual_retry)) {
            if (stopping) controller?.stopAndRelease {} else refresh()
        }.apply {
            tag = "retry"; visibility = View.GONE
            // Cleanup failure must remain retryable even while the pending edit/export is busy.
            setOnClickListener {
                if (started && !destroyed) {
                    if (stopping) controller?.stopAndRelease {} else refresh()
                }
            }
        }.also(panel::addView)
        preparePreview(project)
    }
    private fun attachSurface(surface: SurfaceTexture, width: Int, height: Int) {
        val player = controller ?: return
        val held = surfaces.firstOrNull { it.texture === surface } ?: BorrowedSurface(surface, Surface(surface)).also { surfaces += it }
        player.attach(held.surface, OutputSize(width.coerceAtLeast(1), height.coerceAtLeast(1)))
    }
    private fun time(us: Long): String = "%d:%02d.%03d".format(us / 60_000_000, us / 1_000_000 % 60, us / 1_000 % 1_000)
    private fun showState(state: DraftPreviewController.State) {
        playbackState = state
        if (state.shownOutputTimeUs != null && state.exactFrameReady) lastShownUs = state.shownOutputTimeUs
        if (destroyed || !started) return
        play?.text = getString(if (state.playing) R.string.manual_pause else R.string.manual_play)
        play?.contentDescription = play?.text
        val plan = binding?.prepared?.framePlan
        if (plan != null) {
            clock?.text = getString(R.string.manual_clock, time(lastShownUs), time(plan.durationUs))
            if (!dragging) seek?.progress = plan.frames.binarySearchBy(lastShownUs) { it.outputTimeUs }.coerceAtLeast(0)
        }
        val failed = state.error != null
        previewStatus?.text = getString(when {
            failed -> R.string.manual_preview_failed
            exporting -> R.string.manual_exporting
            state.buffering -> R.string.manual_preparing
            state.playing -> R.string.manual_playing
            state.exactFrameReady -> R.string.manual_paused
            else -> R.string.manual_preparing
        })
        retry?.visibility = if (failed) View.VISIBLE else View.GONE
        retry?.isEnabled = failed
        play?.isEnabled = !busy && !stopping && !failed && (state.exactFrameReady || state.playing)
        seek?.isEnabled = !busy && !stopping && !failed && plan != null
        export?.isEnabled = !busy && !stopping && !failed && state.exactFrameReady
    }
    private fun seekCut(next: Boolean) {
        if (stopping) return
        val active = binding ?: return
        val frames = active.prepared.framePlan.frames
        val cuts = frames.filterIndexed { index, frame -> index == 0 || frame.clipIndex != frames[index - 1].clipIndex }
            .map { it.outputTimeUs }
        val target = if (next) cuts.firstOrNull { it > lastShownUs } ?: active.prepared.framePlan.frames.last().outputTimeUs
            else cuts.lastOrNull { it < lastShownUs } ?: 0L
        controller?.seekTo(target)
    }
    private fun preparePreview(project: HybridProject) {
        val sequence = generation
        jobs.execute {
            var prepared: PreparedHybridPreview? = null
            val result = runCatching {
                checkCurrent(sequence)
                val assets = ProjectAssetStore(store.directory(project.id))
                unsupportedReason(project)?.let { throw IllegalArgumentException(it) }
                val value = HybridPreviewPreparation.prepare(project, exportSettings(project), assets)
                prepared = value
                val audio = if (value.graph.audioTrack == null) null else assets.resolve(project.assets.single { it.id == project.current.music.assetId })
                checkCurrent(sequence)
                // A unique reserved destination is inert until the explicit export action.
                val target = File(cacheDir, "manual-renders/manual-${UUID.randomUUID()}.mp4")
                val request = PreparedHybridExportAdapter.request(value, project, target, audioFile = audio,
                    context = applicationContext, checkCancelled = {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException("Экспорт отменён")
                    })
                Binding(project, value, request)
            }
            if (result.isFailure) prepared?.close()
            runOnUiThread {
                if (destroyed || !started || sequence != generation) {
                    result.getOrNull()?.let { stale -> jobs.execute { stale.prepared.close() } }
                    return@runOnUiThread
                }
                result.fold(onSuccess = { value ->
                    binding = value
                    val plan = value.prepared.framePlan
                    lastShownUs = lastShownUs.coerceIn(0, plan.frames.last().outputTimeUs)
                    seek?.max = plan.frames.lastIndex
                    (seek as? OutputTimeline)?.cutFrames = plan.frames.indices.filter { index ->
                        index > 0 && plan.frames[index].clipIndex != plan.frames[index - 1].clipIndex
                    }
                    val player = DraftPreviewController { state ->
                        if (sequence == generation || stopping) showState(state)
                    }
                    controller = player
                    player.open(value.request)
                    texture?.takeIf { it.isAvailable }?.let { movie -> attachSurface(requireNotNull(movie.surfaceTexture), movie.width, movie.height) }
                    player.seekTo(lastShownUs)
                }, onFailure = { error ->
                    previewStatus?.text = getString(if (error is IllegalArgumentException) R.string.manual_preview_unavailable else R.string.manual_preview_failed)
                    retry?.visibility = View.VISIBLE
                })
            }
        }
    }

    private fun renderVersion() {
        if (busy || stopping) return
        val frozen = binding ?: return
        exporting = true
        setBusy(true, getString(R.string.manual_exporting))
        val sequence = ++generation
        stopPreview(keepPrepared = true) {
            if (destroyed || !started || sequence != generation) return@stopPreview
            binding = null // The export job exclusively owns this prepared lifetime until finally.
            jobs.execute {
                exportThread = Thread.currentThread()
                val target = frozen.request.outputFile
                var published: CompletedRenderStore.Entry? = null
                var linked = false
                val result = try { runCatching {
                    checkCurrent(sequence)
                    target.parentFile!!.mkdirs()
                    val frames = MediaCodecSpeedRampRenderer.render(frozen.request)
                    checkCurrent(sequence)
                    require(frames == frozen.prepared.framePlan.frames.size && target.isFile && target.length() > 0)
                    requireVideoShape(target, frozen.prepared.exportSettings)
                    val latest = store.load(frozen.project.id)
                    require(latest.current.id == frozen.project.current.id)
                    checkCurrent(sequence)
                    val entry = CompletedRenderStore.publish(filesDir, target, getString(R.string.manual_export_summary, latest.current.id))
                    published = entry
                    checkCurrent(sequence)
                    store.save(latest.copy(exports = latest.exports + ProjectExportRef(latest.current.id,
                        entry.file.name, frozen.prepared.exportSettings)), expectedRevisionId = latest.current.id)
                    linked = true
                } } finally {
                    try {
                        target.delete()
                        if (!linked) published?.let { entry -> entry.file.delete(); File(entry.file.parentFile, "${entry.file.name}.properties").delete() }
                    } finally {
                        frozen.prepared.close()
                        exportThread = null
                    }
                }
                runOnUiThread {
                    if (destroyed || !started || sequence != generation) return@runOnUiThread
                    setBusy(false)
                    refresh(getString(if (result.isSuccess) R.string.manual_exported else R.string.manual_export_failed))
                }
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

    private fun chooseFps(fps: Int) {
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
        stopPreview {
            if (destroyed || !started || sequence != generation) return@stopPreview
            jobs.execute {
                val copies = ArrayList<File>()
                val result = runCatching {
                    val inspector = MediaInputInspector {
                        checkCurrent(sequence)
                    }
                    val inputs = uris.mapIndexed { index, uri ->
                        checkCurrent(sequence)
                        val name = videoName(uri, index)
                        val copy = File.createTempFile("manual-input-", ".tmp", cacheDir)
                        copies += copy
                        requireNotNull(contentResolver.openInputStream(uri)) { "Не удалось открыть $name" }.use { input ->
                            FileOutputStream(copy).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    checkCurrent(sequence)
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
                    createBasicProject(inputs, fps) { checkCurrent(sequence) }
                }
                copies.forEach { it.delete() }
                runOnUiThread {
                    if (isDestroyed || sequence != generation) return@runOnUiThread
                    setBusy(false)
                    result.fold(onSuccess = { id ->
                        projectId = id
                        clipId = null; lastShownUs = 0
                        rememberSelection()
                        refresh("Базовый монтаж создан. Можно довести его и сохранить новую версию.")
                    }, onFailure = { error ->
                        status.text = "Не удалось создать базовый монтаж: ${error.message.orEmpty()}"
                    })
                }
            }
        }
    }

    private fun videoName(uri: Uri, index: Int): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Видео ${index + 1}"

    private fun share(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Поделиться монтажом"))
    }

    /** The inspector supplies physical facts; this only publishes through existing asset/import APIs. */
    private fun createBasicProject(inspected: List<Pair<MediaSource, Float>>, fps: Int, checkCancelled: () -> Unit): String {
        require(inspected.size in 1..2)
        ManualMvpBasicGraph.validateSources(inspected.map { it.first })
        val id = "basic-${UUID.randomUUID()}"
        val assets = ProjectAssetStore(store.directory(id))
        val sources = inspected.map { (source, aspect) ->
            checkCancelled()
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
            ProjectExportSettings(1080, 1920, fps, 8_000_000, 192_000), sources, music, null, selections).shared
        checkCancelled()
        store.create(project)
        return id
    }

    private fun change(apply: (HybridProject) -> HybridProject) {
        if (busy) return
        val id = projectId ?: return
        val sequence = ++generation
        setBusy(true, getString(R.string.manual_saving))
        stopPreview {
            if (destroyed || !started || sequence != generation) return@stopPreview
            jobs.execute {
                val result = runCatching {
                    checkCurrent(sequence)
                    val previous = store.load(id)
                    val updated = apply(previous)
                    if (updated !== previous && updated != previous) {
                        checkCurrent(sequence)
                        store.save(updated, expectedRevisionId = previous.current.id)
                    }
                }
                runOnUiThread {
                    if (destroyed || !started || sequence != generation) return@runOnUiThread
                    setBusy(false)
                    refresh(getString(if (result.isSuccess) R.string.manual_saved_change else R.string.manual_change_failed))
                }
            }
        }
    }
    private fun exportSettings(project: HybridProject): ProjectExportSettings {
        val size = project.current.visualSettings.aspect.size(1080)
        return ProjectExportSettings(size.width, size.height, project.fps, 8_000_000, 192_000)
    }
    private fun unsupportedReason(project: HybridProject): String? {
        val revision = project.current
        val graph = revision.graph
        if (project.selectedVideos == null) return getString(R.string.manual_unsupported)
        if (graph.frameAttachments.frames.isNotEmpty() || graph.frameAttachments.maskRefinements.isNotEmpty() ||
            graph.sourceAttachments.any { it.timeline.frames.isNotEmpty() || it.timeline.maskRefinements.isNotEmpty() })
            return getString(R.string.manual_unsupported)
        if (revision.texts.isNotEmpty() || revision.textState.layers.isNotEmpty() ||
            revision.textState.captions.isNotEmpty() || revision.style.showAuthoredText)
            return getString(R.string.manual_unsupported)
        if (revision.visualSettings.framings.any { it.key.aspect == revision.visualSettings.aspect && it.value.mode != FramingMode.MANUAL })
            return getString(R.string.manual_unsupported)
        return null
    }
}
