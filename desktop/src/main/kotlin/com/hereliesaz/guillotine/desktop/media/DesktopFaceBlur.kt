package com.hereliesaz.guillotine.desktop.media

import com.hereliesaz.guillotine.model.TimelineClip
import com.hereliesaz.guillotine.model.TimelineMath
import kotlinx.coroutines.runBlocking
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Builds the data for on-device face-blur on desktop using the **overlay + keyframes** approach: it
 * tracks the largest face across a clip (via [DesktopFaceDetector]) and produces (a) a pre-blurred
 * "patch" image of the face and (b) OFFSET_X / OFFSET_Y / SCALE keyframes that make that patch follow
 * the face when it's dropped on a track above the clip. Because it rides the existing keyframe system,
 * the blur renders in both preview and export and stays editable — no per-frame render-pipeline pass.
 * Pure JVM; nothing leaves the machine.
 */
object DesktopFaceBlur {

    /** The patch covers this multiple of the detected face box, so tracking slack still hides the face. */
    private const val COVER = 1.4f

    /** Keyframe tracks (clip-relative ms → value) plus the generated patch PNG. */
    data class Track(
        val offsetX: List<Pair<Long, Float>>,
        val offsetY: List<Pair<Long, Float>>,
        val scale: List<Pair<Long, Float>>,
        val patch: File,
    )

    private data class Sample(val relMs: Long, val srcMs: Long, val cx: Float, val cy: Float, val size: Float, val face: DesktopFaceDetector.Face)

    /**
     * Sample [clip]'s frames from [uri], track the largest face, write a blurred patch into [outDir],
     * and return the follow keyframes. Returns null when no face is found in the clip.
     *
     * Keyframe times are TIMELINE-relative (ms from the clip's start), and each sample's source time
     * is mapped through [TimelineMath.sourceTimeMs], so the patch follows the face on sped-up/slowed
     * clips too. Positions and SCALE are expressed in the overlay's own render space: an image layer
     * is drawn at canvas height × SCALE, centred at (0.5 + OFFSET_X) × canvas width — so the patch's
     * SCALE is its share of the frame height (not 1, which drew it full-frame), and horizontal offsets
     * are converted through the video's and the canvas's aspect ratios ([canvasAspect] = width / height).
     * The source clip's own static scale/offset is folded in; its rotation is not.
     */
    fun build(model: String, uri: String, clip: TimelineClip, outDir: File, canvasAspect: Float): Track? {
        val dur = clip.durationMs
        if (dur <= 0) return null
        val step = maxOf(300L, dur / 60) // ~every 0.3s of timeline, capped ~60 samples
        val samples = ArrayList<Sample>()
        var frameAspect = 0f
        var frameH = 0
        var relMs = 0L
        while (relMs <= dur) {
            val srcMs = TimelineMath.sourceTimeMs(clip, clip.startTimeMs + relMs)
            val frame = runBlocking { DesktopMediaDecoder.grabFrame(uri, srcMs, maxPx = 640) }
            if (frame != null) {
                if (frameH == 0) {
                    frameH = frame.height
                    frameAspect = frame.width.toFloat() / frame.height.coerceAtLeast(1)
                }
                val face = DesktopFaceDetector.detect(model, frame).maxByOrNull { it.area }
                if (face != null) {
                    val cx = (face.x1 + face.x2) / 2f
                    val cy = (face.y1 + face.y2) / 2f
                    val size = (face.x2 - face.x1).coerceAtLeast(0.01f)
                    samples += Sample(relMs, srcMs, cx, cy, size, face)
                }
            }
            relMs += step
        }
        if (samples.isEmpty() || frameH == 0) return null

        // Base = the frame where the face is largest → the sharpest crop to blur.
        val base = samples.maxByOrNull { it.size }!!
        val baseFrame = runBlocking { DesktopMediaDecoder.grabFrame(uri, base.srcMs, maxPx = 640) } ?: return null
        val patchImg = makeBlurPatch(baseFrame, base.face) ?: return null
        outDir.mkdirs()
        val patchFile = File(outDir, "faceblur_${clip.id}.png")
        ImageIO.write(patchImg, "png", patchFile)

        val clipScale = clip.scale.takeIf { it > 0f } ?: 1f
        val aspect = canvasAspect.takeIf { it > 0f } ?: frameAspect
        // Patch height as a fraction of the (clip-scaled) frame height, at the base face size.
        val patchFrac = patchImg.height.toFloat() / baseFrame.height.coerceAtLeast(1) * clipScale
        return Track(
            offsetX = samples.map { it.relMs to (clip.offsetX + (it.cx - 0.5f) * clipScale * frameAspect / aspect) },
            offsetY = samples.map { it.relMs to (clip.offsetY + (it.cy - 0.5f) * clipScale) },
            scale = samples.map { it.relMs to patchFrac * (it.size / base.size) },
            patch = patchFile,
        )
    }

    /** Crop the face region (expanded by [COVER]) from [frame] and pixelate it into an opaque patch. */
    private fun makeBlurPatch(frame: BufferedImage, face: DesktopFaceDetector.Face): BufferedImage? {
        val w = frame.width
        val h = frame.height
        val fw = (face.x2 - face.x1) * w
        val fh = (face.y2 - face.y1) * h
        val ccx = (face.x1 + face.x2) / 2f * w
        val ccy = (face.y1 + face.y2) / 2f * h
        val pw = (fw * COVER).toInt().coerceAtLeast(8)
        val ph = (fh * COVER).toInt().coerceAtLeast(8)
        val x = (ccx - pw / 2f).toInt().coerceIn(0, maxOf(0, w - 1))
        val y = (ccy - ph / 2f).toInt().coerceIn(0, maxOf(0, h - 1))
        val cw = minOf(pw, w - x)
        val ch = minOf(ph, h - y)
        if (cw < 4 || ch < 4) return null
        val crop = frame.getSubimage(x, y, cw, ch)
        // Pixelate: downscale hard, then upscale nearest-neighbor → blocky, unidentifiable.
        val block = maxOf(1, minOf(cw, ch) / 10)
        val small = BufferedImage(maxOf(1, cw / block), maxOf(1, ch / block), BufferedImage.TYPE_INT_RGB)
        small.createGraphics().apply { drawImage(crop, 0, 0, small.width, small.height, null); dispose() }
        val out = BufferedImage(cw, ch, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            drawImage(small, 0, 0, cw, ch, null)
            dispose()
        }
        return out
    }
}
