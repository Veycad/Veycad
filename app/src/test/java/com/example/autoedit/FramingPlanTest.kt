package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class FramingPlanTest {
    private val whole = NormalizedRect(0f, 0f, 1f, 1f)
    @Test fun portrait_crop_of_landscape_is_centered() {
        val sample = FramingPlan.sample(SourceGeometry(1920,1080,0,1f), ProjectAspect.PORTRAIT_9_16, FramingSettings(FramingMode.MANUAL))
        assertEquals(.31640625f, sample.foregroundSource.right - sample.foregroundSource.left, 1e-6f)
        assertEquals(.5f, (sample.foregroundSource.right + sample.foregroundSource.left)/2, 0f)
        assertEquals(whole, sample.foregroundDestination)
    }
    @Test fun manual_zoom_and_pan_never_leave_source() {
        for ((w,h) in listOf(1920 to 1080,1080 to 1920,1080 to 1080,1080 to 1350))
        for (aspect in ProjectAspect.entries) for (mode in FramingMode.entries)
        for (rotation in listOf(0,90,180,270)) for (sar in listOf(1f,4f/3f))
        for (zoom in listOf(1f,3f)) for (center in listOf(0f,1f)) {
            val geometry=SourceGeometry(w,h,rotation,sar)
            val settings=FramingSettings(mode,center,1f-center,zoom)
            val sample=FramingPlan.sample(geometry,aspect,settings)
            val rect=sample.foregroundSource
            assertTrue(rect.left >= 0f && rect.top >= 0f && rect.right <= 1f && rect.bottom <= 1f)
            val output=aspect.size(360)
            val display=if(rotation%180==0) w.toFloat()*sar/h else h/(w.toFloat()*sar)
            val destination=sample.foregroundDestination
            assertEquals(display*(rect.right-rect.left)/(rect.bottom-rect.top), output.width.toFloat()/output.height*(destination.right-destination.left)/(destination.bottom-destination.top), 1e-5f)
            if(mode != FramingMode.BLURRED_FIT) {
                val clamped=FramingPlan.clampManual(geometry,aspect,settings)
                assertEquals(clamped, FramingPlan.clampManual(geometry,aspect,clamped))
                assertEquals(clamped.centerX,(rect.left+rect.right)/2,1e-6f)
            }
        }
    }
    @Test fun blur_foreground_fits_entire_frame() {
        val s=FramingPlan.sample(SourceGeometry(1920,1080,0,1f),ProjectAspect.PORTRAIT_9_16,FramingSettings(FramingMode.BLURRED_FIT,0f,1f,3f))
        assertEquals(whole,s.foregroundSource)
        assertEquals(.31640625f,s.foregroundDestination.bottom-s.foregroundDestination.top,1e-6f)
        assertEquals(.06f,s.blurRadiusFraction,0f)
        assertEquals(.02f,FramingPlan.BLUR_SIGMA_FRACTION,0f)
        assertEquals(.5f,FramingPlan.BACKGROUND_RESOLUTION_SCALE,0f)
        assertEquals(.31640625f,s.backgroundSource!!.right-s.backgroundSource.left,1e-6f)
    }
    @Test fun rotation_and_sar_are_applied_once() {
        val a=FramingPlan.sample(SourceGeometry(1440,1080,90,4f/3f),ProjectAspect.PORTRAIT_9_16,FramingSettings(FramingMode.MANUAL))
        assertEquals(whole,a.foregroundSource)
        assertEquals(7f,VideoDisplayOrientation.rendererGeometryRotation(7f,90),0f)
    }
    @Test fun oriented_plane_corners_are_not_rotated_and_fit_bars_are_zero() {
        val plane=FrameAttachments.Plane(5,3,FloatArray(15){(it+1).toFloat()},.8f)
        val identity=FramingPlan.Sample(whole,whole,null,0f)
        assertEquals(plane,FramingPlan.mapPlane(plane,identity))
        val fit=FramingPlan.Sample(whole,NormalizedRect(0f,1f/3f,1f,2f/3f),whole,.02f)
        val mapped=FramingPlan.mapPlane(plane,fit)
        assertArrayEquals(floatArrayOf(0f,0f,0f,0f,0f,6f,7f,8f,9f,10f,0f,0f,0f,0f,0f),mapped.values,0f)
        assertEquals(plane,FramingPlan.mapPlane(plane,fit,false))
        assertEquals(.8f,mapped.confidence,0f)
    }
    @Test fun odd_plane_manual_pan_maps_pixel_centers() {
        val plane=FrameAttachments.Plane(5,3,FloatArray(15){it.toFloat()},1f)
        val s=FramingPlan.Sample(NormalizedRect(.4f,0f,1f,1f),whole,null,0f)
        assertArrayEquals(floatArrayOf(2f,2f,3f,4f,4f,7f,7f,8f,9f,9f,12f,12f,13f,14f,14f),FramingPlan.mapPlane(plane,s).values,0f)
        assertEquals(FramingPlan.mapPlane(plane,s),FramingPlan.mapPlane(plane,s,false))
    }
    @Test fun background_mapping_uses_its_own_crop_and_never_enters_foreground_qa() {
        val plane=FrameAttachments.Plane(5,3,FloatArray(15){(it+1).toFloat()},1f)
        val s=FramingPlan.Sample(whole,NormalizedRect(0f,1f/3f,1f,2f/3f),NormalizedRect(.4f,0f,1f,1f),.02f)
        assertArrayEquals(floatArrayOf(3f,3f,4f,5f,5f,8f,8f,9f,10f,10f,13f,13f,14f,15f,15f),FramingPlan.mapPlane(plane,s,false).values,0f)
        assertEquals(0f,FramingPlan.mapPlane(plane,s).values.first(),0f)
    }
}
