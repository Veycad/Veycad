package com.veycad.app

import android.content.Context
import android.net.Uri
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object LicenseArchive {
    /** Streams only bundled notices; never traverses app data or user media. */
    fun export(context: Context, destination: Uri): Boolean = runCatching {
        val assets = context.assets
        val names = requireNotNull(assets.list("licenses"))
        check(names.isNotEmpty())
        val output = requireNotNull(context.contentResolver.openOutputStream(destination, "wt"))
        ZipOutputStream(output.buffered()).use { archive ->
            fun append(path: String) {
                val children = requireNotNull(assets.list(path))
                if (children.isNotEmpty()) {
                    children.sorted().forEach { append("$path/$it") }
                } else {
                    archive.putNextEntry(ZipEntry(path))
                    assets.open(path).use { it.copyTo(archive) }
                    archive.closeEntry()
                }
            }
            names.sorted().forEach { append("licenses/$it") }
        }
        true
    }.getOrDefault(false)
}
