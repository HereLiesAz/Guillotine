package com.hereliesaz.guillotine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.guillotine.ui.theme.Neutral400
import com.hereliesaz.guillotine.ui.theme.Neutral500
import com.hereliesaz.guillotine.ui.theme.Neutral900
import com.hereliesaz.guillotine.ui.theme.Red500
import com.hereliesaz.guillotine.ui.theme.White

/**
 * Minimal assistant command bar: one text box + run button + a single status/result line. The
 * agent it triggers drives the editor through the MCP tools, so the timeline itself is the
 * "transcript" — this bar only narrates the current step.
 */
@Composable
fun AssistantBar(
    state: AssistantViewModel.UiState,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Neutral900)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        if (state.status.isNotBlank()) {
            Text(
                state.status,
                color = if (state.isError) Red500 else Neutral400,
                fontSize = 11.sp,
                maxLines = 2,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.input,
                onValueChange = onInput,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        "Tell the AI what to do — e.g. \"cut the silences in clip 1\"",
                        color = Neutral500, fontSize = 12.sp, maxLines = 1,
                    )
                },
                textStyle = TextStyle(color = White, fontSize = 12.sp),
                singleLine = true,
                enabled = !state.running,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
            Spacer(Modifier.width(8.dp))
            if (state.running) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Red500)
            } else {
                IconButton(onClick = onSend) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Run assistant", tint = Red500)
                }
            }
        }
    }
}
