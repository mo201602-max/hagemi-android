package jp.hagemi.android

import org.json.JSONArray
import org.json.JSONObject

enum class HairVerdict { UNREVIEWED, CONFIRMED, HOLD, REJECTED }
data class HairPoint(val x: Float, val y: Float) {
    val valid: Boolean get() = x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f
}
data class HairCandidate(val point: HairPoint, val path: List<HairPoint>, val flags: List<String>,
                         var verdict: HairVerdict = HairVerdict.UNREVIEWED)
data class HairReview(val bald: Region, val candidates: List<HairCandidate>, val truncated: Boolean = false) {
    fun summary() = HairReviewSummary(candidates.size, candidates.count { it.verdict == HairVerdict.CONFIRMED },
        candidates.count { it.verdict == HairVerdict.HOLD }, candidates.count { it.verdict == HairVerdict.REJECTED })
}
data class HairReviewSummary(val total: Int, val confirmed: Int, val hold: Int, val rejected: Int) {
    val text: String get() = "生え際：確認$confirmed・保留$hold・未確認${total - confirmed - hold - rejected}"
}

object HairReviewCodec {
    const val MAX_CANDIDATES = 400
    const val MAX_PATH_POINTS = 120
    fun validate(review: HairReview) {
        require(review.bald.isValid && review.candidates.size <= MAX_CANDIDATES)
        review.candidates.forEach { c ->
            require(c.point.valid && c.path.size <= MAX_PATH_POINTS && c.path.all { it.valid })
            require(c.flags.size <= 8 && c.flags.all { it.length <= 40 })
        }
    }
    fun encode(review: HairReview): String {
        validate(review)
        fun point(p: HairPoint) = JSONArray().put(p.x.toDouble()).put(p.y.toDouble())
        val array = JSONArray()
        review.candidates.forEach { c ->
            val path = JSONArray(); c.path.forEach { path.put(point(it)) }
            array.put(JSONObject().put("point", point(c.point)).put("path", path)
                .put("flags", JSONArray(c.flags)).put("verdict", c.verdict.name))
        }
        return JSONObject().put("version", 1).put("method", "endpoint-contact-review-v1")
            .put("bald", JSONArray(listOf(review.bald.left, review.bald.top, review.bald.right, review.bald.bottom)))
            .put("truncated", review.truncated).put("candidates", array).toString()
    }
    fun decode(value: String): HairReview {
        val json = JSONObject(value); require(json.getInt("version") == 1 && json.getString("method") == "endpoint-contact-review-v1")
        val box = json.getJSONArray("bald"); require(box.length() == 4)
        val bald = Region(box.getDouble(0), box.getDouble(1), box.getDouble(2), box.getDouble(3))
        val input = json.getJSONArray("candidates"); require(input.length() <= MAX_CANDIDATES)
        fun point(a: JSONArray): HairPoint {
            require(a.length() == 2)
            return HairPoint(a.getDouble(0).toFloat(), a.getDouble(1).toFloat())
        }
        val candidates = (0 until input.length()).map { i ->
            val c = input.getJSONObject(i); val path = c.getJSONArray("path"); val flags = c.getJSONArray("flags")
            require(path.length() <= MAX_PATH_POINTS && flags.length() <= 8)
            HairCandidate(point(c.getJSONArray("point")), (0 until path.length()).map { point(path.getJSONArray(it)) },
                (0 until flags.length()).map { flags.getString(it) }, HairVerdict.valueOf(c.getString("verdict")))
        }
        return HairReview(bald, candidates, json.optBoolean("truncated")).also { validate(it) }
    }
}
