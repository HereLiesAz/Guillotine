package com.hereliesaz.guillotine.ui

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.aznavrail.bottomsheet.AzBottomSheet
import com.hereliesaz.aznavrail.bottomsheet.AzSheetController
import com.hereliesaz.aznavrail.model.AzSheetConfig
import com.hereliesaz.aznavrail.model.AzSheetDetent
import com.hereliesaz.guillotine.ui.theme.Green500
import com.hereliesaz.guillotine.ui.theme.Neutral300
import com.hereliesaz.guillotine.ui.theme.Neutral400
import com.hereliesaz.guillotine.ui.theme.Neutral500
import com.hereliesaz.guillotine.ui.theme.Neutral800
import com.hereliesaz.guillotine.ui.theme.Neutral950
import com.hereliesaz.guillotine.ui.theme.Red500
import com.hereliesaz.guillotine.ui.theme.White

/**
 * AzNavRail four-detent bottom sheet holding the integrated activity log: AI chat output, the
 * running process, its progress, and errors. Anchored to the bottom of its parent [Box] (no
 * AzHostActivityLayout required); at PEEK it's a one-line ticker, expanded it's the full feed.
 *
 * The general AI prompt input still lives in the editor tool strip; the ONLY input here is the
 * clarification-reply field that appears when the agent asks a follow-up question ([awaitingReply]).
 *
 * @param processLabel    non-null while a background process (analysis/export) is running
 * @param processFraction 0..1 when the running process reports determinate progress, else null
 * @param awaitingReply   true when the agent finished with a question and wants a user answer here
 * @param onReply         invoked with the user's clarification text; the sheet clears its field on send
 * @param onOpenAiSettings invoked when the user taps the "Open Settings →" link on an error that names
 *                        an unconfigured AI Analyzer/Generation/Transcription slot (e.g. "No VLM model
 *                        set... in Settings → AI Analyzer → ..."). Errors are otherwise a dead end —
 *                        the user has to remember the path and go find it themselves.
 * @param onOpenSettings  same, for errors naming the Advanced tab specifically (azphalt model installs).
 */
@Composable
fun ActivityLogSheet(
    controller: AzSheetController,
    entries: List<ActivityLog.Entry>,
    processLabel: String?,
    processFraction: Float?,
    onClear: () -> Unit,
    awaitingReply: Boolean = false,
    onReply: (String) -> Unit = {},
    onOpenAiSettings: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Raise a collapsed sheet to PEEK when new activity arrives, so a running agent / analysis /
    // export is noticed — but never force an already-expanded sheet back down. When the agent asks
    // a clarifying question we go one step further and open to HALF so the reply input is visible.
    val newestId = entries.lastOrNull()?.id ?: 0L
    LaunchedEffect(newestId) {
        if (newestId != 0L && controller.detent == AzSheetDetent.HIDDEN) {
            controller.snapTo(AzSheetDetent.PEEK)
        }
    }
    LaunchedEffect(awaitingReply) {
        if (awaitingReply && (controller.detent == AzSheetDetent.HIDDEN || controller.detent == AzSheetDetent.PEEK)) {
            controller.snapTo(AzSheetDetent.HALF)
        }
    }

    AzBottomSheet(
        controller = controller,
        modifier = modifier,
        config = AzSheetConfig(
            backgroundColor = Neutral950,
            cornerRadiusDp = 16.dp,
            peekDp = 52.dp,
        ),
    ) {
        // controller.detent is Compose state — reading it re-renders on detent change. At HIDDEN
        // the shell shows only its own drag strip, so render nothing; PEEK is a one-line ticker;
        // HALF/FULL show the full feed.
        when (controller.detent) {
            AzSheetDetent.HIDDEN -> Unit
            AzSheetDetent.PEEK -> PeekTicker(entries, processLabel, processFraction, awaitingReply)
            AzSheetDetent.HALF, AzSheetDetent.FULL ->
                ExpandedLog(
                    entries, processLabel, processFraction, onClear, awaitingReply, onReply,
                    onOpenAiSettings, onOpenSettings,
                )
        }
    }
}

@Composable
private fun PeekTicker(
    entries: List<ActivityLog.Entry>,
    processLabel: String?,
    processFraction: Float?,
    awaitingReply: Boolean,
) {
    val latest = entries.lastOrNull()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
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
        // Awaiting a clarification reply takes precedence over the latest chat line — the user
        // needs to know to expand and answer. When both a process and a question are somehow
        // active (rare), the process line still shows so progress isn't hidden.
        val text = when {
            processLabel != null -> processLabel
            awaitingReply -> "Tap to answer the AI's question…"
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
    Column(Modifier.fillMaxSize()) {
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
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            
            // If the user is viewing the newest item (index 0 in reverseLayout), keep them
            // pinned there when a new item arrives. If they scrolled away to read history,
            // don't yank their scroll position.
            LaunchedEffect(entries.size) {
                if (listState.firstVisibleItemIndex == 0) {
                    listState.scrollToItem(0)
                }
            }

            // reverseLayout keeps the newest entry pinned to the bottom (visible) without any
            // manual scroll bookkeeping; older lines scroll up out of view. Weight lets the reply
            // row (below) share the sheet's height when it's shown.
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                state = listState,
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
                        // Every "no model/tool configured" error names the exact Settings path to fix
                        // it (e.g. "...in Settings → AI Analyzer → Frame captioning (VLM)."), but until
                        // now that was just text — the user still had to remember it and find their own
                        // way there. This turns it into an actual one-tap link to that screen. "Advanced"
                        // errors (azphalt model installs) route to Settings' own tab; everything else
                        // naming "Settings" is an AI Analyzer/Generation/Transcription slot.
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

/**
 * Inline reply field: shown only while the agent has finished its turn with a clarifying question.
 * Dismisses the keyboard and clears focus on send (same rule as the tool-strip prompt), so the sheet
 * doesn't sit behind a stuck IME after answering.
 */
@Composable
private fun ReplyRow(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val submit: () -> Unit = {
        val t = text.trim()
        if (t.isNotEmpty()) {
            keyboard?.hide()
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
                // Soft-keyboard Enter arrives as '\n'; strip it and treat as a submit.
                val submitNow = v.contains('\n')
                text = v.replace("\n", "")
                if (submitNow) submit()
            },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Answer the AI…", color = Neutral500, fontSize = 12.sp) },
            textStyle = androidx.compose.ui.text.TextStyle(color = White, fontSize = 12.sp),
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
    ActivityLog.Level.USER -> "› "      // ›
    ActivityLog.Level.CHAT -> ""
    ActivityLog.Level.INFO -> ""
    ActivityLog.Level.PROGRESS -> ""
    ActivityLog.Level.SUCCESS -> "✓ "    // ✓
    ActivityLog.Level.ERROR -> "✗ "      // ✗
}
