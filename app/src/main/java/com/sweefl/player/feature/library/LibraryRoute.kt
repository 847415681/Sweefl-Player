package com.sweefl.player.feature.library

import com.sweefl.player.data.preferences.PlayerPreferences
import com.sweefl.player.feature.library.ui.LibraryScreen
import com.sweefl.player.feature.network.NetworkDialog
import com.sweefl.player.feature.settings.rememberGlassEnabled

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

fun mediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

@Composable
fun LibraryRoute(model: LibraryViewModel, settings: PlayerPreferences,
    loadThumbnail: suspend (android.net.Uri) -> android.graphics.Bitmap?,
    onPlay: (android.net.Uri, String?, String?, String?, String?) -> Unit) {
    val context = LocalContext.current
    fun hasAccess() = mediaPermissions().any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    var access by remember { mutableStateOf(hasAccess()) }
    var networkDialog by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    val state by model.state.collectAsStateWithLifecycle()
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = hasAccess()
        model.refresh(access)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { /* Some providers only grant temporary access. */ }
            onPlay(it, null, null, null, null)
        }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                access = hasAccess()
                model.refresh(access)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LibraryScreen(state, access, glassEnabled = rememberGlassEnabled(settings),
        loadThumbnail = loadThumbnail, onGlassChanged = { settings.glassEnabled = it }, onClearHistory = model::clearHistory,
        onPermission = { permissions.launch(mediaPermissions()) },
        onOpen = { filePicker.launch(arrayOf("video/*")) },
        onNetwork = { networkDialog = true },
        onRefresh = { model.refresh(hasAccess()) },
        onPlay = { uri, title -> onPlay(uri, title, null, null, null) })
    if (networkDialog) NetworkDialog(defaultUserAgent = settings.defaultUserAgent, onDismiss = { networkDialog = false }) { uri, userAgent, mode, mime ->
        networkDialog = false
        settings.defaultUserAgent = userAgent
        onPlay(uri, null, userAgent, mode, mime)
    }
}
