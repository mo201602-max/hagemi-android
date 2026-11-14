package jp.hagemi.android

import android.app.Application
import android.content.Intent
import android.graphics.*
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], shadows=[PixelRegionDecoder::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HairReviewUiTest {
    private fun descendants(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{descendants(v.getChildAt(it))} else emptyList()
    private fun waitFor(predicate:()->Boolean) {
        repeat(500) {shadowOf(android.os.Looper.getMainLooper()).idle();if(predicate())return;Thread.sleep(10)}
        error("UI operation did not complete")
    }
    @Test fun verdictSelectionAndRotationPreserveSavedReviewWithoutAlteringPhoto() {
        val context=RuntimeEnvironment.getApplication()
        val folder=File(context.noBackupFilesDir,"records").apply{mkdirs();listFiles().orEmpty().forEach{it.delete()}}
        val picture=Bitmap.createBitmap(160,160,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.GRAY)}
        File(folder,"ui.jpg").outputStream().use{picture.compress(Bitmap.CompressFormat.JPEG,95,it)};picture.recycle()
        File(folder,"ui.json").writeText("{\"time\":42,\"note\":\"保持するメモ\"}")
        val store=RecordStore(context);val record=store.list().single()
        val area=Region(.2,.2,.8,.8)
        store.saveHairReview(record,HairReview(area,listOf(
            HairCandidate(HairPoint(.4f,.4f),listOf(HairPoint(.4f,.4f)),emptyList(),HairVerdict.CONFIRMED),
            HairCandidate(HairPoint(.6f,.6f),listOf(HairPoint(.6f,.6f)),emptyList(),HairVerdict.REJECTED))))
        val originalBytes=record.photo.readBytes()
        val intent=Intent(context,HairReviewActivity::class.java).putExtra("recordId","ui")
        val controller=Robolectric.buildActivity(HairReviewActivity::class.java,intent).setup()
        var activity=controller.get()
        waitFor{descendants(activity.window.decorView).filterIsInstance<TextView>().any{it.text.contains("候補1 / 2")}}
        fun button(text:String)=descendants(activity.window.decorView).filterIsInstance<Button>().first{it.text==text}
        button("次へ").performClick();button("前へ").performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        var spinner=descendants(activity.window.decorView).filterIsInstance<Spinner>().single()
        assertEquals(HairVerdict.CONFIRMED.ordinal,spinner.selectedItemPosition)
        spinner.setSelection(HairVerdict.HOLD.ordinal)
        waitFor{descendants(activity.window.decorView).filterIsInstance<TextView>().any{it.text.contains("保留1")}}
        controller.recreate();activity=controller.get()
        waitFor{descendants(activity.window.decorView).filterIsInstance<TextView>().any{it.text.contains("候補1 / 2")}}
        spinner=descendants(activity.window.decorView).filterIsInstance<Spinner>().single()
        assertEquals(HairVerdict.HOLD.ordinal,spinner.selectedItemPosition)
        button("判定を保存").performClick()
        waitFor{store.loadHairReview(record)?.candidates?.first()?.verdict==HairVerdict.HOLD}
        val persisted=store.loadHairReview(record)!!
        assertEquals(HairVerdict.REJECTED,persisted.candidates[1].verdict)
        assertEquals("保持するメモ",store.list().single().note)
        assertArrayEquals(originalBytes,record.photo.readBytes())
        controller.pause().stop().destroy()
        // A separately saved size estimate may use a different ellipse. Reopening the review
        // must use its own saved area and must not silently replace the reviewed points.
        store.saveMeasurement(record,Measurement(Region(.1,.1,.9,.9),null,null))
        val reopened=Robolectric.buildActivity(HairReviewActivity::class.java,intent).setup()
        activity=reopened.get()
        waitFor{descendants(activity.window.decorView).filterIsInstance<TextView>().any{it.text.contains("候補1 / 2")}}
        spinner=descendants(activity.window.decorView).filterIsInstance<Spinner>().single()
        assertEquals(HairVerdict.HOLD.ordinal,spinner.selectedItemPosition)
        assertEquals(area,store.loadHairReview(record)!!.bald)
        reopened.pause().stop().destroy()
    }
}
