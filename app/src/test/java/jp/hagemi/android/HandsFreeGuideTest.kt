package jp.hagemi.android

import org.junit.Assert.*
import org.junit.Test

class HandsFreeGuideTest {
    @Test fun givesTimeToRaiseThePhoneBeforeStartingSearch() {
        val messages=ArrayList<HandsFreeGuide.Cue>();val guide=HandsFreeGuide(messages::add)
        guide.start(1000);guide.track(true,true,2000)
        assertEquals(listOf(HandsFreeGuide.Cue.PREPARE),messages);assertFalse(guide.isArmed(4499))
        assertTrue(guide.isArmed(4500));guide.track(true,true,4500)
        assertEquals(HandsFreeGuide.Cue.FOUND,messages.last())
    }
    @Test fun searchPromptsDoNotRepeatOnEveryFrame() {
        val messages=ArrayList<HandsFreeGuide.Cue>();val guide=HandsFreeGuide(messages::add)
        guide.start(0);for(t in 3500L..12_000L step 200)guide.track(false,false,t)
        assertEquals(1,messages.count{it==HandsFreeGuide.Cue.SEARCH})
    }
    @Test fun alignmentPromptsDoNotInterruptFocusAndHoldInstructions() {
        val messages=ArrayList<HandsFreeGuide.Cue>();val guide=HandsFreeGuide(messages::add)
        guide.start(0);guide.track(true,true,3500);guide.focusing(0,4500)
        for(t in 4700L..9000L step 200)guide.track(false,false,t)
        assertEquals(HandsFreeGuide.Cue.FOCUS,messages.last())
        guide.holding(9200);guide.track(true,false,10_000)
        assertEquals(HandsFreeGuide.Cue.HOLD,messages.last())
    }
    @Test fun lossAfterFocusCancellationIsAnnouncedButJitterIsNotSpammed() {
        val messages=ArrayList<HandsFreeGuide.Cue>();val guide=HandsFreeGuide(messages::add)
        guide.start(0);guide.track(true,true,3500);guide.focusing(0,4500);guide.resumeTracking()
        guide.track(false,false,5000)
        for(t in 5200L..8000L step 200)guide.track(t%400==0L,false,t)
        assertEquals(1,messages.count{it==HandsFreeGuide.Cue.LOST})
    }
    @Test fun distanceInstructionHasTimeToBeHeardBeforeTrackingResumes() {
        val messages=ArrayList<HandsFreeGuide.Cue>();val guide=HandsFreeGuide(messages::add)
        guide.start(0);guide.focusing(1,5000);guide.needsHelp(7500)
        for(t in 7700L..10_300L step 200)guide.track(false,false,t)
        assertEquals(HandsFreeGuide.Cue.STEP_BACK,messages.last())
    }
    @Test fun leavingCameraCancelsSubsequentGuidance() {
        val messages=ArrayList<HandsFreeGuide.Cue>();val guide=HandsFreeGuide(messages::add)
        guide.start(0);guide.stop();guide.track(true,true,10_000);guide.focusing(0,11_000);guide.holding(12_000);guide.needsHelp(13_000)
        assertEquals(listOf(HandsFreeGuide.Cue.PREPARE),messages);assertFalse(guide.isArmed(20_000))
    }
}
