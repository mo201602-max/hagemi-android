package jp.hagemi.android

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Short voice instructions take priority over sonar; vibration also signals saved/error states. */
class CameraFeedback(context: Context, private val unavailable: () -> Unit) {
    private val application = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 60) }.getOrNull()
    @Suppress("DEPRECATION")
    private val vibrator = application.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private var initializing = false
    private var initStarted = 0L
    private var voiceUntil = 0L
    private var speaking = false
    private var voiceGeneration = 0
    private var closed = false
    private val speech = QueuedSpeech({ words ->
        val id = (++voiceGeneration).toString()
        voiceUntil = SystemClock.elapsedRealtime() + 800
        val success = tts?.speak(words, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.SUCCESS
        speaking = success
        // A misbehaving engine must not silence the sonar indefinitely.
        main.postDelayed({ if (voiceGeneration.toString() == id) speaking = false }, 6500)
        success
    }, { tts?.stop(); speaking = false; voiceGeneration++ })
    fun prepare() {
        if (closed || tts != null) return
        initializing = true; initStarted = SystemClock.elapsedRealtime()
        try { tts = TextToSpeech(application) { status -> main.post {
            if (closed) return@post
            initializing = false
            val available = status == TextToSpeech.SUCCESS && runCatching { tts?.setLanguage(Locale.JAPANESE)?.let { it >= 0 } == true }.getOrDefault(false)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { main.post { if (id == voiceGeneration.toString()) speaking = true } }
                override fun onDone(id: String?) { main.post { if (id == voiceGeneration.toString()) speaking = false } }
                @Deprecated("Platform callback") override fun onError(id: String?) { main.post {
                    if (id == voiceGeneration.toString()) { speaking = false; speech.initialized(false); unavailable() }
                } }
            })
            speech.initialized(available)
            if (!available) unavailable()
        } } } catch (error: Exception) { initializing = false; speech.initialized(false); unavailable() }
    }
    fun announce(cue: HandsFreeGuide.Cue) {
        if (closed) return
        prepare(); tone?.stopTone()
        voiceUntil = SystemClock.elapsedRealtime() + 800
        val timing = when (cue) {
            HandsFreeGuide.Cue.SAVED -> longArrayOf(0, 90, 110, 90, 110, 90)
            HandsFreeGuide.Cue.FAILED -> longArrayOf(0, 300)
            HandsFreeGuide.Cue.HOLD -> longArrayOf(0, 60, 90, 60)
            HandsFreeGuide.Cue.FOUND -> longArrayOf(0, 50)
            else -> null
        }
        if (timing != null) runCatching { vibrator?.vibrate(VibrationEffect.createWaveform(timing, -1)) }
        if (!speech.ready) {
            val sound = when (cue) {
                HandsFreeGuide.Cue.SAVED -> ToneGenerator.TONE_PROP_ACK
                HandsFreeGuide.Cue.FAILED, HandsFreeGuide.Cue.STEP_BACK -> ToneGenerator.TONE_PROP_NACK
                else -> ToneGenerator.TONE_PROP_BEEP2
            }
            tone?.startTone(sound, if (cue == HandsFreeGuide.Cue.SAVED) 350 else 120)
        }
        speech.say(cue.words)
    }
    fun sonar() {
        val now = SystemClock.elapsedRealtime()
        if (!closed && !speaking && now >= voiceUntil && !(initializing && now - initStarted < 1500)) {
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 70)
        }
    }
    fun pauseSonar() { tone?.stopTone() }
    fun stop() {
        speech.stop(); tone?.stopTone(); vibrator?.cancel(); voiceUntil = 0
    }
    fun close() {
        stop(); closed = true; tone?.release(); tts?.shutdown(); tts = null
    }
}
