package com.veycad.app

import java.io.File

/**
 * Product render files must not live in Android's quota-managed cache. The OS may purge cache
 * while MediaCodec is still producing the second or third candidate, which invalidates both the
 * source and already rendered MP4s. Only replaceable picker scratch data belongs in cacheDir.
 */
internal object RenderWorkspace {
    fun sourceDraftDirectory(filesDir: File): File = File(filesDir, "source-draft").apply { mkdirs() }

    fun pendingRendersDirectory(filesDir: File): File =
        File(filesDir, "pending-renders").apply { mkdirs() }

    fun transientImportsDirectory(cacheDir: File): File =
        File(cacheDir, "imports").apply { mkdirs() }

    fun clearTransient(filesDir: File, cacheDir: File) {
        File(cacheDir, "imports").deleteRecursively()
        File(filesDir, "pending-renders").deleteRecursively()
    }

    fun clearSourceDraft(filesDir: File) {
        File(filesDir, "source-draft").deleteRecursively()
    }
}
