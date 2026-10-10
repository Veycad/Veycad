package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class MontageStylePresentationTest {
    @Test fun active_products_have_distinct_owned_identity_and_grammar() {
        val copies = MontageStyleCatalog.available.map(MontageStylePresentation::forStyle)
        assertEquals(3, copies.map { it.symbol }.distinct().size)
        assertEquals(3, copies.map { it.subtitle }.distinct().size)
        copies.forEach { copy ->
            assertTrue(copy.selectorLabel.contains(copy.title))
            assertFalse(copy.selectorLabel.contains("Σ"))
            assertFalse(copy.selectorLabel.contains("живое появление", ignoreCase = true))
            assertTrue(copy.subtitle.length <= 40)
        }
        assertTrue(MontageStylePresentation.forStyle(MontageStyleCatalog.heartbeat).subtitle.contains("повтор фразы"))
        assertTrue(MontageStylePresentation.forStyle(MontageStyleCatalog.fearStrobe).subtitle.contains("Движение"))
        assertTrue(MontageStylePresentation.forStyle(MontageStyleCatalog.fearStrobe).subtitle.contains("строб-акценты"))
        assertFalse(MontageStylePresentation.forStyle(MontageStyleCatalog.fearStrobe).subtitle.contains("белый финал"))
        assertTrue(MontageStylePresentation.forStyle(MontageStyleCatalog.dualityLoop).subtitle.contains("Две связанные сцены"))
    }

    @Test fun heartbeat_and_fear_require_one_source_at_native_minimum_duration() {
        for (style in listOf(MontageStyleCatalog.heartbeat, MontageStyleCatalog.fearStrobe)) {
            val copy = MontageStylePresentation.forStyle(style)
            assertEquals(1, copy.sourceCount)
            assertEquals(15_000L, copy.minimumSourceDurationMs)
            assertEquals("1 видео · от 15 секунд", copy.sourceHint)
            assertEquals(copy.sourceHint, copy.sourceDisplay(emptyList()))
            assertEquals("Добавить видео", copy.addSourceLabel)
        }
    }

    @Test fun duality_requires_exactly_two_sources_at_its_own_native_minimum() {
        val copy = MontageStylePresentation.forStyle(MontageStyleCatalog.dualityLoop)
        assertEquals(2, copy.sourceCount)
        assertEquals(6_000L, copy.minimumSourceDurationMs)
        assertEquals("2 видео · каждое от 6 секунд", copy.sourceHint)
        assertEquals("Добавить 2 видео", copy.addSourceLabel)
        assertEquals(copy.sourceHint, copy.sourceDisplay(emptyList()))
    }

    @Test fun duality_displays_partial_and_complete_imports_in_order() {
        val copy = MontageStylePresentation.forStyle(MontageStyleCatalog.dualityLoop)
        assertEquals("Выбрано 1 из 2 · A.mp4", copy.sourceDisplay(listOf("A.mp4")))
        assertEquals("1 · A.mp4\n2 · B.mp4", copy.sourceDisplay(listOf("A.mp4", "B.mp4")))
        assertEquals("1 · B.mp4\n2 · A.mp4", copy.sourceDisplay(listOf("B.mp4", "A.mp4")))
        assertEquals("1 · A.mp4\n2 · B.mp4", copy.sourceDisplay(listOf("A.mp4", "B.mp4", "unused.mp4")))
    }

    @Test fun switching_to_single_source_displays_only_the_used_primary_source() {
        for (style in listOf(MontageStyleCatalog.heartbeat, MontageStyleCatalog.fearStrobe)) {
            assertEquals("A.mp4", MontageStylePresentation.forStyle(style)
                .sourceDisplay(listOf("A.mp4", "B.mp4")))
        }
    }

    @Test fun active_music_placeholders_and_loading_copy_belong_to_selected_product() {
        MontageStyleCatalog.available.forEach { style ->
            val copy = MontageStylePresentation.forStyle(style)
            assertTrue(copy.musicPlaceholder.contains(style.title))
            assertTrue(copy.musicLoading.contains(style.title))
            assertFalse(copy.musicPlaceholder.contains("фонк", ignoreCase = true))
            assertFalse(copy.musicLoading.contains("фонк", ignoreCase = true))
        }
    }

    @Test fun paused_sigma_remains_locked_without_promising_available_sources_or_music() {
        val copy = MontageStylePresentation.forStyle(MontageStyleCatalog.sigma)
        assertEquals("🔒", copy.symbol)
        assertEquals(MontageStyleCatalog.sigma.unavailableLabel, copy.subtitle)
        assertEquals(MontageStyleCatalog.sigma.unavailableLabel, copy.sourceHint)
        assertNull(copy.minimumSourceDurationMs)
        assertEquals("♫ Временно недоступно", copy.musicPlaceholder)
        assertFalse(MontageStyleCatalog.sigma.available)
    }

    @Test fun restoring_a_paused_or_unknown_style_uses_heartbeat_presentation() {
        for (id in listOf(null, "sigma", "unknown", "upcoming")) {
            val copy = MontageStylePresentation.forStyle(MontageStyleCatalog.restore(id))
            assertEquals("Heartbeat", copy.title)
            assertEquals("♥", copy.symbol)
            assertEquals("1 видео · от 15 секунд", copy.sourceDisplay(emptyList()))
            assertTrue(copy.musicPlaceholder.contains("Heartbeat"))
        }
    }

    @Test fun upcoming_copy_does_not_invent_a_source_duration_or_phonk_track() {
        val copy = MontageStylePresentation.forStyle(MontageStyleCatalog.upcoming)
        assertNull(copy.minimumSourceDurationMs)
        assertEquals("В разработке", copy.sourceHint)
        assertEquals("♫ В разработке", copy.musicPlaceholder)
        assertFalse(copy.selectorLabel.contains("Σ"))
    }

    @Test fun single_source_restore_keeps_the_ordered_primary_not_the_second_import() {
        val ordered = listOf("source-0-A.mp4", "source-1-B.mp4")
        for (style in listOf(MontageStyleCatalog.heartbeat, MontageStyleCatalog.fearStrobe)) {
            assertEquals(listOf("source-0-A.mp4"), MontageStylePresentation.restoredSources(ordered, style))
            assertEquals(listOf("source-0-B.mp4"), MontageStylePresentation.restoredSources(
                listOf("source-0-B.mp4", "source-1-A.mp4"), style))
        }
    }

    @Test fun duality_restore_keeps_both_imports_in_order_without_inventing_a_missing_second() {
        val ordered = listOf("source-0-A.mp4", "source-1-B.mp4")
        assertEquals(ordered, MontageStylePresentation.restoredSources(ordered, MontageStyleCatalog.dualityLoop))
        assertEquals(ordered.take(1), MontageStylePresentation.restoredSources(
            ordered.take(1), MontageStyleCatalog.dualityLoop))
        assertEquals(ordered, MontageStylePresentation.restoredSources(
            ordered + "source-2-unused.mp4", MontageStyleCatalog.dualityLoop))
    }

    @Test fun empty_restore_stays_empty_for_every_active_product() {
        MontageStyleCatalog.available.forEach { style ->
            assertTrue(MontageStylePresentation.restoredSources(emptyList<String>(), style).isEmpty())
        }
    }

    @Test fun music_request_is_owned_by_its_selected_style() {
        val requests = MontageStylePresentation.MusicRequests()
        val ticket = requests.begin("heartbeat")
        assertTrue(requests.isCurrent(ticket, "heartbeat"))
        assertFalse(requests.isCurrent(ticket, "fear_strobe"))
        assertFalse(requests.isCurrent(ticket, "duality_loop"))
    }

    @Test fun reset_rejects_even_a_late_callback_for_the_same_style() {
        val requests = MontageStylePresentation.MusicRequests()
        val ticket = requests.begin("heartbeat")
        requests.invalidate()
        assertFalse(requests.isCurrent(ticket, "heartbeat"))
        val afterReset = requests.begin("heartbeat")
        assertTrue(requests.isCurrent(afterReset, "heartbeat"))
        assertFalse(requests.isCurrent(ticket, "heartbeat"))
    }

    @Test fun newer_music_request_supersedes_the_previous_ticket() {
        val requests = MontageStylePresentation.MusicRequests()
        val first = requests.begin("heartbeat")
        val second = requests.begin("heartbeat")
        assertFalse(requests.isCurrent(first, "heartbeat"))
        assertTrue(requests.isCurrent(second, "heartbeat"))
        val third = requests.begin("fear_strobe")
        assertFalse(requests.isCurrent(second, "heartbeat"))
        assertTrue(requests.isCurrent(third, "fear_strobe"))
    }
}
