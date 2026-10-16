package com.deadlockclipper.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Settings(
    @SerialName("num_clips") val numClips: Int = 5, // 0 = find all highlights (no limit)
    @SerialName("min_len") val minLen: Int = 15,
    @SerialName("max_len") val maxLen: Int = 45,
    @SerialName("vertical_format") val verticalFormat: String = "blur", // "blur" full frame, "zoom", "custom" = my layout
    @SerialName("zoom_level") val zoomLevel: Double = 1.4,
    @SerialName("my_layouts") val myLayouts: Map<String, Comp> = emptyMap(), // keyed "landscape>vertical" etc.
    @SerialName("output_orientation") val outputOrientation: String = "vertical", // or "horizontal"
    @SerialName("badge_region_landscape") val badgeRegionLandscape: List<Double>? = null,
    @SerialName("badge_region_portrait") val badgeRegionPortrait: List<Double>? = null,
    @SerialName("want_kills") val wantKills: Boolean = true,
    @SerialName("want_multikills") val wantMultikills: Boolean = true,
    @SerialName("want_teamfights") val wantTeamfights: Boolean = true,
    @SerialName("want_loud_only") val wantLoudOnly: Boolean = false,
    @SerialName("want_objectives") val wantObjectives: Boolean = false,
    @SerialName("exclude_deaths") val excludeDeaths: Boolean = true,
    @SerialName("output_dir") val outputDir: String = DEFAULT_FOLDER, // folder inside the phone's Movies folder
    val sensitivity: Int = 5, // 1 = only the biggest fights, 10 = small skirmishes too
    @SerialName("quick_scan") val quickScan: Boolean = false,
    @SerialName("pre_roll") val preRoll: Double = 4.0,
    @SerialName("post_roll") val postRoll: Double = 3.0,
    @SerialName("youtube_max_height") val youtubeMaxHeight: Int = 1080, // YouTube download quality, 0 = best
) {
    fun validate(): Settings {
        val maxL = maxLen.coerceIn(5, 60)
        var fmt = when (verticalFormat) {
            "crop" -> "zoom"
            "layout" -> "custom"
            else -> verticalFormat
        }
        if (fmt !in setOf("blur", "zoom", "custom")) fmt = "blur"
        val folder = outputDir.replace(Regex("[\\\\:*?\"<>|]"), "").trim('/', ' ').ifBlank { DEFAULT_FOLDER }
        return copy(
            numClips = numClips.coerceIn(0, 200),
            maxLen = maxL,
            minLen = minLen.coerceIn(5, maxL),
            sensitivity = sensitivity.coerceIn(1, 10),
            verticalFormat = fmt,
            myLayouts = myLayouts.mapNotNull { (k, v) -> Composition.clean(v)?.let { k to it } }.toMap(),
            zoomLevel = if (zoomLevel.isFinite()) Math.round(zoomLevel.coerceIn(1.1, 2.5) * 100) / 100.0 else 1.4,
            outputOrientation = if (outputOrientation in setOf("vertical", "horizontal")) outputOrientation else "vertical",
            badgeRegionLandscape = badgeRegionLandscape?.takeIf(::validBox),
            badgeRegionPortrait = badgeRegionPortrait?.takeIf(::validBox),
            outputDir = folder,
            youtubeMaxHeight = if (youtubeMaxHeight in setOf(0, 480, 720, 1080, 1440)) youtubeMaxHeight else 1080,
        )
    }

    /** The kill badge search area to use for this video (saved one, or the default). */
    fun badgeRegion(info: VideoInfo): List<Double> =
        (if (info.portrait) badgeRegionPortrait else badgeRegionLandscape) ?: VisionConst.defaultRegion(info)

    fun withBadgeRegion(info: VideoInfo, region: List<Double>?): Settings =
        if (info.portrait) copy(badgeRegionPortrait = region) else copy(badgeRegionLandscape = region)

    companion object {
        const val DEFAULT_FOLDER = "Deadlock Clips"
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true; prettyPrint = true }

        fun validBox(r: List<Double>) = r.size == 4 && r[2] > 0.02 && r[3] > 0.02 && r[0] >= 0 && r[1] >= 0 &&
            r[0] + r[2] <= 1.001 && r[1] + r[3] <= 1.001

        fun fromJson(text: String): Settings = json.decodeFromString(serializer(), text).validate()
    }

    fun toJson(): String = json.encodeToString(serializer(), this)
}
