package com.example.autoedit

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.File

/** Holds a real provider open long enough to destroy/reopen the UI while Save is in flight. */
class SaveLifecycleProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = EditSession.get(applicationContext)
        check(session.state.value?.busy != true)
        val entry = requireNotNull(session.state.value?.entry)
        session.save(entry.copy(saved = false), Uri.parse("content://$packageName.save_probe/result"))
        File(filesDir, "save-lifecycle-probe.started").writeText(entry.file.name)
        finish()
    }

    class SlowDestination : ContentProvider() {
        override fun onCreate() = true
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            require(uri.path == "/result" && "w" in mode)
            Thread.sleep(20_000)
            return ParcelFileDescriptor.open(File(requireNotNull(context).filesDir, "save-lifecycle-probe.mp4"),
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or
                    ParcelFileDescriptor.MODE_WRITE_ONLY)
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri) = "video/mp4"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?,
            selectionArgs: Array<out String>?) = 0
    }
}
