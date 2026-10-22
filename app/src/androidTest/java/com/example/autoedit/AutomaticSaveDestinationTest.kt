package com.veycad.app

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.os.Build
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

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
}
