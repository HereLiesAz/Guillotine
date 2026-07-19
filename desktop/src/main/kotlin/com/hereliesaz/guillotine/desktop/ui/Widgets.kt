package com.hereliesaz.guillotine.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
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
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral400
import com.hereliesaz.guillotine.desktop.ui.theme.Red500

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

/**
 * A full-width horizontal grip that reports VERTICAL drag, for resizing stacked panes (e.g. the
 * preview over the timeline). [onDrag] gets the vertical drag delta in px (positive = dragged down).
 * Mirrors the Android `DraggableTimelineDivider`.
 */
@Composable
fun DraggableSplitDivider(
    modifier: Modifier = Modifier,
    onDrag: (Float) -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .background(Color(0xFF171717))
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount)
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.12f)
                .height(4.dp)
                .background(Color(0xFF525252), RoundedCornerShape(2.dp))
                .align(Alignment.Center),
        )
    }
}
