package com.deadlockclipper.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deadlockclipper.core.Composition
import com.deadlockclipper.core.Settings
import com.deadlockclipper.core.fmtTime
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun NumberStepper(value: Int, range: IntRange, suffix: String = "", enabled: Boolean = true, step: Int = 1, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SecondaryButton("−", enabled = enabled && value > range.first) { onChange((value - step).coerceIn(range)) }
        Text("$value$suffix", color = if (enabled) C.text else C.subtle, fontSize = 16.sp, modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        SecondaryButton("+", enabled = enabled && value < range.last) { onChange((value + step).coerceIn(range)) }
    }
}

@Composable
private fun Section(text: String) = Text(text, color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val info = vm.info ?: return
    val s = vm.settings
    fun set(f: (Settings) -> Settings) = vm.updateSettings(f(vm.settings))

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp)) {
            Title("Settings")
            Subtle("${info.name}  •  ${fmtTime(info.duration)}  •  ${info.width}x${info.height} @ ${info.fps.roundToInt()} fps", size = 14)

            Section("Number of clips")
            Row(verticalAlignment = Alignment.CenterVertically) {
                NumberStepper(if (s.numClips == 0) 5 else s.numClips, 1..200, enabled = s.numClips != 0) { n -> set { it.copy(numClips = n) } }
            }
            CheckRow("All highlights (no limit)", s.numClips == 0) { on -> set { it.copy(numClips = if (on) 0 else 5) } }

            Section("Clip length")
            FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberStepper(s.minLen, 5..60, " s") { v -> set { it.copy(minLen = v, maxLen = maxOf(v, it.maxLen)) } }
                Text("to", color = C.text, modifier = Modifier.align(Alignment.CenterVertically))
                NumberStepper(s.maxLen, 5..60, " s") { v -> set { it.copy(maxLen = v, minLen = minOf(v, it.minLen)) } }
            }

            Section("What counts as a highlight")
            CheckRow("My kills and assists", s.wantKills) { on -> set { it.copy(wantKills = on) } }
            CheckRow("Multikills", s.wantMultikills) { on -> set { it.copy(wantMultikills = on) } }
            CheckRow("Teamfights (kills during a big fight score higher)", s.wantTeamfights) { on -> set { it.copy(wantTeamfights = on) } }
            CheckRow("Also include loud fights where no kill was seen (may pick up creep farming)", s.wantLoudOnly) { on -> set { it.copy(wantLoudOnly = on) } }
            CheckRow("Big objective moments (coming soon)", false, enabled = false) {}
            CheckRow("Skip moments where I die (clips end before the death)", s.excludeDeaths) { on -> set { it.copy(excludeDeaths = on) } }

            Section("Fight sensitivity")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Subtle("Low")
                Slider(
                    value = s.sensitivity.toFloat(), onValueChange = { v -> set { it.copy(sensitivity = v.roundToInt()) } },
                    valueRange = 1f..10f, steps = 8, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(thumbColor = C.primary, activeTrackColor = C.primary),
                )
                Subtle("High")
            }
            Subtle("Low = only the biggest fights. High = small skirmishes count too.")
            CheckRow("Quick scan (faster, but can miss kills during laning or quiet moments)", s.quickScan) { on -> set { it.copy(quickScan = on) } }

            Section("Save clips to")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Movies/", color = C.subtle, fontSize = 15.sp)
                OutlinedTextField(
                    value = s.outputDir, onValueChange = { v -> set { it.copy(outputDir = v) } }, singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.primary, unfocusedBorderColor = C.buttonBorder, focusedTextColor = C.text, unfocusedTextColor = C.text),
                )
            }

            Section("Export as")
            FlowRow {
                RadioRow("Vertical 9:16 (Shorts / Reels)", s.outputOrientation == "vertical") { set { it.copy(outputOrientation = "vertical") } }
                RadioRow("Horizontal 16:9 (YouTube)", s.outputOrientation == "horizontal") { set { it.copy(outputOrientation = "horizontal") } }
            }

            Section("Format (tap one)")
            val o = s.outputOrientation
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FormatOption("Full frame + blurred background", "Shows the whole picture.", vm.previews["$o|blur"], o, s.verticalFormat == "blur") {
                    set { it.copy(verticalFormat = "blur") }
                }
                FormatOption("Zoomed gameplay + blurred background", "Gameplay is bigger; its edges are cut off.", vm.previews["$o|zoom"], o, s.verticalFormat == "zoom") {
                    set { it.copy(verticalFormat = "zoom") }
                }
                FormatOption("My layout", "Your own layers: gameplay, webcam, items, text...", vm.previews["$o|custom"], o, s.verticalFormat == "custom") {
                    set { it.copy(verticalFormat = "custom") }
                }
            }
            Spacer(Modifier.height(8.dp))
            SecondaryButton("✎ Edit my layout...") { vm.editMyLayout() }
            val saved = s.myLayouts[Composition.templateKey(info, o)]
            Subtle(
                if (saved == null) "Not made yet for this video and export shape - tap 'Edit my layout'."
                else "Layers (front to back): " + Composition.describe(saved),
            )

            Section("Zoom (gameplay)")
            var zoom by remember(s.zoomLevel) { mutableFloatStateOf((s.zoomLevel * 100).toFloat()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = zoom, onValueChange = { zoom = (it / 5).roundToInt() * 5f }, valueRange = 110f..250f,
                    onValueChangeFinished = {
                        set { it.copy(zoomLevel = zoom / 100.0) }
                        vm.makePreviews(listOf("zoom"))
                    },
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = C.primary, activeTrackColor = C.primary),
                )
                Subtle("${zoom.roundToInt()}%", size = 15)
            }
            val same = (info.height > info.width) == (o == "vertical") &&
                abs(info.width.toDouble() / info.height - if (o == "vertical") 9.0 / 16 else 16.0 / 9) < 0.02
            if (same) Subtle("Your video already has this shape: full frame is just resized, zoom cuts in on the middle.")

            Section("Kill badge area")
            val portrait = info.height > info.width
            val shape = if (portrait) "vertical" else "horizontal"
            val savedRegion = if (portrait) s.badgeRegionPortrait else s.badgeRegionLandscape
            Subtle(
                "Your video is ${info.width}x${info.height} ($shape). " +
                    if (savedRegion != null) "Using the area you set for $shape videos."
                    else "Using the default area. If kills are missed, set it once using a moment with a kill.",
            )
            Spacer(Modifier.height(6.dp))
            SecondaryButton("Set kill badge area...") { vm.openBadgeArea() }
            Spacer(Modifier.height(16.dp))
        }
        Row(
            Modifier.fillMaxWidth().background(C.card).padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            SecondaryButton("Back") { vm.goHome() }
            if (vm.events != null && vm.clips.isNotEmpty()) SecondaryButton("Clips") { vm.backToClips() }
            Spacer(Modifier.weight(1f))
            PrimaryButton("Find highlights") { vm.startAnalysis() }
        }
    }
}

@Composable
private fun FormatOption(title: String, subtitle: String, preview: Bitmap?, orientation: String, selected: Boolean, onClick: () -> Unit) {
    val (w, h) = if (orientation == "horizontal") 220.dp to 124.dp else 124.dp to 220.dp
    Column(
        Modifier.width(maxOf(w, 150.dp) + 16.dp)
            .border(2.dp, if (selected) C.primary else C.cardBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(8.dp),
    ) {
        Box(Modifier.size(w, h).clip(RoundedCornerShape(4.dp)).background(androidx.compose.ui.graphics.Color(0xFF0D0E11)).align(Alignment.CenterHorizontally), contentAlignment = Alignment.Center) {
            if (preview != null) Image(preview.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Subtle("Making preview...")
        }
        RadioRow(title, selected, onClick)
        Subtle(subtitle)
    }
}
