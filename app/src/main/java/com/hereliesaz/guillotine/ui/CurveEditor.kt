package com.hereliesaz.guillotine.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.hereliesaz.guillotine.model.CubicBezier
import com.hereliesaz.guillotine.ui.theme.Neutral600
import com.hereliesaz.guillotine.ui.theme.Neutral950
import com.hereliesaz.guillotine.ui.theme.White
import kotlin.math.hypot

/** Interactive cubic-bezier easing editor (two draggable control handles). */
@Composable
fun CurveEditor(value: CubicBezier, onChange: (CubicBezier) -> Unit, modifier: Modifier = Modifier) {
    var active by remember { mutableIntStateOf(-1) }

    Canvas(
        modifier
            .height(96.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Neutral950)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { pos ->
                        val p1 = Offset(value.x1 * size.width, (1 - value.y1) * size.height)
                        val p2 = Offset(value.x2 * size.width, (1 - value.y2) * size.height)
                        active = if (hypot(pos.x - p1.x, pos.y - p1.y) <= hypot(pos.x - p2.x, pos.y - p2.y)) 1 else 2
                    },
                    onDragEnd = { active = -1 },
                    onDrag = { change, _ ->
                        change.consume()
                        val nx = (change.position.x / size.width).coerceIn(0f, 1f)
                        val ny = (1f - change.position.y / size.height).coerceIn(-0.5f, 1.5f)
                        if (active == 1) onChange(value.copy(x1 = nx, y1 = ny))
                        else if (active == 2) onChange(value.copy(x2 = nx, y2 = ny))
                    },
                )
            },
    ) {
        val w = size.width
        val h = size.height
        fun pt(x: Float, y: Float) = Offset(x * w, (1 - y) * h)

        val start = pt(0f, 0f)
        val end = pt(1f, 1f)
        val c1 = pt(value.x1, value.y1)
        val c2 = pt(value.x2, value.y2)

        // Guide lines from endpoints to control points.
        drawLine(Neutral600, start, c1, strokeWidth = 1f)
        drawLine(Neutral600, end, c2, strokeWidth = 1f)

        // The easing curve.
        val path = Path().apply {
            moveTo(start.x, start.y)
            cubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y)
        }
        drawPath(path, Color(0xFF10B981), style = Stroke(width = 3f))

        // Handles.
        drawCircle(White, radius = 7f, center = c1)
        drawCircle(White, radius = 7f, center = c2)
    }
}
