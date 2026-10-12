package com.deadlockclipper.app.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deadlockclipper.app.gl.TextRender
import com.deadlockclipper.app.ui.C
import kotlin.math.roundToInt

@Composable
fun EdButton(text: String, modifier: Modifier = Modifier, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 38.dp),
        shape = RoundedCornerShape(7.dp),
        border = BorderStroke(1.dp, if (selected) C.accent else Color(0xFF2C3246)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected) C.accent else Color(0xFF1D2130), contentColor = Color(0xFFE6E8EE)),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
    ) { Text(text, fontSize = 14.sp) }
}

@Composable
fun SectionLabel(text: String) = Text(text, color = Color(0xFF7D8499), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)

@Composable
fun Muted(text: String) = Text(text, color = C.muted, fontSize = 12.sp)

@Composable
fun LabeledSlider(value: Float, range: ClosedFloatingPointRange<Float>, fmt: (Float) -> String, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = value.coerceIn(range), onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = C.accent, inactiveTrackColor = Color(0xFF2C3246)),
        )
        Text(fmt(value), color = C.muted, fontSize = 12.sp, modifier = Modifier.width(48.dp))
    }
}

private val fieldColors @Composable get() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = C.accent, unfocusedBorderColor = Color(0xFF2C3246),
    focusedTextColor = Color(0xFFF2F3F7), unfocusedTextColor = Color(0xFFF2F3F7),
    focusedContainerColor = C.edBg, unfocusedContainerColor = C.edBg,
)

/** Top of the list = in front. Untick to hide. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LayersPanel(st: EditorState) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel("LAYERS")
        Muted("Top of the list = in front. Untick to hide.")
        val n = st.comp.layers.size
        Column(Modifier.background(C.edBg, RoundedCornerShape(8.dp)).padding(4.dp)) {
            for (row in 0 until n) {
                val i = n - 1 - row
                val lay = st.comp.layers[i]
                val name = if (lay.isVideo) lay.name else lay.text.split("\n")[0].take(22).ifBlank { "Text" }
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (i == st.sel) Color(0xFF2A2F44) else Color.Transparent, RoundedCornerShape(6.dp))
                        .clickable { st.select(i) }.padding(end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(!lay.hidden, { st.toggleHidden(i) }, colors = CheckboxDefaults.colors(checkedColor = C.layer(i)))
                    Text(if (lay.isVideo) "🎮" else "T", color = C.layer(i), fontWeight = FontWeight.Bold, modifier = Modifier.width(26.dp))
                    Text(name, color = if (lay.hidden) Color(0xFF555B6E) else C.layer(i), fontSize = 14.sp, maxLines = 1)
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            EdButton("+ Video") { st.addVideo() }
            EdButton("+ Text") { st.addText() }
            EdButton("▲") { st.move(1) }
            EdButton("▼") { st.move(-1) }
            EdButton("⎘") { st.duplicateCurrent() }
            EdButton("✕") { st.deleteCurrent() }
        }
        Muted("+ Video adds another part of the recording (webcam, item bar, minimap...). ▲▼ bring forward / send back, ⎘ duplicate, ✕ delete.")
        SectionLabel("QUICK START")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EdButton("Full frame") { st.resetLayout(1.0) }
            EdButton("Zoomed") { st.resetLayout(1.6) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PropertiesPanel(st: EditorState, onTyping: (Boolean) -> Unit) {
    val lay = st.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("PROPERTIES")
        when {
            lay == null -> Muted("Select a layer to change it.")
            lay.isVideo -> {
                Text("Name", color = Color(0xFFE6E8EE))
                OutlinedTextField(
                    value = lay.name, onValueChange = { v -> st.updateCurrent { it.copy(name = v.take(30)) } }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().onFocusChanged { onTyping(it.isFocused) }, colors = fieldColors,
                )
                Text("Size on the canvas", color = Color(0xFFE6E8EE))
                LabeledSlider((lay.dst[2] * 100).toFloat(), 5f..600f, { "${it.roundToInt()}%" }) { v ->
                    st.updateCurrent { it.copy(dst = listOf(it.dst[0], it.dst[1], maxOf(0.05, v / 100.0))) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EdButton("Fit") { st.fitWidth() }
                    EdButton("Fill") { st.fillCanvas() }
                    EdButton("Center") { st.centerLayer() }
                }
                Text("Which part of the video?", color = Color(0xFFE6E8EE))
                EdButton("▢  Mark it on the source video") { st.setModeChecked("source") }
                Muted(
                    "Switch to Source video and drag/resize this layer's box over the webcam, item bar or whatever it " +
                        "should show. Back on Canvas, drag it anywhere and resize it from a corner. Pinch to zoom.",
                )
            }
            else -> TextProps(st, lay, onTyping)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextProps(st: EditorState, lay: com.deadlockclipper.core.Layer, onTyping: (Boolean) -> Unit) {
    var fontsOpen by remember { mutableStateOf(false) }
    Text("Text", color = Color(0xFFE6E8EE))
    OutlinedTextField(
        value = lay.text, onValueChange = { v -> st.updateCurrent { it.copy(text = v.take(300)) } },
        modifier = Modifier.fillMaxWidth().heightIn(min = 70.dp).onFocusChanged { onTyping(it.isFocused) },
        colors = fieldColors, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
    )
    Text("Font", color = Color(0xFFE6E8EE))
    Box {
        EdButton("${lay.font}  ▾", Modifier.fillMaxWidth()) { fontsOpen = true }
        DropdownMenu(fontsOpen, { fontsOpen = false }, Modifier.background(C.edPanel)) {
            for (f in TextRender.families()) {
                val tf = remember(f) { FontFamily(TextRender.typeface(f, false).first) }
                DropdownMenuItem(
                    text = { Text(f, fontFamily = tf, fontSize = 17.sp, color = if (f == lay.font) C.accent else Color(0xFFF2F3F7)) },
                    onClick = { st.updateCurrent { it.copy(font = f) }; fontsOpen = false },
                )
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Colour", color = Color(0xFFE6E8EE))
        ColorSwatch(lay.color) { c -> st.updateCurrent { it.copy(color = c) } }
        Spacer(Modifier.weight(1f))
        EdButton("B", selected = lay.bold) { st.updateCurrent { it.copy(bold = !it.bold) } }
    }
    Text("Size", color = Color(0xFFE6E8EE))
    LabeledSlider((lay.size * 1000).toFloat(), 8f..250f, { "${it.roundToInt()}" }) { v -> st.updateCurrent { it.copy(size = v / 1000.0) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(lay.shadow, { on -> st.updateCurrent { it.copy(shadow = on) } }, colors = CheckboxDefaults.colors(checkedColor = C.accent))
        Text("Shadow", color = Color(0xFFE6E8EE), modifier = Modifier.weight(1f))
        ColorSwatch(lay.shadowColor) { c -> st.updateCurrent { it.copy(shadowColor = c) } }
    }
    LabeledSlider((lay.shadowStrength * 100).toFloat(), 0f..100f, { "${it.roundToInt()}%" }) { v -> st.updateCurrent { it.copy(shadowStrength = v / 100.0) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(lay.outline, { on -> st.updateCurrent { it.copy(outline = on) } }, colors = CheckboxDefaults.colors(checkedColor = C.accent))
        Text("Outline", color = Color(0xFFE6E8EE), modifier = Modifier.weight(1f))
        ColorSwatch(lay.outlineColor) { c -> st.updateCurrent { it.copy(outlineColor = c) } }
    }
    LabeledSlider((lay.outlineWidth * 100).toFloat(), 1f..30f, { "${it.roundToInt()}" }) { v -> st.updateCurrent { it.copy(outlineWidth = v / 100.0) } }
    EdButton("Center horizontally") { st.updateCurrent { it.copy(cx = 0.5) } }
    Muted("Drag the text on the canvas to move it; drag a corner (or pinch) to resize.")
}
