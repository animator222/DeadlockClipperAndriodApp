package com.deadlockclipper.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
fun ProgressScreen(vm: AppViewModel) {
    var confirm by remember { mutableStateOf(false) }
    BackHandler { confirm = true }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Title(vm.progressTitle)
        Spacer(Modifier.height(18.dp))
        LinearProgressIndicator(
            progress = { vm.progressFraction.toFloat() },
            modifier = Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)),
            color = C.primary, trackColor = C.card,
        )
        Spacer(Modifier.height(10.dp))
        Subtle(vm.progressMessage, size = 15)
        Subtle("%.0f%%".format(vm.progressFraction * 100), size = 15)
        Spacer(Modifier.height(18.dp))
        Subtle("You can switch to other apps meanwhile; progress is shown in the notification. Keep the phone charged for long videos.")
        Spacer(Modifier.height(30.dp))
        SecondaryButton(if (vm.cancelling) "Cancelling..." else "Cancel", enabled = !vm.cancelling) { vm.cancel() }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = C.card,
            title = { Text("Cancel?", color = C.text) },
            text = { Text("Stop ${vm.progressTitle.lowercase()}?", color = C.subtle) },
            confirmButton = { TextButton(onClick = { confirm = false; vm.cancel() }) { Text("Stop", color = C.reason) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep going", color = C.text) } },
        )
    }
}
