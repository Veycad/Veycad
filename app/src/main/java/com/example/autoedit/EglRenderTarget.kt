package com.veycad.app

import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Caller owns Surface; this target owns its EGL context and display composition backing. */
class EglRenderTarget private constructor(
    suppliedSurface: Surface,
    initialSize: OutputSize,
    private val policy: RenderTargetPolicy
) : RenderTarget, ViewportRenderTarget {
    private val worker = Thread.currentThread()
    private var closed = false
    override var size: OutputSize = initialSize
        private set
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var window = EGL14.EGL_NO_SURFACE
    private var backing: Backing? = null
    private var copyProgram = 0
    private val positions = buffer(floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f))
    private val coordinates = buffer(floatArrayOf(0f,0f,1f,0f,0f,1f,1f,1f))

    init {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "Unable to get EGL display" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "Unable to initialize EGL" }
            val attrs = mutableListOf(EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,
                EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,
                EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT)
            if(policy.recordable) attrs.addAll(listOf(EGL_RECORDABLE_ANDROID,1))
            attrs += EGL14.EGL_NONE
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display,attrs.toIntArray(),0,configs,0,1,count,0) && count[0]>0) {
                "Unable to choose ${policy.name} EGL config"
            }
            context = EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
            check(context != EGL14.EGL_NO_CONTEXT) { "Unable to create EGL context" }
            window = EGL14.eglCreateWindowSurface(display,configs[0],suppliedSurface,intArrayOf(EGL14.EGL_NONE),0)
            check(window != EGL14.EGL_NO_SURFACE) { "Unable to create EGL window surface" }
            makeCurrent()
            if(policy == RenderTargetPolicy.DISPLAY) {
                copyProgram = GlesProgram.create(GlesFrameShaders.POST_VERTEX_SHADER,GlesFrameShaders.OUTPUT_FRAGMENT_SHADER)
                backing = createBacking(size)
            }
        } catch(error: Throwable) {
            runCatching { close() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    override fun makeCurrent() {
        checkWorker()
        check(!closed) { "EGL target is closed" }
        check(EGL14.eglMakeCurrent(display,window,window,context)) { "Unable to make EGL target current" }
    }

    override fun resize(size: OutputSize) {
        makeCurrent()
        if(size == this.size) return
        // Allocate first so a failure leaves the old backing usable. Input textures/context survive.
        val replacement = if(policy == RenderTargetPolicy.DISPLAY) createBacking(size) else null
        backing?.let(::deleteBacking)
        backing = replacement
        this.size = size
    }

    override fun bindOutput() {
        makeCurrent()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,backing?.framebuffer ?: 0)
        GLES20.glViewport(0,0,size.width,size.height)
    }

    /** Bind the physical output for readback/presentation, after rendering at composition size. */
    internal fun preparePresentation() {
        makeCurrent()
        if(policy == RenderTargetPolicy.ENCODER) return
        val actual = actualSurfaceSize()
        val viewport = OutputViewport.fit(size,actual)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glViewport(0,0,actual.width,actual.height)
        GLES20.glClearColor(0f,0f,0f,1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glViewport(viewport.x,viewport.y,viewport.width,viewport.height)
        GLES20.glUseProgram(copyProgram)
        val position = GLES20.glGetAttribLocation(copyProgram,"aPosition")
        val tex = GLES20.glGetAttribLocation(copyProgram,"aTexCoord")
        positions.position(0); coordinates.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,0,positions)
        GLES20.glEnableVertexAttribArray(tex)
        GLES20.glVertexAttribPointer(tex,2,GLES20.GL_FLOAT,false,0,coordinates)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,requireNotNull(backing).texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(copyProgram,"uInput"),0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
    }

    override fun present(outputTimeUs: Long) {
        makeCurrent()
        val timestamp = policy.presentationTimeNs(outputTimeUs)
        preparePresentation()
        if(timestamp != null) check(EGLExt.eglPresentationTimeANDROID(display,window,timestamp)) {
            "Unable to set encoder presentation timestamp"
        }
        check(EGL14.eglSwapBuffers(display,window)) { "Unable to submit GL frame" }
    }

    internal fun actualSurfaceSize(): OutputSize {
        makeCurrent()
        val width = IntArray(1); val height = IntArray(1)
        check(EGL14.eglQuerySurface(display,window,EGL14.EGL_WIDTH,width,0))
        check(EGL14.eglQuerySurface(display,window,EGL14.EGL_HEIGHT,height,0))
        return OutputSize(width[0],height[0])
    }

    override fun close() {
        checkWorker()
        if(closed) return
        val current = context != EGL14.EGL_NO_CONTEXT && runCatching { makeCurrent() }.isSuccess
        closed = true
        if(current) {
            GLES20.glUseProgram(0)
            backing?.let(::deleteBacking)
            GLES20.glDeleteProgram(copyProgram)
        }
        backing = null; copyProgram = 0
        if(display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
            if(window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,window)
            if(context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display,context)
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY; context = EGL14.EGL_NO_CONTEXT; window = EGL14.EGL_NO_SURFACE
        EGL14.eglReleaseThread()
    }

    private fun checkWorker() {
        check(Thread.currentThread() === worker) { "EGL target must be used on its owning GL worker" }
    }
    private data class Backing(val framebuffer: Int,val texture: Int)
    private fun createBacking(size: OutputSize): Backing {
        val textures = IntArray(1); val framebuffers = IntArray(1)
        try {
            GLES20.glGenTextures(1,textures,0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,textures[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,size.width,size.height,0,
                GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null)
            GLES20.glGenFramebuffers(1,framebuffers,0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,framebuffers[0])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,textures[0],0)
            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)==GLES20.GL_FRAMEBUFFER_COMPLETE) {
                "Unable to allocate ${size.width}x${size.height} display composition"
            }
            return Backing(framebuffers[0],textures[0])
        } catch(error: Throwable) {
            GLES20.glDeleteFramebuffers(1,framebuffers,0); GLES20.glDeleteTextures(1,textures,0)
            throw error
        } finally { GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0) }
    }
    private fun deleteBacking(backing: Backing) {
        GLES20.glDeleteFramebuffers(1,intArrayOf(backing.framebuffer),0)
        GLES20.glDeleteTextures(1,intArrayOf(backing.texture),0)
    }
    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        fun forDisplay(surface: Surface,size: OutputSize): RenderTarget = EglRenderTarget(surface,size,RenderTargetPolicy.DISPLAY)
        fun forEncoder(surface: Surface,size: OutputSize): RenderTarget = EglRenderTarget(surface,size,RenderTargetPolicy.ENCODER)
        private fun buffer(values: FloatArray) = ByteBuffer.allocateDirect(values.size*4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
    }
}
