package jp.hagemi.android

import org.junit.Assert.*
import org.junit.Test

class FocusCaptureControllerTest {
    private class Fixture {
        var now=1000L
        val events=ArrayList<String>()
        val callbacks=ArrayList<(Boolean)->Unit>()
        val subject=FocusCaptureController(object:FocusCaptureController.Port {
            override fun focus(point:FocusTexture.Point,complete:(Boolean)->Unit){events.add("focus");callbacks.add(complete)}
            override fun releaseFocus(){events.add("release")}
            override fun capture(){events.add("capture")}
        },{now},{})
        fun start(mode:FocusCaptureController.Mode=FocusCaptureController.Mode.AUTOMATIC,two:Boolean=false) {
            subject.start(mode,if(two)listOf(FocusTexture.Point(.3f,.5f),FocusTexture.Point(.7f,.5f))else listOf(FocusTexture.Point(.3f,.5f)))
            events.clear()
        }
        fun frame(at:Long,detail:FocusTexture.Detail=FocusTexture.Detail(70f,12f,.2f)) {now=at;subject.observe(detail,at)}
        fun tick(at:Long){now=at;subject.tick()}
    }
    @Test fun keepsFocusLockedThroughCaptureIncludingToggleAndScreenCancellation() {
        val f=Fixture();f.start();f.callbacks.last()(true)
        f.frame(1400);f.frame(1600);f.frame(1800)
        assertEquals(FocusCaptureController.State.CAPTURING,f.subject.state)
        f.subject.cancel();f.subject.cancel()
        assertEquals(listOf("capture"),f.events)
        f.subject.finishCapture()
        assertEquals(listOf("capture","release"),f.events)
        assertEquals(FocusCaptureController.State.IDLE,f.subject.state)
    }
    @Test fun afSuccessDoesNotBypassObviouslySoftFrames() {
        val f=Fixture();f.start(two=true);f.callbacks.last()(true)
        val soft=FocusTexture.Detail(60f,1f,.01f)
        for(t in 1400L..3400L step 200)f.frame(t,soft)
        f.tick(3500);assertEquals(1,f.subject.attempt)
        f.callbacks.last()(true)
        for(t in 3900L..5900L step 200)f.frame(t,soft)
        f.tick(6000)
        assertEquals(FocusCaptureController.State.NEEDS_HELP,f.subject.state)
        assertFalse(f.events.contains("capture"))
    }
    @Test fun missingAfNotificationCanUseSeveralFreshClearFrames() {
        val f=Fixture();f.start()
        for(t in 1400L..2400L step 200)f.frame(t)
        assertEquals(FocusCaptureController.State.CAPTURING,f.subject.state)
        assertEquals(1,f.events.count{it=="capture"})
    }
    @Test fun timeoutNeverForcesAnAutomaticCapture() {
        val f=Fixture();f.start();f.callbacks.last()(false);f.tick(3500)
        assertEquals(FocusCaptureController.State.NEEDS_HELP,f.subject.state)
        assertFalse(f.events.contains("capture"))
    }
    @Test fun oldFramesCannotAuthorizeCapture() {
        val f=Fixture();f.start();f.callbacks.last()(true)
        f.frame(1300);f.frame(1500)
        f.tick(2200)
        assertEquals(FocusCaptureController.State.FOCUSING,f.subject.state)
        f.frame(2300)
        assertEquals(FocusCaptureController.State.FOCUSING,f.subject.state)
    }
    @Test fun cancelledRequestIgnoresLateAfAndFrames() {
        val f=Fixture();f.start();val late=f.callbacks.last();f.subject.cancel();late(true)
        for(t in 1400L..3000L step 200)f.frame(t)
        assertEquals(FocusCaptureController.State.IDLE,f.subject.state);assertFalse(f.events.contains("capture"))
    }
    @Test fun tapFocusHoldsWithoutTakingAPhoto() {
        val f=Fixture();f.start(FocusCaptureController.Mode.TAP);f.callbacks.last()(true)
        f.frame(1400);f.frame(1600);f.frame(1800)
        assertEquals(FocusCaptureController.State.HOLDING,f.subject.state)
        assertTrue(f.events.isEmpty())
    }
    @Test fun onlyManualModeCanUseAfWhenNoAnalysisStreamIsAvailable() {
        val f=Fixture();f.subject.analysisAvailable=false;f.start(FocusCaptureController.Mode.MANUAL);f.callbacks.last()(true);f.tick(1800)
        assertEquals(FocusCaptureController.State.CAPTURING,f.subject.state)
        val auto=Fixture();auto.subject.analysisAvailable=false;auto.start();auto.callbacks.last()(true);auto.tick(3500)
        assertFalse(auto.events.contains("capture"))
    }
    @Test fun availableButStalledAnalysisCannotBeSkippedByManualAfSuccess() {
        val f=Fixture();f.start(FocusCaptureController.Mode.MANUAL);f.callbacks.last()(true);f.tick(3500)
        assertEquals(FocusCaptureController.State.NEEDS_HELP,f.subject.state);assertFalse(f.events.contains("capture"))
    }
    @Test fun anInFlightCaptureCannotBeReplacedByAnotherFocusRequest() {
        val f=Fixture();f.start();f.subject.captureAnyway()
        f.subject.start(FocusCaptureController.Mode.TAP,listOf(FocusTexture.Point(.5f,.5f)))
        assertEquals(listOf("capture"),f.events)
    }
    @Test fun explicitOverrideRetainsFocusAndInvalidatesPendingAfCallback() {
        val f=Fixture();f.start();val late=f.callbacks.last();f.subject.captureAnyway();late(true)
        assertEquals(listOf("capture"),f.events);assertEquals(FocusCaptureController.Mode.MANUAL,f.subject.mode)
    }
    @Test fun callbackFromFirstBoundaryCannotCompleteTheSecondAttempt() {
        val f=Fixture();f.start(two=true);val old=f.callbacks.last();f.tick(3500);old(true)
        val moderate=FocusTexture.Detail(50f,4f,.08f)
        f.frame(3900,moderate);f.frame(4100,moderate);f.tick(4300)
        assertEquals(FocusCaptureController.State.FOCUSING,f.subject.state)
        f.callbacks.last()(true);f.frame(4500,moderate)
        assertEquals(FocusCaptureController.State.CAPTURING,f.subject.state)
    }
    @Test fun oneFrameCannotBeCountedSeveralTimes() {
        val f=Fixture();f.start();f.callbacks.last()(true)
        repeat(5){f.frame(1400)};f.tick(1800)
        assertEquals(FocusCaptureController.State.FOCUSING,f.subject.state)
    }
    @Test fun unavailableAssessmentBreaksThePreviousRunOfClearFrames() {
        val f=Fixture();f.start();f.callbacks.last()(true)
        f.frame(1300);f.frame(1500);f.now=1700;f.subject.observe(null,1700);f.tick(1800)
        assertEquals(FocusCaptureController.State.FOCUSING,f.subject.state)
    }
    private fun prepareSettling():Fixture {
        val f=Fixture();f.subject.automaticSettleMillis=700;f.start();f.callbacks.last()(true)
        f.frame(1400);f.frame(1600);f.frame(1800)
        assertEquals(FocusCaptureController.State.SETTLING,f.subject.state)
        return f
    }
    @Test fun handsFreeWaitsForContinuedDetailAfterTheHoldInstruction() {
        val f=prepareSettling();f.frame(2000);f.frame(2200);f.frame(2400)
        assertFalse(f.events.contains("capture"));f.frame(2600)
        assertEquals(FocusCaptureController.State.CAPTURING,f.subject.state)
        assertEquals(listOf("capture"),f.events)
    }
    @Test fun blurDuringTheHoldRestartsTheWait() {
        val f=prepareSettling();f.frame(2000,FocusTexture.Detail(20f,1f,.01f))
        assertEquals(FocusCaptureController.State.FOCUSING,f.subject.state)
        f.frame(2200);f.frame(2400)
        assertEquals(FocusCaptureController.State.SETTLING,f.subject.state)
        f.frame(2600);f.frame(2800);f.frame(3000);assertFalse(f.events.contains("capture"))
        f.frame(3200);assertTrue(f.events.contains("capture"))
    }
    @Test fun aHoldCannotFinishWithAStalledCameraStream() {
        val f=prepareSettling();f.tick(2600)
        assertEquals(FocusCaptureController.State.FOCUSING,f.subject.state)
        assertFalse(f.events.contains("capture"))
    }
    @Test fun cancellingTheHoldNeverTakesThePendingPhoto() {
        val f=prepareSettling();f.subject.cancel()
        for(t in 2000L..3400L step 200)f.frame(t)
        assertEquals(FocusCaptureController.State.IDLE,f.subject.state);assertFalse(f.events.contains("capture"))
    }
    @Test fun qualityBasedFallbackStillWaitsBeforeHandsFreeCapture() {
        val f=Fixture();f.subject.automaticSettleMillis=700;f.start()
        for(t in 1400L..2400L step 200)f.frame(t)
        assertEquals(FocusCaptureController.State.SETTLING,f.subject.state)
        f.frame(2600);f.frame(2800);f.frame(3000);assertFalse(f.events.contains("capture"))
        f.frame(3200);assertTrue(f.events.contains("capture"))
    }
    @Test fun extraHandsFreeHoldDoesNotDelayManualAndTapModes() {
        for(mode in listOf(FocusCaptureController.Mode.MANUAL,FocusCaptureController.Mode.TAP)) {
            val f=Fixture();f.subject.automaticSettleMillis=700;f.start(mode);f.callbacks.last()(true)
            f.frame(1400);f.frame(1600);f.frame(1800)
            assertEquals(if(mode==FocusCaptureController.Mode.TAP)FocusCaptureController.State.HOLDING else FocusCaptureController.State.CAPTURING,f.subject.state)
        }
    }
}
