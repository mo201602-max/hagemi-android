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
            val bitmap = record?.let { BitmapFactory.decodeFile(it.photo.path) }
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
                fun savedRegion(key: String): Region? = savedInstanceState?.getDoubleArray(key)?.let { Region(it[0], it[1], it[2], it[3]) }
                image.bald = savedRegion("bald") ?: record.measurement?.bald
                image.reference = savedRegion("reference") ?: record.measurement?.reference
                root.addView(image, LinearLayout.LayoutParams(-1, (360 * resources.displayMetrics.density).toInt()))
                modes.setOnCheckedChangeListener { _, checked -> image.selectingReference = checked == referenceMode.id }
                val count = EditText(this).apply {
                    hint = "比較範囲で確認できた毛の本数（任意）"
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    filters = arrayOf(InputFilter.LengthFilter(5))
                    setText(savedInstanceState?.getString("count") ?: record.measurement?.count?.toString().orEmpty())
                }
                countField = count
                root.addView(count)
                root.addView(text("比較範囲は脱毛範囲の外側、すぐ近くの同じ向きの頭皮へ。横切る毛ではなく、生え際を確認できる毛を数えます。数えられない写真では本数を空欄にしてください。"))
                val result = text("")
                root.addView(result)
                root.addView(text("楕円による面積の近似です。写真内の割合は撮影距離で変わるため、それだけで経過比較はできません。本数も手動で数えた密度に基づく実験的な推定で、実際に抜けた本数ではありません。cm²への換算・毛の自動検出は未対応です。", 12f))
                fun measurement(): Measurement {
                    val bald = image.bald ?: error("①脱毛範囲を囲んでください")
                    val raw = count.text.toString().trim()
                    val n = if (raw.isEmpty()) null else {
                        val number = raw.toIntOrNull() ?: error("本数を確認してください")
                        require(number in 1..10000) { "本数は1〜10000を入力してください" }
                        number
                    }
                    if (n != null) require(image.reference != null) { "②比較範囲を囲んでください" }
                    return Measurement(bald, image.reference, n).also { it.estimate }
                }
                fun refresh() {
                    result.text = runCatching {
                        val m = measurement()
                        val fraction = String.format(Locale.JAPAN, "%.2f", AreaEstimate.fraction(m.bald) * 100)
                        "写真内の面積割合：$fraction%（距離に依存）\n" +
                            (m.estimate?.let { "推定：約${AreaEstimate.rounded(it)}本分（手動密度）" } ?: "本数推定：比較範囲と本数を入力すると計算します")
                    }.getOrElse { it.message ?: "範囲を確認してください" }
                }
                image.changed = { refresh() }
                count.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refresh() }
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
        outState.putString("count", countField?.text?.toString().orEmpty())
        super.onSaveInstanceState(outState)
    }
}
