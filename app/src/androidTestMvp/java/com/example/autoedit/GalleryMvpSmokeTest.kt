package com.veycad.app

import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.VideoView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** MVP APK only: production session, real codecs and real offline recognition. */
@RunWith(AndroidJUnit4::class)
class GalleryMvpSmokeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val folder get() = File(context.filesDir, "mvp-smoke").apply { mkdirs() }
    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 180_000L
        while (true) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            check(SystemClock.elapsedRealtime() < deadline) { "MVP smoke timed out" }
            SystemClock.sleep(50)
        }
    }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun ownedUri(file: File): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VeycadMvpSmoke")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use { output -> file.inputStream().use { it.copyTo(output) } }
        values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
        assertEquals(1, context.contentResolver.update(uri, values, null, null))
        return uri
    }
    private fun originals(): List<File> = (0..2).map { index ->
        SyntheticVideo.create(File(folder, "input-$index.mp4"), 1_000L, textureSeed = index)
    }
    private fun picker(uris: List<Uri>, result: Int = Activity.RESULT_OK) {
        val data = Intent().apply {
            if (uris.size == 1) this.data = uris.first()
            else if (uris.isNotEmpty()) clipData = ClipData.newRawUri("MVP clips", uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(
            android.app.Instrumentation.ActivityResult(result, data))
    }

    @Test fun threeSourcePickerRotationRealRenderPreviewSaveAndShare() {
        assertEquals("com.veycad.app.mvp", context.packageName)
        assertTrue(BuildConfig.GALLERY_MVP)
        val sources = originals()
        val bytes = sources.map { it.readBytes() }
        val uris = sources.map(::ownedUri)
        val session = EditSession.get(context)
        context.getSharedPreferences("montage_style", 0).edit().putString("selected", "gallery_montage").commit()
        Intents.init()
        try {
            picker(uris)
            intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(
                android.app.Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor { session.state.value?.busy != true }
                // A prior test may have published a result. Return to the same director UI.
                scenario.onActivity {
                    if (it.findViewById<android.view.View>(R.id.resultContent).visibility == android.view.View.VISIBLE)
                        it.onBackPressedDispatcher.onBackPressed()
                }
                lateinit var splash: android.view.View
                scenario.onActivity { splash = it.findViewById(R.id.splashContent) }
                waitFor { !splash.isShown }
                onView(withId(R.id.selectVideoButton)).perform(scrollTo(), click())
                waitFor { session.state.value?.draft?.files?.size == 3 && session.state.value?.busy == false }
                val draft = session.state.value!!.draft!!
                assertEquals(3, draft.names.size)
                bytes.forEachIndexed { index, original -> assertArrayEquals(original, draft.files[index].readBytes()) }
                scenario.recreate()
                scenario.onActivity { splash = it.findViewById(R.id.splashContent) }
                waitFor { !splash.isShown && session.state.value?.busy == false }
                scenario.onActivity {
                    if (it.findViewById<android.view.View>(R.id.resultContent).visibility == android.view.View.VISIBLE)
                        it.onBackPressedDispatcher.onBackPressed()
                }
                scenario.onActivity {
                    assertTrue(it.findViewById<android.widget.TextView>(R.id.videoSelection).text.contains("Выбрано 3"))
                    assertTrue(it.findViewById<android.widget.Button>(R.id.renderButton).isEnabled)
                }
                onView(withId(R.id.renderButton)).perform(scrollTo(), click())
                waitFor { session.state.value?.busy == false }
                assertNull(session.state.value!!.error)
                val entry = session.state.value!!.entry!!
                assertTrue(entry.summary.contains("3 видео"))
                assertTrue(entry.summary.contains("базовая проверка"))
                val container = VeykadRenderInspector.inspectContainer(entry.file, 720, 1280)
                assertTrue(container.issues.toString(), container.issues.isEmpty())
                val provenance = VeykadRenderInspector.decodedFrameProvenance(VeykadRenderInspector.latest(context))
                assertEquals(setOf(0, 1, 2), provenance.values.map { it.sourceIndex }.toSet())
                lateinit var video: VideoView
                scenario.onActivity { video = it.findViewById(R.id.resultVideo) }
                waitFor { video.isPlaying && video.currentPosition > 100 }
                val encodedBytes = entry.file.readBytes()
                onView(withId(R.id.saveButton)).perform(scrollTo(), click())
                waitFor { session.state.value?.busy == false }
                assertNull(session.state.value!!.error)
                assertTrue(session.state.value!!.entry!!.saved)
                val saved = CompletedRenderStore.savedFiles(context.filesDir).single { it.name == entry.file.name }
                assertEquals(entry.file, saved)
                assertArrayEquals(encodedBytes, saved.readBytes())
                context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Video.Media._ID), "${MediaStore.Video.Media.DISPLAY_NAME}=? AND " +
                        "${MediaStore.Video.Media.OWNER_PACKAGE_NAME}=?", arrayOf(entry.file.name, context.packageName), null)!!.use {
                    assertEquals(1, it.count)
                }
                onView(withId(R.id.shareButton)).perform(scrollTo(), click())
                Intents.intended(hasAction(Intent.ACTION_CHOOSER))
                sources.forEachIndexed { index, file -> assertArrayEquals(bytes[index], file.readBytes()) }
            }
        } finally { Intents.release(); uris.forEach { context.contentResolver.delete(it, null, null) } }
    }

    @Test fun cancelAndInvalidSelectionPreservePreviousVideoAndOriginals() {
        val sources = originals()
        val bytes = sources.map { it.readBytes() }
        val session = EditSession.get(context)
        val previous = CompletedRenderStore.latest(context.filesDir) ?: CompletedRenderStore.publish(
            context.filesDir, sources.first().copyTo(File(folder, "previous.mp4"), true), "previous")
        val previousBytes = previous.file.readBytes()
        val uris = sources.map(::ownedUri)
        main { session.importVideos(uris, MontageStyleCatalog.galleryMontage) }
        waitFor { session.state.value?.busy == false }
        val draft = session.state.value!!.draft!!
        main {
            session.importVideos(uris, MontageStyleCatalog.galleryMontage)
            assertTrue(session.cancelRender())
        }
        waitFor { session.state.value?.busy == false }
        assertNotNull(session.state.value!!.error)
        assertEquals(draft.files, session.state.value!!.draft!!.files)
        main { session.importVideos(List(21) { uris.first() }, MontageStyleCatalog.galleryMontage) }
        assertNotNull(session.state.value!!.error)
        val invalid = File(folder, "invalid.mp4").apply { writeText("invalid") }
        val invalidUri = ownedUri(invalid)
        try {
            main { session.importVideos(listOf(invalidUri), MontageStyleCatalog.galleryMontage) }
            waitFor { session.state.value?.busy == false }
            assertNotNull(session.state.value!!.error)
            assertEquals(draft.files, session.state.value!!.draft!!.files)
            main {
                session.render(draft.files, requireNotNull(draft.music).file, MontageStyleCatalog.galleryMontage)
                assertTrue(session.cancelRender())
            }
            waitFor { session.state.value?.busy == false }
            assertNotNull(session.state.value!!.error)
            assertArrayEquals(previousBytes, previous.file.readBytes())
            assertEquals(previous.file, CompletedRenderStore.latest(context.filesDir)!!.file)
            sources.forEachIndexed { index, source -> assertArrayEquals(bytes[index], source.readBytes()) }
            assertTrue(draft.files.all(File::isFile))
        } finally { (uris + invalidUri).forEach { context.contentResolver.delete(it, null, null) } }
    }

    @Test fun separatelyChosenRawRussianSpeechRecognizedInExistingTextEditor() {
        val wav = instrumentation.context.assets.open("speech/russian.wav").use { it.readBytes() }
        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).apply { position(12) }
        var bytes = ByteArray(0)
        while (buffer.remaining() >= 8) {
            val id = ByteArray(4).also { buffer.get(it) }.toString(Charsets.US_ASCII)
            val size = buffer.int
            if (id == "data") { bytes = ByteArray(size).also { buffer.get(it) }; break }
            buffer.position(buffer.position() + size + (size and 1))
        }
        assertTrue(bytes.isNotEmpty())
        val pcm = File(folder, "russian.raw").apply { writeBytes(bytes) }
        val silent = SyntheticVideo.create(File(folder, "speech-video.mp4"), 8_000L)
        val raw = File(folder, "raw-speech.mp4")
        TextAudioMuxer.mux(silent, PcmFile(pcm, 16000, 1, bytes.size / 2L, true), raw, {})
        val original = raw.readBytes()
        val uri = ownedUri(raw)
        val session = TextEditSession.get(context)
        Intents.init()
        try {
            picker(listOf(uri))
            ActivityScenario.launch<TextEditActivity>(Intent(context, TextEditActivity::class.java)
                .putExtra(TextEditActivity.EXTRA_SOURCE, raw.path)).use {
                waitFor { session.state.value?.project?.sourcePath == raw.canonicalPath && session.state.value?.busy == false }
                onView(withId(R.id.textChooseButton)).perform(scrollTo(), click())
                waitFor { session.state.value?.project?.sourcePath != raw.canonicalPath && session.state.value?.busy == false }
                onView(androidx.test.espresso.matcher.ViewMatchers.withText("Субтитры")).perform(scrollTo(), click())
                onView(withId(R.id.textSpeechButton)).perform(scrollTo(), click())
                waitFor { session.state.value?.busy == false }
                assertTrue(session.state.value!!.error.orEmpty(), session.state.value!!.project!!.captions
                    .joinToString(" ") { it.text }.lowercase().contains("субтитр"))
                assertTrue(session.state.value!!.project!!.captions.all { it.endUs <= 8_000_000L })
                assertArrayEquals(original, raw.readBytes())
                MediaMetadataRetriever().use { metadata ->
                    metadata.setDataSource(raw.path)
                    assertEquals("yes", metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                }
            }
        } finally { Intents.release(); context.contentResolver.delete(uri, null, null) }
    }
}
