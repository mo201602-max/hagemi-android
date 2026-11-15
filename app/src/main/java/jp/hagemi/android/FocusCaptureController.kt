package jp.hagemi.android

/** Main-thread coordinator. Focus remains locked through the actual camera capture callback. */
class FocusCaptureController(private val port: Port, private val clock: () -> Long, private val changed: () -> Unit) {
    interface Port {
        fun focus(point: FocusTexture.Point, complete: (Boolean) -> Unit)
        fun releaseFocus()
        fun capture()
    }
    enum class State { IDLE, FOCUSING, SETTLING, HOLDING, NEEDS_HELP, CAPTURING }
    enum class Mode { AUTOMATIC, MANUAL, TAP }
    var state = State.IDLE; private set
    var mode = Mode.MANUAL; private set
    var attempt = 0; private set
    var activePoint: FocusTexture.Point? = null; private set
    var analysisAvailable = true
    var automaticSettleMillis = 0L
    private var options = emptyList<FocusTexture.Point>()
    private var generation = 0
    private var started = 0L
    private var completedAt = 0L
    private var afSucceeded = false
    private var lastFrame = Long.MIN_VALUE
    private var acceptableFrames = 0
    private var clearFrames = 0
    private var settleAt = 0L

    fun start(mode: Mode, points: List<FocusTexture.Point>) {
        if (state == State.CAPTURING) return
        cancel()
        this.mode = mode
        options = points.distinct().take(2).ifEmpty { listOf(FocusTexture.Point(.5f, .5f)) }
        attempt = 0
        beginAttempt()
    }
    private fun beginAttempt() {
        val token = ++generation
        started = clock(); completedAt = 0; afSucceeded = false
        lastFrame = Long.MIN_VALUE; acceptableFrames = 0; clearFrames = 0
        activePoint = options[attempt]; state = State.FOCUSING; changed()
        port.focus(activePoint!!) { succeeded ->
            if (token == generation && (state == State.FOCUSING || state == State.SETTLING)) {
                completedAt = clock(); afSucceeded = succeeded
                tick()
            }
        }
    }
    fun observe(detail: FocusTexture.Detail?, timestamp: Long) {
        if ((state != State.FOCUSING && state != State.SETTLING) || timestamp <= started + 250 || timestamp <= lastFrame) return
        if (detail == null) { acceptableFrames = 0; clearFrames = 0; return }
        if (lastFrame != Long.MIN_VALUE && timestamp - lastFrame > 450) { acceptableFrames = 0; clearFrames = 0 }
        lastFrame = timestamp
        acceptableFrames = if (detail.acceptable) acceptableFrames + 1 else 0
        clearFrames = if (detail.clear) clearFrames + 1 else 0
        tick()
    }
    fun tick() {
        if (state != State.FOCUSING && state != State.SETTLING) return
        val now = clock(); val elapsed = now - started
        val fresh = lastFrame != Long.MIN_VALUE && now - lastFrame in 0..450
        val verified = fresh && ((afSucceeded && now - completedAt >= 200 && acceptableFrames >= 2) ||
            (elapsed >= 1400 && clearFrames >= 3))
        // Without an analysis stream, manual capture can still use the camera's AF result.
        val afOnly = !analysisAvailable && mode != Mode.AUTOMATIC && afSucceeded && now - completedAt >= 350
        if (state == State.SETTLING && !verified) { state = State.FOCUSING; changed() }
        if (elapsed >= 650 && (verified || afOnly)) {
            if (mode == Mode.TAP) { state = State.HOLDING; changed() }
            else if (mode == Mode.AUTOMATIC && automaticSettleMillis > 0) {
                if (state != State.SETTLING) { settleAt = now; state = State.SETTLING; changed() }
                else if (now - settleAt >= automaticSettleMillis) takePhoto()
            }
            else takePhoto()
        } else if (elapsed >= 2500) {
            if (attempt + 1 < options.size) { attempt++; beginAttempt() }
            else { generation++; state = State.NEEDS_HELP; changed() }
        }
    }
    private fun takePhoto() {
        generation++; state = State.CAPTURING; changed()
        // In particular, do not call releaseFocus here or when switching the sonar toggle off.
        port.capture()
    }
    fun captureAnyway() {
        if (state == State.CAPTURING) return
        mode = Mode.MANUAL; takePhoto()
    }
    fun finishCapture() {
        if (state != State.CAPTURING) return
        generation++; port.releaseFocus(); activePoint = null; state = State.IDLE; changed()
    }
    fun cancel() {
        // Screen/toggle callbacks must not unlock the lens while ImageCapture is still exposing.
        if (state == State.CAPTURING) return
        generation++; port.releaseFocus(); activePoint = null; state = State.IDLE; changed()
    }
}
