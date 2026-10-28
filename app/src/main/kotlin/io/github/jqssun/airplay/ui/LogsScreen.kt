package io.github.jqssun.airplay.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.viewmodel.MainViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(viewModel: MainViewModel) {
    val logs by viewModel.logs.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val previousFocus = remember { FocusRequester() }
    var followingLatest by remember { mutableStateOf(true) }
    // Freeze the bounded snapshot while browsing: even eviction of old log lines
    // must not move the page the reader is looking at.
    var visibleLogs by remember { mutableStateOf(logs) }
    var pageJob by remember { mutableStateOf<Job?>(null) }
    val dragging by listState.interactionSource.collectIsDraggedAsState()

    LaunchedEffect(dragging) {
        if (dragging) followingLatest = false
    }

    LaunchedEffect(logs, followingLatest) {
        if (followingLatest) {
            visibleLogs = logs
            if (logs.isNotEmpty()) listState.scrollToItem(logs.lastIndex)
        }
    }
    LaunchedEffect(visibleLogs.isNotEmpty()) {
        if (visibleLogs.isNotEmpty()) previousFocus.requestFocusUntilLanded()
    }

    fun turnPage(direction: Int) {
        if (visibleLogs.isEmpty()) return
        followingLatest = false
        pageJob?.cancel()
        pageJob = scope.launch {
            val layout = listState.layoutInfo
            // Keep some overlap so a wrapped line is not lost between pages.
            val distance = (layout.viewportEndOffset - layout.viewportStartOffset).coerceAtLeast(1) * 0.85f
            listState.scrollBy(direction * distance)
        }
    }

    val visibleRange by remember {
        derivedStateOf {
            val items = listState.layoutInfo.visibleItemsInfo
            if (items.isEmpty()) "暂无日志" else "第 ${items.first().index + 1}–${items.last().index + 1} 条，共 ${visibleLogs.size} 条"
        }
    }

    Scaffold(
        modifier = Modifier.onPreviewKeyEvent { event ->
            val direction = when (event.key) {
                Key.PageUp -> -1
                Key.PageDown -> 1
                else -> return@onPreviewKeyEvent false
            }
            if (event.type == KeyEventType.KeyDown) turnPage(direction)
            true
        },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_logs)) },
                actions = {
                    IconButton(onClick = viewModel::exportLogs, modifier = Modifier.dpadFocus(CircleShape)) {
                        Icon(Icons.Outlined.Share, stringResource(R.string.btn_export))
                    }
                    IconButton(onClick = {
                        pageJob?.cancel()
                        viewModel.clearLogs()
                        visibleLogs = emptyList()
                        followingLatest = true
                    }, modifier = Modifier.dpadFocus(CircleShape)) {
                        Icon(Icons.Outlined.Delete, stringResource(R.string.btn_clear))
                    }
                },
            )
        },
        bottomBar = {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "$visibleRange · ${if (followingLatest) "自动显示最新" else "正在翻看，记录保持不动"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Stay focusable at the boundaries; disabling the focused button
                    // can make TV focus jump to an unrelated action.
                    FilledTonalButton(
                        onClick = { turnPage(-1) },
                        shape = ButtonDefaults.shape,
                        enabled = visibleLogs.isNotEmpty(),
                        modifier = Modifier.heightIn(min = 56.dp).focusRequester(previousFocus).dpadFocus(ButtonDefaults.shape),
                    ) { Text("上一页", style = MaterialTheme.typography.titleMedium) }
                    FilledTonalButton(
                        onClick = { turnPage(1) },
                        shape = ButtonDefaults.shape,
                        enabled = visibleLogs.isNotEmpty(),
                        modifier = Modifier.heightIn(min = 56.dp).dpadFocus(ButtonDefaults.shape),
                    ) { Text("下一页", style = MaterialTheme.typography.titleMedium) }
                    FilledTonalButton(
                        onClick = {
                            pageJob?.cancel()
                            followingLatest = true
                            visibleLogs = logs
                            pageJob = scope.launch { if (logs.isNotEmpty()) listState.scrollToItem(logs.lastIndex) }
                        },
                        shape = ButtonDefaults.shape,
                        modifier = Modifier.heightIn(min = 56.dp).dpadFocus(ButtonDefaults.shape),
                    ) { Text("查看最新", style = MaterialTheme.typography.titleMedium) }
                }
            }
        },
    ) { padding ->
        SelectionContainer(Modifier.padding(padding)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
                    .onPreviewKeyEvent { event ->
                        val direction = when (event.key) {
                            Key.DirectionUp -> -1
                            Key.DirectionDown -> 1
                            else -> return@onPreviewKeyEvent false
                        }
                        val canScroll = if (direction < 0) listState.canScrollBackward else listState.canScrollForward
                        if (!canScroll) return@onPreviewKeyEvent false
                        if (event.type == KeyEventType.KeyDown) turnPage(direction)
                        true
                    }.focusable(),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(visibleLogs) {
                    Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
