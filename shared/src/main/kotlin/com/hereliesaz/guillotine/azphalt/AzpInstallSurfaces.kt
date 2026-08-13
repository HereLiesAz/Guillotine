package com.hereliesaz.guillotine.azphalt

/**
 * Where an installed `.azp` actually turns up in Guillotine — the answer to "it installed, now what?".
 *
 * A package landing on disk is not the same as a user being able to find it, and the destination differs
 * per package: a shader and a caption animation and an on-device model all install identically and then
 * appear in three unrelated places, or in none. Telling every user the same thing would be wrong for most
 * of them, so the surface is *derived from the package* rather than asserted.
 *
 * azphalt's `spec/web-handoff.md` § Open questions names this gap from the ecosystem side — state
 * reporting "covers the statistic but not *show the user what they just installed*". It's a host's job:
 * nothing outside this app knows what its panels are called.
 *
 * Pure JVM and free of Compose so it can be unit-tested and shared; the wording lives with the UI.
 */
object AzpInstallSurfaces {

    /** A place in Guillotine where an installed package becomes reachable. */
    enum class Surface {
        /** A shader or LUT: its own section, named after the package, in the clip panel with a clip selected. */
        CLIP_EXTENSIONS,

        /** A caption animation: the **Kinetic type** section, with a caption selected. */
        CAPTION_MOTION,

        /**
         * An on-device AI model, extracted into the models directory by [AzpModelInstall] and picked up
         * from there by the host's model resolver.
         *
         * The store install path used to stop at the `.azp` and leave the models inert, telling the user
         * to go and re-run **Settings → Advanced → Install AI model** with a file a deep-link install
         * never gave them. Both routes now run the same model install, so this surface means the model is
         * actually in use. It still does not appear in the clip panel — [AzpInstalledUi.list] skips model
         * assets, since that panel can't apply them and used to imply it eventually would.
         */
        AI_MODEL,

        /**
         * An asset of a type Guillotine has no renderer for. [AzpInstalledUi.list] still lists it (it
         * skips only the types other subsystems own), so it appears in the clip panel as a section named
         * after the package, but nothing can be applied from it.
         */
        LISTED_NOT_APPLICABLE,

        /**
         * Nothing in this build surfaces it — a `code` package (the WASM sandbox isn't shipped; see
         * [AzpCodeRuntime]), or an `app` / `mcp` / `pack` package, which have no consumer here.
         */
        NONE,
    }

    /**
     * The surfaces [manifest] reaches, in a stable order and without duplicates. A `mixed` package that
     * ships both a shader and a motion legitimately reaches two, so this is a list rather than one value.
     *
     * A package with no assets at all reaches [Surface.NONE] — that covers `code`, `app`, `mcp` and
     * `pack` kinds together, since what decides the answer is the payload, not the declared `kind`.
     */
    fun of(manifest: AzpManifest): List<Surface> {
        if (manifest.assets.isEmpty()) return listOf(Surface.NONE)
        val out = LinkedHashSet<Surface>()
        for (asset in manifest.assets) {
            val type = asset.type.trim().lowercase()
            out += when {
                type in AzpMotionInstaller.MOTION_TYPES -> Surface.CAPTION_MOTION
                type in AzpModelInstaller.MODEL_TYPES -> Surface.AI_MODEL
                // The single source of truth for "does this render natively" — deliberately reused rather
                // than restated, so a new shader/LUT alias only has to be added in one place.
                AzpInstalledUi.renderKindOf(type) != AzpInstalledUi.RenderKind.OTHER -> Surface.CLIP_EXTENSIONS
                else -> Surface.LISTED_NOT_APPLICABLE
            }
        }
        return out.toList()
    }

    /**
     * Best-effort answer to "would this catalog entry reach [Surface.NONE]", using only the
     * browse-list summary's [types] — no download, no manifest. A `pack`/`app`/`mcp`/`code`
     * package's summary always has empty (or otherwise unrecognized) `types`, since
     * [AzphaltRegistry.CatalogEntry.types] mirrors its assets' own declared types; this lets the
     * store filter those out before a user spends an install on something guaranteed to be inert,
     * without a network round-trip per entry — installing still doesn't happen until the user asks,
     * but browsing shouldn't offer an "Install" button for something that can only ever land on
     * [Surface.NONE].
     *
     * Every type string [of] treats as a real consumer is checked here too, so this can't produce a
     * false positive (hiding something that would actually work). A false negative — showing
     * something that turns out inert — would mean a catalog entry's `types` doesn't reflect its own
     * assets, a registry-data problem rather than something to design around client-side.
     */
    fun hasKnownConsumer(types: List<String>): Boolean = types.any { raw ->
        val type = raw.trim().lowercase()
        type in AzpMotionInstaller.MOTION_TYPES ||
            type in AzpModelInstaller.MODEL_TYPES ||
            AzpInstalledUi.renderKindOf(type) != AzpInstalledUi.RenderKind.OTHER
    }
}
