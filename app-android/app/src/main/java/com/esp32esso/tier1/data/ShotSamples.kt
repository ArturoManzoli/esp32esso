package com.esp32esso.tier1.data

import com.esp32esso.tier1.ble.GraphSample
import org.json.JSONArray

// Compact array-of-arrays representation. Order:
//   [uptimeMs, thermoblockC, groupC, setpointC, dutyPct, pressureBar, flowMlS, weightG]
// Kept as a positional tuple rather than named objects to keep the JSON blob
// small (each shot can be ~1000 samples).
object ShotSamples {
    fun encode(samples: List<GraphSample>): String {
        val arr = JSONArray()
        samples.forEach { s ->
            val row = JSONArray()
            row.put(s.uptimeMs)
            row.put(finiteOrNull(s.thermoblockC))
            row.put(finiteOrNull(s.groupC))
            row.put(finiteOrNull(s.setpointC))
            row.put(finiteOrNull(s.dutyPct))
            row.put(finiteOrNull(s.pressureBar))
            row.put(finiteOrNull(s.flowMlS))
            row.put(finiteOrNull(s.weightG))
            arr.put(row)
        }
        return arr.toString()
    }

    fun decode(json: String): List<GraphSample> {
        if (json.isBlank()) return emptyList()
        val arr = try { JSONArray(json) } catch (_: Exception) { return emptyList() }
        val out = ArrayList<GraphSample>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONArray(i) ?: continue
            out += GraphSample(
                uptimeMs = row.optLong(0),
                thermoblockC = readFloat(row, 1),
                groupC = readFloat(row, 2),
                setpointC = readFloat(row, 3),
                dutyPct = readFloat(row, 4),
                pressureBar = readFloat(row, 5),
                flowMlS = readFloat(row, 6),
                weightG = readFloat(row, 7),
            )
        }
        return out
    }

    // JSONObject.NULL round-trips NaN as `null` in the array, so read back the
    // same way — anything not a number becomes NaN in the graph.
    private fun finiteOrNull(v: Float): Any =
        if (v.isNaN() || v.isInfinite()) org.json.JSONObject.NULL else v.toDouble()
    private fun readFloat(row: JSONArray, i: Int): Float {
        if (row.isNull(i)) return Float.NaN
        return row.optDouble(i, Double.NaN).toFloat()
    }
}
