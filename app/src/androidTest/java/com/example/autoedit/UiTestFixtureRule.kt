package com.example.autoedit

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matcher
import org.hamcrest.Matchers.*
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import java.io.File

/** Bounded condition waits; real UI and storage, controlled expensive render only. */
class UiTestFixtureRule(private val grantPermissions: Boolean = true) : ExternalResource() {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val context: Context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as UiTestApplication
    internal val renderer get() = app.renderer
    lateinit var scenario: ActivityScenario<MainActivity>
        private set
    private var launched = false
    private var activity: MainActivity? = null
    private lateinit var fixture: File

    override fun before() {
        check(context.packageName.endsWith(".uitest")) { "UI tests must never reset the production app" }
        instrumentation.runOnMainSync { app.resetSession() }
        context.getSystemService(android.app.NotificationManager::class.java).cancelAll()
        cleanGallery()
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        File(context.applicationInfo.dataDir, "shared_prefs").listFiles()?.forEach { file ->
            context.getSharedPreferences(file.nameWithoutExtension, 0).edit().clear().commit()
        }
        context.getDir("ui-imports", 0).listFiles()?.forEach(File::delete)
        context.getSharedPreferences("app_settings", 0).edit().putBoolean("notifications", false).commit()
        fixture = SyntheticVideo.create(File(context.getDir("ui-fixtures", 0), "synthetic.mp4"))
        renderer.outputFixture = fixture
        if (grantPermissions) {
            grant(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT >= 33) grant(Manifest.permission.POST_NOTIFICATIONS)
        }
        Intents.init()
    }

    override fun after() {
        try {
            if (launched) {
                instrumentation.runOnMainSync { app.editSession.cancelRender() }
                awaitIdle()
            }
        } finally {
            try {
                if (launched) scenario.close()
            } finally {
                launched = false
                activity = null
                Intents.release()
                instrumentation.runOnMainSync { app.resetSession() }
                cleanGallery()
                context.getSystemService(android.app.NotificationManager::class.java).cancelAll()
            }
        }
    }

    private fun grant(permission: String) = instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)

    fun launch(): ActivityScenario<MainActivity> {
        check(!launched)
        instrumentation.runOnMainSync { app.resetSession() }
        renderer.outputFixture = fixture
        scenario = ActivityScenario.launch(MainActivity::class.java)
        launched = true
        scenario.onActivity { activity = it }
        waitUntil("splash dismissed") { activity!!.findViewById<View>(R.id.splashContent).visibility == View.GONE }
        return scenario
    }

    fun recreate() {
        scenario.recreate()
        scenario.onActivity { activity = it }
        waitUntil("recreated splash dismissed") { activity!!.findViewById<View>(R.id.splashContent).visibility == View.GONE }
    }

    fun click(id: Int) {
        // All screen actions are inside ScrollViews, except a few dialog actions.
        val view = onView(withId(id))
        view.perform(scrollTo(), click())
    }
    fun text(id: Int): String {
        var value = ""
        scenario.onActivity { value = it.findViewById<TextView>(id).text.toString() }
        return value
    }
    fun awaitDisplayed(id: Int) = waitUntil("view $id visible") {
        activity!!.findViewById<View>(id).let { it.visibility == View.VISIBLE && it.isShown }
    }
    fun awaitEnabled(id: Int) = waitUntil("view $id enabled") { activity!!.findViewById<View>(id).isEnabled }
    fun awaitText(id: Int, substring: String) = waitUntil("view $id contains $substring") {
        activity!!.findViewById<TextView>(id).text.contains(substring)
    }
    fun awaitIdle() {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (launched) {
            var busy = false
            instrumentation.runOnMainSync { busy = app.editSession.state.value?.busy == true }
            if (!busy) break
            if (SystemClock.elapsedRealtime() >= deadline) throw AssertionError("Session did not become idle")
            SystemClock.sleep(25)
        }
        instrumentation.waitForIdleSync()
    }
    fun waitUntil(description: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        if (!launched) error("Launch activity before waiting for $description")
        onView(isRoot()).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isRoot()
            override fun getDescription() = "Wait for $description"
            override fun perform(controller: UiController, view: View) {
                val deadline = SystemClock.elapsedRealtime() + timeoutMs
                while (!condition()) {
                    if (SystemClock.elapsedRealtime() >= deadline) throw AssertionError("Timed out: $description")
                    controller.loopMainThreadForAtLeast(25)
                }
            }
        })
    }

    fun selectStyle(title: String) {
        click(R.id.selectStyleButton)
        onView(allOf(isClickable(), hasDescendant(withText(title)))).perform(scrollTo(), click())
        onView(withText("Применить")).perform(click())
    }

    fun videoUri(name: String): Uri {
        val target = File(context.getDir("ui-imports", 0), name)
        require(target.name == name)
        if (!target.exists()) {
            fixture.copyTo(target)
            java.io.DataOutputStream(java.io.FileOutputStream(target, true)).use { output ->
                val payload = name.toByteArray(Charsets.UTF_8)
                output.writeInt(8 + payload.size)
                output.writeBytes("free")
                output.write(payload)
            }
        }
        return Uri.Builder().scheme("content").authority("${context.packageName}.ui_fixtures")
            .appendPath(name).build()
    }
    fun importVideos(vararg names: String) = returnVideos(names.map(::videoUri), waitForReady = true)
    fun returnVideos(uris: List<Uri>, waitForReady: Boolean = false) {
        val data = Intent().apply {
            if (uris.size == 1) this.data = uris.first()
            else if (uris.isNotEmpty()) clipData = ClipData.newRawUri("UI videos", uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        pickerResponse(Activity.RESULT_OK, data)
        click(R.id.selectVideoButton)
        if (waitForReady) { awaitIdle(); awaitEnabled(R.id.renderButton) }
    }
    fun cancelVideoPicker() {
        pickerResponse(Activity.RESULT_CANCELED, null)
        click(R.id.selectVideoButton)
        awaitIdle()
    }
    private fun pickerResponse(code: Int, data: Intent?) {
        // New responses replace earlier responses instead of a first-match stale stub.
        Intents.release()
        Intents.init()
        intending(anyOf(hasAction(Intent.ACTION_PICK), hasAction(Intent.ACTION_OPEN_DOCUMENT)))
            .respondWith(android.app.Instrumentation.ActivityResult(code, data))
    }
    fun startRender() {
        renderer.reset()
        click(R.id.renderButton)
        waitUntil("renderer receives request") { renderer.currentRequest != null }
    }
    internal fun seedCompleted(name: String = "completed.mp4", saved: Boolean = false): CompletedRenderStore.Entry {
        val source = File(context.getDir("ui-fixtures", 0), name).apply { fixture.copyTo(this, overwrite = true) }
        val entry = CompletedRenderStore.publish(context.filesDir, source, "Heartbeat · 3 фрагментов · бит 93% · проверено")
        if (saved) CompletedRenderStore.markSaved(entry)
        return entry.copy(saved = saved)
    }
    fun seedSaved(name: String = "saved.mp4"): File =
        File(File(context.filesDir, "my-edits").apply { mkdirs() }, name).apply { fixture.copyTo(this, overwrite = true) }

    fun assertGalleryCopy(filename: String) {
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        resolver.query(collection, arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.IS_PENDING),
            "${MediaStore.Video.Media.DISPLAY_NAME}=? AND ${MediaStore.Video.Media.OWNER_PACKAGE_NAME}=?",
            arrayOf(filename, context.packageName), null)!!.use { cursor ->
            assertEquals("Exactly one gallery export", 1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(1))
            val uri = android.content.ContentUris.withAppendedId(collection, cursor.getLong(0))
            val bytes = resolver.openInputStream(uri)!!.use { it.readBytes() }
            assertArrayEquals(File(context.filesDir, "my-edits/$filename").readBytes(), bytes)
        }
    }
    private fun cleanGallery() {
        if (Build.VERSION.SDK_INT >= 29) context.contentResolver.delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            "${MediaStore.Video.Media.OWNER_PACKAGE_NAME}=?", arrayOf(context.packageName))
    }
}
