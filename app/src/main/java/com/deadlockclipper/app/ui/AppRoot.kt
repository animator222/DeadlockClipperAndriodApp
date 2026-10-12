package com.deadlockclipper.app.ui

import android.Manifest
import android.app.Activity
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.deadlockclipper.app.ui.editor.EditorScreen

@Composable
fun AppRoot(vm: AppViewModel, activity: Activity) {
    AppTheme {
        val context = LocalContext.current
        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
        LaunchedEffect(Unit) {
            val prefs = context.getSharedPreferences("app", android.content.Context.MODE_PRIVATE)
            if (prefs.getBoolean("asked_permissions", false)) return@LaunchedEffect
            prefs.edit().putBoolean("asked_permissions", true).apply()
            val wanted = buildList {
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS) // progress of long scans
                if (Build.VERSION.SDK_INT < 29) add(Manifest.permission.WRITE_EXTERNAL_STORAGE) // saving clips
            }
            if (wanted.isNotEmpty()) permissions.launch(wanted.toTypedArray())
        }
        // Long scans and exports: keep the screen on while the progress screen is showing.
        val busy = vm.screen == Screen.Progress
        DisposableEffect(busy) {
            if (busy) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        var confirmLeaveReview by remember { mutableStateOf(false) }

        Box(Modifier.fillMaxSize().background(if (vm.screen == Screen.Editor) C.edBg else C.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
            when (vm.screen) {
                Screen.Home -> HomeScreen(vm)
                Screen.Settings -> {
                    BackHandler { vm.goHome() }
                    SettingsScreen(vm)
                }
                Screen.Progress -> ProgressScreen(vm)
                Screen.Review -> {
                    BackHandler { confirmLeaveReview = true }
                    ReviewScreen(vm)
                }
                Screen.Done -> {
                    BackHandler { vm.backToClips() }
                    DoneScreen(vm)
                }
                Screen.Editor -> vm.editor?.let { EditorScreen(vm, it) }
                Screen.BadgeArea -> {
                    BackHandler { vm.showSettings() }
                    BadgeAreaScreen(vm)
                }
            }
        }

        if (confirmLeaveReview) {
            AlertDialog(
                onDismissRequest = { confirmLeaveReview = false },
                containerColor = C.card,
                title = { Text("Back to settings?", color = C.text) },
                text = { Text("Your clip list stays until you press Find highlights again (the scan is saved, so that's quick).", color = C.subtle) },
                confirmButton = { TextButton(onClick = { confirmLeaveReview = false; vm.showSettings() }) { Text("Back to settings", color = C.reason) } },
                dismissButton = { TextButton(onClick = { confirmLeaveReview = false }) { Text("Stay", color = C.text) } },
            )
        }
        vm.error?.let { ErrorDialog(it) { vm.error = null } }
        vm.message?.let {
            LaunchedEffect(it) {
                toast(context, it)
                vm.message = null
            }
        }
    }
}
