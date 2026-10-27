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
import io.github.jqssun.airplay.onboarding.AutoLaunchStep

/** A single optional decision, followed by a persistent, readable path if it is declined. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutoLaunchPermissionDialog(
    step: AutoLaunchStep,
    onOpenSettings: () -> Unit,
    onDefer: () -> Unit,
    onClose: () -> Unit,
) {
    if (step == AutoLaunchStep.NONE) return
    val firstFocus = remember { FocusRequester() }
    val intro = step == AutoLaunchStep.INTRO
    val title = when (step) {
        AutoLaunchStep.INTRO -> "让投屏画面自动打开"
        AutoLaunchStep.ENABLED -> "已经开启"
        AutoLaunchStep.UNAVAILABLE -> "请从电视设置中开启"
        else -> "以后也可以开启"
    }
    LaunchedEffect(step) { firstFocus.requestFocusUntilLanded() }
    Dialog(
        onDismissRequest = { if (intro) onDefer() else onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().heightIn(max = maxHeight),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = Color.White,
            ) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Image(painterResource(R.drawable.yunyu_mark), null, Modifier.size(64.dp))
                        Text(title, color = Color.White, fontSize = 28.sp, lineHeight = 36.sp,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    }
                    val explanation = when (step) {
                        AutoLaunchStep.INTRO -> "退出云屿投屏、回到电视桌面后，手机下次投屏时也能自动打开画面。"
                        AutoLaunchStep.ENABLED -> "自动打开画面的权限已开启，可以返回首页开始投屏。电视需要保持开机并联网。"
                        AutoLaunchStep.UNAVAILABLE -> "这台电视没有打开授权页面。可以在电视系统的应用设置中，允许“云屿投屏”显示在其他应用上层。"
                        else -> "暂不开启也能投屏。使用前，先在电视上打开“云屿投屏”即可。"
                    }
                    Text(explanation, color = Color.White, fontSize = 21.sp, lineHeight = 32.sp)
                    if (intro) {
                        Text("如出现应用列表，先选“云屿投屏”。允许“显示在其他应用上层”后按返回。",
                            color = Color.White, fontSize = 20.sp, lineHeight = 30.sp)
                        Text("暂不开启时，先在电视上打开本应用，也能正常投屏。",
                            color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 19.sp, lineHeight = 28.sp)
                    } else if (step != AutoLaunchStep.ENABLED) {
                        Text("以后可从这里开启：", color = Color.White, fontSize = 20.sp)
                        Text("云屿投屏 → 画面设置\n投屏时自动打开画面 → 去开启",
                            color = MaterialTheme.colorScheme.primary, fontSize = 22.sp, lineHeight = 34.sp,
                            fontWeight = FontWeight.Medium)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            shape = ButtonDefaults.shape,
                            onClick = { if (intro) onOpenSettings() else onClose() },
                            modifier = Modifier.heightIn(min = 56.dp).focusRequester(firstFocus).dpadFocus(ButtonDefaults.shape),
                            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
                        ) { Text(if (intro) "去开启" else if (step == AutoLaunchStep.ENABLED) "开始投屏" else "知道了", fontSize = 20.sp) }
                        if (intro) OutlinedButton(
                            shape = ButtonDefaults.shape,
                            onClick = onDefer,
                            modifier = Modifier.heightIn(min = 56.dp).dpadFocus(ButtonDefaults.shape),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
                        ) { Text("暂不开启", color = Color.White, fontSize = 20.sp) }
                    }
                }
            }
        }
    }
}
