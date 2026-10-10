package com.veycad.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TextEditActivityDeviceTest {
    private val context get()=ApplicationProvider.getApplicationContext<android.content.Context>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun waitFor(predicate:()->Boolean) {
        val deadline=android.os.SystemClock.elapsedRealtime()+60_000
        while(true) {
            var ready=false; instrumentation.runOnMainSync { ready=predicate() }
            if(ready) return
            check(android.os.SystemClock.elapsedRealtime()<deadline) { "Text editor condition timed out" }
            Thread.sleep(50)
        }
    }
    private fun awaitMainReady(scenario:ActivityScenario<MainActivity>) {
        lateinit var director:android.view.View
        lateinit var splash:android.view.View
        lateinit var result:android.view.View
        scenario.onActivity {
            director=it.findViewById(R.id.directorContent); splash=it.findViewById(R.id.splashContent)
            result=it.findViewById(R.id.resultContent)
        }
        // Main restores a previous completed MP4. Use the same back navigation as a user.
        waitFor { !splash.isShown && (director.isShown || result.isShown) }
        var restoredResult=false
        scenario.onActivity { restoredResult=result.isShown }
        if(restoredResult) androidx.test.espresso.Espresso.pressBack()
        waitFor { director.isShown && director.width>0 && !director.isLayoutRequested }
    }
    private fun awaitMainView(scenario:ActivityScenario<MainActivity>,id:Int) {
        lateinit var view:android.view.View
        scenario.onActivity { view=it.findViewById(id) }
        waitFor { view.isShown && view.width>0 && !view.isLayoutRequested }
    }
    @Test fun previewTapsPlayPauseAndControlsRemainReachable() {
        val source=SyntheticVideo.create(File(context.filesDir,"preview-${UUID.randomUUID()}.mp4"),6000)
        ActivityScenario.launch<TextEditActivity>(Intent(context,TextEditActivity::class.java)
            .putExtra(TextEditActivity.EXTRA_SOURCE,source.path)).use { scenario ->
            lateinit var preview:android.widget.VideoView
            scenario.onActivity { preview=it.findViewById(R.id.textPreview) }
            waitFor { preview.duration>=6000 }
            onView(withId(R.id.textPreview)).perform(click())
            waitFor { preview.isPlaying && preview.currentPosition>=200 }
            onView(withId(R.id.textPreview)).perform(click())
            waitFor { !preview.isPlaying }
            val paused=preview.currentPosition
            Thread.sleep(200)
            scenario.onActivity { assertTrue(kotlin.math.abs(preview.currentPosition-paused)<150) }
            onView(withId(R.id.textHookButton)).perform(scrollTo()).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
        }
    }
    @Test fun selectedReplacementSourceSurvivesActivityRecreation() {
        val first=SyntheticVideo.create(File(context.filesDir,"first-${UUID.randomUUID()}.mp4"),2000)
        val replacement=SyntheticVideo.create(File(context.filesDir,"replacement-${UUID.randomUUID()}.mp4"),1000)
        val session=TextEditSession.get(context)
        ActivityScenario.launch<TextEditActivity>(Intent(context,TextEditActivity::class.java)
            .putExtra(TextEditActivity.EXTRA_SOURCE,first.path)).use { scenario ->
            waitFor { session.state.value?.project?.sourcePath==first.canonicalPath && session.state.value?.busy==false }
            instrumentation.runOnMainSync { session.open(replacement) }
            waitFor { session.state.value?.project?.sourcePath==replacement.canonicalPath && session.state.value?.busy==false }
            scenario.recreate()
            waitFor { session.state.value?.busy==false }
            assertEquals(replacement.canonicalPath,session.state.value!!.project!!.sourcePath)
            assertEquals(1_000_000L,session.state.value!!.project!!.durationUs)
        }
    }
    @Test fun editStylesCuesRestoreDraftCancelAndPublishNewMp4() {
        val source=SyntheticVideo.create(File(context.cacheDir,"ui-text-${UUID.randomUUID()}.mp4"),2000)
        val initialBytes=source.readBytes()
        val intent=Intent(context,TextEditActivity::class.java).putExtra(TextEditActivity.EXTRA_SOURCE,source.path)
        ActivityScenario.launch<TextEditActivity>(intent).use { scenario ->
            val session=TextEditSession.get(context)
            waitFor { session.state.value?.project?.sourcePath==source.canonicalPath && session.state.value?.busy==false }
            onView(withId(R.id.textHookButton)).perform(scrollTo(),click())
            onView(withId(R.id.textContentField)).perform(replaceText("Как сделать X"),closeSoftKeyboard())
            onView(withId(R.id.textStartField)).perform(replaceText("0.250"),closeSoftKeyboard())
            onView(withId(R.id.textEndField)).perform(replaceText("1.250"),closeSoftKeyboard())
            onView(withId(R.id.textFontField)).perform(scrollTo(),click())
            androidx.test.espresso.Espresso.onData(org.hamcrest.Matchers.equalTo("С засечками"))
                .inRoot(androidx.test.espresso.matcher.RootMatchers.isPlatformPopup()).perform(click())
            onView(withId(R.id.textAnimationField)).perform(scrollTo(),click())
            androidx.test.espresso.Espresso.onData(org.hamcrest.Matchers.equalTo("Плавное"))
                .inRoot(androidx.test.espresso.matcher.RootMatchers.isPlatformPopup()).perform(click())
            onView(withText("Сохранить")).perform(click())
            onView(withText("Плашки")).perform(scrollTo(),click())
            onView(withId(R.id.textPlateButton)).perform(scrollTo(),click())
            onView(withId(R.id.textContentField)).perform(replaceText("Описание"),closeSoftKeyboard())
            onView(withText("Сохранить")).perform(click())
            onView(withText("Субтитры")).perform(scrollTo(),click())
            onView(withId(R.id.textCaptionButton)).perform(scrollTo(),click())
            onView(withId(R.id.textContentField)).perform(replaceText("Исправленная речь"),closeSoftKeyboard())
            onView(withText("Сохранить")).perform(click())
            onView(withId(R.id.textSpeechButton)).perform(scrollTo(),click())
            onView(withText("Сохранить мои правки")).perform(click())
            assertEquals("Исправленная речь",session.state.value!!.project!!.captions.single().text)
            scenario.recreate()
            waitFor { session.state.value?.busy==false }
            val saved=TextEditStore(File(context.filesDir,"text-drafts")).load(source.path)!!
            assertEquals(2,saved.layers.size); assertEquals(250_000L,saved.layers.first().startUs)
            assertEquals(TextFont.SERIF,saved.layers.first().style.font)
            assertEquals(TextAnimation.FADE,saved.layers.first().style.animation)
            assertTrue(saved.captionsEdited)
            val before=CompletedRenderStore.list(context.filesDir).map { it.file.name }.toSet()
            instrumentation.runOnMainSync { session.export(); session.cancel() }
            waitFor { session.state.value?.busy==false }
            assertEquals(before,CompletedRenderStore.list(context.filesDir).map { it.file.name }.toSet())
            onView(withId(R.id.textExportButton)).perform(scrollTo(),click())
            waitFor { session.state.value?.busy==false }
            assertNull(session.state.value!!.error)
            assertNotNull(session.state.value!!.entry)
            assertEquals(session.state.value!!.entry!!.file.path,TextEditStore(File(context.filesDir,"text-drafts")).resultPath(source.path))
            assertArrayEquals(initialBytes,source.readBytes())
        }
    }
    @Test fun mainAndCompletedVideoOpenSameEditor() {
        val source=SyntheticVideo.create(File(context.filesDir,"text-entry-${UUID.randomUUID()}.mp4"),2000)
        val session=TextEditSession.get(context)
        instrumentation.runOnMainSync { session.open(source) }
        waitFor { session.state.value?.busy==false && session.state.value?.project?.sourcePath==source.canonicalPath }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitMainReady(scenario)
            onView(withId(R.id.textEditorButton)).perform(scrollTo(),click())
            onView(withId(R.id.textHookButton)).perform(scrollTo())
            androidx.test.espresso.Espresso.pressBack()
        }
        val finished=CompletedRenderStore.publish(context.filesDir,source,"Text test result")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitMainReady(scenario)
            onView(withId(R.id.myEditsButton)).perform(scrollTo(),click())
            awaitMainView(scenario,R.id.myEditsContent)
            onView(withText("Последний монтаж · ещё не сохранён в галерею")).perform(scrollTo(),click())
            awaitMainView(scenario,R.id.resultContent)
            onView(withId(R.id.addResultTextButton)).perform(scrollTo(),click())
            waitFor { session.state.value?.project?.sourcePath==finished.file.canonicalPath && session.state.value?.busy==false }
            assertEquals(2_000_000L,session.state.value!!.project!!.durationUs)
            onView(withId(R.id.textHookButton)).perform(scrollTo())
            androidx.test.espresso.Espresso.pressBack()
        }
    }
}
