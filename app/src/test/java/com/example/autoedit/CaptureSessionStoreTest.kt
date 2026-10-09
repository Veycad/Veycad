package com.example.autoedit

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Properties

class CaptureSessionStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun store() = CaptureSessionStore(temp.root)
    @Test fun recordingMetadataSurvivesNewStoreInstanceWithoutPublishingAProvisionalChoice() {
        val store = store()
        val original = store.create(CaptureScript.fear, CaptureMode.BEST_TAKE, 3, true, 1)
        val changed = original.copy(status = CaptureSessionStore.Status.RECORDING, selectedTake = 2, recordStartedNs = 1_234L)
        store.save(changed)
        assertEquals(changed.copy(selectedTake = null), store().load(original.id))
    }
    @Test fun interruptedProcessDoesNotClaimSuccessfulRecording() {
        val store = store()
        val original = store.create(CaptureScript.fear, CaptureMode.MUSIC, 1, false, 0)
        store.save(original.copy(status = CaptureSessionStore.Status.RECORDING))
        val recovered = store().recover(emptySet()).single()
        assertEquals(CaptureSessionStore.Status.INTERRUPTED, recovered.status)
        assertEquals(0L, recovered.durationMs)
        assertFalse(store.recording(original.id).exists())
    }
    @Test fun liveOwnerIsNotRecoveredAsAnInterruption() {
        val store = store()
        val original = store.create(CaptureScript.fear, CaptureMode.MUSIC, 1, false, 0)
        store.save(original.copy(status = CaptureSessionStore.Status.FINALIZING))
        assertEquals(CaptureSessionStore.Status.FINALIZING, store.recover(setOf(original.id)).single().status)
    }
    @Test fun finalizationMovesOnlyValidatedStagingFile() {
        val store = store()
        val original = store.create(CaptureScript.fear, CaptureMode.MUSIC, 1, true, 0)
        store.staging(original.id).writeBytes(byteArrayOf(1,2,3))
        val ready = store.markReady(original.copy(status = CaptureSessionStore.Status.FINALIZING), 20_000)
        assertEquals(CaptureSessionStore.Status.READY, ready.status)
        assertEquals(3L, ready.bytes)
        assertArrayEquals(byteArrayOf(1,2,3), store.recording(original.id).readBytes())
        assertFalse(store.staging(original.id).exists())
        assertEquals(ready, store().load(original.id))
    }
    @Test(expected = IllegalArgumentException::class) fun cannotFinalizeAnEmptyFile() {
        val store = store()
        val original = store.create(CaptureScript.fear, CaptureMode.MUSIC, 1, true, 0)
        store.staging(original.id).writeBytes(byteArrayOf())
        store.markReady(original.copy(status = CaptureSessionStore.Status.FINALIZING), 20_000)
    }
    @Test fun playbackStopReasonSurvivesFinalizationAndReopeningWithoutMarkingVideoFailed() {
        val store = store()
        val original = store.create(CaptureScript.fear, CaptureMode.BEST_TAKE, 3, true, 0)
        store.staging(original.id).writeBytes(byteArrayOf(1,2,3))
        val reason = "Музыка прервана. Запись остановлена"
        val ready = store.markReady(original.copy(status = CaptureSessionStore.Status.FINALIZING,
            stopReason = reason), 28_000)
        assertEquals(CaptureSessionStore.Status.READY, ready.status)
        assertNull(ready.error)
        assertEquals(reason, store().load(original.id)?.stopReason)
        assertEquals(ready, store().recover(emptySet()).single())
    }
    @Test(expected = IllegalArgumentException::class) fun pathsCannotLeaveCaptureDirectory() {
        store().directory("../source-draft")
    }
    @Test fun publishingAnotherTakeKeepsBothResultLinks() {
        val store = store()
        val original = store.create(CaptureScript.fear, CaptureMode.BEST_TAKE, 3, true, 0)
        store.attachResult(original.id, "first.mp4", 1, automaticallyRecommended = true)
        store.attachResult(original.id, "second.mp4", 2, automaticallyRecommended = false)
        assertEquals(original.id, store().forResult("first.mp4")?.id)
        assertEquals(original.id, store().forResult("second.mp4")?.id)
        val saved = requireNotNull(store().load(original.id))
        assertEquals(listOf("first.mp4", "second.mp4"), saved.resultNames)
        assertEquals(mapOf("first.mp4" to 1, "second.mp4" to 2), saved.resultTakes)
        assertEquals(2, saved.selectedTake)
        assertEquals(1, saved.recommendedTake)
        store.attachResult(original.id, "first.mp4", 1, automaticallyRecommended = true)
        assertEquals(saved, store().load(original.id))
    }
    @Test fun aManualFirstResultDoesNotClaimToBeTheAppsRecommendation() {
        val store = store()
        val original = store.create(CaptureScript.fear, CaptureMode.BEST_TAKE, 3, true, 0)
        store.attachResult(original.id, "manual.mp4", 2, automaticallyRecommended = false)
        val saved = requireNotNull(store().load(original.id))
        assertEquals(2, saved.selectedTake)
        assertNull(saved.recommendedTake)
    }
    @Test fun legacyResultNamesStillLoadWithoutInventingAResultTake() {
        val store = store()
        val original = store.create(CaptureScript.fear, CaptureMode.BEST_TAKE, 3, true, 0)
        val file = store.directory(original.id).resolve("session.properties")
        val properties = Properties().apply {
            file.inputStream().use(::load)
            setProperty("results", "legacy.mp4")
            setProperty("selectedTake", "2") // Old selection could be from a failed render.
        }
        file.outputStream().use { properties.store(it, null) }
        val restored = requireNotNull(store().load(original.id))
        assertEquals(listOf("legacy.mp4"), restored.resultNames)
        assertTrue(restored.resultTakes.isEmpty())
        assertNull(restored.selectedTake)
        assertNull(restored.recommendedTake)
    }
    @Test fun invalidSchemaIsNotSilentlyAccepted() {
        val store = store()
        val original = store.create(CaptureScript.fear, CaptureMode.MUSIC, 1, true, 0)
        store.directory(original.id).resolve("session.properties").writeText("schema=999\nid=${original.id}")
        assertNull(store.load(original.id))
        assertTrue(store.list().isEmpty())
    }
    @Test fun validatedContainerMovedBeforeProcessDeathCanBeRecoveredWithoutOverwritingIt() {
        val store = store()
        val original = store.create(CaptureScript.fear, CaptureMode.MUSIC, 1, true, 0)
        store.recording(original.id).writeBytes(byteArrayOf(4, 5, 6))
        val interrupted = original.copy(status = CaptureSessionStore.Status.INTERRUPTED)
        store.save(interrupted)
        val recovered = store.acceptRecovered(interrupted, 12_000)
        assertEquals(CaptureSessionStore.Status.READY, recovered.status)
        assertArrayEquals(byteArrayOf(4, 5, 6), store.recording(original.id).readBytes())
        assertEquals(recovered, store().load(original.id))
    }
    @Test(expected = IllegalArgumentException::class) fun recoveryCannotPromoteLiveRecording() {
        val store = store()
        val original = store.create(CaptureScript.fear, CaptureMode.MUSIC, 1, true, 0)
        store.recording(original.id).writeBytes(byteArrayOf(1))
        store.acceptRecovered(original.copy(status = CaptureSessionStore.Status.RECORDING), 12_000)
    }
    @Test fun preparedMetadataWithUnfinalizedFramesIsRecoverableAfterProcessDeath() {
        val store = store()
        val original = store.create(CaptureScript.fear, CaptureMode.MUSIC, 1, true, 0)
        store.staging(original.id).writeBytes(byteArrayOf(1, 2))
        assertEquals(CaptureSessionStore.Status.INTERRUPTED, store.recover(emptySet()).single().status)
        assertTrue(store.staging(original.id).isFile)
    }
    @Test fun preparedMetadataWithoutAnyRecordedBytesIsNotShownAsACapture() {
        val store = store()
        val original = store.create(CaptureScript.fear, CaptureMode.MUSIC, 1, true, 0)
        assertNotNull(store().load(original.id))
        assertTrue(store().list().isEmpty())
        assertTrue(store().recover(emptySet()).isEmpty())
        assertTrue(store.directory(original.id).resolve("session.properties").isFile)
        store.staging(original.id).writeBytes(byteArrayOf(1, 2))
        assertEquals(CaptureSessionStore.Status.INTERRUPTED, store().recover(emptySet()).single().status)
        assertArrayEquals(byteArrayOf(1, 2), store.staging(original.id).readBytes())
    }
}
