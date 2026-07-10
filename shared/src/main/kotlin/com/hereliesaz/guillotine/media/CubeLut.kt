package com.hereliesaz.guillotine.media

/**
 * Pure-Kotlin parser for Adobe/Resolve **`.cube` 3D LUT** files — the de-facto interchange format for
 * color grades, exported by DaVinci Resolve, Photoshop, and the huge free LUT libraries online. Kept
 * platform-free (no Android types) so both `:app` (Media3 `SingleColorLut`) and `:desktop` can use it.
 *
 * Guillotine's LUT ecosystem entry point: drop any standard `.cube` file onto a clip and it grades in
 * preview and export. Everything stays on-device.
 */
object CubeLut {

    /**
     * A parsed 3D LUT. [size] is N (the cube edge); [entries] holds N³ RGB triples in the `.cube`
     * file's native order — **red varies fastest**, then green, then blue — as flat floats in `[0, 1]`
     * (`entries[i*3+0/1/2]` = r/g/b for the i-th data line). Consumers pack this into their own texture.
     */
    data class Lut3d(val size: Int, val entries: FloatArray) {
        /** RGB of the node at grid coords ([r], [g], [b]) in `[0, size)` → a Triple of floats in `[0,1]`. */
        fun at(r: Int, g: Int, b: Int): Triple<Float, Float, Float> {
            val i = (r + size * (g + size * b)) * 3
            return Triple(entries[i], entries[i + 1], entries[i + 2])
        }

        override fun equals(other: Any?): Boolean =
            other is Lut3d && size == other.size && entries.contentEquals(other.entries)

        override fun hashCode(): Int = 31 * size + entries.contentHashCode()
    }

    /**
     * Parse `.cube` [text] into a [Lut3d]. Supports `LUT_3D_SIZE`, comments (`#`), `TITLE`, and
     * `DOMAIN_MIN`/`DOMAIN_MAX` (values are rescaled from the domain into `[0,1]`). Throws
     * [IllegalArgumentException] for 1D LUTs or malformed data.
     */
    fun parse(text: String): Lut3d {
        var size = -1
        val domainMin = floatArrayOf(0f, 0f, 0f)
        val domainMax = floatArrayOf(1f, 1f, 1f)
        val data = ArrayList<Float>()

        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val upper = line.uppercase()
            when {
                upper.startsWith("LUT_3D_SIZE") -> size = line.split(Regex("\\s+"))[1].toInt()
                upper.startsWith("LUT_1D_SIZE") ->
                    throw IllegalArgumentException("1D .cube LUTs aren't supported — use a 3D LUT.")
                upper.startsWith("TITLE") -> Unit
                upper.startsWith("DOMAIN_MIN") -> readTriple(line, domainMin)
                upper.startsWith("DOMAIN_MAX") -> readTriple(line, domainMax)
                upper.startsWith("LUT_3D_INPUT_RANGE") -> Unit // rare; ignore (domain covers rescale)
                else -> {
                    // A data row: three floats. Anything else (stray keyword) is skipped.
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 3) {
                        val r = parts[0].toFloatOrNull()
                        val g = parts[1].toFloatOrNull()
                        val b = parts[2].toFloatOrNull()
                        if (r != null && g != null && b != null) {
                            data += rescale(r, domainMin[0], domainMax[0])
                            data += rescale(g, domainMin[1], domainMax[1])
                            data += rescale(b, domainMin[2], domainMax[2])
                        }
                    }
                }
            }
        }

        require(size in 2..129) { "Missing or invalid LUT_3D_SIZE (got $size)." }
        val expected = size * size * size * 3
        require(data.size == expected) {
            "Malformed .cube: expected ${expected / 3} entries for size $size, got ${data.size / 3}."
        }
        return Lut3d(size, data.toFloatArray())
    }

    private fun readTriple(line: String, into: FloatArray) {
        val parts = line.split(Regex("\\s+"))
        for (i in 0 until 3) parts.getOrNull(i + 1)?.toFloatOrNull()?.let { into[i] = it }
    }

    private fun rescale(v: Float, min: Float, max: Float): Float {
        val span = (max - min)
        val out = if (span != 0f) (v - min) / span else v
        return out.coerceIn(0f, 1f)
    }
}
