package com.esp32esso.tier1

import org.json.JSONArray
import org.json.JSONObject

// Operator-drawn brew profiles: a timeline of vertices, each pinning a target
// pressure and a target thermoblock temperature at one instant of the shot. The
// machine interpolates linearly between consecutive vertices, so the graph the
// operator draws in the editor *is* the program the firmware runs.
//
// These compile down to the same ProfilePhase list the transcribed library uses
// (one linear phase per segment between vertices), which is what keeps the whole
// existing path — the profile dropdown, the BLE blob, the firmware's phase
// engine — working unchanged. The only thing wire v2 adds for them is the
// per-phase thermoblock ramp (see ProfilePhase.blockTempStartC).

data class ProfileVertex(
    val timeSec: Float,
    val pressureBar: Float,
    val blockTempC: Float,
)

data class UserProfile(
    val id: Long = 0L,
    val name: String,
    val notes: String = "",
    val vertices: List<ProfileVertex>,
) {
    val durationSec: Float get() = vertices.maxOfOrNull { it.timeSec } ?: 0f

    // Compile the timeline into the phase program the firmware executes. Each
    // pair of consecutive vertices becomes one linear pressure phase carrying
    // that segment's thermoblock ramp; a timeline whose first vertex sits after
    // t=0 gets a leading hold so the shot starts at the drawn value instead of
    // jumping. Segments are dropped once the phase budget is spent.
    fun toBrewProfile(): BrewProfile {
        val points = vertices.sortedBy { it.timeSec }
        val phases = mutableListOf<ProfilePhase>()
        if (points.isNotEmpty()) {
            val first = points.first()
            if (first.timeSec > MIN_SEGMENT_SEC) {
                phases += segment("Hold", first, first.copy(timeSec = 0f), first.timeSec)
            }
            for ((from, to) in points.zipWithNext()) {
                val dt = to.timeSec - from.timeSec
                if (dt < MIN_SEGMENT_SEC) continue
                phases += segment("${from.pressureBar.fmt()}→${to.pressureBar.fmt()} bar", from, to, dt)
            }
        }
        return BrewProfile(
            id = wireId(id),
            name = name,
            source = ProfileSource.CUSTOM,
            // Advisory only on the wire; showing the opening block target keeps
            // the profile card's temperature readout meaningful.
            temperatureC = points.firstOrNull()?.blockTempC ?: 0f,
            description = notes.ifBlank {
                "${points.size} vertices · ${durationSec.fmt()} s"
            },
            phases = phases.take(BrewProfile.WIRE_MAX_PHASES),
        )
    }

    fun toJson(): String {
        val arr = JSONArray()
        for (v in vertices.sortedBy { it.timeSec }) {
            arr.put(
                JSONObject().apply {
                    put("t", v.timeSec.toDouble())
                    put("p", v.pressureBar.toDouble())
                    put("c", v.blockTempC.toDouble())
                },
            )
        }
        return arr.toString()
    }

    companion object {
        // Phases shorter than this are a double-tap on the same spot, not a
        // segment the machine could act on.
        const val MIN_SEGMENT_SEC = 0.2f

        // One phase per segment, plus one for a possible leading hold.
        const val MAX_VERTICES = BrewProfile.WIRE_MAX_PHASES

        const val MAX_PRESSURE_BAR = 12f
        const val MIN_BLOCK_TEMP_C = 88f
        const val MAX_BLOCK_TEMP_C = 140f
        const val DEFAULT_DURATION_SEC = 30f

        // No espresso shot runs anywhere near this long. It exists as a hard stop
        // so the editor's time axis — which is derived from the vertices it is
        // being used to place — can never run away from the finger dragging it.
        const val MAX_DURATION_SEC = 120f

        fun wireId(rowId: Long): String = "user:$rowId"

        fun isUserId(id: String?): Boolean = id?.startsWith("user:") == true

        fun parseVertices(json: String): List<ProfileVertex> = runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                // Clamped on the way in so a profile stored before the limits
                // existed opens as something editable rather than a flat line
                // squashed against a runaway axis.
                ProfileVertex(
                    timeSec = o.optDouble("t", 0.0).toFloat().coerceIn(0f, MAX_DURATION_SEC),
                    pressureBar = o.optDouble("p", 0.0).toFloat().coerceIn(0f, MAX_PRESSURE_BAR),
                    blockTempC = o.optDouble("c", 0.0).toFloat()
                        .coerceIn(MIN_BLOCK_TEMP_C, MAX_BLOCK_TEMP_C),
                )
            }.sortedBy { it.timeSec }
        }.getOrDefault(emptyList())

        // A sane starting shape for a new profile: a gentle pre-infusion ramp,
        // a 9 bar plateau and a taper, at a flat block temperature.
        fun blank(name: String, blockTempC: Float = 105f): UserProfile =
            UserProfile(
                name = name,
                vertices = listOf(
                    ProfileVertex(0f, 2f, blockTempC),
                    ProfileVertex(8f, 3f, blockTempC),
                    ProfileVertex(12f, 9f, blockTempC),
                    ProfileVertex(24f, 9f, blockTempC),
                    ProfileVertex(DEFAULT_DURATION_SEC, 6f, blockTempC),
                ),
            )

        private fun segment(
            name: String,
            from: ProfileVertex,
            to: ProfileVertex,
            durationSec: Float,
        ) = ProfilePhase(
            name = name,
            mode = PhaseMode.PRESSURE,
            targetStart = from.pressureBar,
            targetEnd = to.pressureBar,
            curve = PhaseCurve.LINEAR,
            transitionSec = durationSec,
            durationSec = durationSec,
            blockTempStartC = from.blockTempC,
            blockTempEndC = to.blockTempC,
        )

        private fun Float.fmt(): String =
            if (this == toInt().toFloat()) toInt().toString() else "%.1f".format(this)
    }
}
