package com.veycad.app

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayingAtLeast
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.containsString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class CustomMusicEditorDeviceTest {
    @get:Rule val fixture = UiTestFixtureRule()

    private fun savedProject(): HybridProject {
        val context = fixture.context
        val video = SyntheticVideo.create(File(context.filesDir, "pending-renders/music-editor-input.mp4"))
        val source = MediaInputInspector().inspect("music-editor-video", video, "Видео для своего трека")
        val music = SyntheticAudio.writeWave(File(context.filesDir, "music-editor-track.wav"))
        val graph = ManualMvpBasicGraph.create(listOf(source), "local-music").copy(
            audioTrack = MontageGraph.AudioTrack("local-music", 1f, 2_000_000L),
            metadata = NleProjectMetadata(generator = "custom-music-test"))
        val id = CustomMusicProjectHandoff.publish(context, graph, MediaSourceSet(listOf(source)), music,
            ProjectExportSettings(720, 1280, 30, 5_000_000, 192_000))
        video.delete()
        music.delete()
        return HybridProjectStore(context.filesDir).load(id)
    }

    @Test fun savedCustomMontageReopensAndEditsWithItsMusicOffset() {
        fixture.launch()
        val project = savedProject()
        val store = HybridProjectStore(fixture.context.filesDir)
        assertEquals(2_000_000L, project.current.music.startUs)
        assertEquals(2_000_000L, project.current.graph.audioTrack!!.sourceStartUs)
        val intent = Intent(fixture.context, MusicEditorActivity::class.java)
            .putExtra(MusicEditorActivity.EXTRA_PROJECT_ID, project.id)
        ActivityScenario.launch<MusicEditorActivity>(intent).use { scenario ->
            fixture.waitUntil("custom project opened") {
                var ready = false
                scenario.onActivity { activity ->
                    fun has(view: android.view.View): Boolean = when (view) {
                        is android.widget.TextView -> view.text.toString() == "Конец −1" &&
                            view.isLaidOut && view.width > 0 && view.height > 0
                        is android.view.ViewGroup -> (0 until view.childCount).any { has(view.getChildAt(it)) }
                        else -> false
                    }
                    ready = has(activity.window.decorView)
                }
                ready
            }
            screenshot("manual-music-opened")
            tapAfterSwiping("Конец −1")
            fixture.waitUntil("trim stored with music") { store.load(project.id).current.id > project.current.id }
            val trimmed = store.load(project.id)
            assertEquals(2_000_000L, trimmed.current.music.startUs)
            scenario.recreate()
            fixture.waitUntil("reopened project installed") {
                var ready = false
                scenario.onActivity { activity ->
                    fun has(view: android.view.View): Boolean = when (view) {
                        is android.widget.TextView -> view.text.toString() == "Начало трека: 0:02.0" &&
                            view.isLaidOut && view.width > 0 && view.height > 0
                        is android.view.ViewGroup -> (0 until view.childCount).any { has(view.getChildAt(it)) }
                        else -> false
                    }
                    ready = has(activity.window.decorView)
                }
                ready
            }
            onView(withText(containsString("Начало трека: 0:02.0"))).perform(scrollTo())
            screenshot("manual-music")
            tapAfterSwiping("Отменить")
            fixture.waitUntil("undo keeps music") { store.load(project.id).current.clips == project.current.clips }
            assertEquals(2_000_000L, store.load(project.id).current.graph.audioTrack!!.sourceStartUs)
        }
    }

    @Test fun changedMusicOffsetSurvivesStoreCompilerAndNativeExport() {
        val base = savedProject()
        val store = HybridProjectStore(fixture.context.filesDir)
        val edited = HybridEditCommands.replaceMusic(base, base.current.music.copy(startUs = 3_000_000L))
        store.save(edited, base.current.id)
        val restored = store.load(base.id)
        val settings = ProjectExportSettings(720, 1280, 30, 5_000_000, 192_000)
        val graph = EditableMontageCompiler.compile(EditableMontageProject(restored, settings))
        assertEquals(3_000_000L, graph.audioTrack!!.sourceStartUs)
        val assets = ProjectAssetStore(store.directory(base.id))
        val files = restored.selectedVideos!!.map { selection -> assets.resolve(restored.assets.single { it.id == selection.assetId }) }
        val music = assets.resolve(restored.assets.single { it.id == restored.current.music.assetId })
        val evidence = fixture.context.getDir("music-editor-evidence", 0)
        val output = File(evidence, "edited-offset.mp4")
        val frames = MediaCodecSpeedRampRenderer.render(MediaCodecSpeedRampRenderer.Request(
            graph = graph, sourceFiles = files, outputFile = output, width = 720, height = 1280,
            fps = 30, bitrate = 5_000_000, audioFile = music, context = fixture.context))
        assertTrue(frames > 0 && output.length() > 0L)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(output.path)
            val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
            assertTrue(formats.any { it.getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") })
            assertTrue(formats.any { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") })
        } finally { extractor.release() }
        val pcm = MediaCodecAudioDecoder.decode(output, 1_000_000L)
        assertTrue(pcm.frameCount > 0 && pcm.interleavedSamples.any { kotlin.math.abs(it) > .01f })
        File(evidence, "result.txt").writeText("project=${restored.id}\nrevision=${restored.current.id}\nsourceStartUs=${graph.audioTrack!!.sourceStartUs}\nframes=$frames\nbytes=${output.length()}\n")
    }

    private fun screenshot(name: String) {
        val output = File(fixture.context.getDir("music-editor-evidence", 0), "$name.png")
        output.parentFile!!.mkdirs()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    /** Exercise the real user's gesture on the long music editor, including controls in rows. */
    private fun tapAfterSwiping(text: String) {
        repeat(12) {
            val visible = try {
                onView(withText(text)).check(matches(isDisplayingAtLeast(90)))
                true
            } catch (_: AssertionError) { false }
            if (visible) {
                onView(withText(text)).perform(click())
                return
            }
            onView(isRoot()).perform(swipeUp())
        }
        screenshot("unreachable-control")
        throw AssertionError("Не удалось прокрутить до действия: $text")
    }
}
