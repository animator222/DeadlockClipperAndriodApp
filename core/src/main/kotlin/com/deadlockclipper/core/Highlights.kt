package com.deadlockclipper.core

import kotlin.math.abs

/**
 * Turns the raw scans into events (kills, deaths, fights) and picks the best clips.
 *
 * Everything here is fast, so changing settings in the review screen re-picks clips
 * instantly without scanning the video again.
 */
object Highlights {
    const val BADGE_THRESHOLD = 0.5
    const val SINGLE_FRAME_SCORE = 0.7 // one clear sighting is enough; weaker ones need two frames
    const val POSITION_TOLERANCE = 80.0 // px (source video): how far sightings of one badge may be apart
    const val MENU_MIN_SECONDS = 1.5 // the shop panel must be seen this long to count as the shop
    const val MULTIKILL_GAP = 12.0 // takedowns this close together count as a multikill
    const val MOMENT_GAP = 20.0 // kills this close together go into the same clip (if it fits)
    const val ACTION_QUIET_BEFORE = 3.0
    const val ACTION_QUIET_AFTER = 2.0
    const val ACTION_MAX_AFTER = 10.0
    const val MENU_PAD = 4.0 // ignore badges this many seconds before/after the buy menu is seen open
    const val TEAMFIGHT_MIN_SECONDS = 5.0
    private const val HOP = Loudness.HOP
    private val FPS = VisionConst.SAMPLE_FPS.toDouble()

    var debugLog: (String) -> Unit = {}

    /**
     * Times the buy menu is open: the pale shop panel seen for at least MENU_MIN_SECONDS.
     * (A single pale frame - a bright wall, sky or flash - is not the shop.)
     */
    private fun menuTimes(v: VisionData): FloatArray {
        val pale = BooleanArray(v.size) { v.panel[it] >= VisionConst.MENU_PANEL }
        val keep = BooleanArray(v.size)
        for (r in Num.runs(pale)) if ((r[1] - r[0]) / FPS >= MENU_MIN_SECONDS) for (i in r[0] until r[1]) keep[i] = true
        return v.t.indices.filter { keep[it] }.map { v.t[it] }.toFloatArray()
    }

    /**
     * Turn per-frame badge scores into takedown times. Every badge sighting and what happened
     * to it is added to [report] when given.
     */
    fun findTakedowns(v: VisionData, report: MutableList<String>? = null): List<Double> {
        val t = v.t
        val score = v.badge
        // Tooltips can hide part of the shop for a moment, so badges a few seconds either side of a
        // clearly open shop are ignored too.
        val menuTimes = menuTimes(v)
        val seen = BooleanArray(t.size) { score[it] >= BADGE_THRESHOLD }
        // Bridge flickers: the badge can be missed for a few frames (explosions, effects).
        for (r in Num.runs(BooleanArray(seen.size) { !seen[it] })) {
            val (a, b) = r[0] to r[1]
            if (0 < a && b < seen.size && b - a <= 3) for (i in a until b) seen[i] = true
        }
        fun note(time: Float, text: String) {
            report?.add("${fmtTime(time.toDouble())}  $text")
        }
        val found = ArrayList<Double>()
        for (r in Num.runs(seen)) {
            val (a, b) = r[0] to r[1]
            val seconds = (b - a) / FPS
            val hitIdx = (a until b).filter { score[it] >= BADGE_THRESHOLD }
            val hits = hitIdx.size
            var best = Float.NEGATIVE_INFINITY
            for (i in a until b) best = maxOf(best, score[i])
            if (hits < 2 && best < SINGLE_FRAME_SCORE) {
                note(t[a], "not counted: seen in only 1 frame (score ${"%.2f".format(best)})")
                continue
            }
            if (t[b - 1] - t[a] > seconds + 1) {
                note(t[a], "not counted: spans a gap in the scan")
                continue
            }
            if (seconds > 8) {
                note(t[a], "not counted: stayed on screen ${fmt0(seconds)}s (a real badge disappears within a few seconds)")
                continue
            }
            // The badge stays in place: most sightings must be close to the middle one.
            val xs = FloatArray(hits) { v.bx[hitIdx[it]] }
            val ys = FloatArray(hits) { v.by[hitIdx[it]] }
            val mx = Num.median(xs)
            val my = Num.median(ys)
            val near = hitIdx.indices.count { abs(xs[it] - mx) <= POSITION_TOLERANCE && abs(ys[it] - my) <= POSITION_TOLERANCE }
            if (near.toDouble() / hits < 0.6) {
                note(t[a], "not counted: the red shape moved around (a real badge stays put)")
                continue
            }
            val lo = Num.searchSorted(menuTimes, t[a] - MENU_PAD)
            val hi = Num.searchSorted(menuTimes, t[b - 1] + MENU_PAD)
            if (hi > lo) {
                note(t[a], "not counted: buy menu open around this time")
                continue
            }
            note(t[a], "COUNTED as a kill/assist ($hits frames, best score ${"%.2f".format(best)})")
            found.add(t[a].toDouble())
        }
        debugLog("Buy menu seen open in ${menuTimes.size} sampled frames")
        return found
    }

    /** The death screen is much greyer than normal gameplay for several seconds. */
    fun findDeaths(v: VisionData): List<Double> {
        val t = v.t
        val sat = v.sat
        if (t.size < 20) return emptyList()
        val window = (30 * FPS).toInt()
        val grey = BooleanArray(sat.size) { i ->
            val baseline = Num.median(sat, maxOf(0, i - window), i + 1)
            sat[i] < 0.45 * maxOf(baseline, 1.0)
        }
        val deaths = ArrayList<Double>()
        for (r in Num.runs(grey)) {
            val (a, b) = r[0] to r[1]
            if ((b - a) / FPS >= 2.5 && t[b - 1] - t[a] < (b - a) / FPS + 1) deaths.add(t[a].toDouble())
        }
        return deaths
    }

    /** 1 -> only the loudest 3% of the match counts as a fight, 10 -> the loudest 25%. */
    fun sensitivityPercentile(sensitivity: Int) = 97.0 - (sensitivity - 1) * (22.0 / 9.0)

    fun findEvents(analysis: Analysis, sensitivity: Int): Events {
        val (short, sustained) = Loudness.intensity(analysis.audioDb)
        var threshold = if (sustained.isNotEmpty()) Num.percentile(sustained, sensitivityPercentile(sensitivity)) else 0.0
        threshold = maxOf(threshold, 2.0) // never call a stretch a fight unless clearly above normal

        val fights = ArrayList<Fight>()
        for (r in Num.runs(BooleanArray(sustained.size) { sustained[it] > threshold })) {
            var a = r[0]
            val b = r[1]
            var start = a * HOP
            val end = b * HOP
            if (fights.isNotEmpty() && start - fights.last().end < 2.0) {
                val prev = fights.removeAt(fights.size - 1)
                start = prev.start
                a = (start / HOP).toInt()
            }
            if (end - start >= 2.0) {
                var peak = Float.NEGATIVE_INFINITY
                var sum = 0.0
                for (i in a until b) {
                    peak = maxOf(peak, sustained[i]); sum += sustained[i]
                }
                fights.add(Fight(start, end, peak.toDouble(), sum / (b - a)))
            }
        }

        val spikeLevel = if (short.isNotEmpty()) Num.percentile(short, 98.5) else 0.0
        val spikes = ArrayList<Double>()
        for (i in 1 until short.size - 1) {
            if (short[i] >= spikeLevel && short[i] >= short[i - 1] && short[i] >= short[i + 1]) spikes.add(i * HOP)
        }

        val sightings = ArrayList<String>()
        val events = Events(findTakedowns(analysis.vision, sightings), findDeaths(analysis.vision), fights, spikes, threshold, sustained, sightings)
        debugLog(
            "Events: %d takedowns, %d deaths, %d loud fights (threshold %.1f dB), %d spikes".format(
                events.takedowns.size, events.deaths.size, fights.size, threshold, spikes.size,
            ),
        )
        return events
    }

    private fun cluster(times: List<Double>, gap: Double): List<List<Double>> {
        val groups = ArrayList<MutableList<Double>>()
        for (t in times.sorted()) {
            if (groups.isNotEmpty() && t - groups.last().last() <= gap) groups.last().add(t) else groups.add(mutableListOf(t))
        }
        return groups
    }

    private fun overlappingFight(fights: List<Fight>, start: Double, end: Double): Fight? {
        var best: Fight? = null
        for (f in fights) if (f.end >= start && f.start <= end && (best == null || f.peak > best.peak)) best = f
        return best
    }

    private val MULTI_NAMES = mapOf(2 to "Double kill", 3 to "Triple kill", 4 to "Quad kill")
    private val MULTI_BONUS = mapOf(2 to 15.0, 3 to 30.0, 4 to 50.0)

    /** Group kills into clips: kills up to MOMENT_GAP apart share a clip while it fits. */
    private fun moments(takedowns: List<Double>, maxSpan: Double): List<List<Double>> {
        val groups = ArrayList<MutableList<Double>>()
        for (t in takedowns.sorted()) {
            val g = groups.lastOrNull()
            if (g != null && t - g.last() <= MOMENT_GAP && t - g.first() <= maxSpan) g.add(t) else groups.add(mutableListOf(t))
        }
        return groups
    }

    /** Stretch a clip over the whole fight around its kills, using the game audio. */
    private fun actionBounds(events: Events, first: Double, last: Double, s: Settings, duration: Double): Pair<Double, Double> {
        val curve = events.sustained
        // "Action" = at least halfway between this video's normal loudness and the loudness of the
        // fight around the kill. Relative to each fight, so it works for loud and for heavily
        // compressed audio (YouTube downloads) alike.
        val i0 = maxOf(0, ((first - 3) / HOP).toInt())
        val i1 = minOf(curve.size, ((last + 3) / HOP).toInt() + 1)
        if (i1 <= i0) return (first - s.preRoll) to (last + s.postRoll)
        val normal = Num.median(curve)
        var peak = Float.NEGATIVE_INFINITY
        for (i in i0 until i1) peak = maxOf(peak, curve[i])
        if (peak - normal < 0.3) return (first - s.preRoll) to (last + s.postRoll) // the kill isn't audibly louder
        val level = normal + 0.5 * (peak - normal)
        fun active(t: Double): Boolean {
            val i = (t / HOP).toInt() // truncates toward zero, like Python's int()
            return i >= 0 && i < curve.size && curve[i] > level
        }
        val budget = s.maxLen - (last - first) - s.postRoll - 1.0
        var start = first - s.preRoll
        var t = first
        var quiet = 0.0
        while (first - t < budget && t > 0) {
            t -= HOP
            quiet = if (active(t)) 0.0 else quiet + HOP
            if (quiet >= ACTION_QUIET_BEFORE) break
            if (quiet == 0.0) start = minOf(start, t - 2.0)
        }
        start = maxOf(start, first - budget, 0.0)

        var end = last + s.postRoll
        t = last
        quiet = 0.0
        while (t - last < ACTION_MAX_AFTER && t < duration) {
            t += HOP
            quiet = if (active(t)) 0.0 else quiet + HOP
            if (quiet >= ACTION_QUIET_AFTER) break
            if (quiet == 0.0) end = maxOf(end, t + 1.0)
        }
        end = minOf(end, start + s.maxLen, duration)
        return start to end
    }

    private fun candidates(events: Events, s: Settings, duration: Double): List<Clip> {
        val cands = ArrayList<Clip>()
        val maxPeak = (events.fights.maxOfOrNull { it.peak } ?: 1.0).let { if (it == 0.0) 1.0 else it }
        val usedFights = HashSet<Fight>()
        val maxSpan = maxOf(0.0, s.maxLen - s.preRoll - s.postRoll)

        for (group in moments(events.takedowns, maxSpan)) {
            val n = group.size
            val first = group.first()
            val last = group.last()
            val bestMulti = cluster(group, MULTIKILL_GAP).maxOf { it.size }
            val isMulti = bestMulti >= 2
            if (isMulti && !(s.wantMultikills || s.wantKills)) continue
            if (!isMulti && !s.wantKills) continue
            var score = 10.0 * n + (MULTI_BONUS[bestMulti] ?: if (bestMulti > 4) 50.0 + 20 * (bestMulti - 4) else 0.0)
            val reasons = mutableListOf(
                if (isMulti) MULTI_NAMES[bestMulti] ?: "$bestMulti takedowns (multikill)" else "Your kill / assist",
            )
            if (n > bestMulti) reasons.add("$n kills/assists in this clip")

            val (start, end) = actionBounds(events, first, last, s, duration)
            val action = end - start
            val fight = overlappingFight(events.fights, first - 3, last + 3)
            if (fight != null) {
                usedFights.add(fight)
                if (fight.length >= TEAMFIGHT_MIN_SECONDS && s.wantTeamfights) {
                    score += 20 + 10 * fight.peak / maxPeak
                    reasons.add("Teamfight (loud for ${fmt0(fight.length)}s)")
                } else {
                    score += 5 * fight.peak / maxPeak
                }
            }
            val aligned = group.count { t -> events.spikes.any { abs(t - it) <= 3 } }
            if (aligned > 0) {
                score *= minOf(1.6, 1 + 0.15 * aligned)
                reasons.add("Kill lines up with an audio spike")
            }
            reasons.add("Fight about ${fmt0(action)}s" + if (action < s.minLen) " (padded to your ${s.minLen}s minimum)" else "")
            cands.add(Clip(start, end, score, reasons, group.toMutableList(), focus = first))
        }

        if (s.wantLoudOnly) {
            for (f in events.fights) {
                if (f in usedFights || f.length < TEAMFIGHT_MIN_SECONDS) continue
                val score = minOf(25.0, 6 + 12 * f.peak / maxPeak + 0.5 * f.length)
                cands.add(
                    Clip(
                        f.start - 2, f.end + 2, score,
                        mutableListOf("Loud fight for ${fmt0(f.length)}s (no kill badge seen)"),
                        mutableListOf(), focus = (f.start + f.end) / 2,
                    ),
                )
            }
        }
        return cands
    }

    private fun fitLength(clip: Clip, s: Settings, duration: Double): Clip {
        if (clip.length > s.maxLen) {
            // Keep the window of max length that contains the most takedowns.
            var bestStart = clip.start
            var bestCount = -1
            for (t in clip.takedowns.ifEmpty { listOf(clip.focus) }) {
                val start = maxOf(clip.start, t - s.preRoll)
                val count = clip.takedowns.count { it >= start && it <= start + s.maxLen - s.postRoll }
                if (count > bestCount) {
                    bestStart = start; bestCount = count
                }
            }
            clip.start = bestStart
            clip.end = bestStart + s.maxLen
        }
        if (clip.length < s.minLen) {
            val extra = s.minLen - clip.length
            clip.start -= extra * 0.65 // more build-up before the action than after it
            clip.end += extra * 0.35
        }
        if (clip.start < 0) {
            clip.end -= clip.start
            clip.start = 0.0
        }
        if (clip.end > duration) {
            clip.start = maxOf(0.0, clip.start - (clip.end - duration))
            clip.end = duration
        }
        return clip
    }

    private fun handleDeaths(clip: Clip, deaths: List<Double>, s: Settings): Clip? {
        val d = deaths.firstOrNull { clip.start < it && it < clip.end } ?: return clip
        val actionBefore = clip.takedowns.filter { it < d }.ifEmpty { if (clip.focus < d) listOf(clip.focus) else emptyList() }
        if (actionBefore.isEmpty()) return null // you die before the highlight even starts
        val newEnd = d - 0.5
        clip.start = minOf(clip.start, newEnd - s.minLen)
        val earlier = deaths.filter { it < clip.start + 1 }
        clip.start = maxOf(clip.start, if (earlier.isNotEmpty()) earlier.last() + 1 else 0.0, 0.0)
        clip.end = newEnd
        if (clip.length < 8) return null
        clip.takedowns = clip.takedowns.filter { it < newEnd }.toMutableList()
        clip.reasons.add("Ends just before your death")
        return clip
    }

    fun pickClips(events: Events, s: Settings, duration: Double): List<Clip> {
        val notes = ArrayList<String>()
        events.clipNotes = notes
        fun note(c: Clip, why: String) =
            notes.add("${fmtTime(c.takedowns.firstOrNull() ?: c.focus)}  ${c.reasons.take(1).joinToString(", ")}: $why")
        var cands = candidates(events, s, duration).map { fitLength(it, s, duration) }
        if (s.excludeDeaths) {
            val kept = ArrayList<Clip>()
            for (c in cands) {
                val handled = handleDeaths(c, events.deaths, s)
                if (handled != null) kept.add(handled)
                else note(c, "dropped - you die right at the start ('Skip moments where I die' is on)")
            }
            cands = kept
        }
        cands = cands.sortedByDescending { it.score }
        val chosen = ArrayList<Clip>()
        for (c in cands) {
            if (s.numClips > 0 && chosen.size >= s.numClips) {
                note(c, "not picked - you asked for ${s.numClips} clips and higher-scoring ones filled them " +
                    "(score ${fmt0(c.score)}; tick 'All highlights' to get every kill)")
                continue
            }
            if (chosen.all { o -> c.end + 1 <= o.start || c.start >= o.end + 1 }) chosen.add(c)
            else note(c, "already inside another clip")
        }
        chosen.sortBy { it.start }
        for (c in chosen) c.score = java.math.BigDecimal(c.score).setScale(1, java.math.RoundingMode.HALF_EVEN).toDouble() // like Python round(x, 1)
        debugLog("Picked ${chosen.size} clips from ${cands.size} candidates")
        return chosen
    }
}

class Fight(val start: Double, val end: Double, val peak: Double, val mean: Double) {
    val length get() = end - start
}

class Events(
    val takedowns: List<Double>, // times (s) a kill / kill-assist badge appeared
    val deaths: List<Double>, // times (s) the death screen started
    val fights: List<Fight>,
    val spikes: List<Double>,
    val threshold: Double,
    val sustained: FloatArray,
    val sightings: List<String> = emptyList(), // every badge sighting and what happened to it
    var clipNotes: List<String> = emptyList(), // kills that did not become clips, and why
)

class Clip(
    var start: Double,
    var end: Double,
    var score: Double,
    val reasons: MutableList<String> = mutableListOf(),
    var takedowns: MutableList<Double> = mutableListOf(),
    var focus: Double = 0.0, // best moment, used for the thumbnail
    var comp: Comp? = null, // this clip's own layout from the editor (null = use the settings)
    val id: Long = nextId++,
) {
    val length get() = end - start

    companion object {
        private var nextId = 1L
    }
}
