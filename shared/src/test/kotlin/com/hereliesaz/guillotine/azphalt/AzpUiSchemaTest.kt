package com.hereliesaz.guillotine.azphalt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AzpUiSchemaTest {

    // The exact example from azphalt spec/ui-schema.md.
    private val specExample = """
        {
          "controls": [
            { "type": "slider", "key": "cellSize", "label": "Cell size", "min": 2, "max": 40, "step": 1, "default": 8 },
            { "type": "select", "key": "shape", "label": "Dot shape",
              "options": [{ "value": "circle", "label": "Circle" }, { "value": "square", "label": "Square" }],
              "default": "circle" },
            { "type": "color", "key": "ink", "label": "Ink", "default": "#000000", "alpha": true },
            { "type": "toggle", "key": "cmyk", "label": "CMYK separation", "default": true }
          ]
        }
    """.trimIndent()

    @Test fun `parses the spec example`() {
        val schema = AzpUiSchema.parse(specExample)!!
        assertEquals(4, schema.controls.size)

        val slider = schema.controls[0] as AzpControl.Slider
        assertEquals("cellSize", slider.key)
        assertEquals("Cell size", slider.label)
        assertEquals(2.0, slider.min, 0.0)
        assertEquals(40.0, slider.max, 0.0)
        assertEquals(1.0, slider.step!!, 0.0)
        assertEquals(8.0, slider.default, 0.0)

        val select = schema.controls[1] as AzpControl.Select
        assertEquals(listOf("circle", "square"), select.options.map { it.value })
        assertEquals(listOf("Circle", "Square"), select.options.map { it.label })
        assertEquals("circle", select.default)

        val color = schema.controls[2] as AzpControl.Color
        assertEquals("#000000", color.default)
        assertTrue(color.alpha)

        val toggle = schema.controls[3] as AzpControl.Toggle
        assertTrue(toggle.default)
    }

    @Test fun `skips unknown control types but keeps the rest`() {
        val schema = AzpUiSchema.parse(
            """{ "controls": [
                { "type": "curve", "key": "tone", "label": "Tone" },
                { "type": "toggle", "key": "on", "label": "On", "default": false }
            ] }""",
        )!!
        assertEquals(1, schema.controls.size)
        assertEquals("on", schema.controls.single().key)
    }

    @Test fun `drops keyless non-group controls`() {
        val schema = AzpUiSchema.parse(
            """{ "controls": [ { "type": "slider", "label": "no key" } ] }""",
        )!!
        assertTrue(schema.controls.isEmpty())
    }

    @Test fun `parses nested groups`() {
        val schema = AzpUiSchema.parse(
            """{ "controls": [
                { "type": "group", "label": "Advanced", "controls": [
                    { "type": "number", "key": "seed", "label": "Seed", "default": 3 }
                ] }
            ] }""",
        )!!
        val group = schema.controls.single() as AzpControl.Group
        assertEquals("Advanced", group.label)
        assertEquals("seed", (group.controls.single() as AzpControl.Number).key)
    }

    @Test fun `button falls back to key when action is absent`() {
        val schema = AzpUiSchema.parse(
            """{ "controls": [ { "type": "button", "key": "reset", "label": "Reset" } ] }""",
        )!!
        assertEquals("reset", (schema.controls.single() as AzpControl.Button).action)
    }

    @Test fun `numericDefaults seeds slider, number and toggle values through groups`() {
        val schema = AzpUiSchema.parse(
            """{ "controls": [
                { "type": "slider", "key": "cellSize", "label": "Cell", "min": 2, "max": 40, "default": 8 },
                { "type": "toggle", "key": "cmyk", "label": "CMYK", "default": true },
                { "type": "text", "key": "note", "label": "Note", "default": "hi" },
                { "type": "group", "label": "Adv", "controls": [
                    { "type": "number", "key": "seed", "label": "Seed", "default": 3 }
                ] }
            ] }""",
        )!!
        val defaults = schema.numericDefaults()
        assertEquals(8f, defaults["cellSize"])
        assertEquals(1f, defaults["cmyk"])   // toggle true -> 1
        assertEquals(3f, defaults["seed"])   // reached through the group
        assertEquals(null, defaults["note"]) // text is not numeric
    }

    @Test fun `returns null for non-schema input`() {
        assertNull(AzpUiSchema.parse("not json"))
        assertNull(AzpUiSchema.parse("""{ "nope": [] }"""))
        assertNull(AzpUiSchema.parse("[]"))
    }
}
