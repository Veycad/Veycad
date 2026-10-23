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

    fun clearTransient(filesDir: File, cacheDir: File, lease: MediaWorkCoordinator.Lease? = null) =
        withCleanup(lease) { TemporaryFileCleanup.clear(filesDir, cacheDir,
            TemporaryFileCleanup.Scope.TRANSIENT_RENDER, it) }

    fun clearSourceDraft(filesDir: File, lease: MediaWorkCoordinator.Lease? = null) =
        withCleanup(lease) { TemporaryFileCleanup.clear(filesDir, filesDir,
            TemporaryFileCleanup.Scope.SOURCE_DRAFT, it) }

    private fun <T> withCleanup(lease: MediaWorkCoordinator.Lease?, action: (MediaWorkCoordinator.Lease) -> T): T {
        if (lease != null) return action(lease)
        return checkNotNull(MediaWorkCoordinator.process.tryBeginCleanup()) {
            MediaWorkCoordinator.BUSY_MESSAGE
        }.use(action)
    }
}
