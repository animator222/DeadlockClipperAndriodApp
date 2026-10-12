package com.deadlockclipper.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Report {
    /** Human-readable summary for tuning - the user can send this file back to us. */
    fun text(info: VideoInfo, events: Events, clips: List<Clip>, settings: Settings, segments: List<DoubleArray>, version: String): String {
        val lines = mutableListOf(
            "Video: ${info.name} (${info.uri})",
            "Length: ${fmtTime(info.duration)}  ${info.width}x${info.height} @ ${"%.2f".format(info.fps)} fps, audio=${info.hasAudio}, codec=${info.vcodec}",
            "Settings: ${settings.copy(myLayouts = emptyMap()).toJson().replace("\n", "").replace("  ", "")}",
            "App version: $version (Android)",
            "Scanned for kill badges: ${fmtTime(segments.sumOf { it[1] - it[0] })} of ${fmtTime(info.duration)}",
            "",
            "Kill/assist badges found (${events.takedowns.size}): " + events.takedowns.joinToString(", ") { fmtTime(it) },
            "Deaths found (${events.deaths.size}): " + events.deaths.joinToString(", ") { fmtTime(it) },
            "Loud fights (${events.fights.size}, threshold ${"%.1f".format(events.threshold)} dB above normal):",
        )
        for (f in events.fights.sortedByDescending { it.peak }.take(40)) {
            lines.add("   ${fmtTime(f.start)}-${fmtTime(f.end)}  peak ${"%.1f".format(f.peak)} dB  (${"%.0f".format(f.length)}s)")
        }
        lines.add("")
        lines.add("Chosen clips (${clips.size}):")
        clips.forEachIndexed { i, c ->
            lines.add("  %02d. ${fmtTime(c.start)}-${fmtTime(c.end)}  score ${c.score}  ".format(i + 1) + c.reasons.joinToString("; "))
        }
        return lines.joinToString("\n")
    }
}

object Naming {
    private val DATE = Regex("(20\\d\\d)-(\\d\\d)-(\\d\\d)")
    private val CLIP_NUM = Regex("_clip(\\d+)\\.mp4$")

    /** OBS names files like '2026-09-25 20-11-03.mkv'; otherwise use the file's date. */
    fun recordingDate(displayName: String, lastModifiedMs: Long): String {
        DATE.find(displayName.substringBeforeLast('.'))?.let { m ->
            return "${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]}"
        }
        val ms = if (lastModifiedMs > 0) lastModifiedMs else System.currentTimeMillis()
        return DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))
    }

    /** deadlock_<date>_clipNN.mp4 names that don't clash with clips already in the folder. */
    fun outputNames(date: String, existing: Collection<String>, count: Int): List<String> {
        val prefix = "deadlock_${date}_clip"
        val taken = existing.filter { it.startsWith(prefix) }.mapNotNull { CLIP_NUM.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val first = (taken.maxOrNull() ?: 0) + 1
        return List(count) { "$prefix%02d.mp4".format(first + it) }
    }
}
