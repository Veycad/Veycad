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
            shaders.clear()
            val programs = mutableListOf<Int>()
            val slots = SourceDecoderSlots { index ->
                val program = GlProgramOwnership.create(driver, vertex,
                    if (index == 19) "invalid fragment shader" else
                        "precision mediump float; void main(){gl_FragColor=vec4(1.0);}")
                programs += program
                AutoCloseable { GLES20.glUseProgram(0); GLES20.glDeleteProgram(program) }
            }
            slots.acquire(7, DecoderRole.INCOMING)
            slots.acquire(3, DecoderRole.OUTGOING)
            try {
                assertThrows(IllegalStateException::class.java) { slots.acquire(19, DecoderRole.INCOMING) }
                assertFalse("Replaced role releases its native program before failing open", GLES20.glIsProgram(programs[0]))
                assertTrue("Unchanged role stays owned until cleanup", GLES20.glIsProgram(programs[1]))
                assertTrue(shaders.all { !GLES20.glIsShader(it) })
            } finally { slots.releaseAll() }
            assertTrue(programs.all { !GLES20.glIsProgram(it) })
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            EGL14.eglReleaseThread()
        }
    }
}
