package com.veycad.app

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.os.Build
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matchers.allOf
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AutomaticSaveDestinationTest {
    @get:Rule val ui = UiTestFixtureRule(grantPermissions = false)

    @Test fun automaticSaveUsesSupportedDestinationAndSurvivesRecreation() {
        val entry = ui.seedCompleted("automatic-destination.mp4")
        val bytes = entry.file.readBytes()
        val destination = ui.videoUri("export-destination.mp4")
        intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(
            ActivityResult(Activity.RESULT_OK, Intent().setData(destination)))
        ui.context.getSharedPreferences("app_settings", 0).edit().putBoolean("auto_save", true).commit()
        ui.launch()
        ui.awaitText(R.id.saveButton, "Сохранено")
        ui.awaitIdle()
        val expectedDocuments = if (Build.VERSION.SDK_INT < 29) 1 else 0
        assertEquals(expectedDocuments, Intents.getIntents().count { it.action == Intent.ACTION_CREATE_DOCUMENT })
        if (Build.VERSION.SDK_INT < 29) {
            assertArrayEquals(bytes, File(ui.context.getDir("ui-imports", 0), "export-destination.mp4").readBytes())
        } else ui.assertGalleryCopy(entry.file.name)
        assertTrue(CompletedRenderStore.latest(ui.context.filesDir)!!.saved)
        ui.recreate()
        ui.awaitText(R.id.saveButton, "Сохранено")
        ui.awaitIdle()
        assertEquals(expectedDocuments, Intents.getIntents().count { it.action == Intent.ACTION_CREATE_DOCUMENT })
        assertArrayEquals(bytes, entry.file.readBytes())
    }

    @Test fun savingOlderResultKeepsItsDestinationAcrossRecreation() {
        val older = ui.seedCompleted("pending-older-${System.nanoTime()}.mp4")
        val newer = ui.seedCompleted("pending-newer-${System.nanoTime()}.mp4")
        older.file.setLastModified(1_000)
        newer.file.setLastModified(2_000)
        ui.launch()
        ui.awaitDisplayed(R.id.resultContent)
        pressBack()
        ui.click(R.id.myEditsButton)
        onView(allOf(withText(older.summary), isDisplayed())).perform(scrollTo(), click())
        lateinit var original: MainActivity
        ui.scenario.onActivity { original = it }
        ui.click(R.id.saveButton)
        if (Build.VERSION.SDK_INT < 29) {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertTrue("The real document destination must be pending",
                device.wait(Until.hasObject(By.pkg("com.android.documentsui")), 15_000))
            val created = CountDownLatch(1)
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            val callback = ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && activity !== original && stage == Stage.CREATED) created.countDown()
            }
            monitor.addLifecycleCallback(callback)
            try {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { original.recreate() }
                assertTrue("Recreate while destination is pending", created.await(15, TimeUnit.SECONDS))
            } finally {
                monitor.removeLifecycleCallback(callback)
            }
            val save = device.wait(Until.findObject(By.text("SAVE")), 15_000)
                ?: throw AssertionError("Document provider did not expose Save")
            save.click()
            assertTrue(device.wait(Until.gone(By.pkg("com.android.documentsui")), 15_000))
        } else {
            ui.awaitText(R.id.saveButton, "Сохранено")
            ui.awaitIdle()
        }
        ui.recreate()
        ui.awaitText(R.id.saveButton, "Сохранено")
        ui.awaitIdle()
        val entries = CompletedRenderStore.list(ui.context.filesDir)
        assertTrue(entries.single { it.file == older.file }.saved)
        assertFalse(entries.single { it.file == newer.file }.saved)
    }
}
