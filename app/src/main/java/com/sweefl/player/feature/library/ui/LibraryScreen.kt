package com.sweefl.player.feature.library.ui

import com.sweefl.player.core.designsystem.GlassIconButton as IconButton
import com.sweefl.player.core.designsystem.GlassTextButton as FilledTonalButton
import com.sweefl.player.core.designsystem.GlassTextField as OutlinedTextField
import com.sweefl.player.core.designsystem.AuroraBackground
import com.sweefl.player.core.designsystem.Mint
import com.sweefl.player.core.designsystem.Muted
import com.sweefl.player.core.designsystem.GlassSurface
import com.sweefl.player.core.designsystem.contentSurface
import com.sweefl.player.core.designsystem.ComposeBackdropGlassSurface
import com.sweefl.player.core.designsystem.rememberComposeBackdrop
import com.sweefl.player.core.designsystem.composeBackdropSource
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.sweefl.player.core.model.Video
import com.sweefl.player.domain.formatDuration
import com.sweefl.player.feature.library.LibraryState
import com.sweefl.player.feature.settings.SettingsDialog

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LibraryScreen(
    state: LibraryState, access: Boolean, glassEnabled: Boolean,
    loadThumbnail: suspend (Uri) -> Bitmap?, onGlassChanged: (Boolean) -> Unit, onClearHistory: () -> Unit,
    onPermission: () -> Unit, onOpen: () -> Unit, onNetwork: () -> Unit, onRefresh: () -> Unit,
    onPlay: (Uri, String?) -> Unit
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var search by rememberSaveable { mutableStateOf("") }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var newest by rememberSaveable { mutableStateOf(true) }
    var settings by remember { mutableStateOf(false) }
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val lastUri = state.bookmark?.uri
    val lastTitle = state.bookmark?.title.orEmpty()
    val filtered = remember(state.videos, search, folder, newest) {
        state.videos.filter { (folder == null || it.folder == folder) && it.title.contains(search, ignoreCase = true) }
            .let { if (newest) it.sortedByDescending(Video::added) else it.sortedBy { video -> video.title.lowercase() } }
    }
    BackHandler(folder != null) { folder = null }
    val backdrop = rememberComposeBackdrop()
    val density = LocalDensity.current
    var navigationHeight by remember { mutableIntStateOf(0) }
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val listBottom = with(density) { navigationHeight.toDp() } + bottomInset + 24.dp
    Box(Modifier.fillMaxSize()) {
      Box(Modifier.fillMaxSize().composeBackdropSource(backdrop)) {
       AuroraBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("SWEEFL", color = Mint, fontSize = 11.sp, letterSpacing = 4.sp, fontWeight = FontWeight.Bold)
                    if (wide) Text("你的随身影院", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                GlassSurface(radius = 100) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onOpen) { Icon(Icons.Rounded.Add, "打开文件") }
                        IconButton(onClick = onNetwork) { Icon(Icons.Rounded.Link, "打开网络视频") }
                        IconButton(onClick = { settings = true }) { Icon(Icons.Rounded.Tune, "设置") }
                    }
                }
            }
            if (!wide) Text("你的随身影院", Modifier.padding(bottom = 18.dp), fontSize = 29.sp, fontWeight = FontWeight.Bold)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = listBottom)) {
                if (lastUri != null && search.isBlank() && folder == null) {
                    item {
                        Column(Modifier.fillMaxWidth().contentSurface().clickable { onPlay(lastUri, lastTitle) }.padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.PlayCircle, null, tint = Mint, modifier = Modifier.size(42.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("继续上次的精彩", color = Mint, fontSize = 12.sp)
                                    Text(lastTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                    Text("从 ${formatDuration(state.bookmark?.positionMs ?: 0L)} 播放", color = Muted, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
                item {
                    OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("搜索视频") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Icon(Icons.Rounded.Close, "清空搜索") } },
                        shape = RoundedCornerShape(24.dp), singleLine = true)
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (folder != null) IconButton(onClick = { folder = null }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回文件夹") }
                        Column(Modifier.weight(1f)) {
                            Text(folder?.substringAfterLast('/') ?: if (tab == 0) "所有视频" else "文件夹", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (access) "${filtered.size} 个视频 · 设备本地" else "让好内容触手可及", color = Muted, fontSize = 12.sp)
                        }
                        IconButton(onClick = { newest = !newest }) { Icon(Icons.Rounded.Sort, if (newest) "当前按最新排序，切换名称排序" else "当前按名称排序，切换最新排序") }
                        IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, "刷新媒体库") }
                    }
                }
                if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Mint) }
                if (state.error != null) item { InfoCard("读取遇到问题", state.error, "重试", onRefresh) }
                if (!access) {
                    item { InfoCard("开启你的私人影院", "允许访问视频后，自动整理本地影片和文件夹。你也可以直接打开单个文件。", "选择可访问的视频", onPermission) }
                } else if (!state.loading && filtered.isEmpty()) {
                    item { InfoCard(if (search.isBlank()) "这里还没有视频" else "未找到匹配视频", "可刷新媒体库，或使用右上角按钮打开文件。Android 14 及以上也可重新选择授权的视频。", "管理视频访问权限", onPermission) }
                }
                if (tab == 1 && folder == null && search.isBlank()) {
                    items(filtered.groupBy(Video::folder).toList(), key = { it.first }) { (path, videos) ->
                        Row(Modifier.fillMaxWidth().contentSurface(24).clickable { folder = path }.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Folder, null, tint = Mint, modifier = Modifier.size(34.dp))
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(path.substringAfterLast('/'), fontWeight = FontWeight.SemiBold)
                                Text(path, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("${videos.size}", color = Mint)
                        }
                    }
                } else {
                    items(filtered, key = { it.uri.toString() }) { video ->
                        VideoRow(video, loadThumbnail) { onPlay(video.uri, video.title) }
                    }
                }
            }
        }
       }
      }
      // Separate overlay sibling: it is never recorded into its own optical input.
      ComposeBackdropGlassSurface(backdrop,
          Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
              .padding(horizontal = 20.dp, vertical = 12.dp)
              .widthIn(max = 360.dp).fillMaxWidth()
              .onSizeChanged { navigationHeight = it.height }, enabled = glassEnabled) {
                Row(Modifier.fillMaxWidth().selectableGroup().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    LibraryTab("视频", Icons.Rounded.VideoLibrary, tab == 0, { tab = 0; folder = null }, Modifier.weight(1f))
                    LibraryTab("文件夹", Icons.Rounded.Folder, tab == 1, { tab = 1; folder = null }, Modifier.weight(1f))
                }
            }
    }
    if (settings) SettingsDialog(glassEnabled, onGlassChanged, onClearHistory, onPermission) { settings = false }
}

@Composable
private fun LibraryTab(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val highlight by animateColorAsState(if (selected) Color.White.copy(alpha = .13f) else Color.Transparent,
        animationSpec = tween(180), label = "tab-highlight")
    val foreground by animateColorAsState(if (selected) Mint else Muted,
        animationSpec = tween(180), label = "tab-color")
    Column(modifier.clip(RoundedCornerShape(100.dp)).background(highlight)
        .selectable(selected = selected, role = Role.Tab, onClick = onClick)
        .heightIn(min = 68.dp).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, Modifier.size(26.dp), tint = foreground)
        Spacer(Modifier.height(4.dp))
        Text(label, color = foreground, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun InfoCard(title: String, body: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().contentSurface().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Rounded.Movie, null, tint = Mint, modifier = Modifier.size(36.dp))
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 21.sp)
        Text(body, color = Muted, fontSize = 14.sp)
        FilledTonalButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun VideoRow(video: Video, loadThumbnail: suspend (Uri) -> Bitmap?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().contentSurface(24).clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        VideoThumbnail(video, loadThumbnail)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(video.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(video.folder.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis, color = Muted, fontSize = 12.sp)
            Text("${formatDuration(video.durationMs)}  ·  ${video.size / (1024 * 1024)} MB", color = Mint, fontSize = 11.sp)
        }
        Icon(Icons.Rounded.PlayArrow, null, tint = Mint, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun VideoThumbnail(video: Video, loadThumbnail: suspend (Uri) -> Bitmap?) {
    val bitmap by produceState<Bitmap?>(null, video.uri, loadThumbnail) {
        value = loadThumbnail(video.uri)
    }
    Box(Modifier.size(96.dp, 72.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF28384B)), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) Image(image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Rounded.Movie, null, tint = Muted)
    }
}
