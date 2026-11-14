package jp.hagemi.android

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.*
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.view.*
import java.util.Locale
import java.util.concurrent.Executors

class MeasurementActivity : ComponentActivity() {
    companion object { private val worker = Executors.newSingleThreadExecutor() }
    private var selection: RegionView? = null
    private var countField: EditText? = null
    private var candidateCount = false
    private var meshEstimate: MeshEstimate? = null
    private val hairReviewScreen = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) setResult(Activity.RESULT_OK)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 12) }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        fun text(value: String, size: Float = 14f) = TextView(this).apply { text = value; textSize = size; setPadding(0, 12, 0, 12) }
        root.addView(text("サイズ推定の準備", 24f))
        val status = text("写真を読み込み中…")
        root.addView(status)
        worker.execute {
            val store = RecordStore(this)
            val record = store.list().find { it.id == intent.getStringExtra("recordId") }
            val reviewedArea = record?.let { store.loadHairReview(it)?.bald }
            val bitmap = record?.let {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(it.photo.path, bounds)
                var sample = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1200) sample *= 2
                BitmapFactory.decodeFile(it.photo.path, BitmapFactory.Options().apply { inSampleSize = sample })
            }
            runOnUiThread {
                if (isDestroyed) { bitmap?.recycle(); return@runOnUiThread }
                if (record == null || bitmap == null) { status.text = "写真を読み込めませんでした"; return@runOnUiThread }
                status.text = "写真を斜めにドラッグして範囲を囲んでね。\n①黄色の楕円：脱毛範囲　②水色の四角：周囲の比較範囲"
                val modes = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
                val baldMode = RadioButton(this).apply { id = View.generateViewId(); text = "①脱毛範囲" }
                val referenceMode = RadioButton(this).apply { id = View.generateViewId(); text = "②比較範囲" }
                modes.addView(baldMode); modes.addView(referenceMode); modes.check(baldMode.id)
                root.addView(modes)
                val image = RegionView(this, bitmap)
                selection = image
                meshEstimate = if (savedInstanceState?.containsKey("meshSpacing") == true)
                    MeshEstimate(savedInstanceState.getDouble("meshSpacing"), savedInstanceState.getInt("meshPoints"))
                    else record.measurement?.mesh
                image.meshEstimate = meshEstimate
                fun savedRegion(key: String): Region? = savedInstanceState?.getDoubleArray(key)?.let { Region(it[0], it[1], it[2], it[3]) }
                image.bald = savedRegion("bald") ?: record.measurement?.bald ?: reviewedArea
                image.reference = savedRegion("reference") ?: record.measurement?.reference
                root.addView(image, LinearLayout.LayoutParams(-1, (360 * resources.displayMetrics.density).toInt()))
                root.addView(Button(this).apply {
                    text = "生え際候補を追跡して確認"
                    setOnClickListener {
                        val bald = image.bald
                        if (bald == null) { Toast.makeText(this@MeasurementActivity, "先に脱毛範囲を黄色い楕円で囲んでください", Toast.LENGTH_LONG).show() }
                        else hairReviewScreen.launch(android.content.Intent(this@MeasurementActivity, HairReviewActivity::class.java)
                            .putExtra("recordId", record.id).putExtra("bald", doubleArrayOf(bald.left, bald.top, bald.right, bald.bottom)))
                    }
                })
                modes.setOnCheckedChangeListener { _, checked -> image.selectingReference = checked == referenceMode.id }
                val count = EditText(this).apply {
                    hint = "比較範囲で確認できた毛の本数（任意）"
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    filters = arrayOf(InputFilter.LengthFilter(5))
                    setText(savedInstanceState?.getString("count") ?: record.measurement?.count?.toString().orEmpty())
                }
                candidateCount = savedInstanceState?.getBoolean("candidateCount") ?: record.measurement?.candidateCount ?: false
                countField = count
                root.addView(count)
                root.addView(text("比較範囲は脱毛範囲の外側、すぐ近くの同じ向きの頭皮へ。横切る毛ではなく、生え際を確認できる毛を数えます。数えられない写真では本数を空欄にしてください。"))
                val result = text("")
                root.addView(result)
                root.addView(text("楕円による面積の近似です。写真内の割合は撮影距離で変わるため、それだけで経過比較はできません。換算値も周囲の入力値に基づく実験的な推定で、実際に抜けた本数ではありません。自動解析で表示する点は生え際の候補です。cm²への換算は未対応です。", 12f))
                fun measurement(): Measurement {
                    val bald = image.bald ?: error("①脱毛範囲を囲んでください")
                    val raw = count.text.toString().trim()
                    val n = if (raw.isEmpty()) null else {
                        val number = raw.toIntOrNull() ?: error("本数を確認してください")
                        require(number in 1..10000) { "本数は1〜10000を入力してください" }
                        number
                    }
                    if (n != null) require(image.reference != null) { "②比較範囲を囲んでください" }
                    return Measurement(bald, image.reference, n, candidateCount, meshEstimate).also { it.estimate }
                }
                fun refresh() {
                    result.text = runCatching {
                        val m = measurement()
                        val fraction = String.format(Locale.JAPAN, "%.2f", AreaEstimate.fraction(m.bald) * 100)
                        "写真内の面積割合：$fraction%（距離に依存）\n" +
                            (m.estimate?.let { if (m.mesh != null) "境界間隔の格子換算：約${AreaEstimate.rounded(it)}点相当（本数未確定）" else if (m.candidateCount) "候補密度の参考換算：約${AreaEstimate.rounded(it)}個相当（本数未確定）" else "推定：約${AreaEstimate.rounded(it)}本分（手動密度）" } ?: "本数推定：比較範囲と本数を入力すると計算します")
                    }.getOrElse { it.message ?: "範囲を確認してください" }
                }
                var requestVersion = 0
                var candidateResult: RootCandidates.Result? = null
                val analysisStatus = text("自動解析は試作です。点が本当の生え際か確認してください")
                val propose = Button(this).apply { text = "脱毛範囲と周囲を自動提案" }
                val analyzeReference = Button(this).apply { text = "選んだ比較範囲を自動カウント" }
                val accept = Button(this).apply { text = "点を確認して候補数を仮入力"; isEnabled = false }
                val boundary = Button(this).apply { text = "境界の間隔と三角格子を解析" }
                val useMesh = Button(this).apply { text = "格子換算を記録に採用"; isEnabled = false }
                var pendingMesh: MeshEstimate? = null
                val histogram = SpacingHistogram(this)
                val binary = Switch(this).apply { text = "比較範囲の二値化を表示" }
                root.addView(propose); root.addView(analyzeReference); root.addView(binary)
                root.addView(analysisStatus); root.addView(accept)
                root.addView(boundary)
                root.addView(histogram, LinearLayout.LayoutParams(-1, (110 * resources.displayMetrics.density).toInt()))
                root.addView(useMesh)
                binary.setOnCheckedChangeListener { _, checked -> image.showBinary = checked; image.invalidate() }
                image.changed = {
                    requestVersion++
                    meshEstimate = null; pendingMesh = null; image.meshEstimate = null; image.spacingResult = null
                    useMesh.isEnabled = false; histogram.result = null; histogram.invalidate()
                    candidateResult = null; image.analysis = null
                    image.binaryPreview?.recycle(); image.binaryPreview = null
                    accept.isEnabled = false
                    count.setText("")
                    analysisStatus.text = "範囲が変わりました。比較範囲を再解析できます"
                    refresh()
                }
                fun runAnalysis(autoRegion: Boolean) {
                    if (!autoRegion && image.reference == null) {
                        analysisStatus.text = "先に②比較範囲を囲んでください"; return
                    }
                    val version = ++requestVersion
                    meshEstimate = null; image.meshEstimate = null
                    histogram.result = null; histogram.invalidate()
                    pendingMesh = null; useMesh.isEnabled = false; image.spacingResult = null
                    val reference = image.reference
                    propose.isEnabled = false; analyzeReference.isEnabled = false; boundary.isEnabled = false; accept.isEnabled = false
                    candidateResult = null; image.analysis = null
                    image.binaryPreview?.recycle(); image.binaryPreview = null; image.invalidate()
                    analysisStatus.text = "解析中…"
                    refresh()
                    worker.execute {
                        val answer = runCatching {
                            val longest = maxOf(bitmap.width, bitmap.height)
                            val w = maxOf(1, bitmap.width * minOf(768, longest) / longest)
                            val h = maxOf(1, bitmap.height * minOf(768, longest) / longest)
                            val small = android.graphics.Bitmap.createScaledBitmap(bitmap, w, h, true)
                            val gray = IntArray(w * h)
                            small.getPixels(gray, 0, w, 0, 0, w, h)
                            if (small !== bitmap) small.recycle()
                            for (i in gray.indices) gray[i] = gray[i] and 255
                            var suggested: Region? = null
                            if (autoRegion) {
                                val dw = maxOf(1, w * 160 / maxOf(160, w, h)); val dh = maxOf(1, h * 160 / maxOf(160, w, h))
                                val smooth = IntArray(dw * dh) { i ->
                                    val cx = (i % dw) * w / dw; val cy = (i / dw) * h / dh
                                    var sum = 0; var n = 0
                                    for (dy in -2..2) for (dx in -2..2) {
                                        val x = (cx + dx * w / dw).coerceIn(0, w - 1)
                                        val y = (cy + dy * h / dh).coerceIn(0, h - 1)
                                        sum += gray[y * w + x]; n++
                                    }
                                    sum / n
                                }
                                val patch = ContrastDetector().detect(smooth, dw, dh)
                                if (patch != null) suggested = Region(patch.left.toDouble() / dw, patch.top.toDouble() / dh,
                                    patch.right.toDouble() / dw, patch.bottom.toDouble() / dh).takeIf { it.isValid }
                            }
                            val roots = if (autoRegion) suggested?.let { RootCandidates.proposeReference(gray, w, h, it) }
                                else reference?.let { RootCandidates.analyze(gray, w, h, it) }
                            Pair(suggested, roots)
                        }
                        runOnUiThread {
                            if (isDestroyed) return@runOnUiThread
                            propose.isEnabled = true; analyzeReference.isEnabled = true; boundary.isEnabled = true
                            if (version != requestVersion) return@runOnUiThread
                            answer.onSuccess { (suggested, roots) ->
                                if (autoRegion) {
                                    image.bald = suggested ?: image.bald
                                    image.reference = roots?.region
                                    count.setText("")
                                }
                                image.binaryPreview?.recycle(); image.binaryPreview = null
                                image.analysis = roots; candidateResult = roots
                                if (roots != null) {
                                    image.binaryPreview = android.graphics.Bitmap.createBitmap(
                                        IntArray(roots.binary.size) { if (roots.binary[it]) android.graphics.Color.BLACK else android.graphics.Color.WHITE },
                                        roots.width, roots.height, android.graphics.Bitmap.Config.ARGB_8888)
                                    val countable = roots.visibleSkinFraction >= .5 && roots.points.size in 1..499 &&
                                        image.bald?.let { !AreaEstimate.overlaps(it, roots.region) } == true
                                    analysisStatus.text = "生え際候補：${roots.points.size}個（確定本数ではありません）\n" +
                                        if (countable) "紫の点を確認してください。線の交差や影も候補になることがあります" else "密度の換算には不適です。明るい頭皮と生え際が見える比較範囲を選び直してください"
                                    accept.isEnabled = countable
                                } else {
                                    accept.isEnabled = false
                                    analysisStatus.text = if (autoRegion && suggested == null) "脱毛範囲を提案できませんでした。手で囲んで比較範囲を解析できます" else "周囲に比較できる場所が見つかりません。②比較範囲を手で指定してください"
                                }
                                image.invalidate(); refresh()
                            }.onFailure { analysisStatus.text = "解析できませんでした。範囲を小さくして試してください" }
                        }
                    }
                }
                boundary.setOnClickListener {
                    val bald = image.bald
                    if (bald == null) { analysisStatus.text = "先に脱毛範囲を提案するか、黄色い楕円で囲んでください"; return@setOnClickListener }
                    val version = ++requestVersion
                    meshEstimate = null; image.meshEstimate = null; image.spacingResult = null
                    histogram.result = null; histogram.invalidate()
                    refresh()
                    boundary.isEnabled = false; propose.isEnabled = false; analyzeReference.isEnabled = false
                    accept.isEnabled = false; candidateResult = null
                    useMesh.isEnabled = false; pendingMesh = null
                    analysisStatus.text = "境界帯を解析中…"
                    worker.execute {
                        val outcome = runCatching {
                            val longest = maxOf(bitmap.width, bitmap.height)
                            val w = maxOf(1, bitmap.width * minOf(768, longest) / longest)
                            val h = maxOf(1, bitmap.height * minOf(768, longest) / longest)
                            val small = android.graphics.Bitmap.createScaledBitmap(bitmap, w, h, true)
                            val gray = IntArray(w * h); small.getPixels(gray, 0, w, 0, 0, w, h)
                            if (small !== bitmap) small.recycle()
                            for (i in gray.indices) gray[i] = gray[i] and 255
                            val rx = (bald.right - bald.left) / 2; val ry = (bald.bottom - bald.top) / 2
                            val region = Region((bald.left - rx * .5).coerceAtLeast(0.0), (bald.top - ry * .5).coerceAtLeast(0.0),
                                (bald.right + rx * .5).coerceAtMost(1.0), (bald.bottom + ry * .5).coerceAtMost(1.0))
                            val roots = RootCandidates.analyze(gray, w, h, region)
                            Pair(roots, BoundarySpacing.analyze(roots.points, bald, w, h))
                        }
                        runOnUiThread {
                            if (isDestroyed) return@runOnUiThread
                            boundary.isEnabled = true; propose.isEnabled = true; analyzeReference.isEnabled = true
                            if (version != requestVersion) return@runOnUiThread
                            outcome.onSuccess { (roots, spacing) ->
                                image.binaryPreview?.recycle()
                                image.binaryPreview = android.graphics.Bitmap.createBitmap(
                                    IntArray(roots.binary.size) { if (roots.binary[it]) android.graphics.Color.BLACK else android.graphics.Color.WHITE },
                                    roots.width, roots.height, android.graphics.Bitmap.Config.ARGB_8888)
                                image.analysis = roots; image.spacingResult = spacing
                                candidateResult = null; accept.isEnabled = false
                                histogram.result = spacing; histogram.invalidate()
                                pendingMesh = spacing.estimate(); image.meshEstimate = pendingMesh
                                useMesh.isEnabled = pendingMesh != null
                                val distance = spacing.spacingPx
                                analysisStatus.text = "境界帯の集計対象：${spacing.points.size}個（緑）、他の候補は紫\n" +
                                    "分布の刻み：${String.format(Locale.JAPAN, "%.1f", spacing.binWidth)}px、近傍距離${spacing.distances.size}件\n" +
                                    if (distance == null) spacing.reason.orEmpty() else {
                                        val estimate = pendingMesh!!.amount(bald)
                                        "基準間隔：約${String.format(Locale.JAPAN, "%.1f", distance)}解析px\n" +
                                        "三角格子換算：約${AreaEstimate.rounded(estimate)}点相当（均一配置の仮定）" +
                                        if (estimate > 1500) "\n格子は1500点を超えるため表示を省略します" else ""
                                    }
                                image.invalidate()
                            }.onFailure { analysisStatus.text = "境界解析に失敗しました。範囲を確認してください" }
                        }
                    }
                }
                useMesh.setOnClickListener {
                    val mesh = pendingMesh ?: return@setOnClickListener
                    count.setText("")
                    meshEstimate = mesh; image.meshEstimate = mesh
                    analysisStatus.text = "格子換算を採用しました。下の保存ボタンで記録します。均一配置を仮定した参考値です"
                    refresh()
                }
                propose.setOnClickListener { runAnalysis(true) }
                analyzeReference.setOnClickListener { runAnalysis(false) }
                accept.setOnClickListener {
                    candidateResult?.let { roots ->
                        image.reference = roots.region
                        count.setText(roots.points.size.toString())
                        candidateCount = true
                        analysisStatus.text = "候補数を仮入力しました。誤検出を確認して本数を修正してから保存してください"
                        refresh()
                    }
                }
                count.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { candidateCount = false; meshEstimate = null; refresh() }
                    override fun afterTextChanged(s: Editable?) {}
                })
                refresh()
                val save = Button(this).apply { text = "範囲と推定を保存" }
                root.addView(save)
                save.setOnClickListener {
                    val measurement = runCatching { measurement() }.getOrElse {
                        Toast.makeText(this, it.message, Toast.LENGTH_LONG).show(); return@setOnClickListener
                    }
                    save.isEnabled = false
                    worker.execute {
                        val saved = runCatching { store.saveMeasurement(record, measurement) }
                        runOnUiThread { if (!isDestroyed) {
                            if (saved.isSuccess) { setResult(Activity.RESULT_OK); finish() }
                            else { save.isEnabled = true; Toast.makeText(this, "保存できませんでした。空き容量を確認してください", Toast.LENGTH_LONG).show() }
                        } }
                    }
                }
                root.addView(Button(this).apply { text = "保存せず戻る"; setOnClickListener { finish() } })
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        fun save(key: String, region: Region?) { region?.let { outState.putDoubleArray(key, doubleArrayOf(it.left, it.top, it.right, it.bottom)) } }
        save("bald", selection?.bald); save("reference", selection?.reference)
        meshEstimate?.let { outState.putDouble("meshSpacing", it.spacing); outState.putInt("meshPoints", it.points) }
        outState.putBoolean("candidateCount", candidateCount)
        outState.putString("count", countField?.text?.toString().orEmpty())
        super.onSaveInstanceState(outState)
    }
}
