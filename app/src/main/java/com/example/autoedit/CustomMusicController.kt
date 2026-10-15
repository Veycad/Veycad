package com.veycad.app

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import kotlin.math.roundToInt

/** Screen-owned audition and controls; retained session owns import and rhythm analysis. */
internal class CustomMusicController(private val activity: MainActivity,
    private val onChanged: () -> Unit, private val onImported: () -> Unit) {
    private val session = ViewModelProvider(activity)[CustomMusicSession::class.java]
    private val main = Handler(Looper.getMainLooper())
    private val seek = activity.findViewById<SeekBar>(R.id.musicStartSeek)
    private val preview = activity.findViewById<Button>(R.id.previewMusicButton)
    private val startLabel = activity.findViewById<TextView>(R.id.musicStartLabel)
    private var closed = false
    private var player: MediaPlayer? = null
    private var active = false
    private var stopAudition: Runnable? = null
    val selection get() = session.current.selection
    val analysis get() = session.current.analysis
    val busy get() = session.current.busy
    val ready get() = session.current.ready

    init {
        preview.setOnClickListener { if (player != null) stopPreview() else playPreview() }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) { stopPreview() }
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) showStart(progress * 100_000L)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val selected = selection ?: return
                val shifted = selected.copy(startUs = seekBar.progress * 100_000L)
                if (shifted.startUs != selected.startUs) { stopPreview(); session.analyze(shifted) }
            }
        })
    }

    fun observe() {
        session.state.observe(activity) { value ->
            if (closed) return@observe
            if (value.busy) stopPreview()
            updateFragment()
            if (value.activateCustomStyle) onImported()
            value.error?.let { failure(IllegalStateException(it)) }
            onChanged()
            session.consumeEvents()
        }
    }

    fun updateControls(isActive: Boolean, screenReady: Boolean, allowImport: Boolean) {
        if (active && !isActive) stopPreview()
        active = isActive
        activity.findViewById<View>(R.id.importMusicButton).apply {
            visibility = if (allowImport) View.VISIBLE else View.GONE
            isEnabled = screenReady && !busy
        }
        activity.findViewById<View>(R.id.musicFragmentPanel).visibility =
            if (isActive && selection != null) View.VISIBLE else View.GONE
        seek.isEnabled = screenReady && ready
        preview.isEnabled = screenReady && ready
    }

    fun display(): String {
        val selected = selection ?: return "♫ Выберите трек · Под свою музыку"
        val rhythm = analysis?.estimatedTempoBpm?.let { "≈ ${it.roundToInt()} BPM" }
            ?: if (busy) "анализирую ритм…" else "ритм не определён"
        return "♫ ${selected.title}\n$rhythm · с ${time(selected.startUs)}"
    }
    fun load() {
        session.load()
    }
    fun import(uri: Uri) {
        stopPreview()
        session.import(uri)
    }
    private fun failure(error: Throwable) {
        Toast.makeText(activity, error.message ?: "Не удалось открыть трек. Выберите MP3 или WAV",
            Toast.LENGTH_LONG).show()
    }
    private fun updateFragment() {
        selection?.let { selected ->
            seek.max = (selected.maximumStartMs / 100L).toInt()
            seek.progress = (selected.startUs / 100_000L).toInt()
            showStart(selected.startUs)
        }
    }
    private fun showStart(startUs: Long) {
        startLabel.text = "Начало: ${time(startUs)} · трек ${time(selection?.durationUs ?: 0L)}"
    }
    private fun time(us: Long): String {
        val tenths = us / 100_000L
        return "%d:%02d.%d".format(tenths / 600, (tenths / 10) % 60, tenths % 10)
    }
    private fun playPreview() {
        val selected = selection ?: return
        if (!ready) return
        val audition = MediaPlayer()
        player = audition
        preview.text = "Остановить"
        try {
            audition.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            audition.setDataSource(selected.file.path)
            audition.setOnErrorListener { _, _, _ -> stopPreview(); failure(IllegalStateException("Не удалось прослушать трек")); true }
            audition.setOnCompletionListener { stopPreview() }
            audition.setOnSeekCompleteListener {
                if (player === audition && active && !closed) {
                    audition.start()
                    stopAudition = Runnable { stopPreview() }.also { main.postDelayed(it, 30_000) }
                }
            }
            audition.setOnPreparedListener {
                if (player === audition && active && !closed)
                    audition.seekTo(selected.startUs / 1_000L, MediaPlayer.SEEK_CLOSEST)
            }
            audition.prepareAsync()
        } catch (error: Exception) { stopPreview(); failure(error) }
    }
    fun stopPreview() {
        stopAudition?.let { main.removeCallbacks(it) }
        stopAudition = null
        player?.release(); player = null
        preview.text = "Прослушать фрагмент"
    }
    fun close() {
        closed = true
        stopPreview()
    }
}
