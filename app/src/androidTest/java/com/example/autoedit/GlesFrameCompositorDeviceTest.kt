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

    @Test fun manual_windows_reframe_retained_pixels_in_all_four_aspects_without_new_input() {
        for (aspect in ProjectAspect.entries) {
            val size = aspect.size(144)
            val texture = SurfaceTexture(false).apply { setDefaultBufferSize(size.width, size.height) }
            val surface = Surface(texture)
            val egl = EglRenderTarget.forDisplay(surface, size) as EglRenderTarget
            val pixels = mutableListOf<ByteArray>()
            val evidence = mutableListOf<GlesFrameCompositor.DrawEvidence>()
            val target = object : RenderTarget by egl, ViewportRenderTarget {
                override fun bindOutput() = egl.bindOutput()
                override fun present(outputTimeUs: Long) { pixels += readPixels(size); egl.present(outputTimeUs) }
            }
            val graph = graph()
            val first = manualTable(aspect, size, NormalizedRect(.125f, .0625f, .625f, .5625f))
            val compositor = GlesFrameCompositor(target,
                RenderPassPlanner.plan(graph, RenderPassPlanner.DeviceCapabilities.conservative()), graph.frameAttachments,
                debugFaceRegionProbe = true, manualFraming = first, onDraw = { evidence += it })
            try {
                val input = compositor.decoderSurface(0)
                val context = EGL14.eglGetCurrentContext()
                val names = inputTextureNames()
                produceMarkers(input, size, sourcePtsUs, grid = true)
                compositor.awaitTexture(0) {}
                val frame = HighQualityFramePlan.build(graph).frames[10]
                compositor.drawScheduled(frame, 0)
                // GL producer grid: red is column, green is row counted from bottom, blue identifies input.
                assertGridCorners(pixels.last(), size, listOf(0 to 2, 2 to 2, 0 to 3, 2 to 3), .2f)
                val second = manualTable(aspect, size, NormalizedRect(.625f, .625f, 23f / 24f, 23f / 24f))
                compositor.updateManualFraming(second)
                compositor.drawScheduled(frame, 0)
                assertGridCorners(pixels.last(), size, listOf(2 to 0, 3 to 0, 2 to 1, 3 to 1), .2f)
                assertEquals(context, EGL14.eglGetCurrentContext())
                assertSame(input, compositor.decoderSurface(0))
                assertTrue(names.all(GLES20::glIsTexture))
                assertEquals(listOf(sourcePtsUs, sourcePtsUs), evidence.map { it.decodedSourceTimeUs })
                // Reject atomically: no resize, no borrowing another selection, no partial replacement.
                val incompatible = manualTable(if (aspect == ProjectAspect.SQUARE_1_1) ProjectAspect.FEED_4_5
                    else ProjectAspect.SQUARE_1_1, size, NormalizedRect(0f, 0f, 1f, 1f), sampleFromSettings = true)
                assertThrows(IllegalArgumentException::class.java) { compositor.updateManualFraming(incompatible) }
                val foreign = ManualFramingTable(listOf(SourceId("foreign")),
                    mapOf(SourceId("foreign") to SourceGeometry(size.width, size.height, 0, 1f)), aspect,
                    mapOf(SourceId("foreign") to FramingPlan.Sample(NormalizedRect(.625f,.625f,23f/24f,23f/24f),
                        NormalizedRect(0f,0f,1f,1f),null,0f)))
                assertThrows(IllegalArgumentException::class.java) { compositor.updateManualFraming(foreign) }
                var workerError: Throwable? = null
                Thread { try { compositor.updateManualFraming(first) } catch (error: Throwable) { workerError = error } }
                    .apply { start(); join() }
                assertTrue(workerError is IllegalStateException)
                compositor.drawScheduled(frame, 0)
                assertArrayEquals(pixels[1], pixels[2])
                assertThrows(IllegalArgumentException::class.java) { compositor.drawScheduled(frame.copy(sourceIndex = 1), 0) }
                assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())
            } finally { compositor.close(); surface.release(); texture.release() }
        }
    }

    @Test fun manual_equal_pts_roles_sample_distinct_images_and_their_own_windows() {
        val size = OutputSize(144, 144)
        val texture = SurfaceTexture(false).apply { setDefaultBufferSize(size.width, size.height) }
        val surface = Surface(texture)
        val egl = EglRenderTarget.forDisplay(surface, size) as EglRenderTarget
        var pixels = ByteArray(0)
        var evidence: GlesFrameCompositor.DrawEvidence? = null
        val target = object : RenderTarget by egl, ViewportRenderTarget {
            override fun bindOutput() = egl.bindOutput()
            override fun present(outputTimeUs: Long) { pixels = readPixels(size); egl.present(outputTimeUs) }
        }
        val graph = graph()
        val ids = listOf(SourceId("incoming-selection"), SourceId("outgoing-selection"))
        val whole = NormalizedRect(0f, 0f, 1f, 1f)
        // Outgoing metadata has different rotation/SAR but the same normalized display aspect.
        val table = ManualFramingTable(ids, mapOf(ids[0] to SourceGeometry(144,144,0,1f),
            ids[1] to SourceGeometry(72,144,90,2f)), ProjectAspect.SQUARE_1_1, mapOf(
            ids[0] to FramingPlan.Sample(NormalizedRect(.125f,.0625f,.625f,.5625f),whole,null,0f),
            ids[1] to FramingPlan.Sample(NormalizedRect(.625f,.625f,23f/24f,23f/24f),whole,null,0f)))
        val compositor = GlesFrameCompositor(target,
            RenderPassPlanner.plan(graph,RenderPassPlanner.DeviceCapabilities.conservative()),graph.frameAttachments,
            debugTextureProbe=true, manualFraming=table, onDraw={ evidence=it })
        try {
            produceMarkers(compositor.decoderSurface(0),size,sourcePtsUs,grid=true)
            compositor.awaitTexture(0) {}
            produceMarkers(compositor.decoderSurface(1),size,sourcePtsUs,grid=true,blue=.8f)
            compositor.awaitTexture(1) {}
            val frame=HighQualityFramePlan.build(graph).frames[10]
            compositor.drawOverlap(frame,frame.copy(sourceIndex=1),0,90)
            assertRgbNear(listOf(0,170,51),rgb(pixels,size,size.width/5,size.height/5))
            assertRgbNear(listOf(255,0,204),rgb(pixels,size,size.width*4/5,size.height/5))
            assertEquals(sourcePtsUs,evidence!!.decodedSourceTimeUs)
            assertEquals(sourcePtsUs,evidence!!.decodedSecondarySourceTimeUs)
            assertEquals(1,evidence!!.secondarySourceIndex)
            assertThrows(IllegalArgumentException::class.java) { compositor.drawOverlap(frame,frame.copy(sourceIndex=2),0,0) }
            assertEquals(GLES20.GL_NO_ERROR,GLES20.glGetError())
        } finally { compositor.close(); surface.release(); texture.release() }
    }

    @Test fun manual_face_projection_and_fear_pivot_follow_cached_window_and_source_changes() {
        val size = OutputSize(240,240)
        val texture = SurfaceTexture(false).apply { setDefaultBufferSize(size.width,size.height) }
        val surface = Surface(texture)
        val egl = EglRenderTarget.forDisplay(surface,size) as EglRenderTarget
        var pixels = ByteArray(0)
        val target = object : RenderTarget by egl, ViewportRenderTarget {
            override fun bindOutput() = egl.bindOutput()
            override fun present(outputTimeUs: Long) { pixels=readPixels(size); egl.present(outputTimeUs) }
        }
        val ids=listOf(SourceId("one"),SourceId("two"))
        fun table(rect: NormalizedRect)=ManualFramingTable(ids,ids.associateWith { SourceGeometry(240,240,0,1f) },
            ProjectAspect.SQUARE_1_1,ids.associateWith { FramingPlan.Sample(rect,NormalizedRect(0f,0f,1f,1f),null,0f) })
        val semantics=listOf(0 to (.375f to .3125f),1 to (.65f to .6f)).map { (index,face) ->
            SourceAttachments(index,FrameAttachmentTimeline(listOf(FrameAttachments(sourcePtsUs,
                mask=FrameAttachments.Plane(1,1,listOf(1f),1f),
                faceRegion=FrameAttachments.FaceRegion(face.first,face.second,.018f,.018f,1f)))))
        }
        val graph=graph()
        val compositor=GlesFrameCompositor(target,
            RenderPassPlanner.plan(graph,RenderPassPlanner.DeviceCapabilities.conservative()),graph.frameAttachments,
            debugFaceRegionProbe=true,fearProfile=true,sourceAttachments=semantics,
            manualFraming=table(NormalizedRect(.125f,.0625f,.625f,.5625f)))
        try {
            produceMarkers(compositor.decoderSurface(0),size,sourcePtsUs,solidGray=true,
                redFaceMarkers=listOf(.375f to .3125f,.65f to .6f))
            compositor.awaitTexture(0) {}
            val base=HighQualityFramePlan.build(graph).frames[10]
            val frame=base.copy(transform=base.transform.copy(scale=1.2f))
            fun faceAt(x: Float,y: Float) {
                val color=rgb(pixels,size,(x*size.width).toInt(),((1f-y)*size.height).toInt())
                // Green semantic core must overlap the red marker in the actual source image.
                assertRgbNear(listOf(51,204,0),color)
            }
            compositor.drawScheduled(frame,0); faceAt(.5f,.5f)
            compositor.updateManualFraming(table(NormalizedRect(.25f,.125f,.75f,.625f)))
            compositor.drawScheduled(frame,0); faceAt(.25f,.375f)
            // Same clip index, a new source role: .95 projected Y is pivot-clamped to .8, then scaled to .98.
            compositor.drawScheduled(frame.copy(sourceIndex=1),0); faceAt(.8f,.98f)
            assertEquals(GLES20.GL_NO_ERROR,GLES20.glGetError())
        } finally { compositor.close(); surface.release(); texture.release() }
    }

    @Test fun manual_incoming_mask_tracks_the_same_off_center_source_region() {
        val size=OutputSize(144,144)
        val texture=SurfaceTexture(false).apply { setDefaultBufferSize(size.width,size.height) }
        val surface=Surface(texture)
        val egl=EglRenderTarget.forDisplay(surface,size) as EglRenderTarget
        var pixels=ByteArray(0)
        val target=object : RenderTarget by egl, ViewportRenderTarget {
            override fun bindOutput()=egl.bindOutput()
            override fun present(outputTimeUs: Long) { pixels=readPixels(size); egl.present(outputTimeUs) }
        }
        val graph=graph()
        val mask=FrameAttachments.Plane(16,16,FloatArray(256) { if(it%16<8 && it/16<8) 1f else 0f },1f)
        val compositor=GlesFrameCompositor(target,
            RenderPassPlanner.plan(graph,RenderPassPlanner.DeviceCapabilities.conservative()),graph.frameAttachments,
            sigmaProfile=true,manualFraming=manualTable(ProjectAspect.SQUARE_1_1,size,NormalizedRect(.125f,.0625f,.625f,.5625f)),
            sourceAttachments=listOf(SourceAttachments(0,FrameAttachmentTimeline(listOf(FrameAttachments(sourcePtsUs,mask=mask))))))
        try {
            produceMarkers(compositor.decoderSurface(0),size,sourcePtsUs,solidGray=true)
            compositor.awaitTexture(0) {}
            val frame=HighQualityFramePlan.build(graph).frames[10].copy(globalOutputTimeUs=10_000_000,
                originalOutputTimeUs=10_000_000,transitionEffectsAllowed=false)
            compositor.drawScheduled(frame,0)
            val subject=rgb(pixels,size,size.width/5,size.height*4/5)[0]
            val outside=rgb(pixels,size,size.width*9/10,size.height*4/5)[0]
            assertTrue("Mapped mask should retain its subject: $subject vs $outside",subject>outside+20)
            assertEquals(GLES20.GL_NO_ERROR,GLES20.glGetError())
        } finally { compositor.close(); surface.release(); texture.release() }
    }

    @Test fun supplied_manual_window_has_display_encoder_parity_and_centered_legacy_parity() {
        val graph=graph()
        val frame=HighQualityFramePlan.build(graph).frames[10]
        val offCenter=manualTable(ProjectAspect.PORTRAIT_9_16,composition,NormalizedRect(.125f,.0625f,.625f,.5625f))
        val display=renderDisplay(graph,frame,0,offCenter)
        val encoder=renderEncoder(graph,frame,offCenter)
        assertArrayEquals(display.compositionPixels,encoder.compositionPixels)
        assertEquals(display.passes,encoder.passes)
        assertEquals(display.evidence,encoder.evidence)
        assertEquals(frame.outputTimeUs,encoder.encoderPtsUs)
        val centered=manualTable(ProjectAspect.PORTRAIT_9_16,composition,NormalizedRect(0f,0f,1f,1f))
        assertArrayEquals(renderDisplay(graph,frame,0).compositionPixels,renderDisplay(graph,frame,0,centered).compositionPixels)
        val id=SourceId("square-source")
        val centeredNarrow=ManualFramingTable(listOf(id),mapOf(id to SourceGeometry(720,720,0,1f)),
            ProjectAspect.PORTRAIT_9_16,mapOf(id to FramingPlan.Sample(NormalizedRect(.21875f,0f,.78125f,1f),
                NormalizedRect(0f,0f,1f,1f),null,0f)))
        assertArrayEquals(renderDisplay(graph,frame,0,legacyCrop=SourceFraming.Crop(.5625f,1f)).compositionPixels,
            renderDisplay(graph,frame,0,centeredNarrow).compositionPixels)
    }

    @Test fun manual_post_depth_and_flow_sample_the_transformed_visible_source_region() {
        val size=OutputSize(256,256)
        for(kind in listOf(RenderPassPlanner.PassKind.DIRECTIONAL_BLUR,RenderPassPlanner.PassKind.DEPTH_COMPOSITE)) {
            fun renderField(region: Boolean,high: Boolean,small: Boolean=false,narrow: Boolean=false,encoder: Boolean=false): Result {
                // The source window lies wholly inside the lower-right semantic quadrant.
                // The independent oracle is a uniform field, not another window mapper.
                fun selected(index: Int)=if(region) {
                    if(narrow) index%256>=205 else index%256>=128 && index/256>=128
                } else high
                val attachment=if(kind==RenderPassPlanner.PassKind.DEPTH_COMPOSITE)
                    FrameAttachments(sourcePtsUs,depth=FrameAttachments.Plane(256,256,
                        FloatArray(256*256) { if(selected(it)) 1f else 0f },1f))
                else FrameAttachments(sourcePtsUs,flow=FrameAttachments.FlowPlane(256,256,
                    List(256*256*2) { if(selected(it/2)) { if(it%2==0) 1f else 0f }
                        else { if(it%2==0) 0f else 1f } },1f))
                val plan=RenderPassPlanner.Plan(RenderPassPlanner.Quality.HIGH,listOf(
                    RenderPassPlanner.Pass("source",RenderPassPlanner.PassKind.SOURCE_AND_CHEAP_EFFECTS,emptyList(),1f),
                    RenderPassPlanner.Pass("field",kind,listOf("source"),1f),
                    RenderPassPlanner.Pass("output",RenderPassPlanner.PassKind.ENCODER_SURFACE,listOf("field"),1f)))
                val graph=graph()
                val base=HighQualityFramePlan.build(graph).frames[10]
                val frame=base.copy(transform=base.transform.copy(scale=if(small) .6f else 1.2f,
                    rotationDegrees=if(small) 0f else 17f,translateX=if(small) 0f else .03f,
                    translateY=if(small) 0f else -.04f),transitionProgress=.5f,
                    transitionIn=if(kind==RenderPassPlanner.PassKind.DIRECTIONAL_BLUR) MontageGraph.Transition.WHIP
                        else MontageGraph.Transition.OCCLUSION)
                fun draw(egl: EglRenderTarget): Result {
                    var pixels=ByteArray(0)
                    var evidence: GlesFrameCompositor.DrawEvidence?=null
                    val passes=mutableListOf<RenderPassPlanner.PassKind>()
                    val target=object : RenderTarget by egl, ViewportRenderTarget {
                        override fun bindOutput()=egl.bindOutput()
                        override fun present(outputTimeUs: Long) { pixels=readPixels(size); egl.present(outputTimeUs) }
                    }
                    val compositor=GlesFrameCompositor(target,plan,graph.frameAttachments,debugFaceRegionProbe=true,
                        manualFraming=manualTable(ProjectAspect.SQUARE_1_1,size,NormalizedRect(.625f,.625f,23f/24f,23f/24f)),
                        sourceAttachments=listOf(SourceAttachments(0,FrameAttachmentTimeline(listOf(attachment)))),
                        onDraw={ evidence=it },onPassExecuted={ passes+=it })
                    try {
                        produceMarkers(compositor.decoderSurface(0),size,sourcePtsUs,grid=true,denseGrid=true)
                        compositor.awaitTexture(0) {}
                        produceMarkers(compositor.decoderSurface(1),size,sourcePtsUs,grid=true,denseGrid=true)
                        compositor.awaitTexture(1) {}
                        compositor.drawOverlap(frame,frame,0,0)
                        assertTrue(compositor.executedPasses().containsKey(kind))
                        assertEquals(GLES20.GL_NO_ERROR,GLES20.glGetError())
                        for(scale in listOf(0f,Float.NaN)) assertThrows(IllegalArgumentException::class.java) {
                            compositor.drawOverlap(frame.copy(transform=frame.transform.copy(scale=scale)),frame,0,0)
                        }
                        return Result(pixels,null,requireNotNull(evidence),passes)
                    } finally { compositor.close() }
                }
                if(encoder) return renderEncoder(graph,frame,renderSize=size,renderOverride=::draw)
                val texture=SurfaceTexture(false).apply { setDefaultBufferSize(size.width,size.height) }
                val surface=Surface(texture)
                try { return draw(EglRenderTarget.forDisplay(surface,size) as EglRenderTarget) }
                finally { surface.release(); texture.release() }
            }
            val display=renderField(region=true,high=false)
            val encoded=renderField(region=true,high=false,encoder=true)
            assertArrayEquals(display.compositionPixels,encoded.compositionPixels)
            assertEquals(display.evidence,encoded.evidence)
            assertEquals(display.passes,encoded.passes)
            assertEquals(display.evidence.frame.outputTimeUs,encoded.encoderPtsUs)
            val region=display.compositionPixels
            val uniform=renderField(region=false,high=true).compositionPixels
            val opposite=renderField(region=false,high=false).compositionPixels
            assertArrayEquals("$kind must sample the visible lower-right source field after the exact authored transform",uniform,region)
            assertTrue("$kind fixture must change actual pixels when the sampled field changes",
                uniform.indices.count { kotlin.math.abs((uniform[it].toInt() and 255)-(opposite[it].toInt() and 255))>3 }>20)
            val narrow=renderField(region=true,high=false,narrow=true).compositionPixels
            // Hand-derived inverse of scale 1.2, rotation 17 degrees, translation (.03,-.04):
            // these high/low patches map respectively to source X > .81 / < .79.
            // Omitting the inverse puts them on the opposite side of the .801 source boundary.
            for((xRange,yRange,high) in listOf(Triple(129..133,200..209,true),Triple(141..151,46..56,false))) {
                val expected=if(high) uniform else opposite
                val wrong=if(high) opposite else uniform
                var differing=0
                for(x in xRange) for(y in yRange) {
                    assertEquals(rgb(expected,size,x,y),rgb(narrow,size,x,y))
                    if(rgb(expected,size,x,y)!=rgb(wrong,size,x,y)) differing++
                }
                assertTrue("$kind transform patch must distinguish the wrong field",differing>5)
            }
            val outsideHigh=renderField(region=false,high=true,small=true).compositionPixels
            val outsideLow=renderField(region=false,high=false,small=true).compositionPixels
            // With .6 scale content ends at x=.8. Outside it, edge semantics must have no influence.
            for(y in 70..180 step 10) assertEquals(rgb(outsideHigh,size,206,y),rgb(outsideLow,size,206,y))
        }
    }

    private fun manualTable(aspect: ProjectAspect,size: OutputSize,rect: NormalizedRect,
        sampleFromSettings: Boolean=false): ManualFramingTable {
        val id=SourceId("selection")
        val geometry=SourceGeometry(size.width,size.height,0,1f)
        val sample=if(sampleFromSettings) FramingPlan.sample(geometry,aspect,FramingSettings(FramingMode.MANUAL))
            else FramingPlan.Sample(rect,NormalizedRect(0f,0f,1f,1f),null,0f)
        return ManualFramingTable(listOf(id),mapOf(id to geometry),aspect,mapOf(id to sample))
    }

    private fun assertGridCorners(pixels: ByteArray,size: OutputSize,cells: List<Pair<Int,Int>>,blue: Float) {
        val points=listOf(1 to 1,4 to 1,1 to 4,4 to 4)
        points.zip(cells).forEach { (point,cell) ->
            assertRgbNear(listOf(cell.first*85,cell.second*85,(blue*255).toInt()),
                rgb(pixels,size,point.first*size.width/5,point.second*size.height/5))
        }
    }
    private fun assertRgbNear(expected: List<Int>,actual: List<Int>) {
        expected.zip(actual).forEach { (want,got) -> assertTrue("Expected $expected, got $actual",kotlin.math.abs(want-got)<=3) }
    }

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

    @Test fun held_and_outgoing_texture_provenance_follows_the_actual_sampler_including_debug_override() {
        val size = OutputSize(64,64)
        val graph = graph().let { original ->
            val first = original.clips.single().copy(sourceIndex=19, sourceEndMs=400, outputDurationMs=400)
            original.copy(outputDurationMs=800, clips=listOf(first,first.copy(id="incoming", sourceIndex=7,
                transitionIn=MontageGraph.Transition.WHIP, beatAnchorMs=400)))
        }
        val frames = HighQualityFramePlan.build(graph).frames
        val outgoing = frames.last { it.clipIndex==0 }
        val incoming = frames.first { it.clipIndex==1 }
        for (incomingOnBoth in listOf(false,true)) {
            val outputTexture = SurfaceTexture(false).apply { setDefaultBufferSize(size.width,size.height) }
            val outputSurface = Surface(outputTexture)
            val collector = VeykadRenderInspector.Collector(graph,2)
            val draws = mutableListOf<GlesFrameCompositor.DrawEvidence>()
            val semantics = listOf(19 to .2f, 7 to .9f).map { (source, confidence) ->
                SourceAttachments(source, FrameAttachmentTimeline(listOf(33_333L, 266_667L).map { pts ->
                    FrameAttachments(pts, mask = FrameAttachments.Plane(1, 1, listOf(confidence), confidence))
                }))
            }
            val compositor = GlesFrameCompositor(EglRenderTarget.forDisplay(outputSurface,size),
                RenderPassPlanner.plan(graph,RenderPassPlanner.DeviceCapabilities.conservative()),
                graph.frameAttachments, debugTextureProbe=true, debugProbeIncomingOnBothUnits=incomingOnBoth,
                sourceCrops=List(20) { SourceFraming.Crop(1f,1f) }, sourceAttachments=semantics,
                onDraw={ draws += it; collector.record(it) })
            try {
                produceMarkers(compositor.decoderSurface(0),size,266_667L)
                compositor.awaitTexture(0) {}
                compositor.drawScheduled(outgoing.copy(outputTimeUs=incoming.outputTimeUs,
                    transitionIn=MontageGraph.Transition.HARD_CUT, transitionProgress=-1f),0)
                val held = collector.evidence().single()
                assertEquals(19,held.sourceIndex)
                assertEquals(0,held.clipIndex)
                assertEquals(incoming.outputTimeUs,held.outputTimeUs)
                assertEquals(266_667L,held.decodedSourceTimeUs)
                assertEquals(.2f, draws.single().frame.attachments!!.mask!!.confidence, 0f)
                assertEquals(266_667L, draws.single().frame.attachments!!.sourceTimeUs)
                produceMarkers(compositor.decoderSurface(1),size,266_667L)
                compositor.awaitTexture(1) {}
                produceMarkers(compositor.decoderSurface(0),size,33_333L)
                compositor.awaitTexture(0) {}
                compositor.drawOverlap(incoming,outgoing,0,0)
                val overlap = collector.evidence().last()
                assertEquals(7,overlap.sourceIndex)
                assertEquals(1,overlap.clipIndex)
                assertEquals(33_333L,overlap.decodedSourceTimeUs)
                assertEquals(.9f, draws.last().frame.attachments!!.mask!!.confidence, 0f)
                assertEquals(33_333L, draws.last().frame.attachments!!.sourceTimeUs)
                assertEquals(if(incomingOnBoth) 7 else 19,overlap.secondarySourceIndex)
                assertEquals(if(incomingOnBoth) 33_333L else 266_667L,overlap.decodedSecondarySourceTimeUs)
                // Equal texture PTS still carries the two explicitly bound source identities.
                produceMarkers(compositor.decoderSurface(0), size, 266_667L)
                compositor.awaitTexture(0) {}
                compositor.drawTemporalLayer(incoming.copy(sourceTimeUs=266_667,
                    layer=LayerCompositorModel.Sample(opacity=.6f, kind=MontageGraph.OverlayKind.DOUBLE_EXPOSURE)),
                    outgoing.copy(sourceTimeUs=266_667), 0, 0)
                val temporal = draws.last()
                assertEquals(266_667L, temporal.decodedSourceTimeUs)
                assertEquals(266_667L, temporal.decodedSecondarySourceTimeUs)
                assertEquals(if (incomingOnBoth) 7 else 19, temporal.secondarySourceIndex)
                assertEquals(GLES20.GL_NO_ERROR,GLES20.glGetError())
            } finally { compositor.close(); outputSurface.release(); outputTexture.release() }
        }
    }

    @Test fun rebased_frame_binds_project_scene_and_disabled_entrance_clocks_to_gpu() {
        val size = OutputSize(64, 64)
        val outputTexture = SurfaceTexture(false).apply { setDefaultBufferSize(size.width, size.height) }
        val outputSurface = Surface(outputTexture)
        val egl = EglRenderTarget.forDisplay(outputSurface, size) as EglRenderTarget
        val uniforms = mutableListOf<List<Float>>()
        val presentations = mutableListOf<Long>()
        val target = object : RenderTarget by egl, ViewportRenderTarget {
            override fun bindOutput() = egl.bindOutput()
            override fun present(outputTimeUs: Long) {
                val program = integers(GLES20.GL_CURRENT_PROGRAM, 1)[0]
                uniforms += listOf("uOutputTime", "uOriginalTime", "uEntranceTravel", "uOpeningTitle").map { name ->
                    val location = GLES20.glGetUniformLocation(program, name)
                    assertTrue("Missing uniform $name", location >= 0)
                    FloatArray(1).also { GLES20.glGetUniformfv(program, location, it, 0) }[0]
                }
                assertTrue(readPixels(size).toList().chunked(4).any { pixel ->
                    pixel.take(3).any { (it.toInt() and 255) > 8 }
                })
                presentations += outputTimeUs
                egl.present(outputTimeUs)
            }
        }
        val graph = graph(false)
        val title = AuthoredTitleProfile.Spec(listOf("CUE"), 22f, 0f, false, .5f, .2f, 1f) { time ->
            if (time == 2_500_000L) AuthoredTitleProfile.Sample(0, 1f) else null
        }
        val compositor = GlesFrameCompositor(target,
            RenderPassPlanner.plan(graph, RenderPassPlanner.DeviceCapabilities.conservative()),
            graph.frameAttachments, authoredTitle = title, sigmaProfile = true)
        try {
            produceMarkers(compositor.decoderSurface(0), size, sourcePtsUs)
            compositor.awaitTexture(0) {}
            val frame = HighQualityFramePlan.build(graph).frames.first().copy(outputTimeUs = 0,
                globalOutputTimeUs = 2_500_000, originalOutputTimeUs = 1_000_000)
            compositor.drawScheduled(frame, 0)
            compositor.drawScheduled(frame.copy(outputTimeUs = 33_333, transitionEffectsAllowed = false), 0)
            compositor.drawScheduled(frame.copy(outputTimeUs = 66_667, globalOutputTimeUs = 3_500_000,
                originalOutputTimeUs = 2_000_000), 0)
            assertEquals(listOf(0L, 33_333L, 66_667L), presentations)
            assertEquals(2.5f, uniforms[0][0], .00001f)
            assertEquals(1f, uniforms[0][1], .00001f)
            assertEquals(.525f, uniforms[0][2], .00001f)
            assertEquals(1f, uniforms[0][3], .00001f)
            assertEquals(2.5f, uniforms[1][0], .00001f)
            assertEquals(1f, uniforms[1][1], .00001f)
            assertEquals(0f, uniforms[1][2], .00001f)
            assertEquals(1f, uniforms[1][3], .00001f)
            // Re-enabling must use the new original scene clock, not latch the disabled zero.
            assertEquals(3.5f, uniforms[2][0], .00001f)
            assertEquals(2f, uniforms[2][1], .00001f)
            assertEquals(.27f, uniforms[2][2], .00001f)
            assertEquals(0f, uniforms[2][3], .00001f)
            assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())
        } finally { compositor.close(); outputSurface.release(); outputTexture.release() }
    }

    private data class Result(val compositionPixels: ByteArray,val physicalPixels: ByteArray?,
        val evidence: GlesFrameCompositor.DrawEvidence,val passes: List<RenderPassPlanner.PassKind>,val encoderPtsUs: Long?=null)

    private fun renderDisplay(graph: MontageGraph,frame: HighQualityFramePlan.Frame,rotation: Int,
        manual: ManualFramingTable?=null,legacyCrop: SourceFraming.Crop=SourceFraming.Crop(1f,1f)): Result {
        val texture = SurfaceTexture(false).apply { setDefaultBufferSize(actualDisplay.width,actualDisplay.height) }
        val surface = Surface(texture)
        try {
            val target = EglRenderTarget.forDisplay(surface,composition) as EglRenderTarget
            return render(graph,frame,rotation,target,true,manual,legacyCrop)
        } finally { surface.release(); texture.release() }
    }

    private fun renderEncoder(graph: MontageGraph,frame: HighQualityFramePlan.Frame,manual: ManualFramingTable?=null,
        renderSize: OutputSize=composition,renderOverride: ((EglRenderTarget)->Result)?=null): Result {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var surface: Surface? = null
        try {
            codec.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,renderSize.width,renderSize.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_FRAME_RATE,30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
                setInteger(MediaFormat.KEY_BIT_RATE,4_000_000)
            },null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()
            val target=EglRenderTarget.forEncoder(surface,renderSize) as EglRenderTarget
            val result = renderOverride?.invoke(target) ?: render(graph,frame,0,target,false,manual)
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

    private fun render(graph: MontageGraph,frame: HighQualityFramePlan.Frame,rotation: Int,egl: EglRenderTarget,isDisplay: Boolean,
        manual: ManualFramingTable?=null,legacyCrop: SourceFraming.Crop=SourceFraming.Crop(1f,1f)): Result {
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
            graph.frameAttachments,onDraw={ evidence=it },onPassExecuted={ passes+=it },manualFraming=manual,
            sourceCrops=listOf(legacyCrop))
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
        internal fun produceMarkers(surface: Surface,size: OutputSize,ptsUs: Long,
            grid: Boolean=false,blue: Float=.2f,solidGray: Boolean=false,denseGrid: Boolean=false,
            redFaceMarkers: List<Pair<Float,Float>> = emptyList()) {
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
                val cells=if(denseGrid) 32 else if(grid) 4 else 2
                val colors=if(solidGray) List(4) { floatArrayOf(.5f,.5f,.5f) }
                    else if(denseGrid) List(32*32) { floatArrayOf((it%32%2).toFloat(),(it/32%2).toFloat(),blue) }
                    else if(grid) List(16) { floatArrayOf((it%4)/3f,(it/4)/3f,blue) }
                    else listOf(floatArrayOf(1f,0f,0f),floatArrayOf(0f,1f,0f),floatArrayOf(0f,0f,1f),floatArrayOf(1f,1f,0f))
                colors.forEachIndexed { i,c ->
                    GLES20.glScissor((i%cells)*size.width/cells,(i/cells)*size.height/cells,size.width/cells,size.height/cells)
                    GLES20.glClearColor(c[0],c[1],c[2],1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                }
                redFaceMarkers.forEach { (x,y) ->
                    GLES20.glScissor((x*size.width).toInt()-3,((1f-y)*size.height).toInt()-3,6,6)
                    GLES20.glClearColor(1f,0f,0f,1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
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
