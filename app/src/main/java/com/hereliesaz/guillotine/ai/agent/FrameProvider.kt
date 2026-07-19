package com.hereliesaz.guillotine.ai.agent

import android.graphics.Bitmap

/**
 * Supplies the current playhead frame as raw pixels, so a vision-capable agent brain can *see* what
 * the user sees. This is the single seam through which frame pixels reach a model: the on-device
 * assistant feeds the [Bitmap] straight into its multimodal inference session (pixels never leave the
 * device). A future, explicitly opt-in cloud-vision path would read from this same seam and encode the
 * frame for an image-capable cloud API — keeping "how do we get the current frame" in exactly one place.
 *
 * Implemented by the concrete MCP tool surface, which already knows the timeline, the active clip, and
 * how to decode a frame at the playhead.
 */
fun interface FrameProvider {
    /**
     * The active video clip's frame at the current playhead as a [Bitmap], or `null` when there's no
     * video under the playhead / it can't be decoded. The caller owns the returned bitmap and must
     * recycle it when done.
     */
    fun currentFrame(): Bitmap?
}
