package io.github.jqssun.airplay.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jqssun.airplay.R

/** TV home keeps its controls inside the viewport; diagnostics belong in the help dialog. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CastWelcome(
    serverName: String,
    status: String,
    ready: Boolean,
    resolution: String,
    fps: Int,
    airPlayStatus: String,
    dlnaStatus: String,
    networkAddresses: List<String>,
    playbackError: String,
    actionLabel: String,
    onAction: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLogs: () -> Unit,
    onRefreshDiscovery: () -> Unit,
    actionFocus: FocusRequester,
    updateLabel: String = "检查更新",
    onOpenUpdates: () -> Unit = {}
) {
    var showHelp by remember { mutableStateOf(false) }
    val quality = when (resolution) {
        "1920x1080" -> "1080p"
        "2560x1440" -> "2K"
        "3840x2160" -> "4K"
        "auto" -> "自动"
        "portrait" -> "竖屏"
        "landscape" -> "横屏"
        else -> resolution.replace("x", " × ")
    }
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val wide = maxWidth >= 600.dp && maxWidth > maxHeight
        // A short viewport or larger system text must scroll instead of squeezing the
        // weighted header to zero height and drawing it outside the TV safe area.
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val fixedHeight = wide && maxWidth >= 760.dp * fontScale && maxHeight >= 480.dp * fontScale
        val compact = maxHeight < 480.dp || maxWidth < 800.dp
        val edgeX = if (wide) if (compact) 36.dp else 48.dp else 24.dp
        val edgeY = if (compact) 24.dp else 32.dp
        val gap = if (compact) 18.dp else 28.dp
        val titleSize = if (compact) 32.sp else 38.sp
        val bodySize = if (compact) 18.sp else 20.sp
        val pageScroll = rememberScrollState()
        val identity: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Image(painterResource(R.drawable.yunyu_mark), null, Modifier.size(if (compact) 54.dp else 64.dp))
                    Text(serverName, modifier = Modifier.weight(1f), fontSize = titleSize,
                        lineHeight = if (compact) 40.sp else 46.sp, fontWeight = FontWeight.SemiBold,
                        color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    if (networkAddresses.isEmpty() && ready) "请先连接电视网络" else status,
                    fontSize = bodySize, lineHeight = 28.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Text("手机和电视连同一个 Wi-Fi\n在手机上选择「$serverName」",
                    fontSize = bodySize, lineHeight = 29.sp, color = Color.White)
                Text("画质  $quality  /  $fps 帧", fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (playbackError.isNotBlank()) {
                    Text("影片未播放，请打开“连接帮助”", fontSize = 18.sp, lineHeight = 26.sp,
                        color = MaterialTheme.colorScheme.error)
                }
            }
        }
        val instructions: @Composable () -> Unit = {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(if (compact) 16.dp else 26.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 20.dp)) {
                    GuideRoute("投手机画面", "控制中心  ›  屏幕镜像", compact)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    GuideRoute("投视频、电影", "视频 App  ›  TV / 投屏", compact)
                }
            }
        }
        Column(
            Modifier.fillMaxSize()
                .then(if (fixedHeight) Modifier else Modifier.verticalScroll(pageScroll))
                .padding(horizontal = edgeX, vertical = edgeY),
            verticalArrangement = Arrangement.spacedBy(gap)
        ) {
            if (wide) {
                Row(Modifier.fillMaxWidth().then(if (fixedHeight) Modifier.weight(1f) else Modifier),
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 28.dp else 40.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(0.95f)) { identity() }
                    Box(Modifier.weight(1.05f)) { instructions() }
                }
            } else {
                identity()
                instructions()
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(shape = ButtonDefaults.shape, onClick = onOpenSettings,
                        modifier = Modifier.heightIn(min = 56.dp)
                            .then(if (ready) Modifier.focusRequester(actionFocus) else Modifier).dpadFocus(ButtonDefaults.shape),
                        contentPadding = PaddingValues(horizontal = 26.dp, vertical = 12.dp)) {
                        Icon(Icons.Default.Settings, null, Modifier.size(24.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("画面设置", fontSize = 20.sp)
                    }
                    OutlinedButton(shape = ButtonDefaults.shape, onClick = { showHelp = true },
                        modifier = Modifier.heightIn(min = 56.dp).dpadFocus(ButtonDefaults.shape),
                        contentPadding = PaddingValues(horizontal = 26.dp, vertical = 12.dp)) {
                        Icon(Icons.Default.HelpOutline, null, Modifier.size(24.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("连接帮助", fontSize = 20.sp)
                    }
                    OutlinedButton(shape = ButtonDefaults.shape, onClick = onOpenUpdates,
                        modifier = Modifier.heightIn(min = 56.dp).dpadFocus(ButtonDefaults.shape),
                        contentPadding = PaddingValues(horizontal = 26.dp, vertical = 12.dp)) {
                        Icon(Icons.Default.SystemUpdateAlt, null, Modifier.size(24.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(updateLabel, fontSize = 20.sp)
                    }
                    if (!ready) {
                        FilledTonalButton(shape = ButtonDefaults.shape, onClick = onAction, modifier = Modifier.heightIn(min = 56.dp)
                            .focusRequester(actionFocus).dpadFocus(ButtonDefaults.shape)) {
                            Text(actionLabel, fontSize = 20.sp)
                        }
                    }
                }
                Text("不用开关接收。手机可停止投屏，遥控器返回可退出。",
                    fontSize = if (compact) 16.sp else 18.sp, lineHeight = 25.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (showHelp) {
        val closeFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { closeFocus.requestFocusUntilLanded() }
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("连接帮助", fontSize = 26.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(Modifier.heightIn(max = 250.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("手机和电视连接同一局域网，再从手机选择「$serverName」。找不到设备时，点下方“重新查找”。",
                        fontSize = 18.sp, lineHeight = 27.sp)
                    Text("iPhone 镜像按钮若显示 Mac 名称，点开它再选电视。视频 App 需要支持 DLNA 或 AirPlay。",
                        fontSize = 17.sp, lineHeight = 26.sp)
                    HorizontalDivider()
                    Text("AirPlay：$airPlayStatus\nDLNA：$dlnaStatus\n电视 IP：${networkAddresses.joinToString(" / ").ifBlank { "未取得" }}",
                        fontSize = 16.sp, lineHeight = 25.sp)
                    if (playbackError.isNotBlank()) {
                        Text(playbackError.take(500), fontSize = 17.sp, lineHeight = 26.sp,
                            color = MaterialTheme.colorScheme.error)
                    }
                    Text("画质由手机、电视和网络共同决定。受保护影片或 App 的设备限制可能影响播放。",
                        fontSize = 16.sp, lineHeight = 24.sp)
                }
            },
            confirmButton = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(shape = ButtonDefaults.shape, onClick = onRefreshDiscovery, enabled = ready, modifier = Modifier.dpadFocus(ButtonDefaults.shape)) {
                        Text("重新查找", fontSize = 18.sp)
                    }
                    TextButton(shape = ButtonDefaults.shape, onClick = { showHelp = false; onOpenLogs() }, modifier = Modifier.dpadFocus(ButtonDefaults.shape)) {
                        Text("查看日志", fontSize = 18.sp)
                    }
                    Button(shape = ButtonDefaults.shape, onClick = { showHelp = false }, modifier = Modifier.focusRequester(closeFocus).dpadFocus(ButtonDefaults.shape)) {
                        Text("知道了", fontSize = 18.sp)
                    }
                }
            }
        )
    }
}

@Composable
private fun GuideRoute(title: String, route: String, compact: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)) {
        Text(title, fontSize = if (compact) 23.sp else 26.sp, lineHeight = if (compact) 28.sp else 33.sp, fontWeight = FontWeight.SemiBold)
        Text(route, fontSize = if (compact) 19.sp else 22.sp, lineHeight = if (compact) 26.sp else 30.sp,
            color = MaterialTheme.colorScheme.primary)
    }
}
