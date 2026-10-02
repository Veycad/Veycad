package com.example.autoedit

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.core.MirrorMode
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale

class CaptureActivity : AppCompatActivity() {
    private lateinit var owner: CaptureRecordingSession
    private lateinit var preview: PreviewView
    private lateinit var primary: Button
    private lateinit var secondary: Button
    private lateinit var title: TextView
    private lateinit var detail: TextView
    private lateinit var playback: VideoView
    private var provider: ProcessCameraProvider? = null
    private var video: VideoCapture<Recorder>? = null
    private var previewStreaming = false
    private var preparationVisible = false
    private var recoveryFailedSessionId: String? = null
    private var front = true
    private var script = CaptureScript.fear
    private var mode = CaptureMode.MUSIC
    private var countdown = 0
    private var foreground = false
    private var finishingAfterSave = false
    private var review: CaptureSessionStore.Session? = null
    private var cameraGeneration = 0L
    private var waitingForRecording = false
    private var requestedTake: Int? = null
    private var previewWindow: CaptureTakeTimeline.Window? = null
    private var stopButtonStyle = false
    private val previewBoundary = object : Runnable {
        override fun run() {
            if (!foreground) return
            val window = previewWindow ?: return
            if (playback.currentPosition >= window.endMs) {
                playback.pause()
                playback.seekTo(window.startMs.toInt())
            }
            handler.postDelayed(this, 80)
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) bindCamera() else permissionScreen()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture)
        owner = CaptureRecordingSession.get(this)
        waitingForRecording = owner.state.value?.busy == true
        script = CaptureScript.forStyle(intent.getStringExtra(EXTRA_STYLE).orEmpty()) ?: CaptureScript.fear
        mode = runCatching { CaptureMode.valueOf(intent.getStringExtra(EXTRA_MODE).orEmpty()) }.getOrDefault(CaptureMode.MUSIC)
        front = savedInstanceState?.getBoolean("front", true) ?: true
        recoveryFailedSessionId = savedInstanceState?.getString("recovery_failed_session")
        // Zero is an explicit automatic choice. Do not revive the result's original
        // take from the intent when restoring a newer choice after recreation.
        requestedTake = (if (savedInstanceState != null) savedInstanceState.getInt("requested_take", 0)
            else intent.getIntExtra(EXTRA_TAKE, 0)).takeIf { it > 0 }
        preview = findViewById(R.id.capturePreview)
        preview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        preview.scaleType = PreviewView.ScaleType.FIT_CENTER
        primary = findViewById(R.id.capturePrimary)
        secondary = findViewById(R.id.captureSecondary)
        title = findViewById(R.id.captureTitle)
        detail = findViewById(R.id.captureDetail)
        playback = findViewById(R.id.capturePlayback)
        preview.previewStreamState.observe(this) { state ->
            previewStreaming = state == PreviewView.StreamState.STREAMING
            if (previewStreaming) updateFrameAspect()
            if (foreground && review == null && owner.state.value?.busy != true) {
                if (!previewStreaming && countdown > 0) cameraLostBeforeRecording()
                else if (preparationVisible && countdown == 0) updateCameraReadiness()
            }
        }
        findViewById<TextView>(R.id.captureModeLabel).setText(if (mode == CaptureMode.MUSIC) R.string.capture_music else R.string.capture_best)
        findViewById<CaptureFrameOverlay>(R.id.captureFrameOverlay).outputAspect = if (script == CaptureScript.fear) 1f else 9f / 16f
        findViewById<TextView>(R.id.captureFrameHint).setText(if (script == CaptureScript.fear) R.string.capture_frame_square else R.string.capture_frame_portrait)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val system = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(system.left, system.top, system.right, system.bottom)
            insets
        }
        findViewById<View>(R.id.captureRoot).addOnLayoutChangeListener { root, _, _, _, _, _, _, _, _ ->
            val density = resources.displayMetrics.density
            val chrome = if (owner.state.value?.busy == true) 295 else 390
            val height = (root.height - root.paddingTop - root.paddingBottom - chrome * density)
                .toInt().coerceIn((180 * density).toInt(), (430 * density).toInt())
            findViewById<View>(R.id.capturePreviewFrame).let { frame ->
                if (frame.layoutParams.height != height) frame.layoutParams = frame.layoutParams.apply { this.height = height }
            }
        }
        findViewById<View>(R.id.captureActionBar).addOnLayoutChangeListener { bar, _, _, _, _, _, _, _, _ ->
            findViewById<View>(R.id.captureRoot).let { root ->
                val bottom = bar.height + (16 * resources.displayMetrics.density).toInt()
                if (root.paddingBottom != bottom)
                    root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, bottom)
            }
        }
        findViewById<Button>(R.id.captureBack).setOnClickListener { leave() }
        findViewById<Button>(R.id.captureFlip).setOnClickListener {
            if (!owner.state.value!!.busy && countdown == 0) { front = !front; bindCamera() }
        }
        primary.setOnClickListener { startCountdown() }
        findViewById<Button>(R.id.captureAddTake).setOnClickListener { owner.addTake() }
        secondary.setOnClickListener {
            review = null; requestedTake = null; previewWindow = null
            handler.removeCallbacks(previewBoundary)
            playback.stopPlayback(); showPreparation(); bindCamera()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        val reviewId = savedInstanceState?.getString("review_id") ?: intent.getStringExtra(EXTRA_SESSION)
        owner.recover { sessions ->
            if (isDestroyed) return@recover
            sessions.firstOrNull { it.id == reviewId }?.let { showReview(it) }
        }
        owner.state.observe(this) { state ->
            if (state.busy) showRecording(state)
            else if (waitingForRecording && state.phase == CaptureRecordingSession.Phase.READY && state.session != null) {
                waitingForRecording = false
                if (finishingAfterSave) finish()
                else if (foreground && state.session.stopReason == null && hasUsableWindow(state.session)) returnForMontage(state.session)
                else if (state.session.id != review?.id) showReview(state.session)
            } else if (waitingForRecording && state.phase == CaptureRecordingSession.Phase.FAILED) {
                waitingForRecording = false
                showFailure(state.error ?: getString(R.string.capture_empty))
            } else if (waitingForRecording && state.phase == CaptureRecordingSession.Phase.IDLE) {
                waitingForRecording = false
                if (finishingAfterSave) finish() else showPreparation()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        handler.removeCallbacks(previewBoundary)
        if (previewWindow != null) handler.post(previewBoundary)
        if (review == null && owner.state.value?.busy != true) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) bindCamera()
            else permissionScreen()
        }
    }
    override fun onStop() {
        foreground = false
        cancelCountdown()
        playback.pause()
        if (owner.state.value?.busy == true) owner.stop(getString(R.string.capture_interrupted))
        super.onStop()
    }
    override fun onDestroy() {
        cameraGeneration++
        handler.removeCallbacksAndMessages(null)
        playback.stopPlayback()
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("requested_take", requestedTake ?: 0)
        outState.putBoolean("front", front)
        outState.putString("review_id", review?.id ?: owner.state.value?.takeIf { it.busy }?.session?.id)
        outState.putString("recovery_failed_session", recoveryFailedSessionId)
        super.onSaveInstanceState(outState)
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (countdown == 0 && owner.state.value?.busy != true && review == null) bindCamera()
    }

    private fun permissionScreen() {
        preparationVisible = false
        updateRecordButtonStyle(false)
        findViewById<View>(R.id.captureRecordingIndicator).visibility = View.GONE
        findViewById<View>(R.id.capturePreviewFrame).visibility = View.GONE
        findViewById<View>(R.id.captureFrameHint).visibility = View.GONE
        findViewById<View>(R.id.captureControls).visibility = View.GONE
        title.setText(R.string.capture_permission_title)
        detail.setText(R.string.capture_permission_body)
        val asked = getPreferences(MODE_PRIVATE).getBoolean("asked_camera", false)
        val settings = asked && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        primary.isEnabled = true
        primary.setText(if (settings) R.string.capture_settings else R.string.capture_permission)
        primary.setOnClickListener {
            if (settings) startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            else {
                getPreferences(MODE_PRIVATE).edit().putBoolean("asked_camera", true).apply()
                permission.launch(Manifest.permission.CAMERA)
            }
        }
        findViewById<Button>(R.id.captureFlip).isEnabled = false
    }

    private fun bindCamera() {
        if (!foreground || owner.state.value?.busy == true || review != null) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { permissionScreen(); return }
        val generation = ++cameraGeneration
        preparationVisible = false
        previewStreaming = false
        video = null
        primary.isEnabled = false
        LocalDiagnostics.record(this, "capture_camera_requested", mapOf("front" to front.toString(),
            "style" to script.styleId, "mode" to mode.name,
            "quality" to getSharedPreferences("app_settings", MODE_PRIVATE).getString("render_quality", "720p").orEmpty()))
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isDestroyed || !foreground || generation != cameraGeneration || review != null) return@addListener
            runCatching {
                val cameraProvider = future.get()
                var selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                if (!cameraProvider.hasCamera(selector)) {
                    front = !front
                    selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                }
                val fullHd = getSharedPreferences("app_settings", MODE_PRIVATE).getString("render_quality", "720p") == "1080p"
                val quality = if (fullHd) listOf(Quality.FHD, Quality.HD) else listOf(Quality.HD)
                val recorder = Recorder.Builder().setQualitySelector(QualitySelector.fromOrderedList(quality,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.HD))).build()
                val rotation = preview.display?.rotation ?: Surface.ROTATION_0
                val capture = VideoCapture.Builder(recorder).setTargetRotation(rotation)
                    .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY).build()
                val live = Preview.Builder().setTargetRotation(rotation).build()
                live.setSurfaceProvider(preview.surfaceProvider)
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, selector, live, capture)
                provider = cameraProvider
                video = capture
                LocalDiagnostics.record(this, "capture_camera_bound", mapOf("front" to front.toString(),
                    "style" to script.styleId, "mode" to mode.name))
                updateFrameAspect()
                showPreparation()
            }.onFailure {
                video = null
                CaptureDiagnostics.failure(this, CaptureDiagnostics.Stage.CAMERA_BIND, it,
                    mapOf("front" to front.toString(), "style" to script.styleId, "mode" to mode.name))
                showFailure(getString(R.string.capture_unavailable))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateFrameAspect() {
        val resolution = video?.resolutionInfo ?: return
        val size = resolution.resolution
        val swapped = resolution.rotationDegrees % 180 != 0
        findViewById<CaptureFrameOverlay>(R.id.captureFrameOverlay).sourceAspect =
            if (swapped) size.height.toFloat() / size.width else size.width.toFloat() / size.height
    }

    private fun showPreparation() {
        preparationVisible = true
        updateRecordButtonStyle(false)
        findViewById<View>(R.id.captureRecordingIndicator).visibility = View.GONE
        findViewById<View>(R.id.capturePreviewFrame).visibility = View.VISIBLE
        findViewById<View>(R.id.captureFrameHint).visibility = View.VISIBLE
        title.setText(R.string.capture_prepare)
        title.visibility = View.VISIBLE
        findViewById<View>(R.id.captureModeLabel).visibility = View.VISIBLE
        detail.visibility = View.VISIBLE
        detail.text = getString(R.string.capture_prepare_summary, script.durationMs / 1_000,
            if (mode == CaptureMode.MUSIC) 1 else CaptureScript.DEFAULT_TAKES)
        findViewById<Button>(R.id.captureFlip).apply {
            isEnabled = true
            setText(if (front) R.string.capture_front else R.string.capture_back_camera)
        }
        findViewById<View>(R.id.captureControls).visibility = View.VISIBLE
        findViewById<View>(R.id.captureCuePanel).visibility = View.GONE
        findViewById<View>(R.id.captureFrameOverlay).visibility = View.VISIBLE
        playback.visibility = View.GONE
        preview.visibility = View.VISIBLE
        secondary.visibility = View.GONE
        findViewById<View>(R.id.captureTakePicker).visibility = View.GONE
        findViewById<View>(R.id.captureAddTake).visibility = View.GONE
        updateCameraReadiness()
        primary.setOnClickListener { startCountdown() }
        findViewById<TextView>(R.id.captureClock).text = MontageStyleCatalog.restore(script.styleId).title
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun startCountdown() {
        if (countdown > 0 || owner.state.value?.busy == true || video == null || !previewStreaming || !foreground) return
        countdown = CaptureScript.COUNTDOWN_SECONDS
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        findViewById<View>(R.id.captureControls).visibility = View.GONE
        primary.setText(R.string.capture_cancel_countdown)
        primary.setOnClickListener { cancelCountdown(); showPreparation() }
        val label = findViewById<TextView>(R.id.captureCountdown)
        label.visibility = View.VISIBLE
        fun tick() {
            if (!foreground || countdown <= 0) return
            label.text = countdown.toString()
            handler.postDelayed({
                countdown--
                if (countdown == 0) {
                    label.visibility = View.GONE
                    if (!previewStreaming || video == null) { cameraLostBeforeRecording(); return@postDelayed }
                    video?.let { waitingForRecording = true; owner.start(it.output, script, mode, front, it.targetRotation) }
                } else tick()
            }, 1_000)
        }
        tick()
    }
    private fun updateCameraReadiness() {
        val ready = video != null && previewStreaming
        primary.isEnabled = ready
        primary.setText(if (ready) R.string.capture_start else R.string.capture_camera_loading)
    }
    private fun cameraLostBeforeRecording() {
        cancelCountdown()
        LocalDiagnostics.record(this, "capture_camera_lost_before_record", mapOf(
            "style" to script.styleId, "mode" to mode.name, "front" to front.toString()))
        showFailure(getString(R.string.capture_camera_lost_before_record))
    }
    private fun cancelCountdown() {
        countdown = 0
        handler.removeCallbacksAndMessages(null)
        findViewById<View>(R.id.captureCountdown).visibility = View.GONE
    }
    private fun showRecording(state: CaptureRecordingSession.State) {
        preparationVisible = false
        title.setText(R.string.capture_recording_title)
        title.visibility = View.GONE
        findViewById<View>(R.id.captureModeLabel).visibility = View.GONE
        detail.visibility = View.GONE
        findViewById<View>(R.id.captureControls).visibility = View.GONE
        secondary.visibility = View.GONE
        val recording = state.phase == CaptureRecordingSession.Phase.RECORDING
        updateRecordButtonStyle(recording)
        findViewById<View>(R.id.captureRecordingIndicator).visibility = if (recording) View.VISIBLE else View.GONE
        findViewById<View>(R.id.captureAddTake).visibility = if (recording && state.session?.mode == CaptureMode.BEST_TAKE &&
            state.session.requestedTakes < CaptureScript.MAX_TAKES) View.VISIBLE else View.GONE
        primary.isEnabled = recording || state.phase == CaptureRecordingSession.Phase.PREPARING
        primary.setText(when (state.phase) {
            CaptureRecordingSession.Phase.RECORDING -> R.string.capture_stop
            CaptureRecordingSession.Phase.FINALIZING -> R.string.capture_stopping
            else -> R.string.capture_preparing
        })
        primary.setOnClickListener { owner.stop() }
        findViewById<TextView>(R.id.captureClock).text = getString(R.string.capture_take_status,
            state.take, state.session?.requestedTakes ?: 1, duration(state.elapsedMs))
        findViewById<View>(R.id.captureCuePanel).visibility = if (recording) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.captureCue).text = state.cue
        findViewById<TextView>(R.id.captureNextCue).text = state.nextCue?.let { getString(R.string.capture_next, it) }.orEmpty()
    }

    private fun showReview(session: CaptureSessionStore.Session) {
        if (owner.state.value?.busy == true) return
        preparationVisible = false
        updateRecordButtonStyle(false)
        review = session
        findViewById<View>(R.id.captureRecordingIndicator).visibility = View.GONE
        findViewById<View>(R.id.capturePreviewFrame).visibility = View.VISIBLE
        findViewById<View>(R.id.captureFrameHint).visibility = View.GONE
        provider?.unbindAll()
        video = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        title.visibility = View.VISIBLE
        findViewById<View>(R.id.captureModeLabel).visibility = View.VISIBLE
        detail.visibility = View.VISIBLE
        findViewById<View>(R.id.captureAddTake).visibility = View.GONE
        val ready = session.status == CaptureSessionStore.Status.READY && owner.store.recording(session.id).isFile
        title.setText(if (ready) R.string.capture_saved else R.string.capture_needs_check)
        if (ready && recoveryFailedSessionId == session.id) recoveryFailedSessionId = null
        val windows = CaptureTakeTimeline.windows(requireNotNull(CaptureScript.forStyle(session.styleId)),
            session.requestedTakes, session.durationMs)
        val enough = ready && windows.any(CaptureTakeTimeline::usableForMontage)
        val summary = if (!ready) getString(R.string.capture_interrupted)
            else if (!enough && windows.none { it.complete }) getString(R.string.capture_no_complete_take)
            else if (!enough) getString(R.string.capture_short)
            else getString(R.string.capture_saved_summary, MontageStyleCatalog.restore(session.styleId).title, duration(session.durationMs))
        detail.text = listOfNotNull(session.stopReason, session.error,
            getString(R.string.capture_recovery_failed).takeIf { recoveryFailedSessionId == session.id }, summary)
            .filter { it.isNotBlank() }.distinct().joinToString("\n")
        preview.visibility = View.GONE
        findViewById<View>(R.id.captureFrameOverlay).visibility = View.GONE
        findViewById<View>(R.id.captureCuePanel).visibility = View.GONE
        findViewById<View>(R.id.captureControls).visibility = View.GONE
        playback.visibility = View.GONE
        if (!ready) {
            handler.removeCallbacks(previewBoundary)
            previewWindow = null
            playback.stopPlayback()
            findViewById<View>(R.id.capturePreviewFrame).visibility = View.GONE
        }
        if (ready) {
            playback.visibility = View.VISIBLE
            playback.setMediaController(MediaController(this))
            playback.setVideoPath(owner.store.recording(session.id).absolutePath)
            playback.setOnPreparedListener {
                selectPreviewWindow(windows.firstOrNull { it.ordinal == requestedTake }, start = false)
            }
        }
        primary.setText(R.string.capture_make)
        primary.isEnabled = enough && (requestedTake == null ||
            windows.any { it.ordinal == requestedTake && CaptureTakeTimeline.usableForMontage(it) })
        primary.setOnClickListener {
            returnForMontage(session)
        }
        if (!ready && session.status in setOf(CaptureSessionStore.Status.INTERRUPTED, CaptureSessionStore.Status.FAILED) &&
            (owner.store.recording(session.id).isFile || owner.store.staging(session.id).isFile)) {
            primary.setText(R.string.capture_check_saved)
            primary.isEnabled = true
            primary.setOnClickListener {
                primary.isEnabled = false
                primary.setText(R.string.capture_checking_saved)
                owner.inspectInterrupted(session.id) { result ->
                    if (!isDestroyed && review?.id == session.id) result.onSuccess(::showReview).onFailure {
                        recoveryFailedSessionId = session.id
                        showReview(session)
                    }
                }
            }
        }
        val assessments = CaptureTakePreparation.readAssessments(owner.store.directory(session.id)).associateBy { it.ordinal }
        findViewById<Button>(R.id.captureTakePicker).apply {
            visibility = if (ready && session.requestedTakes > 1) View.VISIBLE else View.GONE
            setOnClickListener {
                val labels = listOf(getString(R.string.capture_auto_select)) + windows.map {
                    val base = getString(when {
                        !it.complete -> R.string.capture_take_incomplete
                        it.durationMs < EditDurationPolicy.MINIMUM_MS -> R.string.capture_take_short
                        it.ordinal == session.recommendedTake -> R.string.capture_recommended_take
                        else -> R.string.capture_take_preview
                    }, it.ordinal, duration(it.durationMs))
                    val reason = assessments[it.ordinal]?.reason
                    if (reason == null) base else getString(R.string.capture_take_with_reason, base, reason)
                }
                AlertDialog.Builder(this@CaptureActivity).setTitle(R.string.capture_other_take)
                    .setSingleChoiceItems(labels.toTypedArray(), windows.indexOfFirst { it.ordinal == requestedTake } + 1) { dialog, index ->
                        val selected = windows.getOrNull(index - 1)
                        requestedTake = selected?.ordinal
                        selectPreviewWindow(selected, start = true)
                        primary.isEnabled = enough && (selected == null ||
                            CaptureTakeTimeline.usableForMontage(selected))
                        detail.text = labels[index]
                        dialog.dismiss()
                    }.setNegativeButton(android.R.string.cancel, null).show()
            }
        }
        secondary.visibility = View.VISIBLE
        findViewById<TextView>(R.id.captureClock).text = duration(session.durationMs)
    }
    private fun selectPreviewWindow(window: CaptureTakeTimeline.Window?, start: Boolean) {
        previewWindow = window
        handler.removeCallbacks(previewBoundary)
        playback.seekTo(window?.startMs?.toInt() ?: 0)
        if (start) playback.start()
        if (window != null) handler.post(previewBoundary)
    }
    private fun hasUsableWindow(session: CaptureSessionStore.Session): Boolean {
        val savedScript = CaptureScript.forStyle(session.styleId) ?: return false
        return CaptureTakeTimeline.windows(savedScript, session.requestedTakes, session.durationMs)
            .any(CaptureTakeTimeline::usableForMontage)
    }
    private fun showFailure(message: String) {
        preparationVisible = false
        provider?.unbindAll()
        video = null
        previewStreaming = false
        previewWindow = null
        handler.removeCallbacks(previewBoundary)
        playback.stopPlayback()
        playback.visibility = View.GONE
        updateRecordButtonStyle(false)
        findViewById<View>(R.id.captureRecordingIndicator).visibility = View.GONE
        findViewById<View>(R.id.capturePreviewFrame).visibility = View.GONE
        findViewById<View>(R.id.captureFrameHint).visibility = View.GONE
        findViewById<View>(R.id.captureControls).visibility = View.GONE
        findViewById<View>(R.id.captureCuePanel).visibility = View.GONE
        findViewById<View>(R.id.captureAddTake).visibility = View.GONE
        findViewById<View>(R.id.captureModeLabel).visibility = View.VISIBLE
        secondary.visibility = View.GONE
        title.setText(R.string.capture_failed_title)
        title.visibility = View.VISIBLE
        detail.visibility = View.VISIBLE
        detail.text = message
        primary.isEnabled = true
        primary.setText(R.string.capture_retry)
        primary.setOnClickListener { review = null; bindCamera() }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    private fun returnForMontage(session: CaptureSessionStore.Session) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_SESSION, session.id).apply {
            requestedTake?.let { putExtra(EXTRA_TAKE, it) }
        })
        finish()
    }
    private fun updateRecordButtonStyle(stopping: Boolean) {
        if (stopButtonStyle == stopping) return
        stopButtonStyle = stopping
        primary.setBackgroundResource(if (stopping) R.drawable.capture_primary_stop else R.drawable.capture_primary_start)
    }
    private fun leave() {
        if (countdown > 0) { cancelCountdown(); showPreparation(); return }
        if (owner.state.value?.busy == true) {
            AlertDialog.Builder(this).setTitle(R.string.capture_exit_title).setMessage(R.string.capture_exit_body)
                .setNegativeButton(R.string.capture_continue, null)
                .setPositiveButton(R.string.capture_keep_exit) { _, _ -> finishingAfterSave = true; owner.stop() }.show()
        } else finish()
    }
    companion object {
        const val EXTRA_STYLE = "capture_style"
        const val EXTRA_MODE = "capture_mode"
        const val EXTRA_SESSION = "capture_session"
        const val EXTRA_TAKE = "capture_take"
        private fun duration(ms: Long): String = String.format(Locale.ROOT, "%d:%02d", ms / 60_000, ms / 1_000 % 60)
    }
}
