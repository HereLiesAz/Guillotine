package com.hereliesaz.guillotine.ai.agent

import kotlin.math.max

/**
 * Hardware facts Guillotine can read locally without permissions. This deliberately stays platform-
 * neutral so the recommendation logic is testable in :shared; Android/desktop only collect the facts.
 */
data class DeviceModelProfile(
    val deviceLabel: String,
    val totalRamBytes: Long,
    val freeStorageBytes: Long,
    val cpuCores: Int,
    val is64Bit: Boolean,
    val lowRam: Boolean,
    val osLabel: String,
    val primaryAbi: String = "",
) {
    val shortSummary: String
        get() = buildString {
            append(deviceLabel.ifBlank { "This device" })
            if (totalRamBytes > 0) append(" · ").append(formatGb(totalRamBytes)).append(" RAM")
            if (cpuCores > 0) append(" · ").append(cpuCores).append(" cores")
            append(if (is64Bit) " · 64-bit" else " · 32-bit")
            if (freeStorageBytes > 0) append(" · ").append(formatGb(freeStorageBytes)).append(" free")
            if (primaryAbi.isNotBlank()) append(" · ").append(primaryAbi)
        }

    companion object {
        fun formatGb(bytes: Long): String {
            if (bytes <= 0L) return "unknown"
            val gb = bytes / 1_000_000_000.0
            return if (gb >= 10.0) "%.0f GB".format(gb) else "%.1f GB".format(gb)
        }
    }
}

enum class DeviceModelFit {
    BEST_FIT,
    RECOMMENDED,
    CAUTION,
    NOT_RECOMMENDED,
}

data class DeviceModelAdvice(
    val model: OnDeviceModel,
    val fit: DeviceModelFit,
    val reason: String,
    /** Higher is better; used only to order models within a device-fit band. */
    val score: Int,
) {
    val badge: String
        get() = when (fit) {
            DeviceModelFit.BEST_FIT -> "Best fit"
            DeviceModelFit.RECOMMENDED -> "Recommended"
            DeviceModelFit.CAUTION -> "Use with caution"
            DeviceModelFit.NOT_RECOMMENDED -> "Not recommended"
        }
}

/**
 * Conservative, explainable device-fit recommendations. This is not a benchmark: it uses observable
 * RAM / storage / process bitness / core count plus the model's real weight size. It intentionally
 * avoids pretending Android exposes a reliable cross-vendor "NPU score".
 */
object DeviceModelAdvisor {
    private const val MB = 1_000_000L
    private const val GB = 1_000_000_000L

    fun advise(profile: DeviceModelProfile, models: List<OnDeviceModel>): List<DeviceModelAdvice> {
        if (models.isEmpty()) return emptyList()

        val assessed = models.map { assess(profile, it) }.toMutableList()
        val bestIndex = assessed.indices
            .filter { assessed[it].fit == DeviceModelFit.RECOMMENDED }
            .maxByOrNull { assessed[it].score }
            ?: assessed.indices
                .filter { assessed[it].fit == DeviceModelFit.CAUTION }
                .maxByOrNull { assessed[it].score }

        if (bestIndex != null) {
            val best = assessed[bestIndex]
            assessed[bestIndex] = best.copy(
                fit = DeviceModelFit.BEST_FIT,
                reason = best.reason
                    .removePrefix("Recommended: ")
                    .removePrefix("Possible, but not a recommendation: ")
                    .let { "Best fit: $it" },
            )
        }

        return assessed.sortedWith(
            compareBy<DeviceModelAdvice> { fitOrder(it.fit) }
                .thenByDescending { it.score }
                .thenBy { it.model.sizeBytes },
        )
    }

    private fun assess(profile: DeviceModelProfile, model: OnDeviceModel): DeviceModelAdvice {
        val blockers = mutableListOf<String>()
        val cautions = mutableListOf<String>()

        val heavyCategory = model.category in setOf(
            ModelCategory.ASSISTANT_LLM,
            ModelCategory.VLM,
            ModelCategory.STEM,
        )
        val estimatedWorkingSet = estimatedWorkingSet(model)
        val ramRatio = if (profile.totalRamBytes > 0L) {
            estimatedWorkingSet.toDouble() / profile.totalRamBytes.toDouble()
        } else {
            0.0
        }

        val storageMargin = max(256L * MB, (model.sizeBytes * 0.30).toLong())
        if (
            profile.freeStorageBytes > 0L &&
            profile.freeStorageBytes < model.sizeBytes + storageMargin
        ) {
            blockers += "only ${DeviceModelProfile.formatGb(profile.freeStorageBytes)} storage is free for a ${model.sizeLabel} model"
        }

        if (!profile.is64Bit && model.sizeBytes >= 1_000L * MB) {
            blockers += "the app is running 32-bit and this model is ${model.sizeLabel}"
        }

        if (profile.lowRam && model.sizeBytes >= 400L * MB) {
            blockers += "Android marks this as a low-RAM device"
        }

        if (heavyCategory && profile.totalRamBytes > 0L) {
            when {
                ramRatio >= 0.60 ->
                    blockers += "${model.sizeLabel} weights plus runtime headroom are too large relative to ${DeviceModelProfile.formatGb(profile.totalRamBytes)} RAM"
                ramRatio >= 0.42 ->
                    cautions += "${model.sizeLabel} is a large share of ${DeviceModelProfile.formatGb(profile.totalRamBytes)} RAM"
                ramRatio >= 0.32 ->
                    cautions += "this model leaves less editor headroom than the lighter choices"
            }
        }

        if (profile.cpuCores in 1..4 && model.sizeBytes >= 1_000L * MB) {
            cautions += "${profile.cpuCores} CPU cores may make startup and responses slow"
        }
        if (profile.cpuCores in 1..4 && model.sizeBytes >= 2_500L * MB) {
            blockers += "its size is a poor fit for a ${profile.cpuCores}-core device"
        }

        val fit = when {
            blockers.isNotEmpty() -> DeviceModelFit.NOT_RECOMMENDED
            cautions.isNotEmpty() -> DeviceModelFit.CAUTION
            else -> DeviceModelFit.RECOMMENDED
        }

        val score = when (fit) {
            DeviceModelFit.NOT_RECOMMENDED -> -1000
            DeviceModelFit.CAUTION -> 100
            DeviceModelFit.RECOMMENDED, DeviceModelFit.BEST_FIT -> 300
        } +
            model.capabilityTier.coerceIn(1, 5) * 20 -
            (ramRatio * 35.0).toInt() -
            when {
                model.sizeBytes >= 3_000L * MB -> 18
                model.sizeBytes >= 1_500L * MB -> 9
                model.sizeBytes >= 750L * MB -> 4
                else -> 0
            }

        val reason = when (fit) {
            DeviceModelFit.NOT_RECOMMENDED ->
                "Not recommended on this device: ${blockers.joinToString("; ")}."
            DeviceModelFit.CAUTION ->
                "Possible, but not a recommendation: ${cautions.joinToString("; ")}."
            DeviceModelFit.RECOMMENDED, DeviceModelFit.BEST_FIT ->
                recommendedReason(profile, model)
        }

        return DeviceModelAdvice(model, fit, reason, score)
    }

    private fun recommendedReason(profile: DeviceModelProfile, model: OnDeviceModel): String {
        val parts = mutableListOf<String>()
        if (profile.totalRamBytes > 0L) {
            parts += "${model.sizeLabel} is comfortable beside ${DeviceModelProfile.formatGb(profile.totalRamBytes)} RAM"
        } else {
            parts += "${model.sizeLabel} keeps the local footprint modest"
        }
        if (profile.cpuCores >= 8) parts += "${profile.cpuCores} cores give it useful compute headroom"
        else if (profile.cpuCores in 5..7) parts += "${profile.cpuCores} cores are a reasonable fit"
        if (model.category == ModelCategory.ASSISTANT_LLM) {
            parts += when (model.capabilityTier.coerceIn(1, 5)) {
                5 -> "it is the strongest local assistant in the catalog"
                4 -> "it offers stronger reasoning without the largest footprint"
                3 -> "it favors speed and efficiency"
                2 -> "it favors a lighter runtime"
                else -> "it is the lightest basic assistant option"
            }
        }
        return "Recommended: ${parts.joinToString("; ")}."
    }

    /**
     * Deliberately conservative budget: model weights + native/runtime/KV/image/audio working memory.
     * It is a ranking estimate, not a promise that Android will allocate exactly this much.
     */
    private fun estimatedWorkingSet(model: OnDeviceModel): Long = when (model.category) {
        ModelCategory.ASSISTANT_LLM -> (model.sizeBytes * 1.15).toLong() + 512L * MB
        ModelCategory.VLM -> (model.sizeBytes * 1.25).toLong() + 1L * GB
        ModelCategory.STEM -> (model.sizeBytes * 1.30).toLong() + 512L * MB
        ModelCategory.ASR, ModelCategory.TTS, ModelCategory.DENOISE,
        ModelCategory.DIARIZE_SEG, ModelCategory.DIARIZE_EMBED ->
            (model.sizeBytes * 1.15).toLong() + 256L * MB
        else -> (model.sizeBytes * 1.10).toLong() + 128L * MB
    }

    private fun fitOrder(fit: DeviceModelFit): Int = when (fit) {
        DeviceModelFit.BEST_FIT -> 0
        DeviceModelFit.RECOMMENDED -> 1
        DeviceModelFit.CAUTION -> 2
        DeviceModelFit.NOT_RECOMMENDED -> 3
    }
}
