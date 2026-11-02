package com.veycad.app

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class LicenseExportScreenTest {
    @get:Rule val ui = UiTestFixtureRule()

    @Test fun savesOnlyBundledLicensesIncludingNestedAndEmptyFiles() {
        val privateFile = File(ui.context.filesDir, "private-video.mp4").apply { writeText("PRIVATE") }
        val destination = ui.videoUri("licenses.zip")
        intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(
            ActivityResult(Activity.RESULT_OK, Intent().setData(destination)))
        openExport()
        val output = File(ui.context.getDir("ui-imports", 0), "licenses.zip")
        ui.waitUntil("complete license archive") { runCatching { ZipFile(output).use { it.size() >= 4 } }.getOrDefault(false) }
        ZipFile(output).use { archive ->
            val names = archive.entries().asSequence().map { it.name }.toList()
            assertEquals(names.size, names.toSet().size)
            assertTrue(names.containsAll(listOf("licenses/whisper.txt", "licenses/whisper-model.txt",
                "licenses/export-test/nested.txt", "licenses/export-test/empty.txt")))
            for (name in names) {
                assertTrue(name.startsWith("licenses/") && !name.contains(".."))
                assertArrayEquals(ui.context.assets.open(name).use { it.readBytes() },
                    archive.getInputStream(archive.getEntry(name)).use { it.readBytes() })
            }
            assertFalse(names.any { it.contains("private-video") || it.contains("speech/") || it.contains("models/") })
        }
        assertEquals("PRIVATE", privateFile.readText())
        assertEquals("application/zip", Intents.getIntents().single { it.action == Intent.ACTION_CREATE_DOCUMENT }.type)
        ui.awaitDisplayed(R.id.settingsContent)
    }

    @Test fun cancellationReturnsToSettingsWithoutChangingPrivateFiles() {
        val privateFile = File(ui.context.filesDir, "private-video.mp4").apply { writeText("KEEP") }
        intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_CANCELED, null))
        openExport()
        ui.awaitDisplayed(R.id.settingsContent)
        assertEquals("KEEP", privateFile.readText())
        assertEquals(1, Intents.getIntents().count { it.action == Intent.ACTION_CREATE_DOCUMENT })
    }

    @Test fun unavailableDestinationReportsFailureWithoutChangingPrivateFiles() {
        val privateFile = File(ui.context.filesDir, "private-video.mp4").apply { writeText("KEEP") }
        assertFalse(LicenseArchive.export(ui.context, android.net.Uri.parse("content://missing.veycad.test/licenses.zip")))
        assertEquals("KEEP", privateFile.readText())
    }

    private fun openExport() {
        ui.launch()
        ui.click(R.id.settingsButton)
        ui.click(R.id.versionButton)
        onView(withText("Сохранить лицензии")).perform(click())
    }
}
