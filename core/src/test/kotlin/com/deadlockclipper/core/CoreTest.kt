package com.deadlockclipper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CoreTest {
    private val info = VideoInfo("content://x", "2026-09-25 20-11-03.mkv", 600.0, 1920, 1080, 60.0, true, "avc")

    @Test
    fun numpyHelpers() {
        val a = floatArrayOf(1f, 2f, 3f, 4f)
        assertEquals(2.5, Num.percentile(a, 50.0), 1e-9)
        assertEquals(3.85, Num.percentile(a, 95.0), 1e-6)
        assertEquals(2.5, Num.median(a), 1e-9)
        val ma = Num.movingAverage(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0), 3)
        assertEquals(1.0, ma[0].toDouble(), 1e-6) // (0+1+2)/3
        assertEquals(3.0, ma[2].toDouble(), 1e-6)
        assertEquals(listOf(listOf(1, 3), listOf(4, 5)), Num.runs(booleanArrayOf(false, true, true, false, true)).map { it.toList() })
        assertEquals(1, Num.searchSorted(floatArrayOf(1f, 2f, 3f), 2.0))
    }

    @Test
    fun formatting() {
        assertEquals("1:05", fmtTime(65.9))
        assertEquals("1:00:01", fmtTime(3601.0))
        assertEquals("1:05.3", fmtPrecise(65.3))
    }

    @Test
    fun settingsRoundTripAndValidate() {
        val s = Settings(minLen = 50, maxLen = 30, zoomLevel = 9.0, verticalFormat = "crop", numClips = 999,
            badgeRegionLandscape = listOf(0.9, 0.9, 0.5, 0.5)).validate()
        assertEquals(30, s.minLen)
        assertEquals(2.5, s.zoomLevel, 1e-9)
        assertEquals("zoom", s.verticalFormat)
        assertEquals(200, s.numClips)
        assertNull(s.badgeRegionLandscape)
        val withLayout = s.copy(myLayouts = mapOf("landscape>vertical" to Composition.simple(info, "vertical", 1.5)))
        assertEquals(withLayout, Settings.fromJson(withLayout.toJson()))
        assertEquals(Settings(), Settings.fromJson("{\"unknown\": 1, \"num_clips\": 5}"))
    }

    @Test
    fun compositionGeometry() {
        val full = Composition.simple(info, "vertical", 1.0)
        val r = Composition.videoRect(full.layers[0], info, full)
        assertEquals(1080.0, r[2], 1e-6) // fits the width
        assertEquals(607.5, r[3], 1e-6)
        assertEquals(1920.0 / 2 - 607.5 / 2, r[1], 1e-6)
        val horiz = Composition.simple(info, "horizontal", 1.0)
        assertEquals(1.0, horiz.layers[0].dst[2], 1e-9)
        assertNull(Composition.clean(Comp("vertical", listOf(Composition.textLayer()))))
        assertNotNull(Composition.clean(full))
        val custom = Settings(verticalFormat = "custom", myLayouts = mapOf("landscape>vertical" to full.copy(layers = full.layers + Composition.newVideoLayer(info))))
        assertEquals(2, Composition.forClip(null, custom, info).layers.size)
        assertEquals("landscape>horizontal", Composition.templateKey(info, "horizontal"))
    }

    @Test
    fun naming() {
        assertEquals("2026-09-25", Naming.recordingDate("2026-09-25 20-11-03.mkv", 0))
        assertEquals(
            listOf("deadlock_2026-09-25_clip03.mp4", "deadlock_2026-09-25_clip04.mp4"),
            Naming.outputNames("2026-09-25", listOf("deadlock_2026-09-25_clip02.mp4", "other.mp4"), 2),
        )
    }

    @Test
    fun scanPlan() {
        assertEquals(1, Loudness.scanPlan(FloatArray(1000) { -30f }, 100.0, false).size)
        val db = FloatArray(6000) { if (it in 3000..3100) -10f else -30f }
        val plan = Loudness.scanPlan(db, 600.0, true)
        assert(plan.sumOf { it[1] - it[0] } < 100) { plan.map { it.toList() } }
    }
}
