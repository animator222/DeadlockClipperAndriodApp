package com.deadlockclipper.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deadlockclipper.app.BuildConfig
import com.deadlockclipper.core.fmtTime

@Composable
fun HomeScreen(vm: AppViewModel) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.openUri(it) } }
    var url by rememberSaveable { mutableStateOf("") }
    var showLogs by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Title("Deadlock Clipper", Modifier.weight(1f))
            Subtle("Version ${BuildConfig.VERSION_NAME}")
        }
        Subtle("Turn a Deadlock recording into highlight clips: vertical for Shorts and Reels, or horizontal for YouTube.", size = 15)

        Box(
            Modifier.fillMaxWidth().heightIn(min = 200.dp)
                .border(2.dp, androidx.compose.ui.graphics.Color(0xFF4A4F5E), RoundedCornerShape(12.dp))
                .clickable { pick.launch(arrayOf("video/*")) }
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Choose a recording", color = C.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Subtle("MP4, MKV, MOV, WebM ...")
                Spacer(Modifier.height(4.dp))
                Subtle("Tip: you can also Share a video to Deadlock Clipper from your gallery or file manager.")
            }
        }
        PrimaryButton("Choose a video file...", Modifier.align(Alignment.CenterHorizontally)) { pick.launch(arrayOf("video/*")) }

        vm.info?.let { v ->
            SecondaryButton("Continue with ${v.name.take(40)} (${fmtTime(v.duration)})", Modifier.fillMaxWidth()) { vm.showSettings() }
        }

        Spacer(Modifier.height(4.dp))
        Subtle("Or paste a YouTube link (local files work best):", size = 14)
        OutlinedTextField(
            value = url, onValueChange = { url = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://www.youtube.com/watch?v=...", color = C.subtle) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (url.isNotBlank()) vm.openUrl(url.trim()) }),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.primary, unfocusedBorderColor = C.buttonBorder, focusedTextColor = C.text, unfocusedTextColor = C.text),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Paste") { clipboard.getText()?.text?.let { url = it.trim() } }
            PrimaryButton("Download", enabled = url.isNotBlank()) { vm.openUrl(url.trim()) }
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SecondaryButton("Logs") { showLogs = true }
        }
    }
    if (showLogs) LogsDialog(vm.paths) { showLogs = false }
}
