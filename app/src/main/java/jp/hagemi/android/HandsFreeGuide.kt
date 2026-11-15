package jp.hagemi.android

/** Keeps spoken guidance meaningful when the user cannot see the preview. */
class HandsFreeGuide(private val emit: (Cue) -> Unit) {
    enum class Cue(val words: String) {
        PREPARE("3秒後に探し始めます。カメラを頭に向けて構えてね"),
        SEARCH("ゆっくりカメラの角度を変えて、頭皮を映してね"),
        FOUND("見つけたよ。音が速くなる位置へ、ゆっくり動かしてね"),
        LOST("見失いました。ゆっくり角度を戻してね"),
        ALIGN("音が速くなる位置を探してね"),
        FOCUS("そこで止めて、動かさないでね"),
        REFOCUS("そのまま、もう一度ピントを合わせます"),
        HOLD("そのまま。撮ります"),
        STEP_BACK("少し離して、そこで止めてね"),
        SAVED("保存できました。スマホを下ろして大丈夫です"),
        FAILED("撮影できませんでした。スマホを下ろして画面を確認してください")
    }
    private enum class Phase { STOPPED, SEARCHING, FOCUSING, HOLDING, HELP }
    private var phase = Phase.STOPPED
    private var armAt = 0L
    private var lastCueAt = 0L
    private var lastFound = false
    private var trackingKnown = false
    private var lastLostAt: Long? = null
    fun start(now: Long) {
        phase = Phase.SEARCHING; armAt = now + 3500; lastCueAt = now
        lastFound = false; trackingKnown = false; lastLostAt = null; emit(Cue.PREPARE)
    }
    fun isArmed(now: Long) = phase != Phase.STOPPED && now >= armAt
    fun track(found: Boolean, centered: Boolean, now: Long) {
        if (!isArmed(now) || phase == Phase.FOCUSING || phase == Phase.HOLDING) return
        if (phase == Phase.HELP && now - lastCueAt < 3000) return
        phase = Phase.SEARCHING
        val cue = when {
            !found && lastFound && (lastLostAt == null || now - lastLostAt!! >= 3500) -> Cue.LOST
            found && (!trackingKnown || !lastFound) && now - lastCueAt >= 2000 -> Cue.FOUND
            !found && now - lastCueAt >= 6500 -> Cue.SEARCH
            found && !centered && now - lastCueAt >= 6000 -> Cue.ALIGN
            else -> null
        }
        lastFound = found; trackingKnown = true
        if (cue != null) { lastCueAt = now; if (cue == Cue.LOST) lastLostAt = now; emit(cue) }
    }
    fun focusing(attempt: Int, now: Long) {
        if (phase == Phase.STOPPED) return
        phase = Phase.FOCUSING; lastCueAt = now
        emit(if (attempt == 0) Cue.FOCUS else Cue.REFOCUS)
    }
    fun holding(now: Long) {
        if (phase == Phase.STOPPED) return
        phase = Phase.HOLDING; lastCueAt = now; emit(Cue.HOLD)
    }
    fun needsHelp(now: Long) {
        if (phase == Phase.STOPPED) return
        phase = Phase.HELP; lastCueAt = now; emit(Cue.STEP_BACK)
    }
    fun resumeTracking() { if (phase != Phase.STOPPED) phase = Phase.SEARCHING }
    fun stop() { phase = Phase.STOPPED; trackingKnown = false; lastFound = false }
}

/** Stores the current instruction until TTS is ready, discarding obsolete or cancelled ones. */
internal class QueuedSpeech(private val speak: (String) -> Boolean, private val halt: () -> Unit) {
    var ready = false; private set
    private var pending: String? = null
    fun say(words: String) { pending = words; flush() }
    fun initialized(available: Boolean) { ready = available; flush() }
    private fun flush() {
        if (!ready) return
        val value = pending ?: return
        if (speak(value)) pending = null else ready = false
    }
    fun stop() { pending = null; halt() }
}
