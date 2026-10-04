package com.hereliesaz.guillotine.ai.safety

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.hereliesaz.guillotine.ai.gen.AndroidGenSink
import com.hereliesaz.guillotine.model.AiProvenance
import com.hereliesaz.guillotine.model.MediaItem
import com.hereliesaz.guillotine.model.MediaKind
import com.hereliesaz.guillotine.model.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer

/**
 * Android side of [ContentSafety] layer 3: runs [NsfwModel] over a generated image, or over frames
 * sampled from a generated video, and deletes the file when it's flagged. Every AI generation path
 * goes through [requireSafe] (or [fetchChecked] for a provider that hands back a URL) before its
 * result is added to the project.
 */
object AndroidContentSafety {

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    @Volatile private var session: OrtSession? = null

    private fun session(context: Context): OrtSession = session ?: synchronized(this) {
        session ?: run {
            val file = NsfwModel.ensure(File(context.filesDir, "safety-models"))
            env.createSession(file.absolutePath, OrtSession.SessionOptions()).also { session = it }
        }
    }

    /** NSFW probability of [bitmap]. */
    private fun score(context: Context, bitmap: Bitmap): Float {
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val input = NsfwModel.preprocess(px, bitmap.width, bitmap.height)
        val shape = longArrayOf(1, 3, NsfwModel.SIZE.toLong(), NsfwModel.SIZE.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { t ->
            session(context).run(mapOf(NsfwModel.INPUT to t)).use { r ->
                @Suppress("UNCHECKED_CAST")
                val logits = (r[0].value as Array<FloatArray>)[0]
                return NsfwModel.nsfwProbability(logits)
            }
        }
    }

    /**
     * Checks the generated media at [uri] (a `file://` uri). Images are checked whole; videos at five
     * points across their length. Throws [NsfwModel.FlaggedException] (after deleting the file) when
     * it's flagged or can't be checked. Audio has no picture and passes.
     */
    suspend fun requireSafe(context: Context, uri: String, kind: MediaKind) = withContext(Dispatchers.Default) {
        if (kind == MediaKind.AUDIO) return@withContext
        val scores = try {
            when (kind) {
                MediaKind.IMAGE -> listOfNotNull(decode(uri)?.let { score(context, it) })
                else -> frames(context, uri).map { score(context, it) }
            }
        } catch (e: NsfwModel.FlaggedException) {
            delete(uri); throw e
        } catch (e: Exception) {
            delete(uri)
            throw NsfwModel.FlaggedException("The generated result couldn't be checked (${e.message}), so it was discarded.")
        }
        try {
            NsfwModel.requireClean(scores)
        } catch (e: NsfwModel.FlaggedException) {
            delete(uri); throw e
        }
    }

    /**
     * Downloads a generator's remote image [url] to the cache, checks it, and returns the media item
     * carrying [provenance]. Used for providers whose result is only a URL (Pollinations).
     */
    suspend fun fetchChecked(context: Context, url: String, name: String, provenance: AiProvenance): MediaItem {
        val local = AndroidGenSink(context).saveUrl(url, "jpg")
        requireSafe(context, local, MediaKind.IMAGE)
        return MediaItem(newId(), local, name, MediaKind.IMAGE, 5_000, aiProvenance = provenance)
    }

    private fun decode(uri: String): Bitmap? = Uri.parse(uri).path?.let { BitmapFactory.decodeFile(it) }

    private fun frames(context: Context, uri: String): List<Bitmap> {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, Uri.parse(uri))
            val durUs = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
            return listOf(0.05, 0.25, 0.5, 0.75, 0.95).mapNotNull { f ->
                r.getFrameAtTime((durUs * f).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } finally {
            r.release()
        }
    }

    private fun delete(uri: String) {
        Uri.parse(uri).path?.let { File(it).delete() }
    }
}
