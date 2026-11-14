package jp.hagemi.android

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Record(val id: String, val time: Long, val note: String, val photo: File, val measurement: Measurement? = null, val hairSummary: HairReviewSummary? = null)

/** All records stay in app-private storage. No network or shared gallery access. */
class RecordStore(context: Context) {
    companion object { private val metadataLock = Any() }
    private val folder = File(context.noBackupFilesDir, "records").apply { mkdirs() }
    fun list(): List<Record> = folder.listFiles().orEmpty().filter { it.extension == "json" && !it.name.endsWith(".roots.json") }.mapNotNull { file ->
        runCatching {
            val json = JSONObject(file.readText())
            val photo = File(folder, "${file.nameWithoutExtension}.jpg")
            if (!photo.exists()) null else Record(file.nameWithoutExtension, json.getLong("time"), json.optString("note"), photo, readMeasurement(json), readHairSummary(json))
        }.getOrNull()
    }.sortedByDescending { it.time }

    fun save(source: File, note: String): Record {
        val id = UUID.randomUUID().toString()
        val photo = File(folder, "$id.jpg")
        val metadata = File(folder, "$id.json")
        try {
            val memory = contextMemoryBudget
            PhotoProcessor.saveGrayscale(source, photo, memory)
            val time = System.currentTimeMillis()
            metadata.writeText(JSONObject().put("time", time).put("note", note).toString())
            return Record(id, time, note, photo)
        } catch (error: Exception) {
            photo.delete(); metadata.delete(); throw error
        }
    }
    private val contextMemoryBudget = if ((context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).let { it.isLowRamDevice || it.memoryClass < 192 }) 4_000_000 else 9_000_000
    private fun readHairSummary(json: JSONObject): HairReviewSummary? = runCatching {
        val item = json.optJSONObject("hairSummary") ?: return null
        HairReviewSummary(item.getInt("total"), item.getInt("confirmed"), item.getInt("hold"), item.getInt("rejected")).also {
            require(it.total in 0..HairReviewCodec.MAX_CANDIDATES && it.confirmed >= 0 && it.hold >= 0 && it.rejected >= 0 && it.confirmed + it.hold + it.rejected <= it.total)
        }
    }.getOrNull()
    fun loadHairReview(record: Record): HairReview? = synchronized(metadataLock) {
        val file = File(folder, "${record.id}.roots.json")
        if (!file.exists() && !File("${file.path}.bak").exists()) return@synchronized null
        runCatching { android.util.AtomicFile(file).openRead().use { stream ->
            require(stream.channel.size() <= 3_000_000)
            stream.bufferedReader().use { HairReviewCodec.decode(it.readText()) }
        } }.getOrNull()
    }
    fun saveHairReview(record: Record, review: HairReview) = synchronized(metadataLock) {
        val encoded = HairReviewCodec.encode(review)
        fun write(file: File, value: String) {
            val atomic = android.util.AtomicFile(file); val stream = atomic.startWrite()
            try { stream.write(value.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
            catch (error: Exception) { atomic.failWrite(stream); throw error }
        }
        val metadata = File(folder, "${record.id}.json")
        val json = JSONObject(metadata.readText()); val summary = review.summary()
        write(File(folder, "${record.id}.roots.json"), encoded)
        json.put("hairSummary", JSONObject().put("total", summary.total).put("confirmed", summary.confirmed).put("hold", summary.hold).put("rejected", summary.rejected))
        write(metadata, json.toString())
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
        Measurement(bald, reference, count, item.optBoolean("candidateCount", false),
            item.optJSONObject("mesh")?.let { MeshEstimate(it.getDouble("spacing"), it.getInt("points")).also { m -> require(m.isValid) } }).also { it.estimate }
    }.getOrNull()

    fun saveMeasurement(record: Record, measurement: Measurement) = synchronized(metadataLock) {
        require(measurement.bald.isValid)
        measurement.estimate // Validate the density input before writing.
        val metadata = File(folder, "${record.id}.json")
        val json = JSONObject(metadata.readText())
        fun array(region: Region) = org.json.JSONArray(listOf(region.left, region.top, region.right, region.bottom))
        val item = JSONObject().put("version", 1).put("method", if (measurement.mesh != null) "boundary-triangular-spacing" else if (measurement.candidateCount) "unverified-component-density" else "manual-reference-density")
            .put("bald", array(measurement.bald))
        measurement.reference?.let { item.put("reference", array(it)) }
        measurement.count?.let { item.put("count", it) }
        item.put("candidateCount", measurement.candidateCount)
        measurement.mesh?.let { item.put("mesh", JSONObject().put("spacing", it.spacing).put("points", it.points)) }
        json.put("measurement", item)
        val atomic = android.util.AtomicFile(metadata)
        val output = atomic.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Exception) { atomic.failWrite(output); throw error }
    }
    fun delete(record: Record) = synchronized(metadataLock) { android.util.AtomicFile(File(folder, "${record.id}.roots.json")).delete(); android.util.AtomicFile(File(folder, "${record.id}.json")).delete(); record.photo.delete() }
}
