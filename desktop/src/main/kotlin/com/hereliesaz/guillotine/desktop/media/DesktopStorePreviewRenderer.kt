package com.hereliesaz.guillotine.desktop.media

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.hereliesaz.guillotine.azphalt.AzphaltRegistry
import com.hereliesaz.guillotine.azphalt.AzpPreviewFetcher
import com.hereliesaz.guillotine.desktop.platform.DesktopStorage
import com.hereliesaz.guillotine.media.CubeLut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders a live "what does this actually look like" thumbnail for an azphalt store listing: the
 * app's own icon, run through that listing's real LUT or shader asset — not a static icon, and
 * not the extension author's own submitted preview image. See [AzpPreviewFetcher] for why this
 * needs a full (small) package download rather than a lighter preview-only fetch.
 *
 * Every render is a network call plus a decode/apply pass, so results are cached twice: in memory
 * for the running session (same LRU pattern as [DesktopMediaDecoder]'s thumbnail cache) and on
 * disk under `DesktopStorage.dataDir/cache/store-previews`, keyed by `id@version`, so re-opening
 * the store doesn't re-download and re-render every entry the user has already scrolled past.
 *
 * Best-effort throughout: a network failure, a malformed package, or an asset type with no
 * still-image render path ([AzpPreviewFetcher] returning null — motion/model assets) all just
 * mean no preview for that entry, cached as such so the failure isn't retried on every
 * recomposition. The store falls back to its plain listing row when this returns null.
 */
object DesktopStorePreviewRenderer {

    private val memoryCache = LinkedHashMap<String, ImageBitmap>(64, 0.75f, true)
    private val unavailable = HashSet<String>()
    private const val CACHE_MAX = 64
    private const val SAMPLE_MAX_PX = 128

    private val cacheDir: File by lazy {
        File(DesktopStorage.dataDir, "cache/store-previews").also { it.mkdirs() }
    }

    /** The bundled app icon, downscaled once — the "project thumbnail" every preview is rendered onto. */
    private val sampleImage: BufferedImage? by lazy {
        runCatching {
            DesktopStorePreviewRenderer::class.java.getResourceAsStream("/icons/icon.png")?.use {
                ImageIO.read(it)?.let { img -> downscale(img, SAMPLE_MAX_PX) }
            }
        }.getOrNull()
    }

    suspend fun render(entry: AzphaltRegistry.CatalogEntry): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = "${entry.id}@${entry.latest}"
        synchronized(memoryCache) {
            memoryCache[key]?.let { return@withContext it }
            if (key in unavailable) return@withContext null
        }
        val diskFile = File(cacheDir, "${sanitize(key)}.png")
        val decoded = if (diskFile.exists()) {
            runCatching { ImageIO.read(diskFile) }.getOrNull()
        } else {
            renderFresh(entry)?.also { img ->
                runCatching { ImageIO.write(img, "png", diskFile) }
            }
        }
        if (decoded == null) {
            synchronized(memoryCache) { unavailable.add(key) }
            return@withContext null
        }
        val bitmap = decoded.toComposeImageBitmap()
        synchronized(memoryCache) {
            memoryCache[key] = bitmap
            while (memoryCache.size > CACHE_MAX) memoryCache.remove(memoryCache.keys.first())
        }
        bitmap
    }

    private fun renderFresh(entry: AzphaltRegistry.CatalogEntry): BufferedImage? {
        val sample = sampleImage ?: return null
        val source = AzpPreviewFetcher.fetch(entry.id, entry.latest.ifBlank { null }) ?: return null
        val copy = BufferedImage(sample.width, sample.height, BufferedImage.TYPE_INT_ARGB)
        copy.createGraphics().apply { drawImage(sample, 0, 0, null); dispose() }
        return when (source) {
            is AzpPreviewFetcher.PreviewSource.Lut -> {
                val lut = runCatching { CubeLut.parse(source.cubeText) }.getOrNull() ?: return null
                DesktopColorMatrix.applyLut(copy, lut)
                copy
            }
            is AzpPreviewFetcher.PreviewSource.Shader ->
                DesktopShaderPass.applyFromSource(copy, source.glslSource, source.params, timeMs = 0L)
        }
    }

    private fun sanitize(key: String) = key.replace(Regex("[^A-Za-z0-9._-]"), "_").take(160)

    private fun downscale(img: BufferedImage, maxPx: Int): BufferedImage {
        if (img.width <= maxPx && img.height <= maxPx) return img
        val scale = maxPx.toFloat() / maxOf(img.width, img.height)
        val w = (img.width * scale).toInt().coerceAtLeast(1)
        val h = (img.height * scale).toInt().coerceAtLeast(1)
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        out.createGraphics().apply {
            setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(img, 0, 0, w, h, null)
            dispose()
        }
        return out
    }
}
