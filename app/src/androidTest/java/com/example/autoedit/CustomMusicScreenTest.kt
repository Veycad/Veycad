package com.veycad.app

import android.app.Activity
import android.content.Intent
import android.widget.SeekBar
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.swipeRight
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CustomMusicScreenTest {
    @get:Rule val ui = UiTestFixtureRule()

    @Test fun importing_wav_changes_to_custom_recipe_and_keeps_the_chosen_fragment_after_recreation() {
        ui.launch()
        ui.importVideos("portrait.mp4")
        val uri = ui.videoUri("my-track.wav")
        SyntheticAudio.writeWave(File(ui.context.getDir("ui-imports", 0), "my-track.wav"))
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(
            android.app.Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri)))
        ui.click(R.id.importMusicButton)
        ui.awaitEnabled(R.id.renderButton)
        onView(withId(R.id.selectStyleButton)).check(matches(withSubstring("Под свою музыку")))
        onView(withId(R.id.musicSelection)).check(matches(withSubstring("my-track.wav")))
        val imported = requireNotNull(CustomMusicStore(ui.context).restore())
        assertArrayEquals(File(ui.context.getDir("ui-imports", 0), "my-track.wav").readBytes(), imported.file.readBytes())
        onView(withId(R.id.musicStartSeek)).perform(scrollTo(), swipeRight())
        ui.awaitEnabled(R.id.renderButton)
        val shifted = requireNotNull(CustomMusicStore(ui.context).restore())
        assertTrue(shifted.startUs > 0L)
        ui.recreate()
        ui.awaitEnabled(R.id.renderButton)
        assertEquals(shifted.startUs, CustomMusicStore(ui.context).restore()!!.startUs)
        onView(withId(R.id.musicFragmentPanel)).perform(scrollTo())
        val screenshot = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(ui.context.getDir("music-editor-evidence", 0), "own-music-screen.png").outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
        ui.click(R.id.renderButton)
        ui.waitUntil("custom audio request") { ui.renderer.currentRequest != null }
        assertEquals(MontageStyleCatalog.Recipe.CUSTOM_MUSIC, ui.renderer.currentRequest!!.recipe)
        assertEquals(shifted.startUs, ui.renderer.currentRequest!!.musicStartUs)
        assertEquals(shifted.file.canonicalPath, ui.renderer.currentRequest!!.musicFile.canonicalPath)
        ui.renderer.complete()
        ui.awaitIdle()
    }

    @Test fun cancelling_the_music_picker_keeps_the_authored_style_and_valid_track() {
        ui.launch()
        ui.importVideos("portrait.mp4")
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(
            android.app.Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
        ui.click(R.id.importMusicButton)
        onView(withId(R.id.selectStyleButton)).check(matches(withSubstring("Пульс под музыку")))
        onView(withId(R.id.renderButton)).check(matches(isEnabled()))
        assertNull(CustomMusicStore(ui.context).restore())
    }

    @Test fun customMusicActivationRequiresOneConfirmedSourceWithoutChangingTheSavedPair() {
        ui.launch()
        ui.selectStyle("Диалог двух сцен")
        ui.importVideos("pair-first.mp4", "pair-second.mp4")
        val store = SourceDraftStore(ui.context.filesDir)
        val pair = requireNotNull(store.read())
        val pairBytes = pair.entries.associate { it.fileName to store.child(it.fileName).readBytes() }
        val uri = ui.videoUri("pair-custom.wav")
        SyntheticAudio.writeWave(File(ui.context.getDir("ui-imports", 0), "pair-custom.wav"))
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(
            android.app.Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri)))
        ui.click(R.id.importMusicButton)
        ui.awaitText(R.id.selectStyleButton, "Под свою музыку")
        ui.waitUntil("custom music ready, pair requires a new one-source selection") {
            var ready = false
            ui.scenario.onActivity { activity ->
                ready = !ViewModelProvider(activity)[CustomMusicSession::class.java].current.busy &&
                    EditSession.get(activity).state.value?.sourceSelectionConfirmed == false
            }
            ready
        }
        onView(withId(R.id.renderButton)).check(matches(isNotEnabled()))
        assertEquals(pair, store.read())
        ui.cancelVideoPicker()
        assertEquals(pair, store.read())
        val failing = ui.videoUri("rejected-single.mp4").buildUpon().appendQueryParameter("failAfter", "1").build()
        ui.returnVideos(listOf(failing))
        ui.awaitIdle()
        assertEquals(pair, store.read())
        pairBytes.forEach { (name, bytes) -> assertArrayEquals(bytes, store.child(name).readBytes()) }
        onView(withId(R.id.renderButton)).check(matches(isNotEnabled()))

        ui.importVideos("confirmed-single.mp4")
        val single = requireNotNull(store.read())
        assertEquals(1, single.entries.size)
        ui.startRender()
        val request = requireNotNull(ui.renderer.currentRequest)
        val music = requireNotNull(CustomMusicStore(ui.context).restore())
        assertEquals(MontageStyleCatalog.Recipe.CUSTOM_MUSIC, request.recipe)
        assertEquals(listOf(store.child(single.entries.single().fileName)), request.sources.items.map { it.file })
        assertEquals(music.file, request.musicFile)
        assertEquals(music.startUs, request.musicStartUs)
        ui.renderer.complete()
        ui.awaitIdle()
    }

    @Test fun pending_import_and_offset_analysis_survive_recreation_and_block_all_style_entries() {
        ui.launch()
        ui.importVideos("portrait.mp4")
        val uri = ui.videoUri("pending.wav")
        SyntheticAudio.writeWave(File(ui.context.getDir("ui-imports", 0), "pending.wav"))
        lateinit var retained: CustomMusicSession
        ui.scenario.onActivity { activity ->
            retained = ViewModelProvider(activity)[CustomMusicSession::class.java]
            retained.import(uri)
            assertTrue(retained.current.busy)
            for (id in listOf(R.id.selectStyleButton, R.id.allStylesButton, R.id.defaultStyleSetting)) {
                val entry = activity.findViewById<View>(id)
                assertFalse(entry.isEnabled)
                entry.performClick() // The listener guard also blocks programmatic/queued clicks.
            }
        }
        ui.recreate()
        ui.awaitEnabled(R.id.renderButton)
        ui.scenario.onActivity { activity ->
            assertSame(retained, ViewModelProvider(activity)[CustomMusicSession::class.java])
            assertEquals("pending.wav", retained.current.selection!!.title)
            retained.analyze(retained.current.selection!!.copy(startUs = 2_300_000))
            assertTrue(retained.current.busy)
        }
        ui.recreate()
        ui.awaitEnabled(R.id.renderButton)
        assertEquals(2_300_000L, CustomMusicStore(ui.context).restore()!!.startUs)
        onView(withId(R.id.selectStyleButton)).check(matches(withSubstring("Под свою музыку")))
    }
}
