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
import androidx.camera.core.resolutionselector.*
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
    override fun onStop() { stopGuidance?.invoke(); super.onStop() }
    override fun onDestroy() { sonar?.release(); shutterSound.release(); super.onDestroy() }
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
        })
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
        panel.addView(label(record.note.ifBlank { "メモなし" }))
        val dialog = AlertDialog.Builder(this).setTitle(date(record.time)).setView(panel)
            .setPositiveButton("閉じる", null).setNegativeButton("削除") { _, _ ->
                AlertDialog.Builder(this).setMessage("この写真とメモを削除しますか？")
                    .setPositiveButton("削除する") { _, _ -> worker.execute { store.delete(record); runOnUiThread { if (!isDestroyed) showTimeline() } } }
                    .setNegativeButton("戻る", null).show()
            }.create()
        dialog.show()
        worker.execute {
            val image = thumbnail(record.photo, 1200)
            runOnUiThread { if (dialog.isShowing) picture.setImageBitmap(image) }
        }
    }

    @androidx.annotation.OptIn(androidx.camera.view.TransformExperimental::class)
    private fun showCamera() {
        cameraScreen = true
        root.removeAllViews()
        root.addView(label("白黒で記録する", 24f))
        root.addView(label("黄色の枠を中央へ。\nソナーをONにすると安定後に自動撮影します。", 14f))
        val detectionStatus = label("検出の準備中…", 14f)
        val preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
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
        root.addView(detectionStatus)
        val automatic = Switch(this).apply { text = "ソナー音・自動撮影" }
        root.addView(automatic)
        val gate = AutoCaptureGate()
        var ready = false
        var lastSeen = 0L
        var lastTone = 0L
        var focusing = false
        var focusGeneration = 0
        var focusCamera: androidx.camera.core.Camera? = null
        var latestTarget: AutoCaptureGate.Target? = null
        fun resetGuidance() {
            gate.reset(); ready = false; lastSeen = 0L; lastTone = 0L
            latestTarget = null; focusing = false; focusGeneration++
            sonar?.stopTone()
            focusCamera?.cameraControl?.cancelFocusAndMetering()
        }
        automatic.setOnCheckedChangeListener { _, checked ->
            resetGuidance()
            if (checked && sonar == null) {
                sonar = runCatching { android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 60) }.getOrNull()
                if (sonar == null) toast("音を開始できませんでした。画面の案内で自動撮影します")
            }
        }
        stopGuidance = { automatic.isChecked = false; resetGuidance() }
        val note = EditText(this).apply { hint = "今日のメモ（任意）"; maxLines = 2; filters = arrayOf(android.text.InputFilter.LengthFilter(500)) }
        root.addView(note)
        val shutter = button("カメラの準備中…") {}
        shutter.isEnabled = false
        root.addView(shutter)
        val back = button("記録一覧へ") { if (!busy) showTimeline() }
        root.addView(back)
        preview.doOnLayout {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!cameraScreen || isDestroyed) return@addListener
            try {
                provider = future.get()
                val cameraPreview = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(android.util.Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                    ).build()).build()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(android.util.Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                    ).build()).build()
                val detector = ContrastDetector()
                var lastFrame = 0L
                analysis.setAnalyzer(analysisWorker) { image ->
                    try {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastFrame >= 200) {
                            lastFrame = now
                            val crop = image.cropRect
                            val longest = maxOf(160, crop.width(), crop.height())
                            val w = maxOf(1, crop.width() * 160 / longest)
                            val h = maxOf(1, crop.height() * 160 / longest)
                            val plane = image.planes[0]
                            val buffer = plane.buffer
                            val offset = buffer.position()
                            val pixels = IntArray(w * h) { index ->
                                val x = crop.left + (index % w) * crop.width() / w
                                val y = crop.top + (index / w) * crop.height() / h
                                buffer.get(offset + y * plane.rowStride + x * plane.pixelStride).toInt() and 255
                            }
                            val candidate = detector.detect(pixels, w, h)
                            val source = ImageProxyTransformFactory().apply {
                                isUsingCropRect = false
                                isUsingRotationDegrees = false
                            }.getOutputTransform(image)
                            val box = candidate?.let { RectF(
                                crop.left + it.left.toFloat() * crop.width() / w, crop.top + it.top.toFloat() * crop.height() / h,
                                crop.left + it.right.toFloat() * crop.width() / w, crop.top + it.bottom.toFloat() * crop.height() / h
                            ) }
                            runOnUiThread {
                                if (cameraScreen && preview.isAttachedToWindow && !isDestroyed && !busy) {
                                    val output = preview.outputTransform
                                    if (box != null && output != null && android.os.SystemClock.elapsedRealtime() - now < 450) {
                                        CoordinateTransform(source, output).mapRect(box)
                                        if (box.intersect(0f, 0f, preview.width.toFloat(), preview.height.toFloat())) {
                                            overlay.update(box)
                                            val distance = kotlin.math.hypot(
                                                (box.centerX() - preview.width / 2f) / preview.width,
                                                (box.centerY() - preview.height / 2f) / preview.height)
                                            detectionStatus.text = if (distance < 0.18f) "中央です。そのまま動かさずに♡" else "候補の黄色い枠を中央の十字へ"
                                            if (automatic.isChecked && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                                                val clock = android.os.SystemClock.elapsedRealtime()
                                                val target = AutoCaptureGate.Target(box.centerX().toDouble() / preview.width,
                                                    box.centerY().toDouble() / preview.height,
                                                    box.width().toDouble() * box.height() / (preview.width.toDouble() * preview.height))
                                                latestTarget = target
                                                lastSeen = now
                                                ready = gate.update(target, now)
                                                detectionStatus.text = if (focusing) "ピントを調整中…このまま♡" else gate.status
                                                if (clock - lastTone >= AutoCaptureGate.interval(distance.toDouble())) {
                                                    sonar?.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 70)
                                                    lastTone = clock
                                                }
                                                if (ready && !focusing) {
                                                    focusing = true
                                                    val generation = ++focusGeneration
                                                    val focusedTarget = target
                                                    val point = preview.meteringPointFactory.createPoint(box.centerX(), box.centerY())
                                                    val focus = runCatching { focusCamera?.cameraControl?.startFocusAndMetering(
                                                        FocusMeteringAction.Builder(point).setAutoCancelDuration(2, java.util.concurrent.TimeUnit.SECONDS).build()) }.getOrNull()
                                                    detectionStatus.text = "ピントを確認中…"
                                                    fun finishFocus() {
                                                        if (generation != focusGeneration) return
                                                        val current = latestTarget
                                                        val sameTarget = current != null && kotlin.math.hypot(current.x - focusedTarget.x, current.y - focusedTarget.y) < .10 &&
                                                            kotlin.math.abs(current.area - focusedTarget.area) / focusedTarget.area.coerceAtLeast(.0001) < .75 &&
                                                            kotlin.math.hypot(current.x - .5, current.y - .5) <= .18
                                                        if (sameTarget && automatic.isChecked && cameraScreen &&
                                                            preview.isAttachedToWindow && !busy && !isDestroyed &&
                                                            lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
                                                            android.os.SystemClock.elapsedRealtime() - lastSeen < 450) {
                                                            automatic.isChecked = false
                                                            shutter.performClick()
                                                        } else {
                                                            resetGuidance()
                                                            detectionStatus.text = "位置が変わりました。もう一度ガイドの中へ"
                                                        }
                                                    }
                                                    // Skin often cannot satisfy the device's AF-success flag.
                                                    // Allow a bounded settling period, then use fresh positional checks.
                                                    preview.postDelayed({ finishFocus() }, 1400)
                                                    focus?.addListener({
                                                        if (generation != focusGeneration) return@addListener
                                                        if (runCatching { focus.get().isFocusSuccessful }.getOrDefault(false)) finishFocus()
                                                    }, ContextCompat.getMainExecutor(this))
                                                }
                                            }
                                        } else { resetGuidance(); overlay.update(null); detectionStatus.text = "候補を探しています…" }
                                    } else { resetGuidance(); overlay.update(null); detectionStatus.text = "髪と頭皮を一緒に映してね。候補を探しています…" }
                                }
                            }
                        }
                    } catch (error: Exception) {
                        runOnUiThread {
                            if (cameraScreen && preview.isAttachedToWindow && !isDestroyed) {
                                resetGuidance(); overlay.update(null); detectionStatus.text = "検出を再試行中。手動撮影も使えます"
                            }
                        }
                    } finally { image.close() }
                }
                provider!!.unbindAll()
                val group = UseCaseGroup.Builder().addUseCase(cameraPreview).addUseCase(capture!!).addUseCase(analysis)
                preview.viewPort?.let { group.setViewPort(it) }
                try {
                    focusCamera = provider!!.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group.build())
                } catch (error: IllegalArgumentException) {
                    analysis.clearAnalyzer()
                    automatic.isEnabled = false
                    provider!!.unbindAll()
                    provider!!.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, capture)
                    detectionStatus.text = "この端末では検出を起動できません。手動撮影は使えます"
                }
                shutter.text = "● 撮影して保存"
                shutter.isEnabled = true
                shutter.setOnClickListener {
                    val imageCapture = capture ?: return@setOnClickListener
                    if (busy) return@setOnClickListener
                    automatic.isChecked = false
                    resetGuidance()
                    busy = true; shutter.isEnabled = false; back.isEnabled = false; shutter.text = "保存中…"
                    val memo = note.text.toString().trim()
                    val temp = try { File.createTempFile("capture-", ".jpg", cacheDir) } catch (error: Exception) {
                        busy = false; shutter.isEnabled = true; back.isEnabled = true; shutter.text = "● 撮影して保存"
                        toast("撮影用の空き容量を確保できませんでした")
                        return@setOnClickListener
                    }
                    shutterSound.play(android.media.MediaActionSound.SHUTTER_CLICK)
                    imageCapture.targetRotation = preview.display.rotation
                    imageCapture.takePicture(ImageCapture.OutputFileOptions.Builder(temp).build(), worker,
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                                try {
                                    store.save(temp, memo)
                                    runOnUiThread { busy = false; if (!isDestroyed) { toast("記録できたじゃん♡"); showTimeline() } }
                                } catch (error: Exception) { failed() } finally { temp.delete() }
                            }
                            override fun onError(error: ImageCaptureException) { temp.delete(); failed() }
                            private fun failed() {
                                runOnUiThread { busy = false; if (!isDestroyed) {
                                    toast("保存できませんでした。空き容量を確認して、もう一度お試しください")
                                    shutter.isEnabled = true; back.isEnabled = true; shutter.text = "● 撮影して保存"
                                } }
                            }
                        })
                }
            } catch (error: Exception) {
                toast("背面カメラを起動できませんでした")
                showTimeline()
            }
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
            (row.getChildAt(1) as TextView).text = getString(R.string.record_summary, date(record.time), record.note.ifBlank { "今日の1枚 ♡" })
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
    private fun date(time: Long) = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(Date(time))
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
}
