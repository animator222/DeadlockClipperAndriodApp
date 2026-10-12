package com.deadlockclipper.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DoneScreen(vm: AppViewModel) {
    val result = vm.exportResult ?: return
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Title("Your clips are ready!")
            Subtle("Saved in: ${result.folder} (they're in your gallery too). Tap a clip to watch it.", size = 14)
            for (f in result.files) {
                Row(
                    Modifier.fillMaxWidth().background(C.card).clickable { openUri(context, f.uri, "video/mp4") }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("▶  ${f.name}", color = C.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text("Share", color = C.reason, modifier = Modifier.clickable { shareUris(context, listOf(f.uri), "video/mp4") }.padding(6.dp))
                }
            }
            for (n in result.notes) Subtle(n, color = C.reason)
        }
        FlowRow(
            Modifier.fillMaxWidth().background(C.card).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SecondaryButton("← Back to clips") { vm.backToClips() }
            SecondaryButton("Make more clips") { vm.goHome() }
            Spacer(Modifier.weight(1f))
            PrimaryButton("Share all") { shareUris(context, result.files.map { it.uri }, "video/mp4") }
        }
    }
}
