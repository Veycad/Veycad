package com.veycad.app

import android.opengl.EGL14
import android.opengl.GLES20
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlProgramLifecycleTest {
    @Test fun repeatedProgramsAndFailedCompilationReleaseShaderHandles() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1))
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        try {
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE
            ), 0, configs, 0, 1, count, 0) && count[0] > 0)
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, surface, surface, context))
            val shaders = mutableListOf<Int>()
            val driver = object : GlProgramOwnership.Driver by GlesProgram {
                override fun createShader(type: Int): Int = GlesProgram.createShader(type).also { shaders += it }
            }
            val vertex = "attribute vec4 p; void main(){gl_Position=p;}"
            repeat(50) {
                val program = GlProgramOwnership.create(driver, vertex,
                    "precision mediump float; void main(){gl_FragColor=vec4(1.0);}")
                assertTrue(GLES20.glIsProgram(program))
                assertTrue(shaders.all { !GLES20.glIsShader(it) })
                GLES20.glUseProgram(0)
                GLES20.glDeleteProgram(program)
                assertFalse(GLES20.glIsProgram(program))
                assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())
                shaders.clear()
            }
            assertThrows(IllegalStateException::class.java) {
                GlProgramOwnership.create(driver, vertex, "invalid fragment shader")
            }
            assertTrue(shaders.all { !GLES20.glIsShader(it) })
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            EGL14.eglReleaseThread()
        }
    }

    @Test fun compositor_failure_releases_programs_input_textures_and_target() {
        val outputTexture = android.graphics.SurfaceTexture(false).apply { setDefaultBufferSize(64,64) }
        val outputSurface = android.view.Surface(outputTexture)
        val egl = EglRenderTarget.forDisplay(outputSurface,OutputSize(64,64))
        val programs = mutableListOf<Int>()
        val shaders = mutableListOf<Int>()
        var inputs = emptyList<Int>()
        var closed = false
        val target = object : RenderTarget by egl {
            override fun close() {
                // Verify deletion while the original context still exists, before target destroys it.
                assertTrue(programs.all { !GLES20.glIsProgram(it) })
                assertTrue(shaders.all { !GLES20.glIsShader(it) })
                assertEquals(2,inputs.size)
                assertTrue(inputs.all { !GLES20.glIsTexture(it) })
                closed = true
                egl.close()
            }
        }
        val driver = object : GlProgramOwnership.Driver by GlesProgram {
            override fun createShader(type: Int): Int {
                if(inputs.isEmpty()) inputs = GlesFrameCompositorDeviceTest.inputTextureNames()
                return GlesProgram.createShader(type).also { shaders += it }
            }
            override fun createProgram(): Int = GlesProgram.createProgram().also { programs += it }
            override fun link(program: Int) {
                GlesProgram.link(program)
                if(programs.size == 2) error("Injected failure after real second program linking")
            }
        }
        try {
            val graph = GlesFrameCompositorDeviceTest.graph()
            val error = assertThrows(IllegalStateException::class.java) {
                GlesFrameCompositor(target,RenderPassPlanner.plan(graph,RenderPassPlanner.DeviceCapabilities.conservative()),
                    graph.frameAttachments,programDriver=driver)
            }
            assertTrue(error.message!!.contains("Injected failure"))
            assertTrue(closed)
            assertEquals(EGL14.EGL_NO_CONTEXT,EGL14.eglGetCurrentContext())
            // The supplied output remains caller-owned, even on partial compositor initialization.
            assertTrue(outputSurface.isValid)
            egl.close()
        } finally { egl.close(); outputSurface.release(); outputTexture.release() }
    }

    @Test fun draw_failure_close_is_idempotent_and_releases_decoder_surfaces() {
        val texture = android.graphics.SurfaceTexture(false).apply { setDefaultBufferSize(64,64) }
        val surface = android.view.Surface(texture)
        val target = EglRenderTarget.forDisplay(surface,OutputSize(64,64))
        val graph = GlesFrameCompositorDeviceTest.graph(true)
        val programs = mutableListOf<Int>()
        val driver = object : GlProgramOwnership.Driver by GlesProgram {
            override fun createProgram(): Int = GlesProgram.createProgram().also { programs += it }
        }
        var checkedResources = false
        val owningTarget = object : RenderTarget by target, ViewportRenderTarget {
            override fun bindOutput() = (target as ViewportRenderTarget).bindOutput()
            override fun close() {
                assertTrue(programs.all { !GLES20.glIsProgram(it) })
                checkedResources = true
                target.close()
            }
        }
        val compositor = GlesFrameCompositor(owningTarget,
            RenderPassPlanner.plan(graph,RenderPassPlanner.DeviceCapabilities.conservative()),graph.frameAttachments,
            onDraw={ error("Injected draw evidence callback failure") },programDriver=driver)
        val incoming = compositor.decoderSurface(0)
        val outgoing = compositor.decoderSurface(1)
        try {
            GlesFrameCompositorDeviceTest.produceMarkers(incoming,OutputSize(64,64),123_456L)
            compositor.awaitTexture(0) {}
            assertThrows(IllegalStateException::class.java) {
                compositor.drawScheduled(HighQualityFramePlan.build(graph).frames[10],0)
            }
        } finally {
            compositor.close(); compositor.close(); target.close()
            assertTrue(checkedResources)
            assertFalse(incoming.isValid); assertFalse(outgoing.isValid)
            assertTrue(surface.isValid)
            surface.release(); texture.release()
        }
    }

    @Test fun target_partial_initialization_failure_and_foreign_worker_are_rejected() {
        val invalidTexture = android.graphics.SurfaceTexture(false)
        val invalid = android.view.Surface(invalidTexture).apply { release() }
        assertThrows(RuntimeException::class.java) {
            EglRenderTarget.forDisplay(invalid,OutputSize(64,64))
        }
        invalidTexture.release()
        assertEquals(EGL14.EGL_NO_CONTEXT,EGL14.eglGetCurrentContext())
        val texture = android.graphics.SurfaceTexture(false).apply { setDefaultBufferSize(64,64) }
        val surface = android.view.Surface(texture)
        val target = EglRenderTarget.forDisplay(surface,OutputSize(64,64))
        try {
            val error = java.util.concurrent.atomic.AtomicReference<Throwable>()
            val foreign = Thread { try { target.makeCurrent() } catch(e: Throwable) { error.set(e) } }
            foreign.start(); foreign.join(2000)
            assertFalse(foreign.isAlive)
            assertTrue(error.get() is IllegalStateException)
            assertTrue(error.get().message!!.contains("owning GL worker"))
            target.makeCurrent()
        } finally { target.close(); target.close(); surface.release(); texture.release() }
    }
}
