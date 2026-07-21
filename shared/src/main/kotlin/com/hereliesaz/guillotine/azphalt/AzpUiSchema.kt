package com.hereliesaz.guillotine.azphalt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * A parsed azphalt **UI schema** (azphalt `spec/ui-schema.md`, format 0.1): the declarative
 * `{ "controls": [ … ] }` an extension ships (referenced by [AzpAsset.ui]) so a host can render the
 * extension's control panel as **native widgets** — Compose here, SwiftUI elsewhere; "no HTML, no DOM,
 * no React." Each control carries a `type`, a unique `key` (groups excepted), a `label`, and
 * type-specific fields; a control's live value is pushed into the asset's `params` under its `key`,
 * while a button fires a named `action` instead.
 *
 * Pure Kotlin so `:app` and `:desktop` share it. Parsing is tolerant, matching the rest of the azphalt
 * loader: a malformed or unknown-`type` control is skipped rather than failing the whole panel.
 */
sealed interface AzpControl {
    val key: String
    val label: String

    data class Slider(
        override val key: String, override val label: String,
        val min: Double, val max: Double, val step: Double?, val default: Double,
    ) : AzpControl

    data class Number(
        override val key: String, override val label: String,
        val min: Double?, val max: Double?, val step: Double?, val default: Double,
    ) : AzpControl

    data class Toggle(
        override val key: String, override val label: String, val default: Boolean,
    ) : AzpControl

    data class Select(
        override val key: String, override val label: String,
        val options: List<Option>, val default: String?,
    ) : AzpControl {
        data class Option(val value: String, val label: String)
    }

    data class Color(
        override val key: String, override val label: String,
        val default: String, val alpha: Boolean,
    ) : AzpControl

    data class Text(
        override val key: String, override val label: String,
        val default: String, val placeholder: String?,
    ) : AzpControl

    data class Button(
        override val key: String, override val label: String, val action: String,
    ) : AzpControl

    /** A container of nested controls. The spec omits `key` for groups, so it is empty. */
    data class Group(override val label: String, val controls: List<AzpControl>) : AzpControl {
        override val key: String get() = ""
    }
}

/** A whole control panel: the ordered [controls] a host renders. */
data class AzpUiSchema(val controls: List<AzpControl>) {

    /** Flatten (through groups) to every control, in order. */
    fun flatten(): List<AzpControl> = controls.flatMap { flattenOne(it) }

    private fun flattenOne(c: AzpControl): List<AzpControl> =
        if (c is AzpControl.Group) c.controls.flatMap { flattenOne(it) } else listOf(c)

    /** The numeric control defaults keyed by control key — used to seed a shader's params on apply. */
    fun numericDefaults(): Map<String, Float> = flatten().mapNotNull { c ->
        when (c) {
            is AzpControl.Slider -> c.key to c.default.toFloat()
            is AzpControl.Number -> c.key to c.default.toFloat()
            is AzpControl.Toggle -> c.key to if (c.default) 1f else 0f
            else -> null
        }
    }.toMap()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Parse a `{ "controls": [ … ] }` schema. Returns `null` when [text] isn't a schema object;
         * individual malformed / unknown-`type` controls are dropped rather than failing the panel.
         */
        fun parse(text: String): AzpUiSchema? {
            val root = try { json.parseToJsonElement(text) } catch (e: Exception) { return null }
            val controls = (root as? JsonObject)?.get("controls") as? JsonArray ?: return null
            return AzpUiSchema(controls.mapNotNull { parseControl(it) })
        }

        private fun JsonObject.prim(name: String): JsonPrimitive? = this[name] as? JsonPrimitive
        private fun JsonObject.num(name: String): Double? = prim(name)?.doubleOrNull
        private fun JsonObject.bool(name: String): Boolean? = prim(name)?.booleanOrNull
        private fun JsonObject.str(name: String): String? = prim(name)?.contentOrNull

        private fun parseControl(el: JsonElement): AzpControl? {
            val o = el as? JsonObject ?: return null
            val type = o.str("type") ?: return null
            val key = o.str("key") ?: ""
            val label = o.str("label") ?: key
            fun requireKey(build: () -> AzpControl): AzpControl? = if (key.isBlank()) null else build()
            return when (type) {
                "slider" -> requireKey {
                    val min = o.num("min") ?: 0.0
                    AzpControl.Slider(key, label, min = min, max = o.num("max") ?: 1.0, step = o.num("step"), default = o.num("default") ?: min)
                }
                "number" -> requireKey {
                    AzpControl.Number(key, label, min = o.num("min"), max = o.num("max"), step = o.num("step"), default = o.num("default") ?: 0.0)
                }
                "toggle" -> requireKey { AzpControl.Toggle(key, label, default = o.bool("default") ?: false) }
                "select" -> requireKey {
                    val opts = (o["options"] as? JsonArray).orEmptyList().mapNotNull { oe ->
                        val oo = oe as? JsonObject ?: return@mapNotNull null
                        val v = oo.str("value") ?: return@mapNotNull null
                        AzpControl.Select.Option(v, oo.str("label") ?: v)
                    }
                    AzpControl.Select(key, label, opts, default = o.str("default"))
                }
                "color" -> requireKey { AzpControl.Color(key, label, default = o.str("default") ?: "#000000", alpha = o.bool("alpha") ?: false) }
                "text" -> requireKey { AzpControl.Text(key, label, default = o.str("default") ?: "", placeholder = o.str("placeholder")) }
                "button" -> requireKey { AzpControl.Button(key, label, action = o.str("action") ?: key) }
                "group" -> AzpControl.Group(label, (o["controls"] as? JsonArray).orEmptyList().mapNotNull { parseControl(it) })
                else -> null // unknown type — skip, stay forward-compatible
            }
        }

        private fun JsonArray?.orEmptyList(): List<JsonElement> = this ?: emptyList()
    }
}
