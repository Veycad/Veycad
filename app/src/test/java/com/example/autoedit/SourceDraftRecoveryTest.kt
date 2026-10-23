package com.veycad.app

import java.io.File
import java.io.IOException
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceDraftRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val legacy = SourceDraftStore.LegacyState("first", "second", false)
    private fun file(base: File, name: String) = File(File(base, "source-draft").apply { mkdirs() }, name).apply { writeBytes(byteArrayOf(7, 8)) }
    @Test fun legacyPairPreservesOrderWithoutCopy() {
        val base = temporary.newFolder(); val id = UUID.randomUUID()
        val second = file(base, "source-1-$id.mp4"); val first = file(base, "source-0-$id.mp4")
        val stamp = first.lastModified()
        val restored = SourceDraftStore(base).restore(legacy)
        assertEquals(listOf(first.name, second.name), restored.snapshot.entries.map { it.fileName })
        assertEquals(listOf("first", "second"), restored.snapshot.entries.map { it.displayName })
        assertEquals(SourceDraftManifest.Origin.LEGACY, restored.snapshot.origin)
        assertEquals(stamp, first.lastModified())
        assertArrayEquals(byteArrayOf(7, 8), first.readBytes())
        assertEquals(restored.snapshot, SourceDraftStore(base).read())
    }
    @Test fun ambiguousLegacyGetsTombstoneBeforeCandidate() {
        for (names in listOf(listOf("source-1-${UUID.randomUUID()}.mp4"),
            listOf("source-0-${UUID.randomUUID()}.mp4", "source-1-${UUID.randomUUID()}.mp4"),
            listOf("one.mp4", "two.mp4"), listOf("one.mp4", "two.mp4", "three.mp4"))) {
            val base = temporary.newFolder(); val files = names.map { file(base, it) }
            val restored = SourceDraftStore(base).restore(legacy.copy(secondName = null))
            assertTrue(restored.snapshot.entries.isEmpty()); assertNotNull(restored.warning)
            assertTrue(files.all(File::exists))
            assertTrue(SourceDraftStore(base).read()!!.entries.isEmpty())
        }
        val base = temporary.newFolder(); file(base, "source-0-${UUID.randomUUID()}.mp4")
        assertNotNull(SourceDraftStore(base).restore(legacy).warning) // missing known second source
    }
    @Test fun tombstoneAndCleanupMarkerPreventResurrection() {
        val base = temporary.newFolder(); val source = file(base, "old.mp4")
        val store = SourceDraftStore(base)
        assertTrue(store.restore(legacy.copy(cleanupIncomplete = true)).snapshot.entries.isEmpty())
        assertTrue(store.restore(legacy.copy(secondName = null)).snapshot.entries.isEmpty())
        assertTrue(source.exists())
    }
    @Test fun committedManifestWinsOverStaleMarker() {
        val base = temporary.newFolder(); val store = SourceDraftStore(base)
        store.restore(legacy.copy(cleanupIncomplete = true))
        val committed = store.replace(listOf(SourceDraftStore.Input("new") { byteArrayOf(3).inputStream() }), 1) { Unit }
            as SourceDraftStore.ReplaceResult.Published
        assertEquals(committed.snapshot, SourceDraftStore(base).restore(legacy.copy(cleanupIncomplete = true)).snapshot)
    }
    @Test fun collectionRequiresExclusiveAdmissionAndPreservesUnknown() {
        val base = temporary.newFolder(); val store = SourceDraftStore(base)
        val current = file(base, "old.mp4")
        store.restore(legacy.copy(secondName = null))
        val obsolete = file(base, "source-0-${UUID.randomUUID()}.mp4")
        val unknown = file(base, "unknown.mp4")
        val owner = MediaWorkCoordinator()
        owner.tryBeginWork()!!.use { work ->
            assertThrows(IllegalStateException::class.java) { store.collectObsolete(work) }
            assertNull(owner.tryBeginCleanup())
        }
        owner.tryBeginCleanup()!!.use { assertTrue(store.collectObsolete(it).isEmpty()) }
        assertTrue(current.exists()); assertTrue(unknown.exists()); assertFalse(obsolete.exists())
        val closed = owner.tryBeginCleanup()!!.also { it.close() }
        assertThrows(IllegalStateException::class.java) { store.collectObsolete(closed) }
    }
    @Test fun corruptCurrentNeverAllowsCollectionOrLegacyFallback() {
        val base = temporary.newFolder(); val old = file(base, "source-0-${UUID.randomUUID()}.mp4")
        File(old.parentFile, "current.json").writeText("broken")
        val store = SourceDraftStore(base)
        assertThrows(IOException::class.java) { store.restore(legacy) }
        MediaWorkCoordinator().tryBeginCleanup()!!.use { assertFalse(store.collectObsolete(it).isEmpty()) }
        assertTrue(old.exists())
    }
    @Test fun previousLegacyOwnershipIsRequiredAfterReplacement() {
        val base = temporary.newFolder(); val old = file(base, "old.mov")
        val store = SourceDraftStore(base); val previous = store.restore(legacy.copy(secondName = null)).snapshot
        store.replace(listOf(SourceDraftStore.Input("new") { byteArrayOf(9).inputStream() }), 1) { Unit }
        val owner = MediaWorkCoordinator()
        owner.tryBeginCleanup()!!.use { SourceDraftStore(base).collectObsolete(it) }
        assertTrue(old.exists())
        owner.tryBeginCleanup()!!.use { store.collectObsolete(it, previous.entries.map { e -> e.fileName }.toSet()) }
        assertFalse(old.exists())
    }
    @Test fun invalidationFailureKeepsOldPointerAndBytes() {
        val base = temporary.newFolder(); val old = file(base, "old.mp4")
        val previous = SourceDraftStore(base).restore(legacy.copy(secondName = null)).snapshot
        val io = object : SourceDraftStore.Io by SourceDraftStore.DiskIo {
            override fun replaceManifest(target: File, bytes: ByteArray) { throw IOException("fail") }
        }
        MediaWorkCoordinator().tryBeginCleanup()!!.use { lease ->
            assertThrows(IOException::class.java) { SourceDraftStore(base, io).invalidate(lease) }
        }
        assertEquals(previous, SourceDraftStore(base).read()); assertTrue(old.exists())
    }
    @Test fun invalidationAcknowledgesReplacementEvenWhenWriterThrowsAfterCommit() {
        val base = temporary.newFolder(); file(base, "old.mp4")
        SourceDraftStore(base).restore(legacy.copy(secondName = null))
        val io = object : SourceDraftStore.Io by SourceDraftStore.DiskIo {
            override fun replaceManifest(target: File, bytes: ByteArray) { atomicWrite(target, bytes); throw IOException("after") }
        }
        MediaWorkCoordinator().tryBeginCleanup()!!.use { lease ->
            assertTrue(SourceDraftStore(base, io).invalidate(lease).entries.isEmpty())
        }
    }
    @Test fun failedBootstrapCannotExposeCandidateAndCollectionStaysInsideOwnedNames() {
        val base = temporary.newFolder(); val old = file(base, "old.mp4")
        val io = object : SourceDraftStore.Io by SourceDraftStore.DiskIo {
            override fun replaceManifest(target: File, bytes: ByteArray) { throw IOException("bootstrap") }
        }
        assertThrows(IOException::class.java) { SourceDraftStore(base, io).restore(legacy.copy(secondName = null)) }
        assertEquals(listOf(old.name), old.parentFile.listFiles()!!.map { it.name })
        val store = SourceDraftStore(base); store.restore(legacy.copy(secondName = null))
        val staging = File(old.parentFile, ".staging-${UUID.randomUUID()}").apply { mkdir() }
        File(staging, "0.partial").writeText("abandoned")
        val temp = File(old.parentFile, ".pending-123.tmp").apply { writeText("temporary") }
        val unknown = File(old.parentFile, ".staging-not-uuid").apply { mkdir() }
        val sentinels = listOf("completed-renders", "projects", "text-sources", "capture-sessions", "provider-cache").map {
            File(File(base, it).apply { mkdir() }, "keep.mp4").apply { writeText(it) }
        }
        MediaWorkCoordinator().tryBeginCleanup()!!.use { assertTrue(store.collectObsolete(it).isEmpty()) }
        assertFalse(staging.exists()); assertFalse(temp.exists()); assertTrue(unknown.exists())
        sentinels.forEach { assertEquals(it.parentFile.name, it.readText()) }
    }
    @Test fun staleSnapshotCannotDeleteCurrentAndInvalidationIsDurable() {
        val base = temporary.newFolder(); val store = SourceDraftStore(base)
        store.restore(legacy.copy(cleanupIncomplete = true))
        val selected = (store.replace(listOf(SourceDraftStore.Input("new") { byteArrayOf(2).inputStream() }), 1) { Unit }
            as SourceDraftStore.ReplaceResult.Published).snapshot
        val owner = MediaWorkCoordinator()
        owner.tryBeginCleanup()!!.use { store.collectObsolete(it, selected.entries.map { e -> e.fileName }.toSet()) }
        assertEquals(selected, SourceDraftStore(base).read())
        owner.tryBeginCleanup()!!.use { store.invalidate(it) }
        assertTrue(SourceDraftStore(base).restore(legacy).snapshot.entries.isEmpty())
    }
}
