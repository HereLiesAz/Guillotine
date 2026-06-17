package com.hereliesaz.guillotine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.hereliesaz.guillotine.ui.theme.Neutral300
import com.hereliesaz.guillotine.ui.theme.Neutral400
import com.hereliesaz.guillotine.ui.theme.Neutral500
import com.hereliesaz.guillotine.ui.theme.Neutral600
import com.hereliesaz.guillotine.ui.theme.Neutral800
import com.hereliesaz.guillotine.ui.theme.Neutral900
import com.hereliesaz.guillotine.ui.theme.Red500
import com.hereliesaz.guillotine.ui.theme.White

@Composable
fun AiComparisonSheet(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Neutral900)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("AI Provider Comparison", color = White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(
                "Guillotine supports multiple AI analyzers. Free on-device options work offline; " +
                    "cloud providers offer deeper understanding of your content.",
                color = Neutral400, fontSize = 12.sp,
            )

            CapabilityTable()

            Text("On-device limitations", color = Red500, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            BulletItem("Local (silence detection): Only detects quiet vs. loud \u2014 no understanding of content, speech, or scenes.")
            BulletItem("ML Kit (vision scan): Recognizes preset faces and object labels \u2014 cannot understand context, intent, dialogue, or complex scenes.")
            BulletItem("Neither can follow creative instructions like \u201Ccut to the beat\u201D, \u201Ckeep funny moments\u201D, or \u201Cremove filler words\u201D.")

            Text("Why use a cloud provider?", color = Red500, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            BulletItem("Full semantic understanding of video content \u2014 knows what is happening, not just what objects are visible.")
            BulletItem("Follows natural language editing instructions (\u201Ckeep the punchline\u201D, \u201Ccut boring transitions\u201D).")
            BulletItem("Context-aware decisions across the whole clip timeline.")
            BulletItem("Audio transcription + content-based audio editing (OpenAI Whisper, Gemini native audio).")
            BulletItem("Gemini processes full video natively \u2014 no frame sampling loss.")

            Text(
                "All cloud providers are bring-your-own-key. You pay the provider directly at their rates.",
                color = Neutral500, fontSize = 11.sp,
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = White, contentColor = Color.Black),
                ) { Text("Got it", fontSize = 12.sp, fontWeight = FontWeight.Medium) }
            }
        }
    }
}

private val headers = listOf("Feature", "Local", "ML Kit", "Gemini", "OpenAI", "Anthropic", "Others")
private val rows = listOf(
    listOf("Video", "\u2014", "Frame scan", "Native", "Frames", "Frames", "Frames"),
    listOf("Audio", "Silence", "\u2014", "Native", "Whisper", "\u2014", "\u2014"),
    listOf("Image", "\u2014", "Face/label", "Yes", "Yes", "Yes", "Yes"),
    listOf("Semantic", "No", "No", "Yes", "Yes", "Yes", "Yes"),
    listOf("Custom prompts", "No", "Limited", "Yes", "Yes", "Yes", "Yes"),
    listOf("Offline", "Yes", "Yes", "No", "No", "No", "No"),
    listOf("Cost", "Free", "Free", "BYO key", "BYO key", "BYO key", "BYO key"),
)

@Composable
private fun CapabilityTable() {
    Column(
        Modifier
            .horizontalScroll(rememberScrollState())
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Neutral800, RoundedCornerShape(8.dp)),
    ) {
        Row(Modifier.background(Neutral800)) {
            headers.forEachIndexed { i, h -> TableCell(h, isHeader = true, isFirst = i == 0) }
        }
        rows.forEach { row ->
            Row {
                row.forEachIndexed { i, cell -> TableCell(cell, isHeader = false, isFirst = i == 0) }
            }
        }
    }
}

@Composable
private fun TableCell(text: String, isHeader: Boolean, isFirst: Boolean) {
    val w = if (isFirst) 110.dp else 80.dp
    val color = when {
        isHeader -> White
        text in setOf("Yes", "Native", "Whisper", "Frames", "Frame scan", "Face/label", "Silence") -> Neutral300
        text == "No" || text == "\u2014" -> Neutral600
        text == "Free" -> Color(0xFF4ADE80)
        text == "BYO key" -> Neutral400
        text == "Limited" -> Neutral500
        else -> Neutral400
    }
    Text(
        text = text,
        color = color,
        fontSize = 10.sp,
        fontWeight = if (isHeader || isFirst) FontWeight.Medium else FontWeight.Normal,
        modifier = Modifier.width(w).padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

@Composable
private fun BulletItem(text: String) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp)) {
        Text("  \u2022  ", color = Neutral500, fontSize = 12.sp)
        Text(text, color = Neutral400, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
}
