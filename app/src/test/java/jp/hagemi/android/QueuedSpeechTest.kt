package jp.hagemi.android

import org.junit.Assert.*
import org.junit.Test

class QueuedSpeechTest {
    @Test fun initializationSpeaksTheCurrentInstructionInsteadOfAnObsoleteOne() {
        val said=ArrayList<String>();val queue=QueuedSpeech({said.add(it);true},{})
        queue.say("構えてね");queue.say("そこで止めてね");assertTrue(said.isEmpty())
        queue.initialized(true);assertEquals(listOf("そこで止めてね"),said)
    }
    @Test fun cancellingBeforeInitializationDoesNotPlayLateInstructions() {
        val said=ArrayList<String>();var stops=0;val queue=QueuedSpeech({said.add(it);true},{stops++})
        queue.say("構えてね");queue.stop();queue.initialized(true)
        assertTrue(said.isEmpty());assertEquals(1,stops)
    }
    @Test fun savedConfirmationCanBeAnnouncedAfterCameraTeardown() {
        val said=ArrayList<String>();val queue=QueuedSpeech({said.add(it);true},{})
        queue.initialized(true);queue.say("ピント合わせ中");queue.stop();queue.say("保存できました")
        assertEquals(listOf("ピント合わせ中","保存できました"),said)
    }
    @Test fun unavailableEngineRetainsOnlyTheLatestInstructionForALaterInitialization() {
        val said=ArrayList<String>();val queue=QueuedSpeech({said.add(it);true},{})
        queue.initialized(false);queue.say("探しています");queue.say("少し離してね");queue.initialized(true)
        assertEquals(listOf("少し離してね"),said)
    }
}
