package com.veycad.app

import android.content.ContextWrapper
import android.content.res.Resources
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class BuiltInMusicInstallDeviceTest {
    @Test fun concurrentFirstSelectionPublishesOneCompleteAuthoredTrack() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        check(base.packageName.endsWith(".uitest"))
        val directory = File(base.cacheDir, "music-install-race-${System.nanoTime()}").apply {
            check(mkdirs())
        }
        val expected = base.resources.openRawResource(R.raw.fear_strobe_author).use { it.readBytes() }
        val copied = CountDownLatch(1)
        val release = CountDownLatch(1)
        val opens = AtomicInteger()
        val resources = object : Resources(base.assets, base.resources.displayMetrics, base.resources.configuration) {
            override fun openRawResource(id: Int): InputStream {
                val input = base.resources.openRawResource(id)
                if (opens.incrementAndGet() != 1) return input
                return object : FilterInputStream(input) {
                    override fun close() {
                        super.close()
                        // Hold the first installer after copying, before publishing its .partial file.
                        copied.countDown()
                        check(release.await(10, TimeUnit.SECONDS)) { "First installer was not released" }
                    }
                }
            }
        }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = directory
            override fun getResources(): Resources = resources
        }
        val first = FutureTask { BuiltInMusicCatalog.select(context, MontageStyleCatalog.fearStrobe.id) }
        val second = FutureTask { BuiltInMusicCatalog.select(context, MontageStyleCatalog.fearStrobe.id) }
        val firstThread = Thread(first, "music-install-first")
        val secondThread = Thread(second, "music-install-second")
        try {
            firstThread.start()
            assertTrue("First installer did not finish copying", copied.await(10, TimeUnit.SECONDS))
            secondThread.start()
            fun waitingForPublication(): Boolean = secondThread.state == Thread.State.BLOCKED &&
                secondThread.stackTrace.firstOrNull()?.let {
                    it.className == BuiltInMusicCatalog::class.java.name && it.methodName == "select"
                } == true
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!second.isDone && !waitingForPublication() && System.nanoTime() < deadline) {
                Thread.sleep(1)
            }
            assertTrue("Second installer neither completed nor waited for publication",
                second.isDone || waitingForPublication())
            release.countDown()
            val firstSelection = first.get(10, TimeUnit.SECONDS)
            val secondSelection = second.get(10, TimeUnit.SECONDS)
            assertEquals(firstSelection.file, secondSelection.file)
            assertArrayEquals(expected, firstSelection.file.readBytes())
            assertEquals(firstSelection, BuiltInMusicCatalog.select(context, MontageStyleCatalog.fearStrobe.id))
            assertEquals("The completed cache must be reused", 1, opens.get())
            assertEquals(listOf(firstSelection.file.name), firstSelection.file.parentFile!!.list()!!.toList())
        } finally {
            release.countDown()
            firstThread.join(10_000)
            secondThread.join(10_000)
            check(!firstThread.isAlive && !secondThread.isAlive)
            check(directory.deleteRecursively())
        }
    }
}
