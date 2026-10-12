package com.deadlockclipper.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.deadlockclipper.app.util.AppLog
import java.io.File

@Composable
fun Title(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier = modifier, color = C.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)

@Composable
fun Subtle(text: String, modifier: Modifier = Modifier, color: Color = C.subtle, size: Int = 13) =
    Text(text, modifier = modifier, color = color, fontSize = size.sp)

@Composable
fun Label(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier = modifier, color = C.text, fontSize = 15.sp)

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 44.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = C.primary, contentColor = Color.White, disabledContainerColor = C.button),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 44.dp),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, C.buttonBorder),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = C.button, contentColor = C.text),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) { Text(text) }
}

@Composable
fun CheckRow(text: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = C.primary, uncheckedColor = Color(0xFF6B7080)),
        )
        Text(text, color = if (enabled) C.text else C.subtle, fontSize = 15.sp)
    }
}

@Composable
fun RadioRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.clickable(onClick = onClick).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick, colors = RadioButtonDefaults.colors(selectedColor = C.primary))
        Text(text, color = C.text, fontSize = 15.sp)
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .background(C.card, RoundedCornerShape(8.dp))
            .border(1.dp, C.cardBorder, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) { content() }
}

fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Deadlock Clipper", text))
    toast(context, "Copied")
}

fun fileUri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

fun shareFile(context: Context, file: File, mime: String) {
    if (!file.exists()) {
        toast(context, "${file.name} doesn't exist yet")
        return
    }
    try {
        val send = Intent(Intent.ACTION_SEND).setType(mime)
            .putExtra(Intent.EXTRA_STREAM, fileUri(context, file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share ${file.name}"))
    } catch (e: Exception) {
        AppLog.w("share", "Share failed: $e")
        toast(context, "Couldn't share the file")
    }
}

fun shareUris(context: Context, uris: List<Uri>, mime: String) {
    if (uris.isEmpty()) return
    try {
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        send.setType(mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share clips"))
    } catch (e: Exception) {
        toast(context, "Couldn't share")
    }
}

fun openUri(context: Context, uri: Uri, mime: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (e: Exception) {
        toast(context, "No app found to open it")
    }
}

/** Error popup with the same options as the PC app: share the log, copy the details. */
@Composable
fun ErrorDialog(error: ErrorInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var showDetails by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.card,
        title = { Text("Something went wrong", color = C.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(error.message, color = C.text, fontSize = 15.sp)
                Spacer(Modifier.padding(4.dp))
                Subtle("Details were saved to the app's log. If this keeps happening, share the log with Claude.")
                if (error.details.isNotBlank()) {
                    TextButton(onClick = { showDetails = !showDetails }) { Text(if (showDetails) "Hide details" else "Show details", color = C.reason) }
                    if (showDetails) Text(error.details.takeLast(4000), color = C.subtle, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK", color = C.text) } },
        dismissButton = {
            Row {
                TextButton(onClick = { AppLog.logFile?.let { shareFile(context, it, "text/plain") } }) { Text("Share log", color = C.reason) }
                TextButton(onClick = { copyText(context, "${error.message}\n\n${error.details}") }) { Text("Copy details", color = C.reason) }
            }
        },
    )
}

/** The PC app's "Open logs folder": share or copy any of the log files. */
@Composable
fun LogsDialog(paths: com.deadlockclipper.app.util.Paths, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val files = listOf(
        Triple(File(paths.logs, "deadlock_clipper.log"), "text/plain", "Main log. Send this one when reporting a problem."),
        Triple(paths.detections, "image/jpeg", "Picture of everything that looked like your kill badge."),
        Triple(paths.analysisReport, "text/plain", "What was detected in the last video."),
        Triple(File(paths.logs, "crash.log"), "text/plain", "Only exists if the app crashed."),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.card,
        title = { Text("Logs", color = C.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((f, mime, what) in files) {
                    Column {
                        Text(f.name + if (f.exists()) "" else "  (not there yet)", color = C.text, fontSize = 15.sp)
                        Subtle(what)
                        if (f.exists()) {
                            Row {
                                TextButton(onClick = { shareFile(context, f, mime) }) { Text("Share", color = C.reason) }
                                if (mime == "text/plain") {
                                    TextButton(onClick = { copyText(context, f.readText().lines().takeLast(150).joinToString("\n")) }) {
                                        Text("Copy last lines", color = C.reason)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = C.text) } },
    )
}

@Composable
fun HSpace(w: Int) = Spacer(Modifier.width(w.dp))
