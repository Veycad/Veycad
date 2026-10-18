package com.veycad.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectAssetStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun draftCleanupKeepsProjectAssets() {
        val files = temporary.newFolder()
        val cache = temporary.newFolder()
        val source = java.io.File(RenderWorkspace.sourceDraftDirectory(files), "clip.mp4").apply { writeText("source") }
        val directory = java.io.File(files, "projects/one")
        val assets = ProjectAssetStore(directory)
        val asset = assets.import(source, ProjectAsset.Kind.VIDEO, 2_000_000)
        assertEquals(asset.fileName, assets.import(source, ProjectAsset.Kind.VIDEO, 2_000_000).fileName)
        RenderWorkspace.clearSourceDraft(files)
        RenderWorkspace.clearTransient(files, cache)
        assertEquals("source", assets.resolve(asset).readText())
        assertEquals("clip.mp4", asset.displayName)
    }
    @Test fun projectDeletionDoesNotDeleteAnotherProjectsCopy() {
        val files = temporary.newFolder()
        val store = HybridProjectStore(files)
        val source = temporary.newFile().apply { writeText("source") }
        val first = ProjectAssetStore(store.directory("one"))
        val second = ProjectAssetStore(store.directory("two"))
        first.import(source, ProjectAsset.Kind.VIDEO, 100)
        val owned = second.import(source, ProjectAsset.Kind.VIDEO, 100)
        store.delete("one")
        source.delete()
        assertEquals("source", second.resolve(owned).readText())
        assertThrows(IllegalArgumentException::class.java) { store.directory("../escape") }
    }
}
