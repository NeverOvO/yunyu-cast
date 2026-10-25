package io.github.jqssun.airplay.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.update.AppUpdateState
import io.github.jqssun.airplay.update.UpdatePhase
import java.util.Locale

/** A single, remote-friendly decision at every stage of a user-requested update. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppUpdateDialog(
    state: AppUpdateState,
    onDismiss: () -> Unit,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    if (!state.dialogVisible) return

    val phase = state.phase
    val primaryFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }
    val primaryLabel = when (phase) {
        UpdatePhase.IDLE -> "检查更新"
        UpdatePhase.UP_TO_DATE -> "知道了"
        UpdatePhase.AVAILABLE -> "下载并安装"
        UpdatePhase.DOWNLOADING -> "稍后安装"
        UpdatePhase.READY -> "安装更新"
        UpdatePhase.INSTALL_PERMISSION -> "允许安装更新"
        UpdatePhase.ERROR -> "重试"
        UpdatePhase.CHECKING, UpdatePhase.VERIFYING -> null
    }
    val title = when (phase) {
        UpdatePhase.IDLE -> "软件更新"
        UpdatePhase.CHECKING -> "正在检查更新"
        UpdatePhase.UP_TO_DATE -> "已经是最新版"
        UpdatePhase.AVAILABLE -> "发现新版本"
        UpdatePhase.DOWNLOADING -> "正在下载新版"
        UpdatePhase.VERIFYING -> "正在准备安装"
        UpdatePhase.READY -> "新版已下载好"
        UpdatePhase.INSTALL_PERMISSION -> "先允许安装更新"
        UpdatePhase.ERROR -> "更新还未完成"
    }
    val explanation = state.message.ifBlank {
        when (phase) {
            UpdatePhase.IDLE -> "点一下检查，就能知道有没有新版。"
            UpdatePhase.CHECKING -> "稍等一下，正在查看有没有新版。"
            UpdatePhase.UP_TO_DATE -> "可以继续放心投屏。"
            UpdatePhase.AVAILABLE -> "点“下载并安装”，电视会帮你下载。"
            UpdatePhase.DOWNLOADING -> "保持电视联网。下载好后会打开安装页面。"
            UpdatePhase.VERIFYING -> "下载已完成，正在准备安装文件。"
            UpdatePhase.READY -> "点“安装更新”，再按电视上的提示确认。"
            UpdatePhase.INSTALL_PERMISSION -> "首次更新需要在系统页面允许“云屿投屏”安装应用。开启后按返回，继续安装。"
            UpdatePhase.ERROR -> "可以点“重试”，或稍后再来。当前版本仍可继续投屏。"
        }
    }
    val highlights = remember(state.release?.notes) { firstUpdateParagraph(state.release?.notes.orEmpty()) }
    LaunchedEffect(phase) {
        if (primaryLabel == null) closeFocus.requestFocusUntilLanded()
        else primaryFocus.requestFocusUntilLanded()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().heightIn(max = maxHeight),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = Color.White,
            ) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Image(painterResource(R.drawable.yunyu_mark), null, Modifier.size(64.dp))
                        Text(title, color = Color.White, fontSize = 28.sp, lineHeight = 36.sp,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    }
                    Text(
                        if (state.release == null) "当前版本  ${state.installedVersion}"
                        else "当前版本  ${state.installedVersion}\n新版本  ${state.release.versionName}",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontSize = 20.sp, lineHeight = 30.sp,
                    )
                    Text(explanation, color = if (phase == UpdatePhase.ERROR) MaterialTheme.colorScheme.error else Color.White,
                        fontSize = 20.sp, lineHeight = 30.sp)

                    if (phase == UpdatePhase.AVAILABLE && highlights.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(highlights, color = Color.White, fontSize = 20.sp, lineHeight = 30.sp)
                    }
                    if (phase == UpdatePhase.DOWNLOADING) {
                        val total = state.totalBytes.takeIf { it > 0L } ?: state.release?.sizeBytes ?: 0L
                        val received = state.downloadedBytes.coerceAtLeast(0)
                        if (total > 0L) {
                            val progress = (received.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
                            Text("${(progress * 100).toInt()}%", color = Color.White,
                                fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp))
                            Text("${formatUpdateSize(received)} / ${formatUpdateSize(total)}", color = Color.White,
                                fontSize = 20.sp)
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(8.dp))
                            Text("已下载 ${formatUpdateSize(received)}", color = Color.White, fontSize = 20.sp)
                        }
                    } else if (phase == UpdatePhase.CHECKING || phase == UpdatePhase.VERIFYING) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(8.dp))
                    } else if (phase == UpdatePhase.AVAILABLE && (state.release?.sizeBytes ?: 0) > 0) {
                        Text("下载大小  ${formatUpdateSize(state.release!!.sizeBytes)}", color = Color.White, fontSize = 20.sp)
                    }

                    if (phase == UpdatePhase.AVAILABLE || phase == UpdatePhase.READY || phase == UpdatePhase.INSTALL_PERMISSION) {
                        Text("安装时会暂时退出投屏。完成后重新打开“云屿投屏”即可。",
                            color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 20.sp, lineHeight = 30.sp)
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (primaryLabel != null) {
                            Button(
                                shape = ButtonDefaults.shape,
                                onClick = {
                                    when (phase) {
                                        UpdatePhase.IDLE -> onCheck()
                                        UpdatePhase.AVAILABLE -> onDownload()
                                        UpdatePhase.READY, UpdatePhase.INSTALL_PERMISSION -> onInstall()
                                        UpdatePhase.ERROR -> if (state.release == null) onCheck() else onDownload()
                                        else -> onDismiss()
                                    }
                                },
                                modifier = Modifier.heightIn(min = 56.dp).focusRequester(primaryFocus).dpadFocus(ButtonDefaults.shape),
                                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
                            ) {
                                Text(primaryLabel, fontSize = 20.sp)
                            }
                        }
                        when (phase) {
                            UpdatePhase.DOWNLOADING -> TextButton(
                                shape = ButtonDefaults.shape,
                                onClick = onCancelDownload,
                                modifier = Modifier.heightIn(min = 56.dp).dpadFocus(ButtonDefaults.shape),
                                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
                            ) { Text("取消下载", color = Color.White, fontSize = 20.sp) }
                            UpdatePhase.UP_TO_DATE -> Unit
                            else -> OutlinedButton(
                                shape = ButtonDefaults.shape,
                                onClick = onDismiss,
                                modifier = Modifier.heightIn(min = 56.dp)
                                    .then(if (primaryLabel == null) Modifier.focusRequester(closeFocus) else Modifier).dpadFocus(ButtonDefaults.shape),
                                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
                            ) {
                                Text(if (phase == UpdatePhase.VERIFYING) "稍后安装" else "以后再说",
                                    color = Color.White, fontSize = 20.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatUpdateSize(bytes: Long): String =
    String.format(Locale.ROOT, "%.1f MB", bytes.coerceAtLeast(0).toDouble() / (1024 * 1024))

/** Release notes are plain display text; keep only the first non-heading paragraph. */
private fun firstUpdateParagraph(notes: String): String {
    val lines = mutableListOf<String>()
    for (line in notes.take(8_192).lineSequence()) {
        val text = line.trim()
        if (text.isEmpty() || text.startsWith('#') || text.startsWith("```")) {
            if (lines.isNotEmpty()) break
            continue
        }
        lines += text
        if (lines.sumOf { it.length } >= 260) break
    }
    return lines.joinToString(" ")
        .replace(Regex("!?\\[([^]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("https?://\\S+"), "")
        .replace("**", "")
        .replace("`", "")
        .trim()
        .take(280)
}
