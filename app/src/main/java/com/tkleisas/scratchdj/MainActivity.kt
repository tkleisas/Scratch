package com.tkleisas.scratchdj

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.tkleisas.scratchdj.ui.DjScreen
import com.tkleisas.scratchdj.ui.PermissionScreen
import com.tkleisas.scratchdj.ui.theme.ScratchTheme

class MainActivity : ComponentActivity() {

    private lateinit var controller: DjController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        controller = (application as ScratchApplication).controller
        setContent {
            ScratchTheme(theme = controller.theme.value) {
                ScratchRoot(controller)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    private fun hideSystemBars() {
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}

@Composable
private fun ScratchRoot(controller: DjController) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(hasAudioPermission(context)) }
    var requestedOnce by rememberSaveable { mutableStateOf(false) }
    var notificationRequested by rememberSaveable { mutableStateOf(false) }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notificationRequested = true }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result ->
        granted = result
        requestedOnce = true
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = hasAudioPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        if (!granted && !requestedOnce) {
            launcher.launch(requiredAudioPermission())
        }
    }

    LaunchedEffect(granted) {
        if (granted) {
            controller.syncLibrary()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !notificationRequested &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    if (granted) {
        DjScreen(controller)
    } else {
        PermissionScreen(
            showSettings = requestedOnce,
            onRequest = { launcher.launch(requiredAudioPermission()) },
            onOpenSettings = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
        )
    }
}

private fun requiredAudioPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

private fun hasAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        requiredAudioPermission(),
    ) == PackageManager.PERMISSION_GRANTED
