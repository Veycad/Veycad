package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class MontageStyleCatalogTest {
    @Test fun sigma_keeps_reference_director_and_stable_identity() {
        assertEquals("sigma", MontageStyleCatalog.sigma.id)
        assertEquals("Сигма", MontageStyleCatalog.sigma.title)
        assertEquals(EventMatchingDirector.Style.DYNAMIC, MontageStyleCatalog.sigma.directorStyle)
    }
    @Test fun unfinished_or_unknown_styles_cannot_be_restored_for_rendering() {
        assertFalse(MontageStyleCatalog.upcoming.available)
        assertEquals("upcoming", MontageStyleCatalog.upcoming.id)
        assertEquals("Upcoming", MontageStyleCatalog.upcoming.title)
        assertEquals(listOf("heartbeat", "fear_strobe", "duality_loop", "custom_music"),
            MontageStyleCatalog.available.map { it.id })
        assertTrue(MontageStyleCatalog.heartbeat.available)
        assertEquals("heartbeat", MontageStyleCatalog.heartbeat.id)
        assertEquals("Heartbeat", MontageStyleCatalog.heartbeat.title)
        assertEquals(MontageStyleCatalog.Recipe.SIGMA, MontageStyleCatalog.sigma.recipe)
        assertEquals(MontageStyleCatalog.Recipe.HEARTBEAT, MontageStyleCatalog.heartbeat.recipe)
        assertEquals(MontageStyleCatalog.Recipe.FEAR_STROBE, MontageStyleCatalog.fearStrobe.recipe)
        assertEquals(MontageStyleCatalog.Recipe.DUALITY_LOOP, MontageStyleCatalog.dualityLoop.recipe)
        assertEquals("fear_strobe", MontageStyleCatalog.fearStrobe.id)
        assertEquals("FEAR", MontageStyleCatalog.fearStrobe.title)
        assertEquals("duality_loop", MontageStyleCatalog.dualityLoop.id)
        assertEquals("DUALITY", MontageStyleCatalog.dualityLoop.title)
        assertEquals(2, MontageStyleCatalog.dualityLoop.sourceCount)
        assertTrue(MontageStyleCatalog.available.filterNot { it == MontageStyleCatalog.dualityLoop }
            .all { it.sourceCount == 1 })
        for (id in listOf(null, "removed", "upcoming", "sigma")) {
            assertEquals(MontageStyleCatalog.heartbeat, MontageStyleCatalog.restore(id))
        }
        assertEquals(MontageStyleCatalog.heartbeat, MontageStyleCatalog.restore("upcoming_motion"))
        assertEquals(MontageStyleCatalog.heartbeat, MontageStyleCatalog.restore("heartbeat"))
        assertEquals(MontageStyleCatalog.fearStrobe, MontageStyleCatalog.restore("fear_strobe"))
        assertEquals(MontageStyleCatalog.dualityLoop, MontageStyleCatalog.restore("duality_loop"))
        assertTrue(MontageStyleCatalog.isScrollableCatalog)
        assertTrue(MontageStyleCatalog.all.size >= MontageStyleCatalog.minimumStylesForScrollablePicker)
        assertEquals(8, MontageStyleCatalog.minimumStylesForScrollablePicker)
        assertEquals(MontageStyleCatalog.all.size, MontageStyleCatalog.all.map { it.id }.distinct().size)
    }

    @Test fun paused_sigma_stays_visible_but_is_not_available() {
        assertTrue(MontageStyleCatalog.all.contains(MontageStyleCatalog.sigma))
        assertFalse(MontageStyleCatalog.sigma.available)
        assertEquals("Временно недоступно", MontageStyleCatalog.sigma.unavailableLabel)
        assertFalse(MontageStyleCatalog.available.contains(MontageStyleCatalog.sigma))
    }

    @Test(expected = IllegalStateException::class)
    fun shared_render_boundary_refuses_sigma() {
        MontageStyleCatalog.requireRecipeAvailable(MontageStyleCatalog.Recipe.SIGMA)
    }

    @Test fun remaining_products_are_not_locked() {
        for (recipe in listOf(MontageStyleCatalog.Recipe.HEARTBEAT,
            MontageStyleCatalog.Recipe.FEAR_STROBE, MontageStyleCatalog.Recipe.DUALITY_LOOP)) {
            assertTrue(MontageStyleCatalog.isRecipeAvailable(recipe))
            MontageStyleCatalog.requireRecipeAvailable(recipe)
        }
    }
}
