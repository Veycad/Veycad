package com.veycad.app

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES20
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Admission/lifetime checks use the real shared manager and real GLES objects, without codecs. */
@RunWith(AndroidJUnit4::class)
class GlesPreviewMemoryDeviceTest {
    @Test fun failed_offscreen_binding_cannot_publish_complete_default_framebuffer() {
        assertOffscreenAllocationRejected(failBinding = true)
    }

    @Test fun zero_offscreen_framebuffer_name_cannot_publish_complete_default_framebuffer() {
        assertOffscreenAllocationRejected(failBinding = false)
    }

    private fun assertOffscreenAllocationRejected(failBinding: Boolean) {
        val budget = PreviewMemoryBudget()
        var foreignDisposed = false
        assertTrue(budget.reserve("core-page", 32) { foreignDisposed = true })
        budget.pin("core-page")
        Fixture().use { fixture ->
            val beforeTextures = liveTextures()
            val beforeFramebuffers = liveFramebuffers()
            var reachedFailure = false
            val allocator = object : GlesFrameCompositor.FramebufferAllocator {
                override fun generate(names: IntArray) {
                    if (failBinding) GLES20.glGenFramebuffers(1, names, 0)
                    else {
                        assertCompleteDefault()
                        names[0] = 0 // Deterministic failed generation, without a pending error.
                        reachedFailure = true
                    }
                }
                override fun bind(name: Int) {
                    assertCompleteDefault()
                    // Real GL_INVALID_ENUM: leaves the complete default framebuffer bound.
                    GLES20.glBindFramebuffer(GLES20.GL_TEXTURE_2D, name)
                    reachedFailure = true
                }
                private fun assertCompleteDefault() {
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                    assertEquals(GLES20.GL_FRAMEBUFFER_COMPLETE,
                        GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER))
                    assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())
                }
            }
            var unexpected: GlesFrameCompositor? = null
            try {
                val failure = assertThrows(IllegalStateException::class.java) {
                    unexpected = fixture.compositor(budget, allPostPasses(), framebufferAllocator = allocator)
                }
                assertTrue("Must reach the native allocation boundary", reachedFailure)
                assertFalse("Confirmed cleanup needs no retry capability", failure is PreviewAllocationFailure)
                assertTrue(failure.message.orEmpty().contains(if (failBinding) "framebuffer binding" else "framebuffer generation"))
                assertTrue("Preserve the original GL error in diagnostics", failure.message.orEmpty()
                    .contains("GL error=0x${(if (failBinding) GLES20.GL_INVALID_ENUM else GLES20.GL_NO_ERROR).toString(16)}"))
                fixture.egl.makeCurrent()
                assertEquals(beforeTextures, liveTextures())
                assertEquals(beforeFramebuffers, liveFramebuffers())
                assertEquals(32L, budget.usedBytes)
                assertFalse(foreignDisposed)
                assertEquals(1, fixture.targetCloseCalls)
                assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())
            } finally { unexpected?.close() }
        }
        budget.release("core-page")
        assertTrue(foreignDisposed)
    }

    @Test fun denied_constructor_reclaims_only_its_partial_allocations() {
        // Enough for input handles, but not the first program: exercise actual partial allocation.
        val budget = PreviewMemoryBudget(32_768)
        assertTrue(budget.reserve("core-page", 32) { error("Pinned foreign page was disposed") })
        budget.pin("core-page")
        Fixture().use { fixture ->
            val textures = liveTextures()
            val framebuffers = liveFramebuffers()
            val failure = assertThrows(IllegalStateException::class.java) { fixture.compositor(budget) }
            assertFalse("Successful cleanup must preserve ordinary admission failure", failure is PreviewAllocationFailure)
            fixture.egl.makeCurrent()
            assertEquals(textures, liveTextures())
            assertEquals(framebuffers, liveFramebuffers())
            assertEquals(32L, budget.usedBytes)
        }
    }

    @Test fun resident_title_and_all_post_targets_cannot_be_evicted_and_close_preserves_foreign_charge() {
        val limit = 4L * 1024 * 1024
        val budget = PreviewMemoryBudget(limit)
        var foreignDisposed = false
        assertTrue(budget.reserve("core-page", 32) { foreignDisposed = true })
        budget.pin("core-page")
        Fixture().use { fixture ->
            val beforeTextures = liveTextures()
            val beforeFramebuffers = liveFramebuffers()
            val compositor = fixture.compositor(budget, allPostPasses(), title())
            val ownedTextures = liveTextures() - beforeTextures
            val ownedFramebuffers = liveFramebuffers() - beforeFramebuffers
            try {
                assertTrue("Expected five offscreen targets", ownedFramebuffers.size >= 5)
                assertTrue("Expected title, semantic and target textures", ownedTextures.size >= 9)
                assertTrue(budget.usedBytes > 32)
                assertFalse("Resident objects must block eviction", budget.reserve("pressure", limit - 32) {})
                assertTrue(ownedTextures.all(GLES20::glIsTexture))
                assertTrue(ownedFramebuffers.all(GLES20::glIsFramebuffer))
            } finally { compositor.close() }
            fixture.egl.makeCurrent()
            assertTrue(ownedTextures.none(GLES20::glIsTexture))
            assertTrue(ownedFramebuffers.none(GLES20::glIsFramebuffer))
            assertEquals(32L, budget.usedBytes)
            assertFalse(foreignDisposed)
            assertThrows(IllegalStateException::class.java) { compositor.decoderSurface(0) }
        }
        budget.release("core-page")
        assertTrue(foreignDisposed)
    }

    @Test fun released_pressure_allows_native_retry_on_the_same_budget_without_clearing_foreign_owners() {
        val limit = 4L * 1024 * 1024
        val budget = PreviewMemoryBudget(limit)
        val pressure = limit - 32_768
        var pressureReleased = false
        assertTrue(budget.reserve("foreign-pressure", pressure) { pressureReleased = true })
        budget.pin("foreign-pressure")
        try {
            Fixture().use { failed ->
                val textures = liveTextures()
                val failure = assertThrows(IllegalStateException::class.java) { failed.compositor(budget) }
                assertFalse(failure is PreviewAllocationFailure)
                failed.egl.makeCurrent()
                assertEquals(textures, liveTextures())
                assertEquals(pressure, budget.usedBytes)
                assertFalse("Denial must preserve the unrelated live owner", pressureReleased)
            }
            budget.release("foreign-pressure")
            assertTrue(pressureReleased)
            assertEquals(0L, budget.usedBytes)
            Fixture().use { retry ->
                val compositor = retry.compositor(budget, allPostPasses(), title())
                try {
                    GlesFrameCompositorDeviceTest.produceMarkers(compositor.decoderSurface(0), OutputSize(64, 64), 0)
                    compositor.awaitTexture(0) {}
                    compositor.drawScheduled(HighQualityFramePlan.build(GlesFrameCompositorDeviceTest.graph()).frames.first(), 0)
                    assertTrue("A recovered admission must render the actual marker pattern",
                        (retry.pixels.indices step 4).map { retry.pixels[it].toInt() and 255 }.distinct().size > 1)
                    assertTrue(budget.usedBytes in 1..limit)
                    assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())
                } finally { compositor.close() }
                assertEquals(0L, budget.usedBytes)
            }
        } finally { budget.release("foreign-pressure") }
    }

    @Test fun failed_context_cleanup_remains_charged_until_explicit_retry() {
        val budget = PreviewMemoryBudget()
        Fixture().use { fixture ->
            val compositor = fixture.compositor(budget)
            val charged = budget.usedBytes
            assertTrue(charged > 0)
            fixture.failCurrent = true
            assertThrows(IllegalStateException::class.java) { compositor.close() }
            assertTrue("Unconfirmed native disposal must remain charged", budget.usedBytes > 0)
            assertTrue(budget.usedBytes <= charged)
            assertEquals("Target must remain available for retry", 0, fixture.targetCloseCalls)
            fixture.failCurrent = false
            compositor.close()
            assertEquals(0L, budget.usedBytes)
            assertEquals(1, fixture.targetCloseCalls)
            compositor.close()
            assertEquals(1, fixture.targetCloseCalls)
        }
    }

    @Test fun failed_constructor_exposes_only_its_cleanup_retry_and_preserves_foreign_pins() {
        val budget = PreviewMemoryBudget()
        var foreignDisposed = false
        budget.reserve("core-page", 32) { foreignDisposed = true }
        budget.pin("core-page")
        Fixture().use { fixture ->
            val beforeTextures = liveTextures()
            val original = IllegalStateException("Injected shader compile failure")
            val driver = object : GlProgramOwnership.Driver by GlesProgram {
                override fun compile(shader: Int, source: String) {
                    fixture.failCurrent = true
                    throw original
                }
            }
            val failure = assertThrows(PreviewAllocationFailure::class.java) {
                fixture.compositor(budget, driver = driver)
            }
            assertSame(original, failure.cause)
            assertTrue(failure.suppressed.isNotEmpty())
            assertTrue(budget.usedBytes > 32)
            assertEquals(0, fixture.targetCloseCalls)
            var threadFailure: Throwable? = null
            Thread { try { failure.retryClose() } catch (error: Throwable) { threadFailure = error } }
                .apply { start(); join() }
            assertTrue(threadFailure is IllegalStateException)
            assertThrows(IllegalStateException::class.java) { failure.retryClose() }
            assertTrue(budget.usedBytes > 32)
            fixture.failCurrent = false
            failure.retryClose()
            assertEquals(32L, budget.usedBytes)
            assertFalse(foreignDisposed)
            assertEquals(1, fixture.targetCloseCalls)
            fixture.egl.makeCurrent()
            assertEquals(beforeTextures, liveTextures())
            failure.retryClose()
            assertEquals(1, fixture.targetCloseCalls)
        }
        budget.release("core-page")
        assertTrue(foreignDisposed)
    }

    @Test fun same_worker_owners_keep_distinct_keys_and_restore_their_context_for_cleanup() {
        val budget = PreviewMemoryBudget()
        Fixture().use { first ->
            val a = first.compositor(budget)
            val firstCharge = budget.usedBytes
            val firstContext = EGL14.eglGetCurrentContext()
            val firstTextures = liveTextures()
            Fixture().use { second ->
                val b = second.compositor(budget)
                try {
                    assertEquals(firstCharge * 2, budget.usedBytes)
                    assertNotEquals(firstContext, EGL14.eglGetCurrentContext())
                    a.close() // Second owner's context was current on entry.
                    assertEquals(firstCharge, budget.usedBytes)
                    second.egl.makeCurrent()
                    assertTrue(liveTextures().isNotEmpty())
                } finally { b.close() }
                assertEquals(0L, budget.usedBytes)
                first.egl.makeCurrent()
                // The output target belongs to the fixture, and survives compositor cleanup.
                assertTrue((liveTextures() intersect firstTextures).size < firstTextures.size)
            }
        }
    }

    @Test fun foreign_eviction_callback_cannot_redirect_constructor_gl_allocations() {
        val limit = 4L * 1024 * 1024
        val budget = PreviewMemoryBudget(limit)
        Fixture().use { first ->
            val owningContext = EGL14.eglGetCurrentContext()
            Fixture().use { foreign ->
                val foreignTextures = liveTextures()
                val foreignFramebuffers = liveFramebuffers()
                assertTrue(budget.reserve("evictable", limit) { foreign.egl.makeCurrent() })
                first.egl.makeCurrent()
                val compositor = first.compositor(budget, allPostPasses(), title())
                try {
                    assertEquals(owningContext, EGL14.eglGetCurrentContext())
                    foreign.egl.makeCurrent()
                    assertEquals(foreignTextures, liveTextures())
                    assertEquals(foreignFramebuffers, liveFramebuffers())
                } finally { compositor.close() }
                assertEquals(0L, budget.usedBytes)
            }
        }
    }

    @Test fun malformed_title_dimensions_reject_without_retaining_partial_resources() {
        for (size in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.MAX_VALUE, -1f)) {
            val budget = PreviewMemoryBudget()
            Fixture().use { fixture ->
                val before = liveTextures()
                assertThrows(IllegalArgumentException::class.java) {
                    fixture.compositor(budget, title = title().copy(textSizePx = size))
                }
                fixture.egl.makeCurrent()
                assertEquals(before, liveTextures())
                assertEquals(0L, budget.usedBytes)
            }
        }
    }

    @Test fun repeated_cached_draws_reuse_title_plane_and_flow_storage_and_denied_growth_preserves_pixels() {
        val limit = 4L * 1024 * 1024
        val budget = PreviewMemoryBudget(limit)
        Fixture().use { fixture ->
            val compositor = fixture.compositor(budget, allPostPasses(), title())
            try {
                GlesFrameCompositorDeviceTest.produceMarkers(compositor.decoderSurface(0), OutputSize(64, 64), 0)
                compositor.awaitTexture(0) {}
                val base = HighQualityFramePlan.build(GlesFrameCompositorDeviceTest.graph()).frames.first()
                fun semantics(width: Int) = FrameAttachments(0,
                    mask = FrameAttachments.Plane(width, 4, FloatArray(width * 4) { .75f }, 1f),
                    depth = FrameAttachments.Plane(width, 4, FloatArray(width * 4) { .5f }, 1f),
                    flow = FrameAttachments.FlowPlane(width, 4, List(width * 8) { .2f }, 1f))
                val frame = base.copy(attachments = semantics(4))
                compositor.drawScheduled(frame, 0)
                val stableCharge = budget.usedBytes
                val stablePixels = fixture.pixels.copyOf()
                assertTrue(stablePixels.any { it != 0.toByte() })
                repeat(100) {
                    compositor.drawScheduled(frame, 0)
                    assertEquals(stableCharge, budget.usedBytes)
                    assertArrayEquals(stablePixels, fixture.pixels)
                }
                assertTrue(budget.reserve("pressure", limit - stableCharge) {})
                budget.pin("pressure")
                assertThrows(IllegalStateException::class.java) {
                    compositor.drawScheduled(base.copy(attachments = semantics(1024)), 0)
                }
                assertEquals(limit, budget.usedBytes)
                budget.release("pressure")
                compositor.drawScheduled(frame, 0)
                assertArrayEquals(stablePixels, fixture.pixels)
                assertEquals(stableCharge, budget.usedBytes)
                assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())
            } finally { compositor.close() }
            assertEquals(0L, budget.usedBytes)
        }
    }

    @Test fun budgeted_title_and_semantic_post_pixels_match_no_table_legacy_path() {
        val budget = PreviewMemoryBudget()
        fun render(manager: PreviewMemoryBudget?): ByteArray = Fixture().use { fixture ->
            val compositor = fixture.compositor(manager, allPostPasses(), title())
            try {
                GlesFrameCompositorDeviceTest.produceMarkers(compositor.decoderSurface(0), OutputSize(64, 64), 0)
                compositor.awaitTexture(0) {}
                val semantic = FrameAttachments(0,
                    mask = FrameAttachments.Plane(4, 4, FloatArray(16) { .75f }, 1f),
                    depth = FrameAttachments.Plane(4, 4, FloatArray(16) { .5f }, 1f),
                    flow = FrameAttachments.FlowPlane(4, 4, List(32) { .2f }, 1f))
                val frame = HighQualityFramePlan.build(GlesFrameCompositorDeviceTest.graph()).frames.first()
                    .copy(attachments = semantic)
                compositor.drawScheduled(frame, 0)
                fixture.pixels.copyOf()
            } finally { compositor.close() }
        }
        assertArrayEquals(render(null), render(budget))
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun rich_caption_and_legacy_pixels_share_export_layout_and_reuse_charged_rgba_uploads() {
        val text = TextEditProject("owned-source.mp4", 1_000_000, 64, 64,
            layers = listOf(TextLayer("rich", "TITLE", 0, 500_000,
                TextStyle(sizeRatio = .10f, color = android.graphics.Color.GREEN))),
            captions = listOf(CaptionCue("cue", "Речь", 0, 500_000)),
            legacyItems = listOf(TextItem("legacy", "A", FrameSpan(0, 15), .25f, .55f, .4f, .2f,
                android.graphics.Color.RED, TextItem.Appearance.ACCENT, 0)))
        fun render(manager: PreviewMemoryBudget?): Pair<ByteArray, ByteArray> = Fixture().use { fixture ->
            val compositor = fixture.compositor(manager, text = text)
            try {
                GlesFrameCompositorDeviceTest.produceMarkers(compositor.decoderSurface(0), OutputSize(64, 64), 0)
                compositor.awaitTexture(0) {}
                val frame = HighQualityFramePlan.build(GlesFrameCompositorDeviceTest.graph()).frames.first()
                compositor.drawScheduled(frame, 0)
                val active = fixture.pixels.copyOf()
                val charged = manager?.usedBytes
                compositor.drawScheduled(frame.copy(outputTimeUs = 500_000, globalOutputTimeUs = 500_000), 0)
                val ended = fixture.pixels.copyOf()
                assertFalse("End-exclusive text must actually disappear from output pixels", active.contentEquals(ended))
                repeat(12) { compositor.drawScheduled(frame, 0) }
                assertArrayEquals(active, fixture.pixels)
                if (manager != null) assertEquals(charged, manager.usedBytes)
                active to ended
            } finally { compositor.close() }
        }
        val legacy = render(null)
        val budget = PreviewMemoryBudget()
        val preview = render(budget)
        assertArrayEquals(legacy.first, preview.first)
        assertArrayEquals(legacy.second, preview.second)
        assertEquals(0L, budget.usedBytes)
    }

    private class Fixture : AutoCloseable {
        private val texture = SurfaceTexture(false).apply { setDefaultBufferSize(64, 64) }
        private val surface = Surface(texture)
        val egl = EglRenderTarget.forDisplay(surface, OutputSize(64, 64)) as EglRenderTarget
        var failCurrent = false
        var targetCloseCalls = 0
        var pixels = ByteArray(0)
        private val target = object : RenderTarget by egl, ViewportRenderTarget {
            override fun makeCurrent() {
                check(!failCurrent) { "Injected context failure" }
                egl.makeCurrent()
            }
            override fun bindOutput() = egl.bindOutput()
            override fun present(outputTimeUs: Long) {
                pixels = GlesFrameCompositorDeviceTest.readPixels(OutputSize(64, 64))
                egl.present(outputTimeUs)
            }
            // Retain fixture EGL solely so glIsTexture/glIsFramebuffer can check actual disposal.
            override fun close() { targetCloseCalls++ }
        }
        fun compositor(budget: PreviewMemoryBudget?, plan: RenderPassPlanner.Plan? = null,
            title: AuthoredTitleProfile.Spec? = null,
            driver: GlProgramOwnership.Driver = GlesProgram,
            framebufferAllocator: GlesFrameCompositor.FramebufferAllocator = GlesFrameCompositor.GlesFramebufferAllocator,
            text: TextEditProject? = null
        ): GlesFrameCompositor {
            val graph = GlesFrameCompositorDeviceTest.graph()
            return GlesFrameCompositor(target, plan ?: RenderPassPlanner.plan(graph,
                RenderPassPlanner.DeviceCapabilities.conservative()), graph.frameAttachments,
                authoredTitle = title, programDriver = driver, framebufferAllocator = framebufferAllocator,
                previewMemoryBudget = budget, textProject = text)
        }
        override fun close() { egl.close(); surface.release(); texture.release() }
    }

    private fun title() = AuthoredTitleProfile.Spec(listOf("TITLE"), 22f, 0f, false, .5f, .2f, 1f) {
        AuthoredTitleProfile.Sample(0, 1f)
    }

    private fun allPostPasses(): RenderPassPlanner.Plan {
        val kinds = listOf(RenderPassPlanner.PassKind.SOURCE_AND_CHEAP_EFFECTS,
            RenderPassPlanner.PassKind.DIRECTIONAL_BLUR, RenderPassPlanner.PassKind.DEPTH_COMPOSITE,
            RenderPassPlanner.PassKind.GLOW_EXTRACT, RenderPassPlanner.PassKind.GLOW_BLUR,
            RenderPassPlanner.PassKind.FINAL_COMPOSITE, RenderPassPlanner.PassKind.ENCODER_SURFACE)
        return RenderPassPlanner.Plan(RenderPassPlanner.Quality.HIGH, kinds.mapIndexed { index, kind ->
            RenderPassPlanner.Pass("pass-$index", kind, if (index == 0) emptyList() else listOf("pass-${index - 1}"), 1f)
        })
    }

    // Enumerating a bounded, fresh test context avoids production-only testing hooks.
    private fun liveTextures(): Set<Int> = (1..256).filter(GLES20::glIsTexture).toSet()
    private fun liveFramebuffers(): Set<Int> = (1..256).filter(GLES20::glIsFramebuffer).toSet()
}
