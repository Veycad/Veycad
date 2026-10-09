package com.example.autoedit

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.io.File
import java.util.concurrent.Executors

/** Owns finalization independently of the camera Activity. Never stores a View or Activity. */
internal class CaptureRecordingSession private constructor(context: Context) {
    private val app = context.applicationContext
    val store = CaptureSessionStore(app.filesDir)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val audio = app.getSystemService(AudioManager::class.java)
    private var audioFocus: AudioFocusRequest? = null
    private var recording: Recording? = null
    private var player: MediaPlayer? = null
    private var musicPlayback: CaptureMusicPlayback? = null
    private var generation = 0L
    private var startedNs = 0L
    private var lastDurationMs = 0L
    private var lastStatsNs = 0L
    private var lastGuidanceMs = 0L
    private var lastCueKey = ""
    private var recovered = false
    private val mutable = MutableLiveData(State())
    val state: LiveData<State> = mutable
    enum class Phase { IDLE, PREPARING, RECORDING, FINALIZING, READY, FAILED }
    data class State(
        val phase: Phase = Phase.IDLE,
        val session: CaptureSessionStore.Session? = null,
        val elapsedMs: Long = 0,
        val take: Int = 1,
        val cue: String? = null,
        val nextCue: String? = null,
        val error: String? = null
    ) { val busy get() = phase in setOf(Phase.PREPARING, Phase.RECORDING, Phase.FINALIZING) }

    fun recover(onResult: (List<CaptureSessionStore.Session>) -> Unit) {
        val live = state.value?.takeIf { it.busy }?.session?.id
        io.execute {
            val sessions = if (!recovered) store.recover(setOfNotNull(live)).also { recovered = true } else store.list()
            main.post { onResult(sessions) }
        }
    }

    fun start(recorder: Recorder, script: CaptureScript, mode: CaptureMode, front: Boolean, rotation: Int) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (state.value?.busy == true) return
        val token = ++generation
        lastDurationMs = 0
        lastStatsNs = 0
        lastGuidanceMs = 0
        val takes = if (mode == CaptureMode.MUSIC) 1 else CaptureScript.DEFAULT_TAKES
        LocalDiagnostics.record(app, "capture_record_requested", mapOf("style" to script.styleId,
            "mode" to mode.name, "front" to front.toString()))
        mutable.value = State(Phase.PREPARING)
        io.execute {
            var stage = CaptureDiagnostics.Stage.STORAGE_CHECK
            val result = runCatching {
                // Conservative prototype reserve: source + derivative + output, at 16 Mbit/s.
                val reserved = CaptureTakeTimeline.totalDurationMs(script,
                    if (mode == CaptureMode.MUSIC) 1 else CaptureScript.MAX_TAKES) * 2_000L * 3 + 128_000_000L
                check(app.filesDir.usableSpace >= reserved) { "Недостаточно места для съёмки и монтажа" }
                stage = CaptureDiagnostics.Stage.MUSIC_ASSET
                BuiltInMusicCatalog.select(app, script.styleId).file
            }
            main.post {
                if (token != generation) return@post
                result.onSuccess { music ->
                    var session: CaptureSessionStore.Session? = null
                    runCatching {
                        stage = CaptureDiagnostics.Stage.MUSIC_PLAYER
                        prepareAudio(music)
                        // Create the draft only after cancellation is no longer possible on another main-thread turn.
                        stage = CaptureDiagnostics.Stage.SESSION_CREATE
                        session = store.create(script, mode, takes, front, rotation)
                        val started = requireNotNull(session)
                        mutable.value = State(Phase.PREPARING, started)
                        // LiveData observers can cancel preparation before the recorder is assigned.
                        if (token != generation || state.value?.phase != Phase.PREPARING) return@runCatching
                        val options = FileOutputOptions.Builder(store.staging(started.id))
                            .setFileSizeLimit(512_000_000L)
                            .setDurationLimitMillis(CaptureTakeTimeline.totalDurationMs(script,
                                if (mode == CaptureMode.MUSIC) 1 else CaptureScript.MAX_TAKES)).build()
                        stage = CaptureDiagnostics.Stage.RECORDER_START
                        recording = recorder.prepareRecording(app, options)
                            .start(ContextCompat.getMainExecutor(app)) { event ->
                                receive(token, script, event)
                            }
                    }.onFailure { fail(session, it.message ?: "Не удалось начать запись", stage, it) }
                }.onFailure { fail(null, it.message ?: "Не удалось подготовить запись", stage, it) }
            }
        }
    }

    private fun prepareAudio(file: File) {
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
                if (change < 0) {
                    LocalDiagnostics.record(app, "capture_audio_focus_lost", mapOf("focus_change" to change.toString()))
                    stop("Съёмка остановлена: воспроизведение музыки прервано")
                }
            }, main).build()
        check(audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            "Не удалось включить музыку. Завершите воспроизведение в другом приложении"
        }
        audioFocus = request
        val preparedPlayer = MediaPlayer()
        player = preparedPlayer
        preparedPlayer.apply {
            setAudioAttributes(attributes)
            setDataSource(file.absolutePath)
            isLooping = true
            prepare()
            setOnErrorListener { _, what, extra ->
                LocalDiagnostics.record(app, "capture_player_failed", mapOf("player_what" to what.toString(),
                    "player_extra" to extra.toString()))
                stop("Музыка прервана. Запись остановлена"); true
            }
        }
        musicPlayback = CaptureMusicPlayback.forPlayer(preparedPlayer, onEvent = { type ->
            state.value?.session?.let { session ->
                logEvent(session.id, type, guidanceTimeMs(),
                    runCatching { preparedPlayer.currentPosition.toLong() }.getOrDefault(-1L))
            }
        }, onFailure = ::stop)
    }

    private fun receive(token: Long, script: CaptureScript, event: VideoRecordEvent) {
        if (token != generation) return
        val current = state.value?.session ?: return
        lastDurationMs = event.recordingStats.recordedDurationNanos / 1_000_000
        lastStatsNs = SystemClock.elapsedRealtimeNanos()
        when (event) {
            is VideoRecordEvent.Start -> {
                if (state.value?.phase != Phase.PREPARING) return
                startedNs = SystemClock.elapsedRealtimeNanos()
                lastCueKey = ""
                val session = current.copy(status = CaptureSessionStore.Status.RECORDING, recordStartedNs = startedNs)
                mutable.value = State(Phase.RECORDING, session)
                persist(session)
                logEvent(session.id, "record-start", 0L, -1L)
                LocalDiagnostics.record(app, "capture_record_started", mapOf("style" to script.styleId,
                    "mode" to session.mode.name, "front" to session.frontCamera.toString()))
                tick(script, token)
            }
            is VideoRecordEvent.Status -> logEvent(current.id, "record-status", lastDurationMs,
                player?.let { runCatching { it.currentPosition.toLong() }.getOrDefault(-1L) } ?: -1L)
            is VideoRecordEvent.Finalize -> {
                recording = null
                main.removeCallbacksAndMessages(TICK_TOKEN)
                releaseAudio()
                val requestedStop = state.value?.phase == Phase.FINALIZING
                val interruption = current.stopReason ?: when {
                    event.error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE && !requestedStop ->
                        app.getString(R.string.capture_source_lost)
                    event.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED ->
                        app.getString(R.string.capture_size_limit)
                    else -> null
                }
                val session = current.copy(status = CaptureSessionStore.Status.FINALIZING, stopReason = interruption)
                LocalDiagnostics.record(app, "capture_record_finalized", mapOf("recorder_error" to event.error.toString(),
                    "video_ms" to lastDurationMs.toString(), "requested_stop" to requestedStop.toString(),
                    "has_stop_reason" to (interruption != null).toString()))
                event.cause?.let { CaptureDiagnostics.failure(app, CaptureDiagnostics.Stage.RECORD_FINALIZE, it,
                    mapOf("recorder_error" to event.error.toString())) }
                mutable.value = state.value?.copy(phase = Phase.FINALIZING, session = session,
                    error = interruption ?: state.value?.error)
                val recoverableStop = event.error in setOf(VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE,
                    VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED, VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED)
                val failure = if (event.hasError() && !recoverableStop)
                    "Запись завершилась с ошибкой (${event.error}). Исходник сохранён для проверки." else null
                io.execute {
                    val result = runCatching {
                        store.save(session)
                        check(failure == null) { requireNotNull(failure) }
                        val durationMs = readableVideoDurationMs(store.staging(session.id))
                        store.markReady(session, durationMs)
                    }
                    main.post {
                        if (token != generation) return@post
                        result.onSuccess { ready -> mutable.value = State(Phase.READY, ready, ready.durationMs,
                            error = state.value?.error) }
                            .onFailure { fail(session, it.message ?: "Не удалось сохранить запись",
                                if (failure == null) CaptureDiagnostics.Stage.CONTAINER_FINALIZE else
                                    CaptureDiagnostics.Stage.RECORD_FINALIZE, it) }
                    }
                }
            }
        }
    }

    private fun tick(script: CaptureScript, token: Long) {
        if (token != generation || state.value?.phase != Phase.RECORDING) return
        // Status is the recorded-media clock; bounded interpolation keeps guidance smooth.
        // A stalled encoder cannot advance the script indefinitely on wall time alone.
        val elapsed = guidanceTimeMs()
        lastGuidanceMs = elapsed
        val session = requireNotNull(state.value?.session)
        val position = CaptureTakeTimeline.position(script, session.requestedTakes, elapsed)
        if (position.finished) { stop(); return }
        musicPlayback?.update(position)
        // Playback failure can stop/finalize the recording synchronously.
        if (token != generation || state.value?.phase != Phase.RECORDING) return
        val cue = app.getString(if (position.recovering) R.string.capture_cue_recover else script.cueAt(position.offsetMs).textRes)
        val next = if (position.recovering) null else script.nextCueAt(position.offsetMs)?.let { app.getString(it.textRes) }
        val key = "${position.ordinal}:$cue"
        if (key != lastCueKey) {
            lastCueKey = key
            val eventType = if (position.recovering) "recovery:${position.ordinal}" else
                "cue:${position.ordinal}:${script.cueAt(position.offsetMs).atMs}"
            logEvent(session.id, eventType, elapsed,
                player?.currentPosition?.toLong() ?: -1L)
        }
        mutable.value = State(Phase.RECORDING, session, elapsed, position.ordinal, cue, next)
        main.postAtTime({ tick(script, token) }, TICK_TOKEN, SystemClock.uptimeMillis() + 100)
    }

    private fun guidanceTimeMs() = maxOf(lastGuidanceMs, lastDurationMs +
        ((SystemClock.elapsedRealtimeNanos() - lastStatsNs) / 1_000_000).coerceIn(0, 250))

    fun stop(reason: String? = null) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val current = state.value ?: return
        if (current.phase !in setOf(Phase.PREPARING, Phase.RECORDING)) return
        LocalDiagnostics.record(app, "capture_stop_requested", mapOf("phase" to current.phase.name,
            "video_ms" to lastGuidanceMs.toString(), "has_stop_reason" to (reason != null).toString()))
        if (current.phase == Phase.PREPARING && recording == null) {
            generation++
            releaseAudio()
            val interrupted = current.session?.copy(status = CaptureSessionStore.Status.INTERRUPTED,
                stopReason = reason, error = reason)
            interrupted?.let(::persist)
            mutable.value = State(session = interrupted, error = reason)
            return
        }
        main.removeCallbacksAndMessages(TICK_TOKEN)
        releaseAudio()
        val session = current.session?.copy(status = CaptureSessionStore.Status.FINALIZING, stopReason = reason)
        session?.let { logEvent(it.id, "stop-request", lastGuidanceMs, -1) }
        mutable.value = current.copy(phase = Phase.FINALIZING, session = session, error = reason)
        session?.let(::persist)
        recording?.stop()
    }

    fun addTake() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val current = state.value ?: return
        val session = current.session ?: return
        if (current.phase != Phase.RECORDING || session.mode != CaptureMode.BEST_TAKE ||
            session.requestedTakes >= CaptureScript.MAX_TAKES) return
        val extended = session.copy(requestedTakes = session.requestedTakes + 1)
        mutable.value = current.copy(session = extended)
        persist(extended)
    }

    private fun logEvent(id: String, type: String, videoMs: Long, musicMs: Long) {
        val at = SystemClock.elapsedRealtimeNanos()
        io.execute { runCatching { store.events(id).appendText("$type\t$at\t$videoMs\t$musicMs\n") } }
    }
    private fun persist(session: CaptureSessionStore.Session) {
        io.execute { runCatching { store.save(session) }.onFailure {
            main.post { if (state.value?.session?.id == session.id) stop("Не удалось сохранить состояние съёмки") }
        } }
    }
    private fun fail(session: CaptureSessionStore.Session?, message: String, stage: CaptureDiagnostics.Stage,
        cause: Throwable) {
        CaptureDiagnostics.failure(app, stage, cause, session?.let {
            mapOf("style" to it.styleId, "mode" to it.mode.name, "front" to it.frontCamera.toString())
        }.orEmpty())
        releaseAudio()
        val failed = session?.copy(status = CaptureSessionStore.Status.FAILED, error = message)
        if (failed != null) io.execute { runCatching { store.save(failed) } }
        mutable.value = State(Phase.FAILED, failed, error = message)
    }
    private fun releaseAudio() {
        musicPlayback?.close()
        musicPlayback = null
        player?.let { runCatching { it.setOnSeekCompleteListener(null); it.release() } }
        player = null
        audioFocus?.let { audio.abandonAudioFocusRequest(it) }
        audioFocus = null
    }

    fun inspectInterrupted(id: String, onResult: (Result<CaptureSessionStore.Session>) -> Unit) {
        check(state.value?.busy != true)
        io.execute {
            val result = runCatching {
                val session = requireNotNull(store.load(id))
                val source = store.recording(id).takeIf { it.isFile } ?: store.staging(id)
                val duration = readableVideoDurationMs(source)
                store.acceptRecovered(session, duration)
            }
            result.exceptionOrNull()?.let { CaptureDiagnostics.failure(app, CaptureDiagnostics.Stage.RECOVERY, it) }
            main.post { onResult(result) }
        }
    }

    companion object {
        private val TICK_TOKEN = Any()
        private var instance: CaptureRecordingSession? = null
        @Synchronized fun get(context: Context) = instance ?: CaptureRecordingSession(context).also { instance = it }
        internal fun readableVideoDurationMs(file: File): Long {
            require(file.isFile && file.length() > 0) { "Запись пуста" }
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                val index = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                } ?: error("В записи отсутствует видео")
                val format = extractor.getTrackFormat(index)
                extractor.selectTrack(index)
                check(extractor.sampleTime >= 0) { "В записи отсутствуют кадры" }
                val durationUs = format.getLong(MediaFormat.KEY_DURATION)
                var lastSampleUs = -1L
                var samples = 0
                do {
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        check(extractor.sampleSize > 0) { "В записи повреждены кадры" }
                    }
                    lastSampleUs = maxOf(lastSampleUs, extractor.sampleTime)
                    samples++
                } while (extractor.advance())
                check(samples > 0 && durationUs > 0 && lastSampleUs >= durationUs - 1_000_000) {
                    "Запись сохранена не полностью"
                }
                return durationUs / 1_000
            } finally { extractor.release() }
        }
    }
}
