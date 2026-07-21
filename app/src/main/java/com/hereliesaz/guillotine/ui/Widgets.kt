package com.hereliesaz.guillotine.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.guillotine.ui.theme.Neutral400
import com.hereliesaz.guillotine.ui.theme.Red500

/** Small text button (e.g. the "AI" affordance in the toolbar). */
@Composable
fun ToolbarButton(text: String, tint: Color = Neutral400, onClick: () -> Unit) {
    Text(
        text = text,
        color = tint,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/** Square icon button with active/disabled visuals, used across the toolbars. */
@Composable
fun IconToolButton(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> Neutral400.copy(alpha = 0.3f)
        active -> Red500
        else -> Neutral400
    }
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = tint,
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .then(if (active) Modifier.background(Red500.copy(alpha = 0.18f)) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(8.dp)
            .size(18.dp),
    )
}


@Composable
fun DraggableTimelineDivider(
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    onDoubleTap: (() -> Unit)? = null,
    onDrag: (Float) -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(16.dp)
            .background(androidx.compose.ui.graphics.Color(0xFF171717))
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount)
                }
            }
            .then(
                if (onDoubleTap != null) {
                    androidx.compose.ui.Modifier.pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { onDoubleTap() })
                    }
                } else androidx.compose.ui.Modifier
            )
    ) {
        Box(
            modifier = androidx.compose.ui.Modifier
                .fillMaxWidth(0.15f)
                .height(4.dp)
                .background(androidx.compose.ui.graphics.Color(0xFF525252), RoundedCornerShape(2.dp))
                .align(androidx.compose.ui.Alignment.Center)
        )
    }
}

/**
 * Vertical sibling of [DraggableTimelineDivider]: a full-height grip that reports HORIZONTAL drag,
 * for resizing side-by-side panes (e.g. preview | clip-properties in the wide layout). [onDrag] gets
 * the horizontal drag delta in px (positive = dragged right).
 */
@Composable
fun DraggableVerticalDivider(
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    onDoubleTap: (() -> Unit)? = null,
    onDrag: (Float) -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(16.dp)
            .background(androidx.compose.ui.graphics.Color(0xFF171717))
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount)
                }
            }
            .then(
                if (onDoubleTap != null) {
                    androidx.compose.ui.Modifier.pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { onDoubleTap() })
                    }
                } else androidx.compose.ui.Modifier
            )
    ) {
        Box(
            modifier = androidx.compose.ui.Modifier
                .fillMaxHeight(0.15f)
                .width(4.dp)
                .background(androidx.compose.ui.graphics.Color(0xFF525252), RoundedCornerShape(2.dp))
                .align(androidx.compose.ui.Alignment.Center)
        )
    }
}
