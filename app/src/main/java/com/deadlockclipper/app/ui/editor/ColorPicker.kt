package com.deadlockclipper.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.deadlockclipper.app.gl.TextRender
import com.deadlockclipper.app.ui.C

private val PALETTE = listOf(
    "#ffffff", "#000000", "#ff3b30", "#ff5a36", "#ff9500", "#ffcc00", "#f0b35a", "#34c759",
    "#00e676", "#5ac8fa", "#007aff", "#5856d6", "#af52de", "#ff2d55", "#8e8e93", "#3a3a3c",
)

fun toColor(hex: String) = Color(TextRender.parseColor(hex))

fun hexOf(c: Color): String {
    val r = (c.red * 255).toInt()
    val g = (c.green * 255).toInt()
    val b = (c.blue * 255).toInt()
    return "#%02x%02x%02x".format(r, g, b)
}

/** A swatch that opens a colour picker. */
@Composable
fun ColorSwatch(hex: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(
        Modifier.size(46.dp, 32.dp).background(toColor(hex), RoundedCornerShape(7.dp))
            .border(2.dp, Color(0xFF3A4260), RoundedCornerShape(7.dp)).clickable { open = true },
    )
    if (open) ColorDialog(hex, { open = false }) { onPick(it); open = false }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorDialog(initial: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var color by remember { mutableStateOf(toColor(initial)) }
    var text by remember { mutableStateOf(initial) }
    fun set(c: Color) {
        color = c
        text = hexOf(c)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.edPanel,
        title = { Text("Choose a colour", color = C.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in PALETTE) {
                        Box(Modifier.size(32.dp).background(toColor(p), RoundedCornerShape(6.dp)).border(1.dp, Color(0xFF3A4260), RoundedCornerShape(6.dp)).clickable { set(toColor(p)) })
                    }
                }
                for ((label, value, apply) in listOf<Triple<String, Float, (Float) -> Color>>(
                    Triple("Red", color.red) { v -> color.copy(red = v) },
                    Triple("Green", color.green) { v -> color.copy(green = v) },
                    Triple("Blue", color.blue) { v -> color.copy(blue = v) },
                )) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = C.muted, modifier = Modifier.width(52.dp))
                        Slider(value = value, onValueChange = { set(apply(it)) }, modifier = Modifier.weight(1f))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).background(color, RoundedCornerShape(6.dp)))
                    OutlinedTextField(
                        value = text, singleLine = true, modifier = Modifier.padding(start = 10.dp).fillMaxWidth(),
                        onValueChange = {
                            text = it
                            if (Regex("#[0-9a-fA-F]{6}").matches(it)) color = toColor(it)
                        },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(hexOf(color)) }) { Text("OK", color = C.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = C.text) } },
    )
}

@Composable
fun VSpace(h: Int) = Box(Modifier.height(h.dp))
