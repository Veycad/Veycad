package com.veycad.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/** Synthetic import media and export destinations, confined to the isolated test app. */
internal class UiFixtureProvider : ContentProvider() {
    override fun onCreate() = true
    private fun file(uri: Uri): File {
        val name = requireNotNull(uri.lastPathSegment)
        require(name == File(name).name && name != "." && name != "..")
        return File(requireNotNull(context).getDir("ui-imports", 0), name)
    }
    override fun getType(uri: Uri) = "video/mp4"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode in setOf("r", "w", "wt"))
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.parseMode(mode))
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val source = file(uri)
        return MatrixCursor(columns).apply {
            addRow(columns.map<String, Any?> { when (it) {
                OpenableColumns.DISPLAY_NAME -> source.name
                OpenableColumns.SIZE -> source.length()
                else -> null
            } }.toTypedArray())
        }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?) = error("Read only")
}
