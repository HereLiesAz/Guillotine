package com.hereliesaz.guillotine.ai

import com.hereliesaz.guillotine.model.EditAction
import com.hereliesaz.guillotine.model.EditSegment
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses keep/remove segments from model text. Models are prompted to emit either
 * a bare JSON array or a `{"segments": [...]}` object, with times in **seconds**;
 * we convert to milliseconds. Tolerates ```json code fences.
 */
object SegmentJson {

    fun parse(raw: String): List<EditSegment> {
        val text = strip(raw)
        val arr = when {
            text.startsWith("[") -> JSONArray(text)
            else -> JSONObject(text).optJSONArray("segments") ?: JSONArray()
        }
        val out = mutableListOf<EditSegment>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val start = (o.optDouble("start", 0.0) * 1000).toLong()
            val end = (o.optDouble("end", 0.0) * 1000).toLong()
            if (end <= start) continue
            val action = if (o.optString("action", "keep").equals("remove", true)) EditAction.REMOVE else EditAction.KEEP
            out += EditSegment(start, end, action, o.optString("reason", ""))
        }
        return out
    }

    private fun strip(raw: String): String =
        raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
}
