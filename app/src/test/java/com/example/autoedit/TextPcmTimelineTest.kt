package com.veycad.app
import org.junit.Assert.assertEquals
import org.junit.Test
class TextPcmTimelineTest {
    @Test fun centerChannelSpeechSurvivesStereoDownmix() {
        assertEquals(0.33333334f,TextPcmMix.sample(6,2,0) { if(it==2) 1f else 0f },0.00001f)
        assertEquals(0.33333334f,TextPcmMix.sample(6,2,1) { if(it==2) 1f else 0f },0.00001f)
        assertEquals(0.4f,TextPcmMix.sample(1,2,1) { 0.4f },0.00001f)
    }
    @Test fun preservesInitialGapAndNeverLoopsShortAudio() {
        val timeline = TextPcmTimeline(16000, 2_000_000)
        assertEquals(8000L, timeline.frameAt(500_000))
        assertEquals(24000L, timeline.remainingAfter(8000))
        assertEquals(0L, timeline.remainingAfter(40000))
    }
}
