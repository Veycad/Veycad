package com.example.autoedit

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.hamcrest.Matchers.not
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run this class before the pre-granted suite on the dedicated API 36 emulator.
 * The host resets permission grants/flags before instrumentation starts: revoking an
 * already granted permission inside instrumentation can terminate its own process. */
@RunWith(AndroidJUnit4::class)
class RuntimePermissionsTest {
    @get:Rule val ui = UiTestFixtureRule(grantPermissions = false)

    @Test fun denied_video_permission_keeps_render_disabled_and_retry_imports_after_full_access() {
        assertEquals("Permission dialog selectors are verified on API 36", 36, Build.VERSION.SDK_INT)
        assertPermission(Manifest.permission.READ_MEDIA_VIDEO, PackageManager.PERMISSION_DENIED)
        ui.launch()
        val uri = ui.videoUri("permission-retry-source.mp4")
        // Only the external gallery response is controlled. Both permission requests
        // and the URI copy/import are handled by the actual Activity and platform.
        ui.returnVideos(listOf(uri))
        clickPermissionButton("permission_deny_button")
        ui.awaitIdle()

        assertPermission(Manifest.permission.READ_MEDIA_VIDEO, PackageManager.PERMISSION_DENIED)
        onView(withId(R.id.renderButton)).check(matches(not(isEnabled())))
        onView(withId(R.id.selectVideoButton)).check(matches(isEnabled()))
        assertTrue(RenderWorkspace.sourceDraftDirectory(ui.context.filesDir).listFiles().orEmpty().isEmpty())
        assertTrue("Denied access must not open the gallery",
            Intents.getIntents().none { it.action == Intent.ACTION_PICK })

        ui.click(R.id.selectVideoButton)
        clickPermissionButton("permission_allow_all_button")
        ui.awaitIdle()
        ui.awaitEnabled(R.id.renderButton)
        ui.awaitText(R.id.videoSelection, "permission-retry-source.mp4")
        assertPermission(Manifest.permission.READ_MEDIA_VIDEO, PackageManager.PERMISSION_GRANTED)
        val imported = RenderWorkspace.sourceDraftDirectory(ui.context.filesDir).listFiles().orEmpty()
        assertEquals(1, imported.size)
        assertArrayEquals(File(ui.context.getDir("ui-imports", 0), "permission-retry-source.mp4").readBytes(),
            imported.single().readBytes())
        assertTrue(Intents.getIntents().any { it.action == Intent.ACTION_PICK })
    }

    @Test fun denied_notifications_reset_switch_and_preference_then_retry_persists_allowed_choice() {
        assertEquals("Permission dialog selectors are verified on API 36", 36, Build.VERSION.SDK_INT)
        assertPermission(Manifest.permission.POST_NOTIFICATIONS, PackageManager.PERMISSION_DENIED)
        ui.launch()
        ui.click(R.id.settingsButton)
        ui.awaitDisplayed(R.id.settingsContent)
        onView(withId(R.id.notificationSwitch)).check(matches(not(isChecked())))

        ui.click(R.id.notificationSwitch)
        clickPermissionButton("permission_deny_button")
        ui.awaitIdle()
        assertPermission(Manifest.permission.POST_NOTIFICATIONS, PackageManager.PERMISSION_DENIED)
        onView(withId(R.id.notificationSwitch)).check(matches(not(isChecked())))
        assertFalse(notificationPreference())

        ui.click(R.id.notificationSwitch)
        clickPermissionButton("permission_allow_button")
        ui.awaitIdle()
        assertPermission(Manifest.permission.POST_NOTIFICATIONS, PackageManager.PERMISSION_GRANTED)
        onView(withId(R.id.notificationSwitch)).check(matches(isChecked()))
        assertTrue(notificationPreference())

        ui.recreate()
        ui.click(R.id.settingsButton)
        ui.awaitDisplayed(R.id.settingsContent)
        onView(withId(R.id.notificationSwitch)).check(matches(isChecked()))
        assertTrue(notificationPreference())
        onView(withId(R.id.renderButton)).check(matches(not(isEnabled())))
    }

    private fun notificationPreference(): Boolean = ui.context
        .getSharedPreferences("app_settings", Context.MODE_PRIVATE).getBoolean("notifications", false)

    private fun assertPermission(permission: String, expected: Int) =
        assertEquals(permission, expected, ui.context.checkSelfPermission(permission))

    private fun clickPermissionButton(resourceName: String) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val selector = By.res("com.android.permissioncontroller", resourceName)
        val button = device.wait(Until.findObject(selector), 15_000L)
            ?: throw AssertionError("Actual OS permission dialog did not show $resourceName")
        button.click()
        assertTrue("OS permission dialog must dismiss after $resourceName",
            device.wait(Until.gone(selector), 15_000L))
    }
}
