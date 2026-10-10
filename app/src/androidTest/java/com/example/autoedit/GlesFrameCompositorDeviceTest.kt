package com.veycad.app

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/** Real GL/Surface/encoder tests. Compilation alone does not establish their result. */
@RunWith(AndroidJUnit4::class)
class GlesFrameCompositorDeviceTest {
    private val composition = OutputSize(720,1280)
    private val actualDisplay = OutputSize(1280,720)
    private val sourcePtsUs = 123_456L

    @Test fun same_scheduled_frame_draws_same_composition_on_display_and_encoder() {
        for(postPipeline in listOf(false,true)) {
            val graph = graph(postPipeline)
            val frame = HighQualityFramePlan.build(graph).frames[10].let {
                it.copy(transform=it.transform.copy(scale=.75f))
            }
            val display = renderDisplay(graph,frame,90)
            val encoded = renderEncoder(graph,frame)
            assertArrayEquals(display.compositionPixels,encoded.compositionPixels)
            assertEquals(display.evidence,encoded.evidence)
            assertEquals(sourcePtsUs,display.evidence.decodedSourceTimeUs)
            assertEquals(frame.outputTimeUs,encoded.encoderPtsUs)
            assertEquals(display.passes,encoded.passes)
            assertTrue(display.passes.contains(RenderPassPlanner.PassKind.SOURCE_AND_CHEAP_EFFECTS))
            if(postPipeline) assertTrue(display.passes.contains(RenderPassPlanner.PassKind.GLOW_EXTRACT))
            val bounds = foregroundBounds(display.compositionPixels,composition)
            // Known .75 authored scale; source fills input and marker quadrants remain distinct.
            if(!postPipeline) assertArrayEquals(intArrayOf(90,160,630,1120),bounds)
            else {
                assertTrue(bounds[0] in 70..90 && bounds[1] in 140..160)
                assertTrue(bounds[2] in 630..650 && bounds[3] in 1120..1140)
            }
            val markers = listOf(180 to 320,540 to 320,180 to 960,540 to 960)
                .map { (x,y) -> rgb(display.compositionPixels,composition,x,y) }
            assertEquals(4,markers.toSet().size)
            assertTrue(markers.any { it[0]>200 && it[1]<40 && it[2]<40 })
            assertTrue(markers.any { it[1]>200 && it[0]<40 && it[2]<40 })
            assertTrue(markers.any { it[2]>200 && it[0]<40 && it[1]<40 })
            // Display frame has black bars on physical Surface, same composition at lower resolution.
            val physical = requireNotNull(display.physicalPixels)
            assertEquals(listOf(0,0,0),rgb(physical,actualDisplay,20,360))
            assertEquals(listOf(0,0,0),rgb(physical,actualDisplay,1260,360))
            val viewport = OutputViewport.fit(composition,actualDisplay)
            for((x,y) in markers) {
                val physicalX = viewport.x+x*viewport.width/composition.width
                val physicalY = viewport.y+y*viewport.height/composition.height
                assertEquals(rgb(display.compositionPixels,composition,x,y),rgb(physical,actualDisplay,physicalX,physicalY))
            }
        }
    }

    @Test fun surface_texture_metadata_rotation_is_not_applied_twice() {
        val graph = graph(false)
        val frame = HighQualityFramePlan.build(graph).frames[10]
        val baseline = renderDisplay(graph,frame,0)
        for(rotation in listOf(90,180,270)) {
            assertArrayEquals(baseline.compositionPixels,renderDisplay(graph,frame,rotation).compositionPixels)
        }
    }

    @Test fun target_resize_preserves_context_input_textures_and_updates_composition_backing() {
        val texture = SurfaceTexture(false).apply { setDefaultBufferSize(actualDisplay.width,actualDisplay.height) }
        val surface = Surface(texture)
        val target = EglRenderTarget.forDisplay(surface,composition) as EglRenderTarget
        val graph = graph(false)
        val compositor = GlesFrameCompositor(target,RenderPassPlanner.plan(graph,RenderPassPlanner.DeviceCapabilities.conservative()),graph.frameAttachments)
        try {
            val decoder = compositor.decoderSurface(0)
            val context = EGL14.eglGetCurrentContext()
            val names = inputTextureNames()
            val resized = OutputSize(360,640)
            target.resize(resized)
            assertEquals(context,EGL14.eglGetCurrentContext())
            assertTrue(decoder.isValid)
            assertTrue(names.all(GLES20::glIsTexture))
            target.bindOutput()
            assertArrayEquals(intArrayOf(0,0,360,640),integers(GLES20.GL_VIEWPORT,4))
            assertNotEquals(0,integers(GLES20.GL_FRAMEBUFFER_BINDING,1)[0])
            val framebuffer = integers(GLES20.GL_FRAMEBUFFER_BINDING,1)[0]
            val maxTextureSize = integers(GLES20.GL_MAX_TEXTURE_SIZE,1)[0]
            assertThrows(IllegalStateException::class.java) { target.resize(OutputSize(maxTextureSize+1,1)) }
            while(GLES20.glGetError()!=GLES20.GL_NO_ERROR) { /* drain expected allocation error */ }
            assertEquals(resized,target.size)
            target.bindOutput()
            assertEquals(framebuffer,integers(GLES20.GL_FRAMEBUFFER_BINDING,1)[0])
            assertTrue(GLES20.glIsFramebuffer(framebuffer))
            GLES20.glClearColor(1f,0f,0f,1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            target.preparePresentation()
            val pixels = readPixels(actualDisplay)
            assertEquals(listOf(255,0,0),rgb(pixels,actualDisplay,640,360))
            assertEquals(listOf(0,0,0),rgb(pixels,actualDisplay,20,360))
        } finally { compositor.close(); surface.release(); texture.release() }
    }

    private data class Result(val compositionPixels: ByteArray,val physicalPixels: ByteArray?,
        val evidence: GlesFrameCompositor.DrawEvidence,val passes: List<RenderPassPlanner.PassKind>,val encoderPtsUs: Long?=null)

    private fun renderDisplay(graph: MontageGraph,frame: HighQualityFramePlan.Frame,rotation: Int): Result {
        val texture = SurfaceTexture(false).apply { setDefaultBufferSize(actualDisplay.width,actualDisplay.height) }
        val surface = Surface(texture)
        try {
            val target = EglRenderTarget.forDisplay(surface,composition) as EglRenderTarget
            return render(graph,frame,rotation,target,true)
        } finally { surface.release(); texture.release() }
    }

    private fun renderEncoder(graph: MontageGraph,frame: HighQualityFramePlan.Frame): Result {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var surface: Surface? = null
        try {
            codec.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,composition.width,composition.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_FRAME_RATE,30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
                setInteger(MediaFormat.KEY_BIT_RATE,4_000_000)
            },null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()
            val result = render(graph,frame,0,EglRenderTarget.forEncoder(surface,composition) as EglRenderTarget,false)
            codec.signalEndOfInputStream()
            val info = MediaCodec.BufferInfo()
            var pts: Long? = null
            val deadline = System.nanoTime()+10_000_000_000L
            while(pts == null && System.nanoTime()<deadline) {
                val index = codec.dequeueOutputBuffer(info,10_000)
                if(index>=0) {
                    if(info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0) pts=info.presentationTimeUs
                    codec.releaseOutputBuffer(index,false)
                }
            }
            assertNotNull("No encoded output frame",pts)
            return result.copy(encoderPtsUs=pts)
        } finally { runCatching { codec.stop() }; codec.release(); surface?.release() }
    }

    private fun render(graph: MontageGraph,frame: HighQualityFramePlan.Frame,rotation: Int,egl: EglRenderTarget,isDisplay: Boolean): Result {
        var composed: ByteArray?=null; var physical: ByteArray?=null; var evidence: GlesFrameCompositor.DrawEvidence?=null
        val passes=mutableListOf<RenderPassPlanner.PassKind>()
        val target=object : RenderTarget by egl, ViewportRenderTarget {
            override fun bindOutput() = egl.bindOutput()
            override fun present(outputTimeUs: Long) {
                assertArrayEquals(intArrayOf(0,0,composition.width,composition.height),integers(GLES20.GL_VIEWPORT,4))
                assertEquals(isDisplay,integers(GLES20.GL_FRAMEBUFFER_BINDING,1)[0]!=0)
                composed=readPixels(composition)
                if(isDisplay) {
                    assertEquals(actualDisplay,egl.actualSurfaceSize())
                    egl.preparePresentation(); physical=readPixels(actualDisplay)
                }
                egl.present(outputTimeUs)
            }
        }
        val compositor=GlesFrameCompositor(target,RenderPassPlanner.plan(graph,RenderPassPlanner.DeviceCapabilities.conservative()),
            graph.frameAttachments,onDraw={ evidence=it },onPassExecuted={ passes+=it })
        try {
            produceMarkers(compositor.decoderSurface(0),composition,sourcePtsUs)
            compositor.awaitTexture(0) {}
            compositor.drawScheduled(frame,rotation)
            assertEquals(GLES20.GL_NO_ERROR,GLES20.glGetError())
            return Result(requireNotNull(composed),physical,requireNotNull(evidence),passes)
        } finally { compositor.close() }
    }

    companion object {
        internal fun graph(glow: Boolean=false)=MontageGraph(1000L,1000L,clips=listOf(
            MontageGraph.Clip("marker",0L,1000L,1000L,MontageGraph.ShotRole.ACTION,
                MontageGraph.Transition.HARD_CUT,MontageGraph.Motion.HOLD,1f,0L)),
            effectGraph=if(glow) GpuEffectGraph(listOf(GpuEffectGraph.Node("glow",GpuEffectGraph.Kind.GLOW,0L,1_000_000L,.1f))) else GpuEffectGraph())
        internal fun integers(key: Int,count: Int)=IntArray(count).also { GLES20.glGetIntegerv(key,it,0) }
        internal fun inputTextureNames(): List<Int> = (0..1).map {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0+it)
            integers(android.opengl.GLES11Ext.GL_TEXTURE_BINDING_EXTERNAL_OES,1)[0]
        }
        internal fun readPixels(size: OutputSize): ByteArray {
            val buffer=ByteBuffer.allocateDirect(size.width*size.height*4)
            GLES20.glReadPixels(0,0,size.width,size.height,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,buffer)
            return ByteArray(buffer.capacity()).also { buffer.position(0); buffer.get(it) }
        }
        private fun rgb(bytes: ByteArray,size: OutputSize,x: Int,y: Int)= (0..2).map { bytes[(y*size.width+x)*4+it].toInt() and 255 }
        private fun foregroundBounds(bytes: ByteArray,size: OutputSize): IntArray {
            var left=size.width; var bottom=size.height; var right=0; var top=0
            for(y in 0 until size.height) for(x in 0 until size.width) {
                if((0..2).any { (bytes[(y*size.width+x)*4+it].toInt() and 255)>8 }) {
                    left=minOf(left,x); bottom=minOf(bottom,y); right=maxOf(right,x+1); top=maxOf(top,y+1)
                }
            }
            return intArrayOf(left,bottom,right,top)
        }
        /** Real EGL producer into the compositor's decoder Surface, with deterministic source PTS. */
        internal fun produceMarkers(surface: Surface,size: OutputSize,ptsUs: Long) {
            val display=EGL14.eglGetCurrentDisplay(); val previousContext=EGL14.eglGetCurrentContext()
            val previousDraw=EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW); val previousRead=EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
            val configs=arrayOfNulls<android.opengl.EGLConfig>(1); val count=IntArray(1)
            check(EGL14.eglChooseConfig(display,intArrayOf(EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,
                EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_NONE),0,configs,0,1,count,0) && count[0]>0)
            val context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
            var window=EGL14.EGL_NO_SURFACE
            try {
                check(context!=EGL14.EGL_NO_CONTEXT)
                window=EGL14.eglCreateWindowSurface(display,configs[0],surface,intArrayOf(EGL14.EGL_NONE),0)
                check(window!=EGL14.EGL_NO_SURFACE)
                check(EGL14.eglMakeCurrent(display,window,window,context))
                GLES20.glViewport(0,0,size.width,size.height)
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                val colors=listOf(floatArrayOf(1f,0f,0f),floatArrayOf(0f,1f,0f),floatArrayOf(0f,0f,1f),floatArrayOf(1f,1f,0f))
                colors.forEachIndexed { i,c ->
                    GLES20.glScissor((i%2)*size.width/2,(i/2)*size.height/2,size.width/2,size.height/2)
                    GLES20.glClearColor(c[0],c[1],c[2],1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                }
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
                check(EGLExt.eglPresentationTimeANDROID(display,window,ptsUs*1000L))
                check(EGL14.eglSwapBuffers(display,window))
            } finally {
                check(EGL14.eglMakeCurrent(display,previousDraw,previousRead,previousContext))
                if(window!=EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,window)
                if(context!=EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display,context)
            }
        }
    }
}
