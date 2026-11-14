package jp.hagemi.android

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class HairReviewStorageTest {
    private lateinit var folder:File
    private lateinit var store:RecordStore
    @Before fun prepare() {
        val context=RuntimeEnvironment.getApplication()
        folder=File(context.noBackupFilesDir,"records").apply{mkdirs();listFiles().orEmpty().forEach{it.delete()}}
        File(folder,"old.jpg").writeText("photo-placeholder")
        File(folder,"old.json").writeText(JSONObject().put("time",42).put("note","前のメモ").toString())
        store=RecordStore(context)
    }
    private fun review()=HairReview(Region(.2,.2,.8,.8),listOf(HairCandidate(HairPoint(.4f,.4f),listOf(HairPoint(.4f,.4f)),listOf("short"),HairVerdict.HOLD)))
    @Test fun legacyRecordsRemainReadableWithoutReview() {
        val record=store.list().single();assertEquals("前のメモ",record.note);assertNull(record.hairSummary);assertNull(store.loadHairReview(record))
    }
    @Test fun rootReviewAndMeasurementsPreserveEachOtherAndOldMemo() {
        val old=store.list().single();store.saveHairReview(old,review())
        store.saveMeasurement(old,Measurement(Region(.1,.1,.7,.7),null,null))
        val record=store.list().single()
        assertEquals("前のメモ",record.note);assertEquals(42L,record.time)
        assertEquals(1,record.hairSummary!!.hold);assertNotNull(record.measurement)
        assertEquals(review(),store.loadHairReview(record))
        val confirmed=review().copy(candidates=listOf(review().candidates.single().copy(verdict=HairVerdict.CONFIRMED)))
        store.saveHairReview(record,confirmed)
        assertNotNull(store.list().single().measurement);assertEquals(1,store.list().single().hairSummary!!.confirmed)
    }
    @Test fun corruptReviewDoesNotHidePhotoOrMemo() {
        val old=store.list().single();File(folder,"old.roots.json").writeText("broken")
        assertEquals(1,store.list().size);assertEquals("前のメモ",store.list().single().note)
        assertNull(store.loadHairReview(old))
    }
    @Test fun deleteRemovesTheReviewSidecarAsWell() {
        val old=store.list().single();store.saveHairReview(old,review());store.delete(old)
        assertTrue(store.list().isEmpty());assertFalse(File(folder,"old.roots.json").exists())
    }
}
