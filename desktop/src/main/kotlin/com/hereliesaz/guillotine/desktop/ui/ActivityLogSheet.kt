package com.hereliesaz.guillotine.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.guillotine.desktop.ui.theme.Green500
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral300
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral400
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral500
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral800
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral950
import com.hereliesaz.guillotine.desktop.ui.theme.Red500
import com.hereliesaz.guillotine.desktop.ui.theme.White
import com.hereliesaz.guillotine.ui.ActivityLog

enum class LogPanelState { HIDDEN, PEEK, EXPANDED }

@Composable
fun ActivityLogPanel(
    panelState: MutableState<LogPanelState>,
    entries: List<ActivityLog.Entry>,
    processLabel: String?,
    processFraction: Float?,
    onClear: () -> Unit,
    awaitingReply: Boolean = false,
    onReply: (String) -> Unit = {},
    /** Tapped from an error naming an unconfigured AI Analyzer/Generation/Transcription slot. */
    onOpenAiSettings: () -> Unit = {},
    /** Tapped from an error naming the Advanced tab specifically (azphalt model installs). */
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val newestId = entries.lastOrNull()?.id ?: 0L
    LaunchedEffect(newestId) {
        if (newestId != 0L && panelState.value == LogPanelState.HIDDEN) {
            panelState.value = LogPanelState.PEEK
        }
    }
    LaunchedEffect(awaitingReply) {
        if (awaitingReply && panelState.value != LogPanelState.EXPANDED) {
            panelState.value = LogPanelState.EXPANDED
        }
    }

    AnimatedVisibility(
        visible = panelState.value != LogPanelState.HIDDEN,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = modifier,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(Neutral950)
        ) {
            DragHandle(onClick = {
                panelState.value = when (panelState.value) {
                    LogPanelState.HIDDEN -> LogPanelState.PEEK
                    LogPanelState.PEEK -> LogPanelState.EXPANDED
                    LogPanelState.EXPANDED -> LogPanelState.HIDDEN
                }
            })

            when (panelState.value) {
                LogPanelState.HIDDEN -> Unit
                LogPanelState.PEEK -> PeekTicker(
                    entries, processLabel, processFraction, awaitingReply,
                    onExpand = { panelState.value = LogPanelState.EXPANDED },
                )
                LogPanelState.EXPANDED -> ExpandedLog(
                    entries, processLabel, processFraction, onClear, awaitingReply, onReply,
                    onOpenAiSettings, onOpenSettings,
                )
            }
        }
    }
}

@Composable
private fun DragHandle(onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(32.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Neutral500)
        )
    }
}

@Composable
private fun PeekTicker(
    entries: List<ActivityLog.Entry>,
    processLabel: String?,
    processFraction: Float?,
    awaitingReply: Boolean,
    onExpand: () -> Unit,
) {
    val latest = entries.lastOrNull()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onExpand)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (processLabel != null) {
            if (processFraction != null) {
                CircularProgressIndicator(
                    progress = { processFraction },
                    modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Red500,
                )
            } else {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Red500)
            }
            Spacer(Modifier.width(8.dp))
        }
        val text = when {
            processLabel != null -> processLabel
            awaitingReply -> "Click to answer the AI's question…"
            latest != null -> latest.text
            else -> "No activity yet."
        }
        val color = when {
            processLabel != null -> Neutral300
            awaitingReply -> Red500
            latest != null -> levelColor(latest.level)
            else -> Neutral500
        }
        Text(text, color = color, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
        if (processLabel != null && processFraction != null) {
            Spacer(Modifier.width(8.dp))
            Text("${(processFraction * 100).toInt()}%", color = Neutral500, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ExpandedLog(
    entries: List<ActivityLog.Entry>,
    processLabel: String?,
    processFraction: Float?,
    onClear: () -> Unit,
    awaitingReply: Boolean,
    onReply: (String) -> Unit,
    onOpenAiSettings: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().height(320.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Activity", color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            if (entries.isNotEmpty()) {
                Text(
                    "Clear", color = Neutral400, fontSize = 12.sp,
                    modifier = Modifier.clickable(onClick = onClear).padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        if (processLabel != null) {
            if (processFraction != null) {
                LinearProgressIndicator(
                    progress = { processFraction },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = Red500, trackColor = Neutral800,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = Red500, trackColor = Neutral800,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Neutral800))
        if (entries.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No activity yet.", color = Neutral500, fontSize = 12.sp)
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(entries.asReversed(), key = { it.id }) { entry ->
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            prefix(entry.level) + entry.text,
                            color = levelColor(entry.level),
                            fontSize = 12.sp,
                            fontFamily = if (entry.level == ActivityLog.Level.USER) FontFamily.Default else FontFamily.Monospace,
                            fontWeight = if (entry.level == ActivityLog.Level.USER) FontWeight.Medium else FontWeight.Normal,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                        )
                        // Same fix as Android's ActivityLogSheet: a "no model configured" error already
                        // names the exact Settings path — make that an actual one-tap link instead of
                        // text the user has to go find their own way from.
                        if (entry.level == ActivityLog.Level.ERROR && "Settings" in entry.text) {
                            Text(
                                "Open Settings →",
                                color = White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .clickable(
                                        onClick = if ("Advanced" in entry.text) onOpenSettings else onOpenAiSettings,
                                    )
                                    .padding(start = 12.dp, top = 1.dp, bottom = 3.dp),
                            )
                        }
                    }
                }
            }
        }
        if (awaitingReply) {
            ReplyRow(onSend = onReply)
        }
    }
}

@Composable
private fun ReplyRow(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val submit: () -> Unit = {
        val t = text.trim()
        if (t.isNotEmpty()) {
            focusManager.clearFocus()
            text = ""
            onSend(t)
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Neutral800))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { v ->
                val submitNow = v.contains('\n')
                text = v.replace("\n", "")
                if (submitNow) submit()
            },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Answer the AI…", color = Neutral500, fontSize = 12.sp) },
            textStyle = TextStyle(color = White, fontSize = 12.sp),
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Send",
            color = Red500,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clickable(onClick = submit)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

private fun levelColor(level: ActivityLog.Level) = when (level) {
    ActivityLog.Level.USER -> White
    ActivityLog.Level.CHAT -> Neutral300
    ActivityLog.Level.INFO -> Neutral400
    ActivityLog.Level.PROGRESS -> Neutral400
    ActivityLog.Level.SUCCESS -> Green500
    ActivityLog.Level.ERROR -> Red500
}

private fun prefix(level: ActivityLog.Level) = when (level) {
    ActivityLog.Level.USER -> "› "
    ActivityLog.Level.CHAT -> ""
    ActivityLog.Level.INFO -> ""
    ActivityLog.Level.PROGRESS -> ""
    ActivityLog.Level.SUCCESS -> "✓ "
    ActivityLog.Level.ERROR -> "✗ "
}
