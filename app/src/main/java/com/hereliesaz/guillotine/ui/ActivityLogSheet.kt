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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
 * There is deliberately **no** prompt field here — the single AI input lives in the editor tool
 * strip; this sheet is output only.
 *
 * @param processLabel    non-null while a background process (analysis/export) is running
 * @param processFraction 0..1 when the running process reports determinate progress, else null
 */
@Composable
fun ActivityLogSheet(
    controller: AzSheetController,
    entries: List<ActivityLog.Entry>,
    processLabel: String?,
    processFraction: Float?,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Raise a collapsed sheet to PEEK when new activity arrives, so a running agent / analysis /
    // export is noticed — but never force an already-expanded sheet back down.
    val newestId = entries.lastOrNull()?.id ?: 0L
    LaunchedEffect(newestId) {
        if (newestId != 0L && controller.detent == AzSheetDetent.HIDDEN) {
            controller.snapTo(AzSheetDetent.PEEK)
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
            AzSheetDetent.PEEK -> PeekTicker(entries, processLabel, processFraction)
            AzSheetDetent.HALF, AzSheetDetent.FULL -> ExpandedLog(entries, processLabel, processFraction, onClear)
        }
    }
}

@Composable
private fun PeekTicker(
    entries: List<ActivityLog.Entry>,
    processLabel: String?,
    processFraction: Float?,
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
        val text = processLabel ?: latest?.text ?: "No activity yet."
        val color = when {
            processLabel != null -> Neutral300
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
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No activity yet.", color = Neutral500, fontSize = 12.sp)
            }
        } else {
            // reverseLayout keeps the newest entry pinned to the bottom (visible) without any
            // manual scroll bookkeeping; older lines scroll up out of view.
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(entries.asReversed(), key = { it.id }) { entry ->
                    Text(
                        prefix(entry.level) + entry.text,
                        color = levelColor(entry.level),
                        fontSize = 12.sp,
                        fontFamily = if (entry.level == ActivityLog.Level.USER) FontFamily.Default else FontFamily.Monospace,
                        fontWeight = if (entry.level == ActivityLog.Level.USER) FontWeight.Medium else FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    )
                }
            }
        }
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
