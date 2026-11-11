package dev.lyra.music.ui.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.lyra.music.R

object Permissions {
    val audioPermission: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasAudio(context: Context) =
        ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED

    fun hasNotifications(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun hasAllFiles(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun allFilesIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))

    fun batteryIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
}

/** Re-evaluates [check] every time the activity resumes (user may return from Settings). */
@Composable
fun rememberPermissionState(check: () -> Boolean): Boolean {
    val owner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(check()) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) granted = check() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return granted
}

/**
 * Multi-step onboarding: (1) audio permission (required), (2) notifications (Android 13+),
 * (3) all-files access (optional), (4) battery optimisation (optional, OriginOS-specific hint).
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    val audioGranted = rememberPermissionState { Permissions.hasAudio(context) }
    val notifGranted = rememberPermissionState { Permissions.hasNotifications(context) }
    val allFiles = rememberPermissionState { Permissions.hasAllFiles() }
    val battery = rememberPermissionState { Permissions.isIgnoringBatteryOptimizations(context) }

    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) step = 1
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { step = 2 }

    // Auto-advance past steps that are already satisfied.
    val effectiveStep = when {
        !audioGranted -> 0
        step <= 1 && !notifGranted -> 1
        step <= 2 && !allFiles -> 2
        step <= 3 && !battery -> 3
        else -> 4
    }
    LaunchedEffect(effectiveStep) { if (effectiveStep == 4) onDone() }
    if (effectiveStep == 4) return

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (effectiveStep) {
            0 -> PermissionStep(
                title = stringResource(R.string.perm_title),
                body = stringResource(R.string.perm_audio_rationale),
                primary = stringResource(R.string.perm_grant),
                onPrimary = { audioLauncher.launch(Permissions.audioPermission) },
                secondary = stringResource(R.string.perm_open_settings),
                onSecondary = { context.startActivity(Permissions.appSettingsIntent(context)) },
            )
            1 -> PermissionStep(
                title = stringResource(R.string.notification_channel_playback),
                body = stringResource(R.string.notification_channel_playback_desc),
                primary = stringResource(R.string.perm_grant),
                onPrimary = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else step = 2
                },
                secondary = stringResource(R.string.skip),
                onSecondary = { step = 2 },
            )
            2 -> PermissionStep(
                title = stringResource(R.string.perm_all_files_title),
                body = stringResource(R.string.perm_all_files_rationale),
                primary = stringResource(R.string.perm_grant),
                onPrimary = { runCatching { context.startActivity(Permissions.allFilesIntent(context)) }; step = 3 },
                secondary = stringResource(R.string.skip),
                onSecondary = { step = 3 },
            )
            else -> PermissionStep(
                title = stringResource(R.string.perm_battery_title),
                body = stringResource(R.string.perm_battery_rationale),
                primary = stringResource(R.string.perm_grant),
                onPrimary = { runCatching { context.startActivity(Permissions.batteryIntent(context)) }; step = 4 },
                secondary = stringResource(R.string.skip),
                onSecondary = { step = 4 },
            )
        }
    }
}

@Composable
private fun PermissionStep(
    title: String, body: String,
    primary: String, onPrimary: () -> Unit,
    secondary: String, onSecondary: () -> Unit,
) {
    Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Spacer(Modifier.height(16.dp))
    Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(32.dp))
    Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth()) { Text(primary) }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onSecondary) { Text(secondary) }
}
