package com.hereliesaz.guillotine.azphalt

import android.content.Context
import com.hereliesaz.guillotine.ai.agent.ModelDownloadManager
import com.hereliesaz.guillotine.ai.agent.OnDeviceModel
import com.hereliesaz.guillotine.ai.agent.RECOMMENDED_OBJECT_DETECTION_MODELS
import com.hereliesaz.guillotine.ai.agent.RECOMMENDED_RECOGNITION_MODELS
import com.hereliesaz.guillotine.ai.agent.RECOMMENDED_SCENE_MODELS
import java.io.File

/**
 * The three vision models Guillotine used to bundle, now offered through the azphalt store
 * (`submissions/com.hereliesaz.guillotine.*` in HereLiesAz/azphalt). Installs go through the same
 * signed-package path as the store screen and Settings: [AzphaltRegistry.download] →
 * [AzpModelInstall.install] into `azp-models/`, the directory [com.hereliesaz.guillotine.platform.ModelResolver]
 * scans. An untrusted or re-keyed package is refused here rather than prompted for; the store screen
 * is where that question gets asked.
 */
object VisionModelPacks {

    data class Pack(
        /** Store package id. */
        val id: String,
        val title: String,
        /** What it unlocks, in the user's terms. */
        val why: String,
        /** What you lose without it. */
        val without: String,
        /** Catalog entry for the same file (name, size, in-app download fallback). */
        val model: OnDeviceModel,
    )

    val PACKS: List<Pack> = listOf(
        Pack(
            id = "com.hereliesaz.guillotine.efficientdet-lite2",
            title = "Object detection",
            why = "Finds things in the frame and where they are — a phone in a hand, a car at the edge, a dog " +
                "crossing. Powers \"cut every shot with my phone in it\", object tracking and object removal.",
            without = "Guillotine falls back to whole-image labels, which miss small or off-centre objects.",
            model = RECOMMENDED_OBJECT_DETECTION_MODELS.first(),
        ),
        Pack(
            id = "com.hereliesaz.guillotine.efficientnet-lite0",
            title = "Scene tags",
            why = "Names what a shot is of — beach, stage, kitchen, crowd — so you can keep or cut by scene.",
            without = "Scene instructions use the basic built-in labeler, which knows fewer scenes.",
            model = RECOMMENDED_SCENE_MODELS.first(),
        ),
        Pack(
            id = "com.hereliesaz.guillotine.mobilenet-v3-small-embedder",
            title = "Same-thing recognition",
            why = "Tells \"this exact object\" apart from others of its kind, so pointing at one frame (\"this is " +
                "my phone\") finds that phone through the clip, not every phone.",
            without = "Matching falls back to the object's class: any phone counts.",
            model = RECOMMENDED_RECOGNITION_MODELS.first { it.fileName == "mobilenet_v3_small.tflite" },
        ),
    )

    fun isInstalled(context: Context, pack: Pack): Boolean =
        File(File(context.filesDir, "azp-models"), pack.model.fileName).isFile ||
            ModelDownloadManager.installedPath(context, pack.model) != null

    /** Downloads [pack] from the azphalt store and installs its model. Blocking; call off the main thread. */
    fun install(context: Context, pack: Pack, onProgress: (Float?) -> Unit = {}) {
        val bytes = AzphaltRegistry.download(AzpInstallLink(pack.id, null))
        AzpModelInstall.install(
            bytes,
            trustedKeys = AzphaltTrust.FLAGSHIP_SIGNING_KEYS,
            modelsDir = File(context.filesDir, "azp-models"),
            pins = AzpPublisherPins(File(context.filesDir, "azp-publishers.json")),
        ) { p -> onProgress(p.bytesTotal?.takeIf { it > 0 }?.let { p.bytesDone.toFloat() / it }) }
    }
}
