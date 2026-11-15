package jp.hagemi.android

import android.app.Activity
import android.app.AlertDialog
import android.graphics.*
import android.os.Bundle
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.core.view.*
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class HairReviewState:ViewModel() {
    var review:HairReview?=null
    var dirty=false
    var index=0
}
class HairReviewActivity:ComponentActivity() {
    private val retained by viewModels<HairReviewState>()
    companion object {private val worker=Executors.newSingleThreadExecutor()}
    private val cancelled=AtomicBoolean()
    @Volatile private var previewVersion=0
    private var review:HairReview?=null
    private var dirty=false
    private var analyzing=false
    private var saving=false
    private lateinit var picture:HairReviewView
    private lateinit var summary:TextView
    private lateinit var status:TextView
    private lateinit var previous:Button
    private lateinit var next:Button
    private lateinit var verdict:Spinner
    private lateinit var save:Button
    private lateinit var detect:Button
    private var selecting=false
    private var index=0
    private var record:Record?=null
    private var photoW=1;private var photoH=1
    private var bald:Region?=null
    private var modeDetail=true
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(16),0,dp(16),0)}
        setContentView(root);WindowCompat.setDecorFitsSystemWindows(window,false)
        ViewCompat.setOnApplyWindowInsetsListener(root){v,insets->val b=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout());v.setPadding(dp(16)+b.left,b.top,dp(16)+b.right,b.bottom);insets}
        fun text(value:String,size:Float=14f)=TextView(this).apply{text=value;textSize=size;setPadding(0,dp(6),0,dp(6))}
        root.addView(text("生え際を確認",24f))
        summary=text("読み込み中…");root.addView(summary)
        status=text("自動の点は候補です。拡大写真を見て確認・保留・除外を選びます。",12f);root.addView(status)
        picture=HairReviewView(this);root.addView(picture,LinearLayout.LayoutParams(-1,0,1f))
        val row=LinearLayout(this)
        val mode=Button(this).apply{text="全体図";setOnClickListener {modeDetail=!modeDetail;text=if(modeDetail)"全体図" else "拡大";loadPreview()}}
        val marks=Switch(this).apply{text="印を表示";isChecked=true;setOnCheckedChangeListener{_,on->picture.showMarks=on;picture.invalidate()}}
        row.addView(mode);row.addView(marks);root.addView(row)
        val navigation=LinearLayout(this)
        previous=Button(this).apply{text="前へ";setOnClickListener{select(index-1)}}
        next=Button(this).apply{text="次へ";setOnClickListener{select(index+1)}}
        navigation.addView(previous,LinearLayout.LayoutParams(0,-2,1f));navigation.addView(next,LinearLayout.LayoutParams(0,-2,1f));root.addView(navigation)
        verdict=Spinner(this).apply {adapter=ArrayAdapter(this@HairReviewActivity,android.R.layout.simple_spinner_dropdown_item,listOf("未確認","生え際と確認","保留","除外"))}
        root.addView(verdict)
        verdict.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?){}
            override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:android.view.View?,position:Int,id:Long) {
                if(selecting||analyzing||saving||position!=verdict.selectedItemPosition)return
                val candidate=review?.candidates?.getOrNull(index)?:return
                val value=HairVerdict.entries[position]
                if(candidate.verdict!=value){candidate.verdict=value;dirty=true;update()}
            }
        }
        val actions=LinearLayout(this)
        detect=Button(this).apply{text="候補を検出";setOnClickListener {
            if(analyzing){cancelled.set(true);return@setOnClickListener}
            if(review!=null)AlertDialog.Builder(this@HairReviewActivity).setMessage("候補と判定を置き換えて再検出しますか？保存前の判定は戻せません。")
                .setPositiveButton("再検出"){_,_->runDetection()}.setNegativeButton("戻る",null).show()
            else runDetection()
        }}
        save=Button(this).apply{text="判定を保存";setOnClickListener{saveReview()}}
        actions.addView(detect,LinearLayout.LayoutParams(0,-2,1f));actions.addView(save,LinearLayout.LayoutParams(0,-2,1f));root.addView(actions)
        picture.onSelect={i->select(i)}
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true){override fun handleOnBackPressed(){
            if(dirty)AlertDialog.Builder(this@HairReviewActivity).setMessage("判定を保存せず戻りますか？").setPositiveButton("戻る"){_,_->cancelled.set(true);finish()}.setNegativeButton("続ける",null).show()
            else {cancelled.set(true);finish()}
        }})
        val draft=savedInstanceState?.getString("review")
        index=if(retained.review!=null)retained.index else savedInstanceState?.getInt("index")?:0
        dirty=if(retained.review!=null)retained.dirty else savedInstanceState?.getBoolean("dirty")?:false
        val passed=intent.getDoubleArrayExtra("bald")
        worker.execute {
            val store=RecordStore(this)
            val found=store.list().find{it.id==intent.getStringExtra("recordId")}
            val existing=found?.let{store.loadHairReview(it)}
            val restored=draft?.let{runCatching{HairReviewCodec.decode(it)}.getOrNull()}
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};found?.let{BitmapFactory.decodeFile(it.photo.path,bounds)}
            runOnUiThread {
                if(isDestroyed)return@runOnUiThread
                if(found==null||bounds.outWidth<=0){summary.text="写真を読み込めませんでした";setControls(false);return@runOnUiThread}
                record=found;photoW=bounds.outWidth;photoH=bounds.outHeight
                bald=passed?.takeIf{it.size==4}?.let{Region(it[0],it[1],it[2],it[3])}?.takeIf{it.isValid}?:existing?.bald?:found.measurement?.bald
                review=retained.review?:restored?:existing
                // A newly drawn region requires a new review rather than silently reusing old candidate coordinates.
                if(review!=null&&bald!=null&&review!!.bald!=bald){review=null;dirty=false}
                if(review==null&&found.hairSummary!=null&&existing==null) {
                    update(false);loadPreview();status.text="保存済みの判定を読み込めませんでした。必要なら候補を再検出できます。保存するまで以前の判定ファイルは残ります。"
                } else if(review==null)runDetection() else {update();loadPreview()}
            }
        }
        setControls(false)
    }
    private fun runDetection() {
        val photo=record?:return
        val area=bald?:run{update(false);loadPreview();status.text="サイズ推定画面で脱毛範囲を囲んでください";return}
        cancelled.set(false);analyzing=true;setControls(false);detect.isEnabled=true;detect.text="中止"
        status.text="生え際の候補を探しています…"
        worker.execute {
            val outcome=runCatching {HairPhotoAnalysis.analyze(photo.photo,area,cancelled){n,total->runOnUiThread{if(!isDestroyed&&analyzing)status.text="解析中 $n / $total 区画"}}}
            runOnUiThread {
                if(isDestroyed)return@runOnUiThread
                analyzing=false;detect.text="再検出"
                outcome.onSuccess {review=it;index=0;dirty=true;update();loadPreview()}
                    .onFailure {status.text=if(it is CancellationException)"解析を中止しました" else it.message?:"解析できませんでした。範囲を小さくして試してください";update(false)}
            }
        }
    }
    private fun select(value:Int){if(analyzing||saving)return;val count=review?.candidates?.size?:0;if(value !in 0 until count)return;index=value;update();loadPreview()}
    private fun update(replaceStatus:Boolean=true) {
        val r=review;summary.text=r?.summary()?.text?:"未解析"
        picture.candidates=r?.candidates?:emptyList();picture.selected=index;picture.invalidate()
        val c=r?.candidates?.getOrNull(index)
        selecting=true;verdict.setSelection(c?.verdict?.ordinal?:0,false);selecting=false
        if(replaceStatus)status.text=when {
            r==null->"未解析です"
            r.candidates.isEmpty()->"候補がありません。範囲や写真の鮮明さを確認してください"
            else->"候補${index+1} / ${r.candidates.size}　"+(if(r.truncated)"上限到達・範囲を狭めてください\n" else "")+(c?.flags?.map{flagText(it)}?.joinToString("・")?.ifBlank{"拡大して生え際か確認してください"}?:"")
        }
        retained.review=review;retained.dirty=dirty;retained.index=index
        setControls(!analyzing&&!saving)
    }
    private fun setControls(enabled:Boolean){
        detect.isEnabled=enabled&&record!=null&&bald!=null
        save.isEnabled=enabled&&review!=null
        verdict.isEnabled=enabled&&review?.candidates?.isNotEmpty()==true
        previous.isEnabled=enabled&&index>0
        next.isEnabled=enabled&&index+1<(review?.candidates?.size?:0)
    }
    @Suppress("DEPRECATION")
    private fun loadPreview() {
        val photo=record?:return;val request=++previewVersion
        val c=review?.candidates?.getOrNull(index);val detail=modeDetail&&c!=null
        worker.execute {
            if(request!=previewVersion)return@execute
            val result=runCatching {
                if(detail) {
                    val radius=(maxOf(photoW,photoH)/3000f*100).toInt().coerceIn(40, 300)
                    val x=(c!!.point.x*photoW).toInt();val y=(c.point.y*photoH).toInt()
                    val crop=Rect(maxOf(0,x-radius),maxOf(0,y-radius),minOf(photoW,x+radius),minOf(photoH,y+radius))
                    val decoder=requireNotNull(BitmapRegionDecoder.newInstance(photo.photo.path,false))
                    try {Pair(requireNotNull(decoder.decodeRegion(crop,BitmapFactory.Options())),crop)} finally {decoder.recycle()}
                } else {
                    var sample=1;while(maxOf(photoW,photoH)/sample>1000)sample*=2
                    Pair(requireNotNull(BitmapFactory.decodeFile(photo.photo.path,BitmapFactory.Options().apply{inSampleSize=sample})),null)
                }
            }
            runOnUiThread {
                if(isDestroyed||request!=previewVersion){result.getOrNull()?.first?.recycle();return@runOnUiThread}
                result.onSuccess{picture.bitmap=it.first;picture.crop=it.second;picture.detail=detail;picture.photoWidth=photoW;picture.photoHeight=photoH;picture.invalidate()}
                    .onFailure{status.text="写真を読み込めませんでした。全体図への切り替えや写真の再取り込みを試してください"}
            }
        }
    }
    private fun saveReview() {
        val r=review?:return;val photo=record?:return
        saving=true;setControls(false)
        worker.execute {
            val outcome=runCatching{RecordStore(this).saveHairReview(photo,r)}
            runOnUiThread {if(!isDestroyed){
                saving=false
                outcome.onSuccess {dirty=false;setResult(Activity.RESULT_OK);Toast.makeText(this,"判定を保存しました",Toast.LENGTH_SHORT).show();update()}
                    .onFailure {Toast.makeText(this,"保存できませんでした。空き容量を確認してください",Toast.LENGTH_LONG).show();update()}
            }}
        }
    }
    override fun onSaveInstanceState(state:Bundle){
        review?.let{val json=HairReviewCodec.encode(it);if(json.length<100_000)state.putString("review",json)}
        state.putInt("index",index);state.putBoolean("dirty",dirty);super.onSaveInstanceState(state)
    }
    override fun onDestroy(){cancelled.set(true);previewVersion++;super.onDestroy()}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun flagText(flag:String)=when(flag){"short"->"短い毛の線";"taper"->"先細りの形";"scalp_unclear"->"周囲の地肌を要確認";"overlap"->"重なりの可能性";"width_unknown"->"太さを判定しにくい";else->"要確認"}
}
