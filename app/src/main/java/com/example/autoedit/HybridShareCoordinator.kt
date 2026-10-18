package com.veycad.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Shares published files only; does not own rendering, storage or an Activity. */
internal class HybridShareCoordinator(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("hybrid_share", Context.MODE_PRIVATE)

    data class ShareAction(val operationId: String, val entry: CompletedRenderStore.Entry,
        val automaticEligible: Boolean)

    fun pending(operationId: String, entry: CompletedRenderStore.Entry): ShareAction =
        ShareAction(operationId, entry, operationId !in consumedIds())

    /** Commit before launching: dismissal, launch failure and observer replay cannot retry automatically. */
    fun consume(operationId: String): Boolean = synchronized(CONSUMED_LOCK) {
        require(operationId.isNotBlank() && '\n' !in operationId)
        val ids = consumedIds()
        if (operationId in ids) return@synchronized false
        preferences.edit().putString("consumed", (ids + operationId).takeLast(MAX_CONSUMED).joinToString("\n")).commit()
    }

    private fun consumedIds(): List<String> = preferences.getString("consumed", null)
        ?.split('\n')?.filter(String::isNotEmpty).orEmpty()

    fun createIntent(entry: CompletedRenderStore.Entry): Intent = createIntent(entry.file)

    fun createIntent(file: File): Intent {
        val published = validateFile(app.filesDir, file)
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", published)
        return Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(published.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun openChooser(context: Context, file: File): Result<Unit> = runCatching {
        context.startActivity(Intent.createChooser(createIntent(file), context.getString(R.string.share_chooser_title)))
    }

    /** One screen instance owns this request. Never save or restore it in an Activity Bundle. */
    class AutomaticRequest(private val consume: (String) -> Boolean) {
        private var requestedOperation: String? = null

        fun request(operationId: String) { requestedOperation = operationId }

        fun complete(operationId: String?, entry: CompletedRenderStore.Entry?, foreground: Boolean): ShareAction? {
            val requested = requestedOperation ?: return null
            requestedOperation = null
            if (requested != operationId || entry == null) return null
            if (!consume(requested) || !foreground) return null
            return ShareAction(requested, entry, automaticEligible = true)
        }

        /** Leaving the originating screen conservatively turns the action into manual sharing. */
        fun abandon() {
            val requested = requestedOperation ?: return
            requestedOperation = null
            consume(requested)
        }
    }

    companion object {
        private const val MAX_CONSUMED = 128
        private val CONSUMED_LOCK = Any()

        internal fun validateFile(filesDir: File, file: File): File {
            val published = file.canonicalFile
            val allowed = listOf("completed-renders", "my-edits").any { directory ->
                published.path.startsWith(File(filesDir, directory).canonicalPath + File.separator)
            }
            require(allowed && published.extension.equals("mp4", ignoreCase = true) &&
                published.isFile && published.length() > 0L) { "Готовый MP4 отсутствует или недоступен" }
            return published
        }
    }
}
