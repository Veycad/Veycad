package com.example.autoedit

import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.util.concurrent.Executors

/** Production "one director" flow. There is intentionally no manual editing mode. */
class MainActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var sourceFile: File? = null
    private var secondarySourceFile: File? = null
    private var sourceDisplayNames: List<String> = emptyList()
    private var musicSelection: BuiltInMusicCatalog.Selection? = null
    private val musicRequests = MontageStylePresentation.MusicRequests()
    private var renderedFile: File? = null
    private var renderedSaved = false
    private var resultOpenedFromLibrary = false
    private var rendering = false
    private var importing = false
    private var montageStyle = MontageStyleCatalog.heartbeat
    private lateinit var session: EditSession
    private var completedEntry: CompletedRenderStore.Entry? = null
    private var legacySaveEntry: CompletedRenderStore.Entry? = null
    private var saving = false
    private var observedEntry: File? = null
    private var previewGeneration = 0
    private var previewPrepared = false
    private var previewPlaybackRequested = false
    private var activityResumed = false

    private lateinit var selectVideoButton: Button
    private lateinit var renderButton: Button
    private lateinit var progressPanel: LinearLayout
    private lateinit var progressTitle: TextView
    private lateinit var progressDetail: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var resultPanel: LinearLayout
    private lateinit var resultVideo: VideoView
    private lateinit var resultLoading: TextView
    private lateinit var resultSummary: TextView
    private lateinit var directorContent: ScrollView
    private lateinit var resultContent: ScrollView
    private lateinit var myEditsContent: ScrollView
    private lateinit var settingsContent: ScrollView
    private lateinit var myEditsList: LinearLayout
    private lateinit var saveButton: Button
    private lateinit var autoSaveSwitch: SwitchCompat
    private lateinit var notificationSwitch: SwitchCompat

    private val videoGallery = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.data?.let { uri ->
            session.importVideos(listOf(uri), montageStyle)
        }
    }
    private val dualVideoGallery = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val unique = uris.distinct()
        if (unique.isEmpty()) return@registerForActivityResult
        if (unique.size != 2) {
            toast("Для стиля DUALITY выберите ровно два видео")
            return@registerForActivityResult
        }
        session.importVideos(unique, montageStyle)
    }
    private val videoAccessRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        LocalDiagnostics.record(this, "video_access_result", mapOf("full_access" to granted.toString()))
        if (granted) {
            openVideoGallery()
        } else {
            toast("Разрешите полный доступ к видео, чтобы открыть галерею")
        }
    }
    private val legacySave = registerForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val entry = legacySaveEntry
        legacySaveEntry = null
        if (uri != null && entry != null) session.save(entry, uri)
        updateReadyState()
    }
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        settingsPreferences().edit().putBoolean(PREF_NOTIFICATIONS, granted).apply()
        notificationSwitch.isChecked = granted
        if (!granted) toast("Уведомления не включены — разрешение не выдано")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        // Android 15+ lays the app behind system bars. Keep all scrollable actions
        // inside the touchable area, including the last settings/library button.
        val content = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(content)
        session = EditSession.get(this)
        bindViews()
        findViewById<TextView>(R.id.appVersion).text =
            "Veykad ${BuildConfig.VERSION_NAME} · сборка ${BuildConfig.VERSION_CODE}"
        montageStyle = MontageStyleCatalog.restore(
            getSharedPreferences("montage_style", MODE_PRIVATE).getString("selected", null))
        updateStyleLabel()
        bindActions()
        savedInstanceState?.getString("legacy_save_file")?.let { path ->
            session.state.value?.entry?.takeIf { it.file.path == path }?.let { legacySaveEntry = it }
        }
        restoreSourceDraft()
        updateMyEditsButton()
        LocalDiagnostics.record(this, "app_open")
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    resultContent.visibility == View.VISIBLE && resultOpenedFromLibrary -> showMyEdits()
                    resultContent.visibility == View.VISIBLE -> showDirectorScreen()
                    myEditsContent.visibility == View.VISIBLE -> showDirectorScreen()
                    settingsContent.visibility == View.VISIBLE -> showDirectorScreen()
                    else -> finish()
                }
            }
        })
        showDirectorAfterSplash()
        session.state.observe(this) { state ->
            importing = state.importing
            rendering = state.busy && !state.saving && !state.importing
            saving = state.saving
            completedEntry = state.entry
            updateReadyState()
            state.draft?.let { draft ->
                sourceFile = draft.files[0]
                secondarySourceFile = draft.files.getOrNull(1)
                sourceDisplayNames = draft.names
                if (draft.music.styleId == montageStyle.id) {
                    musicRequests.invalidate()
                    musicSelection = draft.music
                } else if (musicSelection?.styleId != montageStyle.id) musicSelection = null
                updateSourceDisplay()
                updateMusicDisplay()
                if (musicSelection == null) refreshMusicForStyle()
                updateReadyState()
            }
            if (importing) {
                progressPanel.visibility = View.VISIBLE
                progressBar.isIndeterminate = true
                progressTitle.text = "Загружаю видео"
                progressDetail.text = "Копирую выбранные файлы"
            } else if (saving) {
                progressPanel.visibility = View.VISIBLE
                progressBar.isIndeterminate = true
                progressTitle.text = "Сохраняю монтаж"
                progressDetail.text = "Копирую видео в галерею и библиотеку"
            } else if (rendering) {
                progressPanel.visibility = View.VISIBLE
                state.progress?.let(::showProgress)
            } else progressPanel.visibility = View.GONE
            if (state.entry != null && observedEntry != state.entry.file && !state.busy) {
                observedEntry = state.entry.file
                showCompletedResult(state.entry)
                notifyRenderReady(state.entry)
                if (settingsPreferences().getBoolean(PREF_AUTO_SAVE, false) && !state.entry.saved) {
                    session.save(state.entry)
                }
            } else if (!resultOpenedFromLibrary && renderedFile == state.entry?.file) {
                renderedSaved = state.entry?.saved == true
                saveButton.isEnabled = !state.busy && !renderedSaved
                saveButton.text = if (saving) "Сохраняю…" else if (renderedSaved) "Сохранено" else "Сохранить в галерею"
            }
            state.error?.let { error ->
                toast(error)
                // A transient Toast can disappear before the user returns to the screen.
                // Keep the job failure visible until another action refreshes readiness.
                findViewById<TextView>(R.id.renderHint).text = error
            }
            findViewById<Button>(R.id.cancelRenderButton).visibility = if (rendering) View.VISIBLE else View.GONE
            updateMyEditsButton()
        }
    }

    override fun onDestroy() {
        stopPreviewPlayback()
        musicRequests.invalidate()
        // Only music selection uses the screen executor; media jobs belong to EditSession.
        worker.shutdown()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        if (canPlayPreview()) resultVideo.start()
    }

    override fun onPause() {
        activityResumed = false
        if (resultVideo.isPlaying) resultVideo.pause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        legacySaveEntry?.let { outState.putString("legacy_save_file", it.file.path) }
        super.onSaveInstanceState(outState)
    }

    private fun bindViews() {
        selectVideoButton = findViewById(R.id.selectVideoButton)
        renderButton = findViewById(R.id.renderButton)
        progressPanel = findViewById(R.id.progressPanel)
        progressTitle = findViewById(R.id.progressTitle)
        progressDetail = findViewById(R.id.progressDetail)
        progressBar = findViewById(R.id.renderProgress)
        resultPanel = findViewById(R.id.resultPanel)
        resultVideo = findViewById(R.id.resultVideo)
        resultLoading = findViewById(R.id.resultLoading)
        resultSummary = findViewById(R.id.resultSummary)
        directorContent = findViewById(R.id.directorContent)
        resultContent = findViewById(R.id.resultContent)
        myEditsContent = findViewById(R.id.myEditsContent)
        settingsContent = findViewById(R.id.settingsContent)
        myEditsList = findViewById(R.id.myEditsList)
        saveButton = findViewById(R.id.saveButton)
        autoSaveSwitch = findViewById(R.id.autoSaveSwitch)
        notificationSwitch = findViewById(R.id.notificationSwitch)
    }

    private fun bindActions() {
        findViewById<Button>(R.id.selectStyleButton).setOnClickListener { showStylePicker() }
        findViewById<TextView>(R.id.allStylesButton).setOnClickListener { showStylePicker() }
        findViewById<TextView>(R.id.settingsButton).setOnClickListener { showSettings() }
        findViewById<TextView>(R.id.backFromSettingsButton).setOnClickListener { showDirectorScreen() }
        findViewById<View>(R.id.defaultStyleSetting).setOnClickListener { showStylePicker(fromSettings = true) }
        findViewById<View>(R.id.qualitySetting).setOnClickListener { showQualityPicker() }
        findViewById<Button>(R.id.clearCacheButton).setOnClickListener { confirmClearTemporaryFiles() }
        findViewById<Button>(R.id.privacyButton).setOnClickListener { showPrivacy() }
        findViewById<Button>(R.id.helpButton).setOnClickListener { showHelp() }
        findViewById<Button>(R.id.versionButton).setOnClickListener { showVersion() }
        autoSaveSwitch.setOnCheckedChangeListener { _, checked ->
            settingsPreferences().edit().putBoolean(PREF_AUTO_SAVE, checked).apply()
        }
        notificationSwitch.setOnCheckedChangeListener { _, checked ->
            if (!checked) {
                settingsPreferences().edit().putBoolean(PREF_NOTIFICATIONS, false).apply()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                settingsPreferences().edit().putBoolean(PREF_NOTIFICATIONS, true).apply()
            }
        }
        selectVideoButton.setOnClickListener {
            if (rendering || importing || saving || legacySaveEntry != null) return@setOnClickListener
            LocalDiagnostics.record(this, "tap_select_video")
            if (montageStyle.sourceCount == 2) {
                dualVideoGallery.launch(arrayOf("video/*"))
            } else if (hasFullVideoAccess()) {
                openVideoGallery()
            } else {
                videoAccessRequest.launch(videoPermission())
            }
        }
        renderButton.setOnClickListener {
            LocalDiagnostics.record(this, "tap_render")
            startAutomaticEdit()
        }
        saveButton.setOnClickListener {
            LocalDiagnostics.record(this, "tap_save")
            saveResult()
        }
        findViewById<Button>(R.id.shareButton).setOnClickListener {
            LocalDiagnostics.record(this, "tap_share")
            shareResult()
        }
        findViewById<Button>(R.id.myEditsButton).setOnClickListener {
            LocalDiagnostics.record(this, "tap_my_edits")
            showMyEdits()
        }
        findViewById<Button>(R.id.backFromEditsButton).setOnClickListener { showDirectorScreen() }
        findViewById<Button>(R.id.newEditButton).setOnClickListener {
            LocalDiagnostics.record(this, "tap_new_edit")
            showNewEditScreen()
        }
        findViewById<Button>(R.id.cancelRenderButton).setOnClickListener {
            session.cancelRender()
            progressDetail.text = "Отменяю монтаж. Освобождаю ресурсы…"
        }
        // VideoView consumes touches for its optional media controller on some Android
        // versions, so its normal click listener alone does not receive screen taps.
        resultVideo.setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            true
        }
        resultVideo.setOnClickListener {
            LocalDiagnostics.record(this, "tap_preview", mapOf("playing" to resultVideo.isPlaying.toString()))
            if (resultVideo.isPlaying) {
                previewPlaybackRequested = false
                resultVideo.pause()
            } else {
                previewPlaybackRequested = true
                if (canPlayPreview()) resultVideo.start()
            }
        }
    }

    private fun showStylePicker(fromSettings: Boolean = false) {
        if (rendering || importing || saving || legacySaveEntry != null) return
        LocalDiagnostics.record(this, "tap_select_style")
        StylePickerDialog.show(this, MontageStyleCatalog.all, montageStyle.id) { selected ->
            if (!selected.available) return@show
            montageStyle = selected
            getSharedPreferences("montage_style", MODE_PRIVATE).edit()
                .putString("selected", selected.id).apply()
            updateStyleLabel()
            refreshMusicForStyle()
            if (fromSettings) updateSettingsValues()
            LocalDiagnostics.record(this, "select_montage_style", mapOf("style" to selected.id))
        }
    }

    private fun showDirectorAfterSplash() {
        val splash = findViewById<View>(R.id.splashContent)
        splash.postDelayed({
            if (resultContent.visibility != View.VISIBLE && myEditsContent.visibility != View.VISIBLE) directorContent.visibility = View.VISIBLE
            directorContent.animate().alpha(1f).setDuration(260L).start()
            splash.animate().alpha(0f).setDuration(220L).withEndAction {
                splash.visibility = View.GONE
                LocalDiagnostics.record(this, "director_ready")
            }.start()
        }, SPLASH_DURATION_MS)
    }

    private fun hasFullVideoAccess(): Boolean =
        checkSelfPermission(videoPermission()) == PackageManager.PERMISSION_GRANTED

    private fun videoPermission(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    private fun openVideoGallery() {
        LocalDiagnostics.record(this, "open_video_gallery")
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).apply {
            type = "video/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        videoGallery.launch(intent)
    }

    private fun updateReadyState() {
        val ready = !rendering && !importing && !saving && legacySaveEntry == null
        findViewById<Button>(R.id.myEditsButton).isEnabled = ready
        findViewById<Button>(R.id.newEditButton).isEnabled = ready
        selectVideoButton.isEnabled = ready
        findViewById<Button>(R.id.selectStyleButton).isEnabled = ready
        val sourcesReady = sourceFile?.isFile == true &&
            (montageStyle.sourceCount == 1 || secondarySourceFile?.isFile == true)
        val musicReady = musicSelection?.let { it.styleId == montageStyle.id && it.file.isFile } == true
        renderButton.isEnabled = ready && montageStyle.available && sourcesReady && musicReady
        findViewById<TextView>(R.id.renderHint).text = when {
            importing -> "Добавляю исходники…"
            rendering -> "Монтаж создаётся"
            saving -> "Сохраняю результат…"
            !montageStyle.available -> montageStyle.unavailableLabel
            !sourcesReady -> if (montageStyle.sourceCount == 2) "Сначала добавь два видео" else "Сначала добавь видео"
            !musicReady -> "Подбираю музыку…"
            else -> "Всё готово к монтажу"
        }
    }

    private fun updateStyleLabel() {
        val presentation = MontageStylePresentation.forStyle(montageStyle)
        findViewById<Button>(R.id.selectStyleButton).text = presentation.selectorLabel
        selectVideoButton.text = presentation.addSourceLabel
        updateSourceDisplay()
        updateMusicDisplay()
        updateReadyState()
    }

    private fun updateSourceDisplay() {
        findViewById<TextView>(R.id.videoSelection).text =
            MontageStylePresentation.forStyle(montageStyle).sourceDisplay(sourceDisplayNames)
    }

    private fun updateMusicDisplay() {
        val music = musicSelection?.takeIf { it.styleId == montageStyle.id }
        findViewById<TextView>(R.id.musicSelection).text = if (music != null) {
            "♫ ${music.track.title} · ${music.track.bpm} BPM"
        } else MontageStylePresentation.forStyle(montageStyle).musicPlaceholder
    }

    private fun refreshMusicForStyle() {
        val styleId = montageStyle.id
        val request = musicRequests.begin(styleId)
        musicSelection = null
        findViewById<TextView>(R.id.musicSelection).text = MontageStylePresentation.forStyle(montageStyle).musicLoading
        updateReadyState()
        worker.execute {
            runCatching { BuiltInMusicCatalog.select(applicationContext, styleId) }
                .onSuccess { selected -> runOnUiThread {
                    if (isDestroyed || !musicRequests.isCurrent(request, montageStyle.id)) return@runOnUiThread
                    musicSelection = selected
                    findViewById<TextView>(R.id.musicSelection).text =
                        "♫ ${selected.track.title} · ${selected.track.bpm} BPM"
                    updateReadyState()
                    LocalDiagnostics.record(this, "style_music_ready", mapOf(
                        "style" to styleId,
                        "music" to selected.track.id
                    ))
                } }
                .onFailure { error -> runOnUiThread {
                    if (isDestroyed || !musicRequests.isCurrent(request, montageStyle.id)) return@runOnUiThread
                    updateReadyState()
                    showFailure(error)
                } }
        }
    }

    private fun startAutomaticEdit() {
        if (rendering || importing || saving || legacySaveEntry != null) return
        if (!montageStyle.available) {
            toast(montageStyle.unavailableLabel)
            return
        }
        val source = sourceFile ?: return
        val secondary = secondarySourceFile.takeIf { montageStyle.sourceCount == 2 }
        if (montageStyle.sourceCount == 2 && secondary == null) return
        val music = musicSelection ?: return
        if (music.styleId != montageStyle.id) {
            refreshMusicForStyle()
            return
        }
        val fullHd = settingsPreferences().getString(PREF_QUALITY, QUALITY_720) == QUALITY_1080
        session.render(
            source, secondary, music.file, montageStyle,
            width = if (fullHd) 1080 else 720,
            height = if (fullHd) 1920 else 1280,
            bitrate = if (fullHd) 8_000_000 else 5_000_000
        )
    }

    private fun showProgress(progress: VeycadAutomaticEditor.Progress) {
        val copy = when (progress.stage) {
            VeycadAutomaticEditor.Stage.ANALYSING_VIDEO ->
                "Анализирую видео" to "Ищу лица, движение, композицию и чистые моменты"
            VeycadAutomaticEditor.Stage.DIRECTING ->
                "Строю режиссуру" to "Сопоставляю визуальные события с ритмом музыки"
            VeycadAutomaticEditor.Stage.RENDERING ->
                "Рендерю варианты" to "Кандидат ${progress.completedCandidates + 1} из ${progress.totalCandidates}: GLES, H.264 и AAC"
            VeycadAutomaticEditor.Stage.VERIFYING ->
                "Проверяю результат" to "Готово ${progress.completedCandidates} из ${progress.totalCandidates}: декодирую и измеряю качество"
            VeycadAutomaticEditor.Stage.COMPLETE -> "Монтаж готов" to "Выбран лучший вариант"
        }
        progressTitle.text = copy.first
        progressDetail.text = copy.second
        progressBar.isIndeterminate = progress.stage in setOf(
            VeycadAutomaticEditor.Stage.ANALYSING_VIDEO,
            VeycadAutomaticEditor.Stage.DIRECTING
        )
        if (!progressBar.isIndeterminate) {
            progressBar.progress = when (progress.stage) {
                VeycadAutomaticEditor.Stage.RENDERING,
                VeycadAutomaticEditor.Stage.VERIFYING ->
                    progress.completedCandidates * 100 / progress.totalCandidates.coerceAtLeast(1)
                VeycadAutomaticEditor.Stage.COMPLETE -> 100
                else -> 0
            }
        }
    }

    private fun showCompletedResult(entry: CompletedRenderStore.Entry) {
        renderedFile = entry.file
        resultOpenedFromLibrary = false
        renderedSaved = entry.saved
        directorContent.visibility = View.GONE
        myEditsContent.visibility = View.GONE
        settingsContent.visibility = View.GONE
        resultContent.visibility = View.VISIBLE
        resultContent.scrollTo(0, 0)
        resultPanel.visibility = View.VISIBLE
        saveButton.visibility = View.VISIBLE
        saveButton.isEnabled = !entry.saved && !saving
        saveButton.text = if (entry.saved) "✓  Сохранено" else "↓  Сохранить в галерею"
        resultSummary.text = entry.summary
        preparePreview(entry.file)
    }

    private fun preparePreview(file: File) {
        stopPreviewPlayback()
        val generation = previewGeneration
        previewPlaybackRequested = true
        resultLoading.visibility = View.VISIBLE
        resultLoading.text = "Открываю готовый монтаж…"
        resultVideo.visibility = View.VISIBLE
        resultVideo.alpha = 0f
        resultVideo.setOnPreparedListener { player ->
            if (generation != previewGeneration) return@setOnPreparedListener
            previewPrepared = true
            player.isLooping = true
            player.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            resultVideo.seekTo(1)
            resultVideo.alpha = 1f
            resultLoading.visibility = View.GONE
            if (canPlayPreview()) resultVideo.start()
        }
        resultVideo.setOnErrorListener { _, what, extra ->
            if (generation != previewGeneration) return@setOnErrorListener true
            previewPrepared = false
            previewPlaybackRequested = false
            LocalDiagnostics.record(this, "preview_failed", mapOf(
                "what" to what.toString(),
                "extra" to extra.toString(),
                "bytes" to file.length().toString()
            ))
            resultVideo.alpha = 0f
            resultLoading.visibility = View.VISIBLE
            resultLoading.text = "Предпросмотр не открылся. Готовое видео можно сохранить в галерею."
            true
        }
        resultVideo.setVideoPath(file.absolutePath)
    }

    private fun canPlayPreview(): Boolean =
        previewPlaybackRequested && previewPrepared && activityResumed &&
            resultContent.visibility == View.VISIBLE && resultVideo.visibility == View.VISIBLE

    private fun stopPreviewPlayback() {
        previewGeneration += 1
        previewPrepared = false
        previewPlaybackRequested = false
        resultVideo.setOnPreparedListener(null)
        resultVideo.setOnErrorListener(null)
        resultVideo.stopPlayback()
    }

    private fun showSettings() {
        if (rendering || importing || saving || legacySaveEntry != null) return
        stopPreviewPlayback()
        directorContent.visibility = View.GONE
        resultContent.visibility = View.GONE
        myEditsContent.visibility = View.GONE
        settingsContent.visibility = View.VISIBLE
        settingsContent.scrollTo(0, 0)
        updateSettingsValues()
        LocalDiagnostics.record(this, "open_settings")
    }

    private fun updateSettingsValues() {
        findViewById<TextView>(R.id.settingsStyleValue).text = "${montageStyle.title}  ›"
        val quality = settingsPreferences().getString(PREF_QUALITY, QUALITY_720) ?: QUALITY_720
        findViewById<TextView>(R.id.settingsQualityValue).text = "$quality  ›"
        autoSaveSwitch.isChecked = settingsPreferences().getBoolean(PREF_AUTO_SAVE, false)
        notificationSwitch.isChecked = settingsPreferences().getBoolean(PREF_NOTIFICATIONS, false)
        findViewById<TextView>(R.id.settingsCacheValue).text = formatBytes(temporaryBytes())
        findViewById<Button>(R.id.versionButton).text =
            "Версия Veykad                                      ${BuildConfig.VERSION_NAME}"
    }

    private fun showQualityPicker() {
        val values = arrayOf(QUALITY_720, QUALITY_1080)
        val current = settingsPreferences().getString(PREF_QUALITY, QUALITY_720)
        AlertDialog.Builder(this)
            .setTitle("Качество готового видео")
            .setSingleChoiceItems(values, values.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                settingsPreferences().edit().putString(PREF_QUALITY, values[which]).apply()
                updateSettingsValues()
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun confirmClearTemporaryFiles() {
        if (session.state.value?.busy == true) {
            toast("Дождитесь завершения текущей операции")
            return
        }
        val size = formatBytes(temporaryBytes())
        AlertDialog.Builder(this)
            .setTitle("Очистить временные файлы?")
            .setMessage("Будут удалены выбранные исходники, кэш анализа и незавершённые данные рендера ($size). Сохранённые эдиты останутся.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Очистить") { _, _ ->
                musicRequests.invalidate()
                session.clearDraft()
                getSharedPreferences(SOURCE_DRAFT_PREFS, MODE_PRIVATE).edit().clear().apply()
                RenderWorkspace.clearTransient(filesDir, cacheDir)
                cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                sourceFile = null
                secondarySourceFile = null
                sourceDisplayNames = emptyList()
                musicSelection = null
                updateSourceDisplay()
                updateMusicDisplay()
                updateReadyState()
                updateSettingsValues()
                toast("Временные файлы очищены")
            }
            .show()
    }

    private fun showPrivacy() {
        AlertDialog.Builder(this)
            .setTitle("Конфиденциальность")
            .setMessage("Видео обрабатываются только на устройстве. Veykad не отправляет исходники, кадры или готовые монтажи в интернет. Доступ к видео используется только для выбранных вами файлов.")
            .setPositiveButton("Понятно", null)
            .show()
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle("Помощь")
            .setMessage("Как создать монтаж?\nДобавьте видео, выберите стиль и нажмите «Создать монтаж».\n\nГде результат?\nПосле рендера он появится в «Эдитах». Сохранение в галерею выполняется отдельной кнопкой или автоматически, если включено в настройках.\n\nПочему кнопка неактивна?\nСначала выберите нужное число исходников и дождитесь подбора музыки.")
            .setPositiveButton("Готово", null)
            .show()
    }

    private fun showVersion() {
        AlertDialog.Builder(this)
            .setTitle("Veykad ${BuildConfig.VERSION_NAME}")
            .setMessage("Сборка ${BuildConfig.VERSION_CODE}\nЛокальный движок автоматического монтажа")
            .setPositiveButton("Закрыть", null)
            .show()
    }

    private fun temporaryBytes(): Long {
        fun File.bytes(): Long = when {
            isFile -> length()
            isDirectory -> listFiles()?.sumOf { it.bytes() } ?: 0L
            else -> 0L
        }
        return cacheDir.bytes() +
            RenderWorkspace.sourceDraftDirectory(filesDir).bytes() +
            RenderWorkspace.pendingRendersDirectory(filesDir).bytes()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "%.1f ГБ".format(bytes / 1_000_000_000f)
        bytes >= 1_000_000L -> "%.1f МБ".format(bytes / 1_000_000f)
        bytes >= 1_000L -> "%.1f КБ".format(bytes / 1_000f)
        else -> "$bytes Б"
    }

    private fun notifyRenderReady(entry: CompletedRenderStore.Entry) {
        if (!settingsPreferences().getBoolean(PREF_NOTIFICATIONS, false)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val preferences = settingsPreferences()
        if (preferences.getString(PREF_LAST_NOTIFIED, null) == entry.file.path) return
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(
                READY_CHANNEL_ID, "Готовые монтажи", NotificationManager.IMPORTANCE_DEFAULT
            ))
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.notify(READY_NOTIFICATION_ID, NotificationCompat.Builder(this, READY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Монтаж готов")
            .setContentText("Откройте Veykad, чтобы посмотреть результат")
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build())
        preferences.edit().putString(PREF_LAST_NOTIFIED, entry.file.path).apply()
    }

    private fun settingsPreferences() = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)

    private fun showNewEditScreen() {
        if (session.state.value?.busy == true || legacySaveEntry != null) return
        stopPreviewPlayback()
        musicRequests.invalidate()
        session.clearDraft()
        getSharedPreferences(SOURCE_DRAFT_PREFS, MODE_PRIVATE).edit().clear().apply()
        resultContent.visibility = View.GONE
        myEditsContent.visibility = View.GONE
        settingsContent.visibility = View.GONE
        directorContent.visibility = View.VISIBLE
        directorContent.scrollTo(0, 0)
        resultPanel.visibility = View.GONE
        sourceFile = null
        secondarySourceFile = null
        sourceDisplayNames = emptyList()
        musicSelection = null
        renderedFile = null
        renderedSaved = false
        resultOpenedFromLibrary = false
        updateSourceDisplay()
        updateMusicDisplay()
        updateReadyState()
    }

    private fun showDirectorScreen() {
        stopPreviewPlayback()
        myEditsContent.visibility = View.GONE
        settingsContent.visibility = View.GONE
        resultContent.visibility = View.GONE
        directorContent.visibility = View.VISIBLE
        updateMyEditsButton()
    }

    private fun showMyEdits() {
        if (rendering || importing || saving || legacySaveEntry != null) return
        stopPreviewPlayback()
        directorContent.visibility = View.GONE
        resultContent.visibility = View.GONE
        settingsContent.visibility = View.GONE
        myEditsContent.visibility = View.VISIBLE
        myEditsContent.scrollTo(0, 0)
        myEditsList.removeAllViews()
        val edits = myEditsDirectory().listFiles()
            ?.filter { it.isFile && it.extension.equals("mp4", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        completedEntry?.takeIf { !it.saved }?.let { entry ->
            myEditsList.addView(Button(this).apply {
                text = "Последний монтаж · ещё не сохранён в галерею"
                isAllCaps = false
                setOnClickListener { showCompletedResult(entry) }
            })
        }
        if (edits.isEmpty() && completedEntry?.saved != false) {
            myEditsList.addView(TextView(this).apply {
                text = "✦\n\nЗдесь появятся эдиты\n\nСоздай первый монтаж — он сохранится в твоей коллекции."
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.text_primary))
                textSize = 18f
                background = getDrawable(R.drawable.panel_surface)
                setPadding(32, 72, 32, 72)
            })
        } else edits.forEach { file ->
            myEditsList.addView(Button(this).apply {
                text = file.nameWithoutExtension
                isAllCaps = false
                setTextColor(getColor(R.color.text_primary))
                background = getDrawable(R.drawable.panel_surface)
                minHeight = (72 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (10 * resources.displayMetrics.density).toInt() }
                setOnClickListener { showStoredEdit(file) }
            })
        }
    }

    private fun showStoredEdit(file: File) {
        if (rendering || importing || saving || legacySaveEntry != null) return
        renderedFile = file
        renderedSaved = true
        resultOpenedFromLibrary = true
        myEditsContent.visibility = View.GONE
        directorContent.visibility = View.GONE
        settingsContent.visibility = View.GONE
        resultContent.visibility = View.VISIBLE
        resultContent.scrollTo(0, 0)
        resultPanel.visibility = View.VISIBLE
        saveButton.visibility = View.GONE
        resultSummary.text = "Сохранённый монтаж · ${file.length() / 1_000_000f} МБ"
        preparePreview(file)
    }

    private fun saveResult() {
        val entry = completedEntry?.takeIf { it.file == renderedFile } ?: return
        if (entry.saved || rendering || importing || saving || legacySaveEntry != null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            legacySaveEntry = entry
            updateReadyState()
            legacySave.launch("${entry.file.nameWithoutExtension}.mp4")
        } else session.save(entry)
    }

    private fun shareResult() {
        val source = renderedFile?.takeIf { it.isFile } ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", source)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Поделиться монтажом"))
        LocalDiagnostics.record(this, "share_opened", mapOf(
            "saved" to renderedSaved.toString(),
            "bytes" to source.length().toString()
        ))
    }

    private fun myEditsDirectory(): File = File(filesDir, "my-edits").apply { mkdirs() }

    private fun updateMyEditsButton() {
        val count = myEditsDirectory().listFiles()?.count { it.isFile && it.extension.equals("mp4", true) } ?: 0
        findViewById<Button>(R.id.myEditsButton).text = if (count == 0) "▣\nЭдиты" else "▣  $count\nЭдиты"
    }

    private fun restoreSourceDraft() {
        val drafts = RenderWorkspace.sourceDraftDirectory(filesDir).listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in setOf("mp4", "mov", "m4v", "webm", "mkv", "3gp") }
            ?.sortedBy { it.name }
            .orEmpty()
        if (drafts.isEmpty()) return
        val preferences = getSharedPreferences(SOURCE_DRAFT_PREFS, MODE_PRIVATE)
        val restored = MontageStylePresentation.restoredSources(drafts, montageStyle)
        runCatching { BuiltInMusicCatalog.select(this, montageStyle.id) }.onSuccess { music ->
            sourceFile = restored[0]
            secondarySourceFile = restored.getOrNull(1)
            musicSelection = music
            sourceDisplayNames = listOfNotNull(
                preferences.getString(SOURCE_DRAFT_NAME, null) ?: restored[0].name,
                restored.getOrNull(1)?.let {
                    preferences.getString(SOURCE_DRAFT_SECOND_NAME, null) ?: it.name
                }
            )
            updateSourceDisplay()
            findViewById<TextView>(R.id.musicSelection).text =
                "♫ ${music.track.title} · ${music.track.bpm} BPM"
            updateReadyState()
            LocalDiagnostics.record(this, "source_draft_restored", mapOf(
                "source_count" to restored.size.toString(),
                "bytes" to restored.sumOf(File::length).toString(),
                "music" to music.track.id
            ))
        }
    }

    private fun showFailure(error: Throwable) {
        toast(MontageFailurePresentation.message(error, montageStyle.sourceCount))
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val SPLASH_DURATION_MS = 650L
        private const val SOURCE_DRAFT_PREFS = "source_draft"
        private const val SOURCE_DRAFT_NAME = "display_name"
        private const val SOURCE_DRAFT_SECOND_NAME = "display_name_2"
        private const val SETTINGS_PREFS = "app_settings"
        private const val PREF_QUALITY = "render_quality"
        private const val PREF_AUTO_SAVE = "auto_save"
        private const val PREF_NOTIFICATIONS = "notifications"
        private const val PREF_LAST_NOTIFIED = "last_notified_render"
        private const val QUALITY_720 = "720p"
        private const val QUALITY_1080 = "1080p"
        private const val READY_CHANNEL_ID = "ready_edits"
        private const val READY_NOTIFICATION_ID = 1201
    }
}
