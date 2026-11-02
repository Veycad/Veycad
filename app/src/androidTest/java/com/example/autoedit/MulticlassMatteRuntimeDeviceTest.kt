package com.veycad.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Native SDK execution/isolation only; no mask-accuracy or artistic acceptance claim. */
@RunWith(AndroidJUnit4::class)
class MulticlassMatteRuntimeDeviceTest {
    @Test fun isolatedMatteExecutesAfterFaceSdkAndRejectsARevokedLease() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".uitest"))
        val blank = Bitmap.createBitmap(480, 320, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        try {
            LocalSemanticFrameAnalyzer().use { analyzer ->
                val emptyMask = FrameAttachments.Plane(32, 32, FloatArray(1024), 1f)
                val result = analyzer.analyze(blank, emptyMask, runPose = false)
                assertTrue("Bundled face SDK must execute in the main process", result.faceInferenceSucceeded)
                assertEquals(0, result.detectedFaceCount)
            }
        } finally { blank.recycle() }
        val source = File.createTempFile("matte-runtime-", ".mp4", context.cacheDir)
        val lease = File(source.parentFile, "${source.name}.lease")
        try {
            lease.writeText("active")
            context.resources.openRawResource(R.raw.style_preview_heartbeat).use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            val targets = MulticlassMatteClient.decodedTargets(source, longArrayOf(0, 500_000, 1_000_000))
            assertEquals(3, targets.size)
            val masks = MulticlassMatteClient.analyzeTargets(context, source, targets, lease)
            assertEquals(targets.toSet(), masks.keys)
            for (mask in masks.values) {
                assertTrue(mask.width > 0 && mask.height > 0)
                assertEquals(mask.width * mask.height, mask.size)
                assertTrue(mask.values.all { it.isFinite() && it in 0f..1f })
            }
            val cache = File(context.cacheDir, "opening-matte.bin")
            val completed = cache.readBytes()
            assertFalse(File(cache.path + ".partial").exists())
            assertTrue(lease.delete())
            val cancelled = assertThrows(IllegalStateException::class.java) {
                MulticlassMatteClient.analyzeTargets(context, source, targets, lease)
            }
            assertEquals(IllegalStateException::class.java, cancelled.javaClass)
            assertTrue(cancelled.message.orEmpty().contains("Монтаж отменён"))
            assertArrayEquals("Revoked work must not replace completed masks", completed, cache.readBytes())
        } finally {
            lease.delete()
            source.delete()
        }
    }
}
