package com.hereliesaz.guillotine.azphalt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.hereliesaz.guillotine.R
import com.hereliesaz.guillotine.media.AndroidLutBitmap
import com.hereliesaz.guillotine.media.AndroidShaderBitmap
import com.hereliesaz.guillotine.media.CubeLut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Renders a live "what does this actually look like" thumbnail for an azphalt store listing: the
 * app's own icon, run through that listing's real LUT or shader asset — not a static icon, and
 * not the extension author's own submitted preview image. See [AzpPreviewFetcher] for why this
 * needs a full (small) package download rather than a lighter preview-only fetch. Mirrors
 * `DesktopStorePreviewRenderer` on desktop.
 *
 * Every render is a network call plus a decode/apply pass, so results are cached twice: in memory
 * for the running session ([LruCache], same pattern as `MediaPreview`'s thumbnail cache) and on
 * disk under `context.cacheDir/store-previews`, keyed by `id@version`, so re-opening the store
 * doesn't re-download and re-render every entry the user has already scrolled past.
 *
 * Best-effort throughout: a network failure, a malformed package, a shader on a pre-API-33 device
 * (see [AndroidShaderBitmap]), or an asset type with no still-image render path
 * ([AzpPreviewFetcher] returning null — motion/model assets) all just mean no preview for that
 * entry, cached as such so the failure isn't retried on every recomposition. The store falls back
 * to its plain listing row when this returns null.
 */
object AzpStorePreviewRenderer {

    private val memoryCache = LruCache<String, ImageBitmap>(64)
    private val unavailable = HashSet<String>()
    private const val SAMPLE_MAX_PX = 128

    private var sampleBitmap: Bitmap? = null

    suspend fun render(context: Context, entry: AzphaltRegistry.CatalogEntry): ImageBitmap? =
        withContext(Dispatchers.IO) {
            val key = "${entry.id}@${entry.latest}"
            memoryCache.get(key)?.let { return@withContext it }
            synchronized(unavailable) { if (key in unavailable) return@withContext null }

            val cacheDir = File(context.cacheDir, "store-previews").also { it.mkdirs() }
            val diskFile = File(cacheDir, "${sanitize(key)}.png")
            val decoded = if (diskFile.exists()) {
                runCatching { BitmapFactory.decodeFile(diskFile.absolutePath) }.getOrNull()
            } else {
                renderFresh(context, entry)?.also { bmp ->
                    runCatching { diskFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                }
            }
            if (decoded == null) {
                synchronized(unavailable) { unavailable.add(key) }
                return@withContext null
            }
            decoded.asImageBitmap().also { memoryCache.put(key, it) }
        }

    private fun renderFresh(context: Context, entry: AzphaltRegistry.CatalogEntry): Bitmap? {
        val sample = sample(context) ?: return null
        val source = AzpPreviewFetcher.fetch(entry.id, entry.latest.ifBlank { null }) ?: return null
        return when (source) {
            is AzpPreviewFetcher.PreviewSource.Lut -> {
                val lut = runCatching { CubeLut.parse(source.cubeText) }.getOrNull() ?: return null
                AndroidLutBitmap.apply(sample, lut)
            }
            is AzpPreviewFetcher.PreviewSource.Shader ->
                AndroidShaderBitmap.apply(sample, source.glslSource, source.params)
        }
    }

    /** The bundled app icon, downscaled once — the "project thumbnail" every preview renders onto. */
    private fun sample(context: Context): Bitmap? {
        sampleBitmap?.let { return it }
        return runCatching {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val full = BitmapFactory.decodeResource(context.resources, R.drawable.guillotine_icon, opts) ?: return null
            val scale = SAMPLE_MAX_PX.toFloat() / maxOf(full.width, full.height)
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(full, (full.width * scale).toInt().coerceAtLeast(1), (full.height * scale).toInt().coerceAtLeast(1), true)
            } else {
                full
            }
            sampleBitmap = scaled
            scaled
        }.getOrNull()
    }

    private fun sanitize(key: String) = key.replace(Regex("[^A-Za-z0-9._-]"), "_").take(160)
}
