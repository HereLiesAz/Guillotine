package com.hereliesaz.guillotine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.guillotine.azphalt.AzpControl
import com.hereliesaz.guillotine.azphalt.AzpUiSchema
import com.hereliesaz.guillotine.ui.theme.Neutral400
import com.hereliesaz.guillotine.ui.theme.Neutral500
import com.hereliesaz.guillotine.ui.theme.Neutral800
import com.hereliesaz.guillotine.ui.theme.White
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull

/**
 * Renders an azphalt [AzpUiSchema] as native Compose widgets — the host side of azphalt's UI-schema job
 * (spec/ui-schema.md). Values are read through [value] and written through [onValue] as raw azphalt
 * `params` JSON (so any control type round-trips through [AzpParamStore]); a button calls [onAction].
 *
 * Deliberately generic: it knows nothing about clips or effects, so the same renderer serves any panel
 * host (the clip-properties panel today; an applied-effect surface once the runtime lands).
 */
@Composable
fun AzpUiSchemaControls(
    schema: AzpUiSchema,
    value: (key: String) -> JsonElement?,
    onValue: (key: String, value: JsonElement) -> Unit,
    onAction: (action: String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        schema.controls.forEach { AzpControlView(it, value, onValue, onAction) }
    }
}

private fun JsonElement?.prim(): JsonPrimitive? = this as? JsonPrimitive

@Composable
private fun AzpControlView(
    control: AzpControl,
    value: (String) -> JsonElement?,
    onValue: (String, JsonElement) -> Unit,
    onAction: (String) -> Unit,
) {
    when (control) {
        is AzpControl.Slider -> {
            val current = value(control.key).prim()?.floatOrNull ?: control.default.toFloat()
            val range = control.min.toFloat()..maxOf(control.max, control.min + 1e-3).toFloat()
            val steps = control.step?.takeIf { it > 0 }?.let {
                (((control.max - control.min) / it).toInt() - 1).coerceAtLeast(0)
            } ?: 0
            Column {
                LabelRow(control.label, "%.2f".format(current))
                Slider(
                    value = current.coerceIn(range.start, range.endInclusive),
                    onValueChange = { onValue(control.key, JsonPrimitive(it)) },
                    valueRange = range,
                    steps = steps,
                )
            }
        }

        is AzpControl.Number -> {
            var text by remember(control.key) {
                mutableStateOf((value(control.key).prim()?.doubleOrNull ?: control.default).cleanNumber())
            }
            OutlinedTextField(
                value = text,
                onValueChange = { s ->
                    text = s
                    s.toDoubleOrNull()?.let { onValue(control.key, JsonPrimitive(it)) }
                },
                label = { Text(control.label, fontSize = 12.sp) },
                singleLine = true,
                textStyle = TextStyle(color = White, fontSize = 12.sp),
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }

        is AzpControl.Toggle -> {
            val current = value(control.key).prim()?.booleanOrNull ?: control.default
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(control.label, color = Neutral400, fontSize = 12.sp)
                Switch(checked = current, onCheckedChange = { onValue(control.key, JsonPrimitive(it)) })
            }
        }

        is AzpControl.Select -> {
            val current = value(control.key).prim()?.contentOrNull ?: control.default
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(control.label, color = Neutral400, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    control.options.forEach { opt ->
                        OptionChip(opt.label, selected = opt.value == current) {
                            onValue(control.key, JsonPrimitive(opt.value))
                        }
                    }
                }
            }
        }

        is AzpControl.Color -> {
            var text by remember(control.key) {
                mutableStateOf(value(control.key).prim()?.contentOrNull ?: control.default)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(parseHexColor(text) ?: Neutral800)
                        .border(1.dp, Neutral800, RoundedCornerShape(6.dp)),
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { s -> text = s; onValue(control.key, JsonPrimitive(s)) },
                    label = { Text(control.label, fontSize = 12.sp) },
                    singleLine = true,
                    textStyle = TextStyle(color = White, fontSize = 12.sp),
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .fillMaxWidth(),
                )
            }
        }

        is AzpControl.Text -> {
            var text by remember(control.key) {
                mutableStateOf(value(control.key).prim()?.contentOrNull ?: control.default)
            }
            OutlinedTextField(
                value = text,
                onValueChange = { s -> text = s; onValue(control.key, JsonPrimitive(s)) },
                label = { Text(control.label, fontSize = 12.sp) },
                placeholder = control.placeholder?.let { { Text(it, color = Neutral500, fontSize = 12.sp) } },
                singleLine = true,
                textStyle = TextStyle(color = White, fontSize = 12.sp),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        is AzpControl.Button -> {
            Button(
                onClick = { onAction(control.action) },
                colors = ButtonDefaults.buttonColors(containerColor = Neutral800),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(control.label, color = White, fontSize = 12.sp) }
        }

        is AzpControl.Group -> {
            ClipPanelSection(control.label) {
                control.controls.forEach { AzpControlView(it, value, onValue, onAction) }
            }
        }
    }
}

@Composable
private fun LabelRow(label: String, valueText: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Neutral400, fontSize = 12.sp)
        Text(valueText, color = Neutral500, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun OptionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) Color.Black else Neutral400,
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) White else Color.Transparent)
            .border(1.dp, if (selected) White else Neutral800, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** A tidy string for a schema number default (drops a trailing `.0`). */
private fun Double.cleanNumber(): String = if (this == toLong().toDouble()) toLong().toString() else toString()

/** Parse a `#RRGGBB` / `#AARRGGBB` hex color for the swatch; null when unparseable. */
private fun parseHexColor(hex: String): Color? = try {
    Color(android.graphics.Color.parseColor(hex.trim()))
} catch (e: Exception) {
    null
}
