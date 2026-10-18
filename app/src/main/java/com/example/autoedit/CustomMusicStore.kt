package com.veycad.app

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

/** Owns imported audio in durable private storage; provider access is needed only for the copy. */
internal class CustomMusicStore(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("custom_music", Context.MODE_PRIVATE)
    private val directory get() = File(app.filesDir, "custom-music").apply { mkdirs() }

    data class Selection(val file: File, val title: String, val durationUs: Long, val startUs: Long = 0L) {
        init {
            require(title.isNotBlank() && durationUs >= MINIMUM_REMAINING_US)
            require(startUs in 0..durationUs - MINIMUM_REMAINING_US)
        }
        val maximumStartMs get() = (durationUs - MINIMUM_REMAINING_US) / 1_000L
    }

    /** A failed or cancelled import never replaces the last valid selection. */
    fun importFile(uri: Uri, checkCancelled: () -> Unit = {}): Selection {
        val title = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),
            null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.takeIf { it.isNotBlank() } ?: "Мой трек"
        val target = File(directory, "track-${UUID.randomUUID()}.audio")
        val partial = File(target.path + ".partial")
        try {
            app.contentResolver.openInputStream(uri)?.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        checkCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= MAXIMUM_IMPORT_BYTES) { "Выберите аудиофайл размером до 128 МБ" }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("Не удалось прочитать выбранный трек")
            require(partial.length() > 0L) { "Выбранный аудиофайл пуст" }
            val durationUs = inspectDuration(partial)
            require(durationUs >= MINIMUM_REMAINING_US) { "Выберите трек длиной хотя бы 1 секунду" }
            checkCancelled()
            check(partial.renameTo(target)) { "Не удалось сохранить трек" }
            return Selection(target, title, durationUs)
        } catch (error: Throwable) {
            target.delete()
            throw error
        } finally { partial.delete() }
    }

    fun analyze(selection: Selection, checkCancelled: () -> Unit = {}): AudioBeatMap {
        val pcm = MediaCodecAudioDecoder.decode(selection.file, ANALYSIS_DURATION_US,
            checkCancelled = checkCancelled, startUs = selection.startUs)
        require(pcm.frameCount >= pcm.sampleRate / 2L) { "В выбранном фрагменте слишком мало аудио" }
        return AudioBeatMapAnalyzer.analyzeLoopingFragment(pcm.mono(), pcm.sampleRate, checkCancelled = checkCancelled)
    }

    /** Publish after decode validation. Filename generation prevents track-name/profile collisions. */
    fun save(selection: Selection) {
        require(selection.file.isFile && selection.file.parentFile?.canonicalFile == directory.canonicalFile)
        check(preferences.edit().putString("file", selection.file.name).putString("title", selection.title)
            .putLong("duration_us", selection.durationUs).putLong("start_us", selection.startUs).commit()) {
            "Не удалось сохранить выбор музыки"
        }
        directory.listFiles()?.filter { it != selection.file && it.extension == "audio" }?.forEach(File::delete)
    }

    fun restore(): Selection? = runCatching {
        val name = preferences.getString("file", null) ?: return null
        require(name == File(name).name)
        val file = File(directory, name)
        if (!file.isFile) return null
        Selection(file, preferences.getString("title", null) ?: "Мой трек",
            preferences.getLong("duration_us", 0L), preferences.getLong("start_us", 0L))
    }.getOrNull()

    private fun inspectDuration(file: File): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.path)
            val format = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: error("В файле нет аудиодорожки. Выберите MP3 или WAV")
            require(format.containsKey(MediaFormat.KEY_DURATION)) { "Не удалось определить длину трека" }
            format.getLong(MediaFormat.KEY_DURATION)
        } finally { extractor.release() }
    }

    companion object {
        const val ANALYSIS_DURATION_US = 30_000_000L
        const val MINIMUM_REMAINING_US = 1_000_000L
        private const val MAXIMUM_IMPORT_BYTES = 128L * 1024 * 1024
    }
}
