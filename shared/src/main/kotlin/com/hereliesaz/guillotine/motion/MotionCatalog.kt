package com.hereliesaz.guillotine.motion

/**
 * The kinetic-typography presets available to the caption picker: the first-party defaults bundled with
 * the app plus any installed from a `.azp` at runtime.
 *
 * Each default here is authored in the exact same `az-motion` JSON a third-party plugin ships — the
 * effects are **data, not code**. Every one is also published as its own standalone `.azp` package under
 * `extensions/src/motion-*` (one plugin per effect); these bundled copies just make the feature work out
 * of the box. Keep the two in sync when editing an effect.
 *
 * [BUILT_IN] is parsed once at class-load; a malformed default throws immediately (a programming error a
 * unit test catches), never silently drops an effect.
 */
object MotionCatalog {

    /** Resolve a preset by id from [available] (built-ins + installed), or null if none matches. */
    fun find(available: List<MotionPreset>, id: String?): MotionPreset? =
        id?.let { wanted -> available.firstOrNull { it.id == wanted } }

    /** The first-party default presets, in picker order. */
    val BUILT_IN: List<MotionPreset> = listOf(
        // ---- Entrances -----------------------------------------------------
        """{ "format":"az-motion", "id":"com.hereliesaz.guillotine.motion.fade-in", "name":"Fade In",
             "blurb":"Softly fades up from transparent.", "category":"entrance",
             "window":{"in":0.3},
             "tracks":[ {"channel":"opacity","mode":"in","keys":[{"t":0,"v":0},{"t":1,"v":1}]} ] }""",

        """{ "format":"az-motion", "id":"com.hereliesaz.guillotine.motion.rise", "name":"Rise",
             "blurb":"Floats up into place while fading in.", "category":"entrance",
             "window":{"in":0.32},
             "tracks":[
               {"channel":"opacity","mode":"in","keys":[{"t":0,"v":0},{"t":0.8,"v":1}]},
               {"channel":"offsetY","mode":"in","keys":[{"t":0,"v":0.28},{"t":1,"v":0}]} ] }""",

        """{ "format":"az-motion", "id":"com.hereliesaz.guillotine.motion.drop", "name":"Drop",
             "blurb":"Falls down into place while fading in.", "category":"entrance",
             "window":{"in":0.32},
             "tracks":[
               {"channel":"opacity","mode":"in","keys":[{"t":0,"v":0},{"t":0.8,"v":1}]},
               {"channel":"offsetY","mode":"in","keys":[{"t":0,"v":-0.28},{"t":1,"v":0}]} ] }""",

        """{ "format":"az-motion", "id":"com.hereliesaz.guillotine.motion.slide-left", "name":"Slide From Left",
             "blurb":"Glides in from the left edge.", "category":"entrance",
             "window":{"in":0.32},
             "tracks":[
               {"channel":"opacity","mode":"in","keys":[{"t":0,"v":0},{"t":0.7,"v":1}]},
               {"channel":"offsetX","mode":"in","keys":[{"t":0,"v":-0.5},{"t":1,"v":0}]} ] }""",

        """{ "format":"az-motion", "id":"com.hereliesaz.guillotine.motion.slide-right", "name":"Slide From Right",
             "blurb":"Glides in from the right edge.", "category":"entrance",
             "window":{"in":0.32},
             "tracks":[
               {"channel":"opacity","mode":"in","keys":[{"t":0,"v":0},{"t":0.7,"v":1}]},
               {"channel":"offsetX","mode":"in","keys":[{"t":0,"v":0.5},{"t":1,"v":0}]} ] }""",

        """{ "format":"az-motion", "id":"com.hereliesaz.guillotine.motion.pop", "name":"Pop",
             "blurb":"Springs in with a bouncy overshoot.", "category":"entrance",
             "window":{"in":0.34},
             "tracks":[
               {"channel":"opacity","mode":"in","keys":[{"t":0,"v":0},{"t":0.5,"v":1}]},
               {"channel":"scale","mode":"in","keys":[
                 {"t":0,"v":0.5},{"t":0.62,"v":1.14,"ease":[0.25,0.1,0.25,1]},{"t":1,"v":1,"ease":[0.25,0.1,0.25,1]}]} ] }""",

        """{ "format":"az-motion", "id":"com.hereliesaz.guillotine.motion.zoom-in", "name":"Zoom In",
             "blurb":"Grows from small to full size.", "category":"entrance",
             "window":{"in":0.34},
             "tracks":[
               {"channel":"opacity","mode":"in","keys":[{"t":0,"v":0},{"t":0.7,"v":1}]},
               {"channel":"scale","mode":"in","keys":[{"t":0,"v":0.2},{"t":1,"v":1}]} ] }""",

        """{ "format":"az-motion", "id":"com.hereliesaz.guillotine.motion.punch", "name":"Punch In",
             "blurb":"Slams in oversized, then settles.", "category":"entrance",
             "window":{"in":0.3},
             "tracks":[
               {"channel":"opacity","mode":"in","keys":[{"t":0,"v":0},{"t":0.5,"v":1}]},
               {"channel":"scale","mode":"in","keys":[{"t":0,"v":1.8},{"t":1,"v":1}]} ] }""",

        """{ "format":"az-motion", "id":"com.hereliesaz.guillotine.motion.spin-in", "name":"Spin In",
             "blurb":"Twists and scales into place.", "category":"entrance",
             "window":{"in":0.36},
             "tracks":[
               {"channel":"opacity","mode":"in","keys":[{"t":0,"v":0},{"t":0.6,"v":1}]},
               {"channel":"rotation","mode":"in","keys":[{"t":0,"v":-120},{"t":1,"v":0}]},
               {"channel":"scale","mode":"in","keys":[{"t":0,"v":0.3},{"t":1,"v":1}]} ] }""",

        """{ "format":"az-motion", "id":"com.hereliesaz.guillotine.motion.bounce", "name":"Bounce",
             "blurb":"Drops in and bounces to a stop.", "category":"entrance",
             "window":{"in":0.4},
             "tracks":[
               {"channel":"opacity","mode":"in","keys":[{"t":0,"v":0},{"t":0.4,"v":1}]},
               {"channel":"offsetY","mode":"in","keys":[
                 {"t":0,"v":-0.32},{"t":0.7,"v":0.05,"ease":[0.25,0.1,0.25,1]},{"t":1,"v":0,"ease":[0.25,0.1,0.25,1]}]} ] }""",

        // ---- Entrance + exit ----------------------------------------------
        """{ "format":"az-motion", "id":"com.hereliesaz.guillotine.motion.fade", "name":"Fade In & Out",
             "blurb":"Fades up on entry and out before it ends.", "category":"exit",
             "window":{"in":0.22,"out":0.22},
             "tracks":[
               {"channel":"opacity","mode":"in","keys":[{"t":0,"v":0},{"t":1,"v":1}]},
               {"channel":"opacity","mode":"out","keys":[{"t":0,"v":1},{"t":1,"v":0}]} ] }""",

        // ---- Sustained emphasis -------------------------------------------
        """{ "format":"az-motion", "id":"com.hereliesaz.guillotine.motion.pulse", "name":"Pulse",
             "blurb":"Gently breathes larger and back, throughout.", "category":"emphasis",
             "tracks":[
               {"channel":"scale","mode":"sustain","keys":[{"t":0,"v":1},{"t":0.5,"v":1.08},{"t":1,"v":1}]} ] }""",

        """{ "format":"az-motion", "id":"com.hereliesaz.guillotine.motion.wiggle", "name":"Wiggle",
             "blurb":"Rocks side to side for a playful jitter.", "category":"emphasis",
             "tracks":[
               {"channel":"rotation","mode":"sustain","keys":[
                 {"t":0,"v":0},{"t":0.25,"v":7},{"t":0.5,"v":0},{"t":0.75,"v":-7},{"t":1,"v":0}]} ] }""",

        """{ "format":"az-motion", "id":"com.hereliesaz.guillotine.motion.float", "name":"Float",
             "blurb":"Drifts up and down like it's weightless.", "category":"emphasis",
             "window":{"in":0.25},
             "tracks":[
               {"channel":"opacity","mode":"in","keys":[{"t":0,"v":0},{"t":1,"v":1}]},
               {"channel":"offsetY","mode":"sustain","keys":[{"t":0,"v":0},{"t":0.5,"v":-0.04},{"t":1,"v":0}]} ] }""",
    ).map { text ->
        MotionPreset.parse(text) ?: error("built-in motion preset failed to parse: $text")
    }
}
