package jp.hagemi.android

import android.content.Context
import android.graphics.*
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Record(val id: String, val time: Long, val note: String, val photo: File)

/** All records stay in app-private storage. No network or shared gallery access. */
class RecordStore(context: Context) {
    private val folder = File(context.noBackupFilesDir, "records").apply { mkdirs() }
    fun list(): List<Record> = folder.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { file ->
        runCatching {
            val json = JSONObject(file.readText())
            val photo = File(folder, "${file.nameWithoutExtension}.jpg")
            if (!photo.exists()) null else Record(file.nameWithoutExtension, json.getLong("time"), json.optString("note"), photo)
        }.getOrNull()
    }.sortedByDescending { it.time }

    fun save(source: File, note: String): Record {
        val id = UUID.randomUUID().toString()
        val photo = File(folder, "$id.jpg")
        val metadata = File(folder, "$id.json")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        require(bounds.outWidth > 0) { "画像を読み込めませんでした" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        val bitmap = requireNotNull(BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample }))
        try {
            val orientation = ExifInterface(source).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                }
            }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            try {
                val gray = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
                try {
                    Canvas(gray).drawBitmap(rotated, 0f, 0f, Paint().apply {
                        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                    })
                    photo.outputStream().use { check(gray.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
                } finally { gray.recycle() }
            } finally { if (rotated !== bitmap) rotated.recycle() }
            val time = System.currentTimeMillis()
            metadata.writeText(JSONObject().put("time", time).put("note", note).toString())
            return Record(id, time, note, photo)
        } catch (error: Exception) {
            photo.delete(); metadata.delete(); throw error
        } finally { bitmap.recycle() }
    }
    fun delete(record: Record) { File(folder, "${record.id}.json").delete(); record.photo.delete() }
}
