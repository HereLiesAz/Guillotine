package com.hereliesaz.guillotine.desktop.platform

import com.hereliesaz.guillotine.ai.AiSettings
import com.hereliesaz.guillotine.ai.BeatAnalyzer
import com.hereliesaz.guillotine.ai.Loudness
import com.hereliesaz.guillotine.ai.Spleeter
import com.hereliesaz.guillotine.ai.VocalIsolator
import com.hereliesaz.guillotine.ai.gen.AsyncJobPoller
import com.hereliesaz.guillotine.ai.gen.GenBackends
import com.hereliesaz.guillotine.ai.gen.GenKind
import com.hereliesaz.guillotine.ai.gen.GenProviderType
import com.hereliesaz.guillotine.ai.gen.GenRequest
import com.hereliesaz.guillotine.ai.gen.PollConfig
import com.hereliesaz.guillotine.ai.gen.meta
import com.hereliesaz.guillotine.desktop.media.DesktopDiarizer
import com.hereliesaz.guillotine.desktop.media.DesktopFaceBlur
import com.hereliesaz.guillotine.desktop.media.DesktopFaceDetector
import com.hereliesaz.guillotine.desktop.media.DesktopImageEffect
import com.hereliesaz.guillotine.desktop.media.DesktopImageEmbedder
import com.hereliesaz.guillotine.desktop.media.DesktopFfmpegFilter
import com.hereliesaz.guillotine.desktop.media.DesktopImageLabeler
import com.hereliesaz.guillotine.desktop.media.DesktopInpaint
import com.hereliesaz.guillotine.desktop.media.DesktopTts
import com.hereliesaz.guillotine.desktop.media.DesktopMediaDecoder
import com.hereliesaz.guillotine.desktop.media.DesktopMediaImport
import com.hereliesaz.guillotine.desktop.media.DesktopVoskTranscriber
import com.hereliesaz.guillotine.desktop.media.DesktopYamnet
import com.hereliesaz.guillotine.editor.EditorViewModel
import com.hereliesaz.guillotine.editor.FACE_BLUR_PREFIX
import com.hereliesaz.guillotine.model.LearnedConcept
import com.hereliesaz.guillotine.model.MediaItem
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import com.hereliesaz.guillotine.mcp.McpToolsSurface
import com.hereliesaz.guillotine.mcp.boolProp
import com.hereliesaz.guillotine.mcp.intProp
import com.hereliesaz.guillotine.mcp.numberProp
import com.hereliesaz.guillotine.mcp.stringProp
import com.hereliesaz.guillotine.mcp.toolDefinition
import com.hereliesaz.guillotine.model.AspectRatio
import com.hereliesaz.guillotine.model.BeatMap
import com.hereliesaz.guillotine.model.ClipType
import com.hereliesaz.guillotine.model.CubicBezier
import com.hereliesaz.guillotine.model.EditSegment
import com.hereliesaz.guillotine.model.KeyframeProperty
import com.hereliesaz.guillotine.model.MediaKind
import com.hereliesaz.guillotine.model.TimelineClip
import com.hereliesaz.guillotine.model.newId
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI

class DesktopMcpTools(
    private val vm: EditorViewModel,
    private val settingsProvider: () -> AiSettings = { AiSettings() },
) : McpToolsSurface, com.hereliesaz.guillotine.ai.agent.FrameImageSource {

    /**
     * Pixel size of the actual project canvas. Shared with preview/export so ORIGINAL is the imported
     * media's exact width/height and fixed aspect changes never invent a second geometry policy.
     */
    private fun resolveFrameSize(doc: com.hereliesaz.guillotine.model.Document): Pair<Int, Int> {
        val canvas = doc.projectCanvasSize()
        return canvas.width to canvas.height
    }

    private fun ok() = JSONObject().put("ok", true)

    private fun msFmt(ms: Long): String {
        val abs = ms.coerceAtLeast(0L)
        return if (abs < 60_000L) String.format(java.util.Locale.US, "%.1fs", abs / 1000.0)
        else String.format(java.util.Locale.US, "%d:%02d", abs / 60_000L, (abs % 60_000L) / 1000L)
    }

    private fun clipJson(clip: com.hereliesaz.guillotine.model.TimelineClip) = JSONObject().apply {
        put("id", clip.id); put("type", clip.type.name); put("trackId", clip.trackId)
        put("startTimeMs", clip.startTimeMs); put("trimStartMs", clip.trimStartMs)
        put("durationMs", clip.durationMs); put("prompt", clip.prompt)
        put("isAnalyzing", clip.isAnalyzing); put("editCount", clip.edits.size)
        if (clip.edits.isNotEmpty()) put("edits", segmentsJson(clip.edits))
    }

    private fun segmentsJson(edits: List<EditSegment>) = JSONArray().apply {
        edits.forEach { e ->
            put(JSONObject().apply {
                put("startMs", e.startMs); put("endMs", e.endMs)
                put("action", e.action.name.lowercase()); put("reason", e.reason)
            })
        }
    }

    private fun emptySchema() = JSONObject().apply { put("type", "object"); put("properties", JSONObject()) }

    private fun objSchema(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()) =
        JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
            if (required.isNotEmpty()) put("required", JSONArray(required))
        }
}
