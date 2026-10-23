package com.veycad.app

import android.graphics.SurfaceTexture
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EglRenderTargetDeviceTest {
    @Test fun failed_unbind_or_context_destroy_keeps_handles_until_native_retry_acknowledges_them() {
        for (failUnbind in listOf(true, false)) {
            val texture = SurfaceTexture(false).apply { setDefaultBufferSize(64, 96) }
            val surface = Surface(texture)
            val native = EglRenderTarget.NativeCleanup
            var unbinds = 0
            var windows = 0
            var contexts = 0
            var terminations = 0
            var threadReleases = 0
            var fail = true
            val calls = object : EglRenderTarget.CleanupDriver by native {
                override fun unbind(display: EGLDisplay): Boolean {
                    unbinds++
                    return if (failUnbind && fail) false else native.unbind(display)
                }
                override fun destroyWindow(display: EGLDisplay, window: EGLSurface): Boolean {
                    windows++
                    return native.destroyWindow(display, window)
                }
                override fun destroyContext(display: EGLDisplay, context: EGLContext): Boolean {
                    contexts++
                    return if (!failUnbind && fail) false else native.destroyContext(display, context)
                }
                override fun terminate(display: EGLDisplay): Boolean {
                    terminations++
                    return native.terminate(display)
                }
                override fun releaseThread(): Boolean {
                    threadReleases++
                    return native.releaseThread()
                }
            }
            val target = EglRenderTarget.forDisplay(surface, OutputSize(64, 96), calls)
            try {
                target.makeCurrent()
                assertThrows(IllegalStateException::class.java) { target.close() }
                assertThrows(IllegalStateException::class.java) { target.makeCurrent() }
                assertEquals("No terminate acknowledgement while context ownership remains", 0, terminations)
                assertEquals(0, threadReleases)
                if (failUnbind) { assertEquals(0, windows); assertEquals(0, contexts) }
                else { assertEquals(1, windows); assertEquals(1, contexts) }
                fail = false
                target.close(); target.close()
                assertEquals(1, windows)
                assertEquals(if (failUnbind) 1 else 2, contexts)
                assertEquals(if (failUnbind) 2 else 1, unbinds)
                assertEquals(1, terminations)
                assertEquals(1, threadReleases)
                assertTrue("The target never releases its caller's Surface", surface.isValid)
                EglRenderTarget.forDisplay(surface, OutputSize(64, 96)).use { replacement -> replacement.makeCurrent() }
            } finally {
                fail = false
                target.close()
                surface.release(); texture.release()
            }
        }
    }
}
