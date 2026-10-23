package com.veycad.app

import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SourceDraftManifestTest {
    private fun sample(): SourceDraftManifest.Snapshot {
        val id = UUID.randomUUID()
        return SourceDraftManifest.Snapshot(id, SourceDraftManifest.Origin.IMPORT,
            listOf(SourceDraftManifest.Entry("source-0-$id.mp4", "Видео 🌙 \"test\"\n../not-a-path", 7)))
    }
    @Test fun displayNamesRoundTripAndOversizeIsRejected() {
        val snapshot = sample()
        assertEquals(snapshot, SourceDraftManifest.decode(SourceDraftManifest.encode(snapshot)))
        val huge = SourceDraftManifest.Snapshot(snapshot.batchId, snapshot.origin,
            listOf(snapshot.entries[0].copy(displayName = "я".repeat(65_536))))
        assertThrows(IllegalArgumentException::class.java) { SourceDraftManifest.encode(huge) }
        assertThrows(Exception::class.java) { SourceDraftManifest.decode(ByteArray(65_537)) }
    }
    @Test fun invalidManifestsNeverGuessSources() {
        val valid = SourceDraftManifest.encode(sample()).toString(Charsets.UTF_8)
        val invalid = listOf("broken", "[]", valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("\"length\":7", "\"length\":\"7\""), valid.replace("\"length\":7", "\"length\":0"),
            valid.replace("\"length\":7", "\"length\":1.5"), valid.replace("source-0-", "../source-0-"),
            valid.replace("source-0-", "source-1-"), valid.replace("\"import\"", "\"anything\""),
            JSONObject(valid).apply { getJSONArray("sources").put(getJSONArray("sources").get(0)) }.toString())
        invalid.forEach { assertThrows("Accepted $it", Exception::class.java) { SourceDraftManifest.decode(it.toByteArray()) } }
        assertThrows(Exception::class.java) { SourceDraftManifest.decode(byteArrayOf(0xc3.toByte(), 0x28)) }
    }
    @Test fun snapshotOwnsItsEntriesAndEmptyIsExplicit() {
        val entries = sample().entries.toMutableList()
        val snapshot = SourceDraftManifest.Snapshot(UUID.randomUUID(), SourceDraftManifest.Origin.LEGACY, entries)
        entries.clear()
        assertEquals(1, snapshot.entries.size)
        val empty = SourceDraftManifest.Snapshot(UUID.randomUUID(), SourceDraftManifest.Origin.IMPORT, emptyList())
        assertEquals(empty, SourceDraftManifest.decode(SourceDraftManifest.encode(empty)))
    }
}
