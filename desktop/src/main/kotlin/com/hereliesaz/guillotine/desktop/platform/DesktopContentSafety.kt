package com.hereliesaz.guillotine.desktop.platform

import com.hereliesaz.guillotine.ai.safety.NsfwModel
import com.hereliesaz.guillotine.desktop.media.DesktopMediaDecoder
import com.hereliesaz.guillotine.desktop.media.DesktopMediaImport
import com.hereliesaz.guillotine.desktop.media.DesktopOnnx
import com.hereliesaz.guillotine.model.AiProvenance
import com.hereliesaz.guillotine.model.MediaItem
import com.hereliesaz.guillotine.model.MediaKind
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI

/**
 * Desktop side of [com.hereliesaz.guillotine.ai.safety.ContentSafety] layer 3: [NsfwModel] over a
 * generated image, or five frames of a generated video, through ONNX Runtime. A flagged or uncheckable
 * result is deleted and [NsfwModel.FlaggedException] thrown, so nothing reaches the project unchecked.
 */
object DesktopContentSafety {

    private fun modelPath(): String = NsfwModel.ensure(File(DesktopStorage.dataDir, "safety-models")).absolutePath

    private fun score(image: BufferedImage): Float {
        val w = image.width; val h = image.height
        val px = image.getRGB(0, 0, w, h, null, 0, w)
        val input = NsfwModel.preprocess(px, w, h)
        val shape = longArrayOf(1, 3, NsfwModel.SIZE.toLong(), NsfwModel.SIZE.toLong())
        val session = DesktopOnnx.session(modelPath())
        DesktopOnnx.floatTensor(input, shape).use { t ->
            DesktopOnnx.run(session, mapOf(NsfwModel.INPUT to t)).use { r ->
                return NsfwModel.nsfwProbability(DesktopOnnx.firstFloatOutput(r))
            }
        }
    }

    /** Checks the generated media at [uri] (`file:` uri); audio passes. */
    suspend fun requireSafe(uri: String, kind: MediaKind) {
        if (kind == MediaKind.AUDIO) return
        val file = File(URI(uri))
        val scores = try {
            if (kind == MediaKind.IMAGE) {
                listOfNotNull(javax.imageio.ImageIO.read(file)?.let { score(it) })
            } else {
                val durMs = DesktopMediaImport.probe(file)?.durationMs ?: 0L
                listOf(0.05, 0.25, 0.5, 0.75, 0.95).mapNotNull { f ->
                    DesktopMediaDecoder.grabFrame(uri, (durMs * f).toLong(), maxPx = 512)?.let { score(it) }
                }
            }
        } catch (e: NsfwModel.FlaggedException) {
            file.delete(); throw e
        } catch (e: Exception) {
            file.delete()
            throw NsfwModel.FlaggedException("The generated result couldn't be checked (${e.message}), so it was discarded.")
        }
        try {
            NsfwModel.requireClean(scores)
        } catch (e: NsfwModel.FlaggedException) {
            file.delete(); throw e
        }
    }

    /** Downloads a generator's remote image [url], checks it, and returns it as media with [provenance]. */
    suspend fun fetchChecked(url: String, name: String, provenance: AiProvenance): MediaItem {
        val local = DesktopGenSink().saveUrl(url, "jpg")
        requireSafe(local, MediaKind.IMAGE)
        val item = DesktopMediaImport.probe(File(URI(local)))
            ?: throw NsfwModel.FlaggedException("The generated image couldn't be read.")
        return item.copy(name = name, aiProvenance = provenance)
    }
}
