package jp.hagemi.android

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.core.view.doOnLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    // Keep in-flight image callbacks valid across Activity recreation.
    companion object {
        private val worker = Executors.newSingleThreadExecutor()
        private val analysisWorker = Executors.newSingleThreadExecutor()
    }
    private lateinit var store: RecordStore
    private lateinit var root: LinearLayout
    private var provider: ProcessCameraProvider? = null
    private var capture: ImageCapture? = null
    private var cameraScreen = false
    private var busy = false
    private var stopGuidance: (() -> Unit)? = null
    private var sonar: android.media.ToneGenerator? = null
    private val shutterSound = android.media.MediaActionSound()
    private var speech: android.speech.tts.TextToSpeech? = null
    private var speechReady = false
    private var lastSpeech = 0L
    override fun onStop() { stopGuidance?.invoke(); speech?.stop(); super.onStop() }
    override fun onDestroy() { sonar?.release(); shutterSound.release(); speech?.shutdown(); super.onDestroy() }
    private val measurementScreen = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) showTimeline()
    }
    private val importPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            busy = true; showTimeline()
            worker.execute {
                val outcome = runCatching {
                    val temp = File.createTempFile("photo-import-", ".image", cacheDir)
                    try {
                        requireNotNull(contentResolver.openInputStream(uri)).use { input -> temp.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024); var total = 0L
                            while (true) {
                                val n = input.read(buffer); if (n < 0) break
                                total += n; require(total <= 32L * 1024 * 1024) { "32MB以下の写真を選んでください" }
                                output.write(buffer, 0, n)
                            }
                        } }
                        store.save(temp, "")
                    } finally { temp.delete() }
                }
                runOnUiThread { busy = false; if (!isDestroyed) {
                    toast(outcome.getOrNull()?.let { "写真を白黒で取り込みました\n${it.resolution?.text.orEmpty()}" }
                        ?: "写真を取り込めませんでした。画像形式・空き容量を確認してください")
                    if (!cameraScreen) showTimeline()
                } }
            }
        }
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showCamera() else {
            toast("撮影にはカメラの許可が必要です。端末の設定から変更できます")
            showTimeline()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shutterSound.load(android.media.MediaActionSound.SHUTTER_CLICK)
        store = RecordStore(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(250, 249, 246))
            setPadding(dp(20), 0, dp(20), 0)
        }
        setContentView(root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(dp(20) + bars.left, bars.top + dp(12), dp(20) + bars.right, bars.bottom + dp(12))
            insets
        }
        showTimeline()
    }

    private fun label(value: String, size: Float = 16f) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.rgb(35, 35, 35)); setPadding(0, dp(8), 0, dp(8))
    }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; setOnClickListener { action() }
    }
    private fun showTimeline() {
        stopGuidance?.invoke()
        stopGuidance = null
        cameraScreen = false
        capture = null
        provider?.unbindAll()
        root.removeAllViews()
        root.addView(label("十円ハゲみちゃん ♡", 26f))
        root.addView(label("今日も記録できたら、それだけでえらいじゃん♡"))
        root.addView(button("＋ 白黒で撮影する") {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) showCamera()
            else permission.launch(Manifest.permission.CAMERA)
        }.apply { isEnabled = !busy })
        root.addView(button("写真を取り込む") {
            importPhoto.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }.apply { isEnabled = !busy })
        if (busy) root.addView(label("写真を処理しています…", 12f))
        root.addView(label("わたしの記録", 20f))
        root.addView(label("写真はこの端末のアプリ内に保存されます。\nアプリを削除すると記録も消えます。", 12f))
        val list = ListView(this).apply { dividerHeight = dp(12) }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        worker.execute {
            val records = store.list()
            runOnUiThread {
                if (!cameraScreen && !isDestroyed) {
                    list.adapter = RecordsAdapter(records)
                    if (records.isEmpty()) root.addView(label("まだ記録はありません。\n最初の1枚を残してみよう♡"))
                    list.setOnItemClickListener { _, _, position, _ -> showRecord(records[position]) }
                }
            }
        }
    }

    private fun showRecord(record: Record) {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), 0) }
        val picture = ImageView(this).apply { adjustViewBounds = true }
        panel.addView(picture, LinearLayout.LayoutParams(-1, dp(320)))
        val photoSize = label(record.resolution?.text ?: "写真のサイズを確認中…", 12f)
        panel.addView(photoSize)
        panel.addView(label(record.note.ifBlank { "メモなし" }))
        record.measurement?.let { panel.addView(label(measurementSummary(it), 12f)) }
        record.hairSummary?.let { panel.addView(label(it.text, 12f)) }
        val dialog = AlertDialog.Builder(this).setTitle(date(record.time)).setView(panel)
            .setPositiveButton("閉じる", null).setNegativeButton("削除") { _, _ ->
                AlertDialog.Builder(this).setMessage("この写真とメモを削除しますか？")
                    .setPositiveButton("削除する") { _, _ -> worker.execute { store.delete(record); runOnUiThread { if (!isDestroyed) showTimeline() } } }
                    .setNegativeButton("戻る", null).show()
            }.create()
        if (record.hairSummary != null) panel.addView(button("生え際の判定を見る") {
            dialog.dismiss()
            measurementScreen.launch(android.content.Intent(this, HairReviewActivity::class.java).putExtra("recordId", record.id))
        })
        dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "サイズ推定") { _, _ ->
            measurementScreen.launch(android.content.Intent(this, MeasurementActivity::class.java).putExtra("recordId", record.id))
        }
        dialog.show()
        worker.execute {
            val image = thumbnail(record.photo, 1200)
            val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(record.photo.path, dimensions)
            val sizeText = if (dimensions.outWidth > 0) "保存：${dimensions.outWidth} × ${dimensions.outHeight} px" +
                (record.resolution?.takeIf { it.reduced }?.let { "（元：${it.sourceWidth} × ${it.sourceHeight} px）" } ?: "") else "写真のサイズを読み込めませんでした"
            runOnUiThread { if (dialog.isShowing) { picture.setImageBitmap(image); photoSize.text = sizeText } }
        }
    }

    @androidx.annotation.OptIn(androidx.camera.view.TransformExperimental::class)
    private fun showCamera() {
        cameraScreen = true
        root.removeAllViews()
        root.addView(label("白黒で記録する", 24f))
        root.addView(label("黄色の枠を中央へ。青い丸でピントを合わせます。\n画面をタップして位置を指定することもできます。", 14f))
        val detectionStatus = label("検出の準備中…", 14f)
        val preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
            setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply {
                colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            })
        }
        val overlay = DetectionOverlay(this)
        val cameraFrame = FrameLayout(this).apply {
            addView(preview, FrameLayout.LayoutParams(-1, -1))
            addView(overlay, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(cameraFrame, LinearLayout.LayoutParams(-1, 0, 1f))
        val qualityStatus = label("撮影サイズを確認中…", 12f)
        root.addView(qualityStatus); root.addView(detectionStatus)
        val automatic = Switch(this).apply { text = "ソナー音・自動撮影" }
        root.addView(automatic)
        val note = EditText(this).apply { hint = "今日のメモ（任意）"; maxLines = 2; filters = arrayOf(android.text.InputFilter.LengthFilter(500)) }
        root.addView(note)
        val shutter = button("カメラの準備中…") {}.apply { isEnabled = false }
        root.addView(shutter)
        val force = button("ピント確認を省略して撮影") {}.apply { visibility = View.GONE }
        root.addView(force)
        val back = button("記録一覧へ") { if (!busy) showTimeline() }
        root.addView(back)
        val gate = AutoCaptureGate()
        var lastSeen = 0L; var lastTone = 0L; var retryAfter = 0L
        var focusCamera: androidx.camera.core.Camera? = null
        var latestTarget: AutoCaptureGate.Target? = null
        var focusedTarget: AutoCaptureGate.Target? = null
        var options = emptyList<FocusTexture.Option>()
        var tapped: FocusTexture.Point? = null
        val requested = java.util.concurrent.atomic.AtomicReference<FocusTexture.Point?>(null)
        val scanActive = java.util.concurrent.atomic.AtomicBoolean(false)
        data class Mapping(val source: androidx.camera.view.transform.OutputTransform, val crop: Rect)
        var mapping: Mapping? = null
        lateinit var focus: FocusCaptureController
        fun clock() = android.os.SystemClock.elapsedRealtime()
        fun viewPoint(point: FocusTexture.Point): PointF {
            val current = mapping; val output = preview.outputTransform
            if (current == null || output == null) return PointF(point.x * preview.width, point.y * preview.height)
            val xy = floatArrayOf(current.crop.left + point.x * current.crop.width(), current.crop.top + point.y * current.crop.height())
            CoordinateTransform(current.source, output).mapPoints(xy)
            return PointF(xy[0].coerceIn(0f, preview.width.toFloat()), xy[1].coerceIn(0f, preview.height.toFloat()))
        }
        fun currentPoints() = (listOfNotNull(tapped) + options.map { it.point }).distinct().take(2)
        fun usableScreen() = cameraScreen && preview.isAttachedToWindow && !isDestroyed &&
            lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        fun resetGuidance() {
            gate.reset(); lastSeen = 0L; lastTone = 0L; focusedTarget = null
            sonar?.stopTone(); focus.cancel()
        }
        fun capturePhoto() {
            val imageCapture = capture
            if (imageCapture == null || !usableScreen()) { focus.finishCapture(); return }
            busy = true; shutter.isEnabled = false; back.isEnabled = false; force.visibility = View.GONE
            shutter.text = "保存中…"
            // The toggle listener may reset guidance, but the controller keeps AF locked while capturing.
            automatic.isChecked = false
            sonar?.stopTone()
            val memo = note.text.toString().trim()
            val temp = try { File.createTempFile("capture-", ".jpg", cacheDir) } catch (error: Exception) {
                focus.finishCapture(); busy = false; shutter.isEnabled = true; back.isEnabled = true
                shutter.text = "ピントを合わせて撮影"; toast("撮影用の空き容量を確保できませんでした"); return
            }
            fun failed() {
                runOnUiThread {
                    focus.finishCapture(); busy = false
                    if (!isDestroyed) {
                        shutter.isEnabled = true; back.isEnabled = true; shutter.text = "ピントを合わせて撮影"
                        toast("保存できませんでした。空き容量を確認して、もう一度お試しください")
                    }
                }
            }
            shutterSound.play(android.media.MediaActionSound.SHUTTER_CLICK)
            imageCapture.targetRotation = preview.display.rotation
            try {
                imageCapture.takePicture(ImageCapture.OutputFileOptions.Builder(temp).build(), worker,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                            // Release only after the high-resolution exposure has completed, before PNG conversion.
                            runOnUiThread { focus.finishCapture() }
                            try {
                                val record = store.save(temp, memo)
                                runOnUiThread { busy = false; if (!isDestroyed) { toast("記録できたじゃん♡\n${record.resolution?.text.orEmpty()}"); showTimeline() } }
                            } catch (error: Exception) { failed() } finally { temp.delete() }
                        }
                        override fun onError(error: ImageCaptureException) { temp.delete(); failed() }
                    })
            } catch (error: Exception) { temp.delete(); failed() }
        }
        focus = FocusCaptureController(object : FocusCaptureController.Port {
            override fun focus(point: FocusTexture.Point, complete: (Boolean) -> Unit) {
                requested.set(point)
                val xy = viewPoint(point); overlay.focusAt(xy, false)
                val metering = preview.meteringPointFactory.createPoint(xy.x, xy.y, .10f)
                val action = CameraQuality.focusAction(metering)
                val future = runCatching { focusCamera?.cameraControl?.startFocusAndMetering(action) }.getOrNull()
                if (future == null) complete(false)
                else future.addListener({ complete(runCatching { future.get().isFocusSuccessful }.getOrDefault(false)) }, ContextCompat.getMainExecutor(this@MainActivity))
            }
            override fun releaseFocus() {
                requested.set(null); overlay.focusAt(null, false)
                runCatching { focusCamera?.cameraControl?.cancelFocusAndMetering() }
            }
            override fun capture() { capturePhoto() }
        }, ::clock) {
            scanActive.set(focus.state == FocusCaptureController.State.FOCUSING)
            when (focus.state) {
                FocusCaptureController.State.FOCUSING -> {
                    shutter.isEnabled = false; shutter.text = "ピントを調整中…"; force.visibility = View.GONE
                    detectionStatus.text = if (focus.attempt == 0) "髪との境界でピントを調整中。そのまま動かさずに♡" else "別の境界でもう一度ピントを調整中…"
                    if (focus.attempt == 0 && focus.mode == FocusCaptureController.Mode.AUTOMATIC) speakGuidance("ピントを合わせています。そのまま動かさないでね")
                }
                FocusCaptureController.State.HOLDING -> {
                    shutter.isEnabled = !busy; shutter.text = "ピントを合わせて撮影"
                    focus.activePoint?.let { overlay.focusAt(viewPoint(it), true) }
                    detectionStatus.text = "ピントを固定しました。撮影ボタンで記録できます"
                }
                FocusCaptureController.State.NEEDS_HELP -> {
                    shutter.isEnabled = !busy; shutter.text = "ピントを合わせ直して撮影"; force.visibility = View.VISIBLE
                    gate.reset(); retryAfter = clock() + 3000
                    detectionStatus.text = "細部を確認できません。少し離す・明るい場所にする・髪との境界をタップして試してください"
                    if (focus.mode == FocusCaptureController.Mode.AUTOMATIC) {
                        sonar?.startTone(android.media.ToneGenerator.TONE_PROP_NACK, 150)
                        speakGuidance("少し離して、髪と地肌の境目を映してね", urgent = true)
                    }
                }
                FocusCaptureController.State.IDLE -> {
                    shutter.isEnabled = !busy && capture != null; shutter.text = if (busy) "保存中…" else "ピントを合わせて撮影"
                    force.visibility = View.GONE
                }
                FocusCaptureController.State.CAPTURING -> { force.visibility = View.GONE }
            }
        }
        automatic.setOnCheckedChangeListener { _, checked ->
            resetGuidance()
            if (!checked) speech?.stop()
            if (checked) {
                tapped = null; retryAfter = 0
                enableSpeech()
                if (sonar == null) {
                    sonar = runCatching { android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 60) }.getOrNull()
                    if (sonar == null) toast("音を開始できませんでした。画面の案内で自動撮影します")
                }
            }
        }
        stopGuidance = { if (automatic.isChecked) automatic.isChecked = false else resetGuidance() }
        shutter.setOnClickListener {
            if (!busy && usableScreen()) {
                automatic.isChecked = false
                focus.start(FocusCaptureController.Mode.MANUAL, currentPoints())
            }
        }
        force.setOnClickListener { if (!busy && usableScreen()) focus.captureAnyway() }
        // Status wrapping and the help button can resize the preview. Keep the marker on the
        // same camera point when that happens instead of retaining old view pixel coordinates.
        preview.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            requested.get()?.let { overlay.focusAt(viewPoint(it), focus.state == FocusCaptureController.State.HOLDING) }
        }
        preview.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_UP && !busy && usableScreen()) {
                view.performClick()
                val current = mapping; val output = preview.outputTransform
                val point = if (current != null && output != null) {
                    val xy = floatArrayOf(event.x, event.y)
                    CoordinateTransform(output, current.source).mapPoints(xy)
                    FocusTexture.Point((xy[0] - current.crop.left) / current.crop.width(), (xy[1] - current.crop.top) / current.crop.height())
                } else FocusTexture.Point(event.x / preview.width, event.y / preview.height)
                if (point.x in 0f..1f && point.y in 0f..1f) {
                    automatic.isChecked = false; tapped = point
                    focus.start(FocusCaptureController.Mode.TAP, listOf(point))
                }
            }
            true
        }
        val poll = object : Runnable {
            override fun run() {
                if (cameraScreen && preview.isAttachedToWindow && !isDestroyed) {
                    if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) focus.tick()
                    preview.postDelayed(this, 100)
                }
            }
        }
        preview.post(poll)
        preview.doOnLayout {
            val future = ProcessCameraProvider.getInstance(this)
            future.addListener({
                if (!cameraScreen || isDestroyed || !preview.isAttachedToWindow) return@addListener
                try {
                    provider = future.get()
                    val cameraPreview = CameraQuality.preview().also { it.setSurfaceProvider(preview.surfaceProvider) }
                    capture = CameraQuality.capture()
                    val analysis = CameraQuality.analysis()
                    val detector = ContrastDetector(); var lastFrame = 0L; var lastOptions = 0L
                    analysis.setAnalyzer(analysisWorker) { image ->
                        try {
                            val now = clock()
                            if (now - lastFrame >= 200) {
                                lastFrame = now
                                val crop = Rect(image.cropRect); val longest = maxOf(160, crop.width(), crop.height())
                                val w = maxOf(1, crop.width() * 160 / longest); val h = maxOf(1, crop.height() * 160 / longest)
                                val plane = image.planes[0]; val buffer = plane.buffer; val offset = buffer.position()
                                fun pixel(x: Int, y: Int) = buffer.get(offset + y * plane.rowStride + x * plane.pixelStride).toInt() and 255
                                val pixels = IntArray(w * h) { i -> pixel(crop.left + (i % w) * crop.width() / w, crop.top + (i / w) * crop.height() / h) }
                                val candidate = detector.detect(pixels, w, h)
                                val fw = maxOf(5, crop.width() * minOf(640, longest) / longest)
                                val fh = maxOf(5, crop.height() * minOf(640, longest) / longest)
                                fun detailPixel(x: Int, y: Int) = pixel(crop.left + x * crop.width() / fw, crop.top + y * crop.height() / fh)
                                val region = candidate?.let { Region(it.left.toDouble() / w, it.top.toDouble() / h, it.right.toDouble() / w, it.bottom.toDouble() / h) }
                                val nextOptions = if (!scanActive.get() && (lastOptions == 0L || now - lastOptions >= 400)) {
                                    lastOptions = now; FocusTexture.boundaryOptions(fw, fh, region, ::detailPixel)
                                } else null
                                val detail = requested.get()?.let { FocusTexture.measure(fw, fh, it, ::detailPixel) }
                                val source = ImageProxyTransformFactory().apply { isUsingCropRect = false; isUsingRotationDegrees = false }.getOutputTransform(image)
                                val box = candidate?.let { RectF(crop.left + it.left.toFloat() * crop.width() / w, crop.top + it.top.toFloat() * crop.height() / h,
                                    crop.left + it.right.toFloat() * crop.width() / w, crop.top + it.bottom.toFloat() * crop.height() / h) }
                                runOnUiThread {
                                    if (!usableScreen() || busy || clock() - now >= 450) return@runOnUiThread
                                    mapping = Mapping(source, crop)
                                    if (nextOptions != null) options = nextOptions
                                    val output = preview.outputTransform
                                    val found = box != null && output != null && run { CoordinateTransform(source, output).mapRect(box); box.intersect(0f, 0f, preview.width.toFloat(), preview.height.toFloat()) }
                                    val target = if (found) AutoCaptureGate.Target(box!!.centerX().toDouble() / preview.width, box.centerY().toDouble() / preview.height,
                                        box.width().toDouble() * box.height() / (preview.width.toDouble() * preview.height)) else null
                                    latestTarget = target
                                    if (target != null) lastSeen = now
                                    overlay.update(if (found) box else null)
                                    if (focus.state == FocusCaptureController.State.FOCUSING && focus.mode == FocusCaptureController.Mode.AUTOMATIC) {
                                        val initial = focusedTarget
                                        val same = target != null && initial != null && kotlin.math.hypot(target.x - initial.x, target.y - initial.y) < .12 &&
                                            kotlin.math.abs(target.area - initial.area) / initial.area.coerceAtLeast(.0001) < 1.25 && kotlin.math.hypot(target.x - .5, target.y - .5) <= .18
                                        if (!same) { resetGuidance(); detectionStatus.text = "位置が変わりました。もう一度ガイドの中へ" }
                                    }
                                    focus.observe(detail, now)
                                    if (busy) return@runOnUiThread
                                    val distance = target?.let { kotlin.math.hypot(it.x - .5, it.y - .5) }
                                    if (automatic.isChecked) {
                                        val ready = gate.update(target, now)
                                        if (target != null && clock() >= retryAfter && clock() - lastTone >= AutoCaptureGate.interval(distance!!)) {
                                            sonar?.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 70); lastTone = clock()
                                        }
                                        if (ready && focus.state != FocusCaptureController.State.FOCUSING && clock() >= retryAfter && clock() - lastSeen < 450) {
                                            focusedTarget = latestTarget
                                            focus.start(FocusCaptureController.Mode.AUTOMATIC, options.map { it.point })
                                        } else if (focus.state == FocusCaptureController.State.IDLE) detectionStatus.text = if (target == null) "髪と頭皮を一緒に映してね。候補を探しています…" else gate.status
                                    } else if (focus.state == FocusCaptureController.State.IDLE) {
                                        detectionStatus.text = if (target == null) "髪と頭皮を一緒に映してね。タップでピント位置も指定できます" else if (distance!! < .18) "中央です。撮影ボタンで境界にピントを合わせます" else "候補の黄色い枠を中央の十字へ"
                                    }
                                }
                            }
                        } catch (error: Exception) {
                            runOnUiThread { if (usableScreen() && !busy) { resetGuidance(); overlay.update(null); detectionStatus.text = "検出を再試行中。手動撮影も使えます" } }
                        } finally { image.close() }
                    }
                    provider!!.unbindAll()
                    fun cameraGroup() = UseCaseGroup.Builder().addUseCase(cameraPreview).addUseCase(capture!!).addUseCase(analysis)
                        .setViewPort(ViewPort.Builder(android.util.Rational(preview.width, preview.height), preview.display.rotation).setScaleType(ViewPort.FIT).build()).build()
                    try { focusCamera = provider!!.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, cameraGroup()) }
                    catch (error: IllegalArgumentException) {
                        provider!!.unbindAll(); capture = CameraQuality.capture(includeSlowHighResolution = false)
                        try { focusCamera = provider!!.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, cameraGroup()) }
                        catch (standardError: IllegalArgumentException) {
                            analysis.clearAnalyzer(); automatic.isEnabled = false; focus.analysisAvailable = false; provider!!.unbindAll()
                            focusCamera = provider!!.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, capture)
                            detectionStatus.text = "この端末では検出を起動できません。タップと手動撮影は使えます"
                        }
                    }
                    val stillSize = capture?.resolutionInfo?.cropRect; val searchSize = analysis.resolutionInfo?.resolution
                    qualityStatus.text = stillSize?.let { "撮影：${it.width()} × ${it.height()} px" }.orEmpty() + (searchSize?.let { " ／ サーチ：${it.width} × ${it.height}" } ?: "")
                    shutter.text = "ピントを合わせて撮影"; shutter.isEnabled = true
                } catch (error: Exception) { toast("背面カメラを起動できませんでした"); showTimeline() }
            }, ContextCompat.getMainExecutor(this))
        }
    }

    private inner class RecordsAdapter(private val records: List<Record>) : BaseAdapter() {
        override fun getCount() = records.size
        override fun getItem(position: Int) = records[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }, LinearLayout.LayoutParams(dp(90), dp(90)))
                addView(label("", 14f).apply { setPadding(dp(12), 0, 0, 0); maxLines = 4 }, LinearLayout.LayoutParams(0, dp(90), 1f))
            }
            val record = records[position]
            val image = row.getChildAt(0) as ImageView
            image.tag = record.id; image.setImageDrawable(null)
            (row.getChildAt(1) as TextView).text = getString(R.string.record_summary, date(record.time),
                (record.hairSummary?.let { "${it.text}\n" } ?: "") + (record.measurement?.let { "${measurementSummary(it)}\n" } ?: "") + record.note.ifBlank { "今日の1枚 ♡" })
            worker.execute {
                val bitmap = thumbnail(record.photo, 240)
                runOnUiThread { if (!isDestroyed && image.tag == record.id) image.setImageBitmap(bitmap) }
            }
            return row
        }
    }
    private fun thumbnail(file: File, edge: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        var sample = 1
        while (maxOf(options.outWidth, options.outHeight) / sample > edge) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
    private fun measurementSummary(value: Measurement) = value.estimate?.let {
        if (value.mesh != null) "境界間隔の格子換算：約${AreaEstimate.rounded(it)}点相当"
        else if (value.candidateCount) "候補密度の参考換算：約${AreaEstimate.rounded(it)}個相当"
        else "推定 約${AreaEstimate.rounded(it)}本分（手動密度）"
    } ?: "脱毛範囲を記録済み（本数未推定）"
    private fun date(time: Long) = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(Date(time))
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun enableSpeech() {
        if (speech != null) return
        speech = android.speech.tts.TextToSpeech(applicationContext) { status -> runOnUiThread {
            speechReady = status == android.speech.tts.TextToSpeech.SUCCESS &&
                speech?.setLanguage(Locale.JAPANESE)?.let { it >= 0 } == true
        } }
    }
    private fun speakGuidance(value: String, urgent: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (speechReady && (urgent || now - lastSpeech >= 3500)) {
            lastSpeech = now
            speech?.speak(value, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "camera-guidance")
        }
    }
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
}
