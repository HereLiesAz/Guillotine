package com.hereliesaz.guillotine.ai.safety

import com.hereliesaz.guillotine.model.AiProvenance

/**
 * In-app reporting for AI-generated content (Google Play's AI-Generated Content policy). Reporting a
 * generated clip removes it from the project and opens an email to [ADDRESS] carrying what's needed
 * to act on it: the provider, model and prompt. The platforms open [mailtoUri] with their own mail
 * handler.
 */
object ContentReport {

    const val ADDRESS = "hereliesaz@gmail.com"

    fun subject(p: AiProvenance) = "Guillotine content report: ${p.provider}"

    fun body(p: AiProvenance, reason: String = "", appVersion: String = ""): String = buildString {
        appendLine("Reported AI-generated content in Guillotine.")
        appendLine()
        appendLine("Provider: ${p.provider}")
        if (p.model.isNotBlank()) appendLine("Model: ${p.model}")
        appendLine("Prompt: ${p.prompt}")
        appendLine("Generated at: ${java.time.Instant.ofEpochMilli(p.createdAtMs)}")
        if (appVersion.isNotBlank()) appendLine("App version: $appVersion")
        appendLine()
        appendLine("What's wrong with it (optional):")
        if (reason.isNotBlank()) appendLine(reason)
    }

    fun mailtoUri(p: AiProvenance, reason: String = "", appVersion: String = ""): String {
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        return "mailto:$ADDRESS?subject=${enc(subject(p))}&body=${enc(body(p, reason, appVersion))}"
    }
}
