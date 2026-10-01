package com.example.autoedit

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class HeartbeatMontageProfileTest {
    @Test fun production_audio_asset_matches_the_authored_heartbeat_score() {
        val heartbeat = File("src/main/res/raw/heartbeat_author.m4a")
        val sigma = File("src/main/res/raw/leonid_reentry_phonk.m4a")

        assertTrue(HeartbeatMontageProfile.matchesAudio(heartbeat))
        assertFalse(HeartbeatMontageProfile.matchesAudio(sigma))
    }
    @Test fun second_phrase_reprises_the_same_roles_with_echo_without_extending_the_finale() {
        val scenes = HeartbeatMontageProfile.scenes
        assertEquals(26, scenes.size)
        assertEquals(0L, scenes.first().startUs)
        scenes.zipWithNext().forEach { (a,b) -> assertEquals(a.endUs,b.startUs) }
        assertEquals((4..14).toList(), scenes.drop(15).map { it.sourceRole })
        assertTrue(scenes.drop(15).all { it.echo })
        assertTrue(scenes.take(15).none { it.echo })
        assertTrue(scenes.all { it.targetLuma in .05f.. .95f })
        assertEquals(.092f, scenes.first().targetLuma, 0f)
        assertEquals(.693f, scenes.last().targetLuma, 0f)
        assertEquals(HeartbeatMontageProfile.LAST_VISUAL_END_US, scenes.last().endUs)
        assertNull(HeartbeatMontageProfile.sceneAt(19_800_000))
        assertEquals(4, HeartbeatMontageProfile.sceneAt(11_750_000)!!.sourceRole)
        assertEquals(16_667L, scenes[1].boundaryUncertaintyUs)
    }
    @Test fun measured_face_scale_profile_keeps_missing_roles_explicit() {
        assertNull(HeartbeatMontageProfile.targetFaceWidthForRole(0))
        assertNull(HeartbeatMontageProfile.targetFaceWidthForRole(4))
        assertNull(HeartbeatMontageProfile.targetFaceWidthForRole(15))
        (1..14).filterNot { it == 4 }.forEach { role ->
            assertTrue(HeartbeatMontageProfile.targetFaceWidthForRole(role)!! in .10f.. .60f)
        }
        assertEquals(.150f, HeartbeatMontageProfile.targetFaceWidthForRole(11)!!, 0f)
        assertEquals(.521f, HeartbeatMontageProfile.targetFaceWidthForRole(5)!!, 0f)
        assertEquals(.059f, HeartbeatMontageProfile.targetMotionForRole(7)!!, 0f)
        assertEquals(.645f, HeartbeatMontageProfile.targetMotionForRole(10)!!, 0f)
        assertNull(HeartbeatMontageProfile.targetMotionForRole(15))
    }
    @Test fun measured_pulses_are_ordered_bounded_and_half_open() {
        val profile = HeartbeatMontageProfile
        assertEquals(26, profile.pulses.size)
        // Immutable measured reference values, independent of the director/audit fixtures
        // that consume this profile. Retiming any interior pulse must break this contract.
        assertEquals(listOf(100_000L, 650_000L, 1_766_667L, 2_700_000L, 3_033_333L,
            4_800_000L, 6_833_333L, 8_850_000L, 9_733_333L, 10_050_000L, 10_550_000L,
            10_816_667L, 11_033_333L, 11_316_667L, 11_633_333L, 12_816_667L,
            14_850_000L, 16_866_667L, 17_750_000L, 18_066_667L, 18_566_667L,
            18_833_333L, 19_050_000L, 19_333_333L, 19_650_000L, 19_766_667L),
            profile.pulses.map { it.startUs })
        assertEquals(listOf(133_333L, 666_667L, 1_783_333L, 2_716_667L, 3_066_667L,
            4_833_333L, 6_866_667L, 8_883_333L, 9_766_667L, 10_066_667L, 10_566_667L,
            10_833_333L, 11_050_000L, 11_333_333L, 11_650_000L, 12_850_000L,
            14_883_333L, 16_900_000L, 17_783_333L, 18_083_333L, 18_583_333L,
            18_850_000L, 19_066_667L, 19_350_000L, 19_666_667L, 19_800_000L),
            profile.pulses.map { it.endUs })
        assertEquals(listOf(1, 2, 3, 25), profile.pulses.mapIndexedNotNull { index, pulse ->
            index.takeIf { pulse.kind == HeartbeatMontageProfile.PulseKind.WHITE }
        })
        profile.pulses.zipWithNext().forEach { (a,b) -> assertTrue(a.endUs <= b.startUs) }
        profile.pulses.forEach {
            assertTrue(it.endUs <= profile.LAST_VISUAL_END_US)
            assertEquals(it, profile.pulseAt(it.startUs))
            assertEquals(it, profile.pulseAt(it.endUs-1))
            assertNotEquals(it, profile.pulseAt(it.endUs))
        }
        assertNull(profile.pulseAt(-1))
        assertNull(profile.pulseAt(profile.LAST_VISUAL_END_US))
    }
    @Test fun heartbeat_measurements_do_not_alias_sigma() {
        assertNotEquals(ReferenceMontageProfile.ID, HeartbeatMontageProfile.ID)
        assertEquals(18_034L, ReferenceMontageProfile.OUTPUT_DURATION_MS)
        assertEquals(60, HeartbeatMontageProfile.REFERENCE_FPS)
        assertEquals(1_366_667L, HeartbeatMontageProfile.OUTPUT_DURATION_US - HeartbeatMontageProfile.LAST_VISUAL_END_US)
    }
    @Test fun measured_bright_role_repeats_in_both_phrases() {
        val accents = HeartbeatMontageProfile.lightAccents
        assertEquals(2, accents.size)
        assertEquals(listOf(7_283_333L, 15_300_000L), accents.map { it.startUs })
        assertEquals(listOf(9, 9), accents.map { accent ->
            HeartbeatMontageProfile.sceneAt(accent.startUs)!!.sourceRole
        })
        assertTrue(accents.all { it.endUs - it.startUs == 500_000L })
        assertEquals(listOf(.89f, .94f), accents.map { it.targetLuma })
        assertEquals(listOf(3f, 3f), accents.map { it.plateauExposure })
    }
    @Test fun opening_title_sequence_is_contiguous_and_ends_at_the_first_montage_run() {
        val cues = HeartbeatMontageProfile.openingTitles
        assertEquals(
            listOf(
                HeartbeatMontageProfile.OpeningTitle.HEART,
                HeartbeatMontageProfile.OpeningTitle.HEARTBEAT,
                HeartbeatMontageProfile.OpeningTitle.MY,
                HeartbeatMontageProfile.OpeningTitle.MY_HEARTBEAT
            ),
            cues.map { it.title }
        )
        assertEquals(0L, cues.first().startUs)
        cues.zipWithNext().forEach { (left, right) -> assertEquals(left.endUs, right.startUs) }
        assertEquals(HeartbeatMontageProfile.scenes[2].endUs, cues.last().endUs)
        cues.forEach {
            assertEquals(it.title, HeartbeatMontageProfile.openingTitleAt(it.startUs))
            assertNull(HeartbeatMontageProfile.openingTitleAt(it.endUs).takeIf { title -> title == it.title })
        }
        assertNull(HeartbeatMontageProfile.openingTitleAt(cues.last().endUs))
    }
}
