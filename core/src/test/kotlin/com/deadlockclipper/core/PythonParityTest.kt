package com.deadlockclipper.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.zip.GZIPInputStream
import kotlin.math.abs

/**
 * The PC app's Python highlight picker was run on generated matches (see python_reference.json.gz);
 * the Android port must find the same kills, deaths, fights and clips.
 */
class PythonParityTest {
    private val cases: JsonArray by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("python_reference.json.gz")!!
        Json.parseToJsonElement(GZIPInputStream(stream).readBytes().decodeToString()).jsonArray
    }

    private fun JsonArray.floats() = FloatArray(size) { this[it].jsonPrimitive.double.toFloat() }
    private fun JsonArray.doubles() = map { it.jsonPrimitive.double }

    private fun close(a: Double, b: Double, tol: Double, what: String) {
        if (abs(a - b) > tol) throw AssertionError("$what: expected $b but was $a")
    }

    @Test
    fun loudnessIntensityMatches() {
        for (case in cases) {
            val c = case.jsonObject
            val (short, sustained) = Loudness.intensity(c["audio_db"]!!.jsonArray.floats())
            val expSus = c["sustained_head"]!!.jsonArray.doubles()
            val expShort = c["short_head"]!!.jsonArray.doubles()
            expSus.forEachIndexed { k, v -> close(sustained[k * 7].toDouble(), v, 1e-3, "sustained[$k]") }
            expShort.forEachIndexed { k, v -> close(short[k * 7].toDouble(), v, 1e-3, "short[$k]") }
        }
    }

    @Test
    fun eventsAndClipsMatch() {
        var clipsChecked = 0
        for ((ci, case) in cases.withIndex()) {
            val c = case.jsonObject
            val v = c["vision"]!!.jsonObject
            fun col(k: String) = v[k]!!.jsonArray.floats()
            val vision = VisionData(col("t"), col("badge"), col("bx"), col("by"), col("sat"), col("val"), col("panel"))
            val analysis = Analysis(c["audio_db"]!!.jsonArray.floats(), vision, emptyList())
            val duration = c["duration"]!!.jsonPrimitive.double
            for ((vi, variant) in c["variants"]!!.jsonArray.withIndex()) {
                val e = variant.jsonObject
                val s = settingsFrom(e["settings"]!!.jsonObject)
                val events = Highlights.findEvents(analysis, s.sensitivity)
                val where = "case $ci variant $vi"
                assertEquals("$where takedowns", e["takedowns"]!!.jsonArray.doubles(), events.takedowns)
                assertEquals("$where deaths", e["deaths"]!!.jsonArray.doubles(), events.deaths)
                close(events.threshold, e["threshold"]!!.jsonPrimitive.double, 1e-3, "$where threshold")
                val fights = e["fights"]!!.jsonArray
                assertEquals("$where fights", fights.size, events.fights.size)
                assertEquals("$where spikes", e["nspikes"]!!.jsonPrimitive.int, events.spikes.size)
                assertEquals("$where sightings", e["sightings"]!!.jsonArray.map { it.jsonPrimitive.content }, events.sightings)
                val clips = Highlights.pickClips(events, s, duration)
                assertEquals("$where clip notes", e["clip_notes"]!!.jsonArray.map { it.jsonPrimitive.content }, events.clipNotes)
                val expected = e["clips"]!!.jsonArray
                assertEquals("$where clip count", expected.size, clips.size)
                expected.forEachIndexed { k, x ->
                    val arr = x.jsonArray
                    close(clips[k].start, arr[0].jsonPrimitive.double, 1e-6, "$where clip $k start")
                    close(clips[k].end, arr[1].jsonPrimitive.double, 1e-6, "$where clip $k end")
                    close(clips[k].score, arr[2].jsonPrimitive.double, 1e-9, "$where clip $k score")
                    assertEquals("$where clip $k reasons", arr[3].jsonArray.map { it.jsonPrimitive.content }, clips[k].reasons)
                    clipsChecked++
                }
            }
        }
        assert(clipsChecked > 100)
    }

    private fun settingsFrom(o: JsonObject) = Settings(
        numClips = o["num_clips"]!!.jsonPrimitive.int,
        minLen = o["min_len"]!!.jsonPrimitive.int,
        maxLen = o["max_len"]!!.jsonPrimitive.int,
        wantKills = o["want_kills"]!!.jsonPrimitive.boolean,
        wantMultikills = o["want_multikills"]!!.jsonPrimitive.boolean,
        wantTeamfights = o["want_teamfights"]!!.jsonPrimitive.boolean,
        wantLoudOnly = o["want_loud_only"]!!.jsonPrimitive.boolean,
        excludeDeaths = o["exclude_deaths"]!!.jsonPrimitive.boolean,
        sensitivity = o["sensitivity"]!!.jsonPrimitive.int,
    )
}
