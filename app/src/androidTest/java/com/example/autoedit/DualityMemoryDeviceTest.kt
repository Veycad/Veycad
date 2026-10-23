package com.veycad.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DualityMemoryDeviceTest {
    @Test(timeout = 300_000) fun twoSixtySecondSourcesKeepObservationsWithoutRetainingRenderPlanes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".uitest"))
        val folder = File(context.cacheDir, "duality-memory-${System.nanoTime()}").apply { check(mkdirs()) }
        val profile = SourceAnalysisProfile.requiredFor(MontageStyleCatalog.Recipe.DUALITY_LOOP)
        assertFalse(profile.retainsAttachments)
        try {
            val sources = listOf(
                SyntheticVideo.create(File(folder, "first.mp4"), 60_000),
                SyntheticVideo.create(File(folder, "second.mp4"), 60_000, lumaPhase = 50))
            val caches = sources.map { source -> File(context.cacheDir,
                "full-video-analysis/${MediaFrameAnalysisCache.key(source, 250_000L, profile).fileName}") }
            assertEquals("Sources must have distinct content/cache keys", 2, caches.distinct().size)
            caches.forEach { it.delete() }
            val cold = sources.map { source -> MediaFrameVisualAnalyzer.analyze(context, source, profile) }
            assertTrue("Cold analyses must publish their own cache entries", caches.all(File::isFile))
            assertEquals(480, cold.sumOf { it.observations.size })
            cold.forEach { analysis ->
                assertEquals(60_000_000L, analysis.durationUs)
                assertEquals(240, analysis.semanticFrames)
                assertTrue("Cold analysis must actually infer masks", analysis.maskFrames > 0)
                assertTrue("Real segmentation must run on the cold path", analysis.semanticModelSuccesses >= 240)
                assertTrue(analysis.attachments.frames.isEmpty())
                assertTrue(analysis.attachments.maskRefinements.isEmpty())
            }
            // Both cold analyses remain live, matching automatic DUALITY's two-source lifetime.
            sources.zip(cold).forEach { (source, original) ->
                val cached = MediaFrameVisualAnalyzer.analyze(context, source, profile)
                assertEquals(original.observations, cached.observations)
                assertTrue(cached.attachments.frames.isEmpty())
            }
        } finally {
            folder.deleteRecursively()
        }
    }
}
