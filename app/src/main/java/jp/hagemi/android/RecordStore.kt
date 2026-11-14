package jp.hagemi.android

import android.content.Context
import android.graphics.*
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Record(val id: String, val time: Long, val note: String, val photo: File, val measurement: Measurement? = null)

/** All records stay in app-private storage. No network or shared gallery access. */
class RecordStore(context: Context) {
    private val folder = File(context.noBackupFilesDir, "records").apply { mkdirs() }
    fun list(): List<Record> = folder.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { file ->
        runCatching {
            val json = JSONObject(file.readText())
            val photo = File(folder, "${file.nameWithoutExtension}.jpg")
            if (!photo.exists()) null else Record(file.nameWithoutExtension, json.getLong("time"), json.optString("note"), photo, readMeasurement(json))
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
    private fun readMeasurement(json: JSONObject): Measurement? = runCatching {
        val item = json.optJSONObject("measurement") ?: return null
        fun region(key: String): Region {
            val box = item.getJSONArray(key)
            return Region(box.getDouble(0), box.getDouble(1), box.getDouble(2), box.getDouble(3)).also { require(it.isValid) }
        }
        val bald = region("bald")
        val reference = if (item.has("reference")) region("reference") else null
        val count = if (item.has("count")) item.getInt("count") else null
        Measurement(bald, reference, count).also { it.estimate }
    }.getOrNull()

    fun saveMeasurement(record: Record, measurement: Measurement) {
        require(measurement.bald.isValid)
        measurement.estimate // Validate the density input before writing.
        val metadata = File(folder, "${record.id}.json")
        val json = JSONObject(metadata.readText())
        fun array(region: Region) = org.json.JSONArray(listOf(region.left, region.top, region.right, region.bottom))
        val item = JSONObject().put("version", 1).put("method", "manual-reference-density")
            .put("bald", array(measurement.bald))
        measurement.reference?.let { item.put("reference", array(it)) }
        measurement.count?.let { item.put("count", it) }
        json.put("measurement", item)
        val atomic = android.util.AtomicFile(metadata)
        val output = atomic.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Exception) { atomic.failWrite(output); throw error }
    }
    fun delete(record: Record) { File(folder, "${record.id}.json").delete(); record.photo.delete() }
}
