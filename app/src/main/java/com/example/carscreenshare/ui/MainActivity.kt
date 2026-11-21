package com.example.carscreenshare.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.carscreenshare.capture.ScreenCaptureService
import com.example.carscreenshare.input.TouchInputService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { SetupScreen() }
            }
        }
    }
}

private val runtimePermissions = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    add("com.google.android.gms.permission.CAR_SPEED")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

@Composable
private fun SetupScreen() {
    val context = LocalContext.current
    val sharing by ScreenCaptureService.sharing.collectAsStateWithLifecycle()

    // Bumped on resume so the status of each step is re-read after visiting Settings.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    val permissionsGranted = remember(refresh) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }
    val touchEnabled = remember(refresh) { TouchInputService.isEnabled(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh++ }

    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ScreenCaptureService.start(context, result.resultCode, data)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Car Screen Share", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Shows your phone screen on the Android Auto display. The picture is hidden " +
                "automatically whenever the car is moving.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Step(
            number = 1,
            title = "Allow location & notifications",
            body = "Vehicle speed decides when sharing is allowed. The car's own speed is used " +
                "when available; the phone's GPS is the backup.",
            done = permissionsGranted,
        ) {
            Button(onClick = { permissionLauncher.launch(runtimePermissions) }) { Text("Grant") }
        }

        Step(
            number = 2,
            title = "Turn on touch control (optional)",
            body = "Lets you tap and scroll on the car screen. In Accessibility, open " +
                "\"Car Screen Share\" and switch it on.\n\nIf Android says it's a restricted " +
                "setting: open App info for Car Screen Share, tap ⋮ (top right) → " +
                "\"Allow restricted settings\", then try again.",
            done = touchEnabled,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) { Text("Accessibility") }
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    )
                }) { Text("App info") }
            }
        }

        Step(
            number = 3,
            title = "Allow the app in Android Auto (one time)",
            body = "Because this app isn't from the Play Store, Android Auto hides it until you:\n" +
                "1. Open Android Auto settings and tap \"Version\" 10 times to unlock developer mode.\n" +
                "2. ⋮ menu → Developer settings → tick \"Unknown sources\".\n" +
                "3. In Android Auto settings → Customize launcher, make sure Car Screen Share is shown.",
            done = null,
        ) {
            OutlinedButton(onClick = { openAndroidAutoSettings(context) }) { Text("Android Auto settings") }
        }

        Step(
            number = 4,
            title = if (sharing) "Sharing is on" else "Start sharing",
            body = if (sharing) {
                "Open Car Screen Share on the car display. Keep the phone unlocked; " +
                    "anything on the phone screen shows in the car while parked."
            } else {
                "Android will ask what to share — choose \"Entire screen\" to mirror everything, " +
                    "or a single app."
            },
            done = sharing,
        ) {
            if (sharing) {
                OutlinedButton(onClick = { ScreenCaptureService.stop(context) }) { Text("Stop sharing") }
            } else {
                Button(onClick = {
                    val mpm = context.getSystemService(MediaProjectionManager::class.java)
                    captureLauncher.launch(mpm.createScreenCaptureIntent())
                }) { Text("Start sharing") }
            }
        }

        Text(
            "Audio keeps playing through the car as usual; only the picture is mirrored.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Step(
    number: Int,
    title: String,
    body: String,
    done: Boolean?,
    actions: @Composable () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$number. $title", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (done == true) Text("✓ Done", color = MaterialTheme.colorScheme.primary)
            }
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.size(4.dp))
            actions()
        }
    }
}

private fun openAndroidAutoSettings(context: android.content.Context) {
    val aaPackage = "com.google.android.projection.gearhead"
    val intent = Intent("com.google.android.projection.gearhead.SETTINGS").setPackage(aaPackage)
    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$aaPackage"))
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        context.startActivity(fallback)
    }
}
