# Guillotine Extensibility & Ecosystem

Guillotine is built to take **standard, already-existing formats** for presets, filters, effects, and
plugins — not a bespoke Guillotine-only format nobody else supports. If you've made a LUT for DaVinci
Resolve, a transition for [gl-transitions](https://gl-transitions.com/), or an MCP tool for Claude,
the goal is that it drops straight into Guillotine.

The invariant holds throughout: **your video and audio never leave the device.** Extensions run
on-device (GL shaders, LUTs, native filters) or, for the MCP plugin protocol, exchange only *text*
with a controller — never your media.

Status legend: ✅ shipped · 🛠 in progress · 🗺 planned.

---

## 1. LUTs — `.cube` 3D color grades ✅

The universal color-grade interchange format, exported by DaVinci Resolve, Photoshop/Camera Raw, and
shared in countless free LUT packs.

- **Drop-in:** clip → **Filters → LUT → Pick .cube**. Any standard 3D `.cube` (sizes 2–129,
  `DOMAIN_MIN/MAX` respected) grades the clip in **both live preview and export** — WYSIWYG.
- **AI:** `apply_lut(clip_id, path)` / `clear_lut(clip_id)` MCP tools.
- **How it works:** [`CubeLut`](../shared/src/main/kotlin/com/hereliesaz/guillotine/media/CubeLut.kt)
  (pure-Kotlin, shared) parses the file;
  [`LutCube`](../app/src/main/java/com/hereliesaz/guillotine/media/LutCube.kt) builds a Media3
  `SingleColorLut` GL effect (`cube[R][G][B]`, ARGB), applied last in
  [`VideoEffects`](../app/src/main/java/com/hereliesaz/guillotine/media/VideoEffects.kt) so it grades
  the adjusted picture.
- **Author a LUT:** any tool that exports `.cube` works. 1D `.cube` LUTs are rejected (use a 3D LUT).

---

## 2. GL Transitions & ISF shaders — GLSL effects/transitions 🗺

Two widely-used open GLSL standards:

- **[GL Transitions](https://gl-transitions.com/):** a community library of transition shaders, each a
  `vec4 transition(vec2 uv)` function over `getFromColor`/`getToColor` uniforms with typed parameters.
- **[ISF](https://isf.video/) (Interactive Shader Format):** a JSON-header-over-GLSL convention for
  effect/generator shaders with declared `INPUTS`, used by VDMX, Resolume, and others.

**Plan.** A `GlslEffect` wrapping Media3's `GlShaderProgram`/`GlEffect` so the same shader runs in
preview and export, plus small adapters that map a GL-Transitions `transition()` into a crossfade
between two clips, and an ISF header into declared uniform controls. Transitions attach at a clip
boundary; ISF effects attach per-clip like a LUT. Shader source stays on-device.

Tracking: this is the next ecosystem milestone after LUTs.

---

## 3. MCP plugin protocol — AI-drivable tools 🛠 (foundation ✅)

Guillotine's editor is already a **Model Context Protocol** surface: every capability (cut, filter,
LUT, denoise, generate, …) is an MCP tool, and controller LLMs drive the editor purely through those
tools — exchanging **text only**, never media.

Already shipped:

- **Embedded MCP server.** The app serves `/mcp` over HTTP on port **6274**, gated by a bearer token
  (Settings → Advanced). Any MCP client — Claude Desktop, a script, another app — can list and call
  the editor's tools. See [`McpServer`]/[`McpAuth`](../app/src/main/java/com/hereliesaz/guillotine/mcp/McpAuth.kt).
- **Remote relay.** [`tools/mcp-relay`](../tools) + a Cloudflare Worker bridges the on-device server to
  a remote MCP client without exposing the device, using the same token.
- **User-defined tools.** `create_user_tool(name, description)` / `run_user_tool(name, clip_id)` let a
  user (or an LLM) mint a named macro over existing tools, persisted via
  [`UserToolStore`](../app/src/main/java/com/hereliesaz/guillotine/data/UserToolStore.kt) and shared in
  the settings backup bundle.

**Formalizing the protocol (in progress).** A published `TOOLS.md` schema (the JSON tool definitions
this build already emits from `McpTools.toolDefinitions()`), a stable versioned tool namespace, and a
plugin manifest so third-party MCP tool packs can be discovered/installed. Until then, point any MCP
client at `/mcp` with the token — the tool list is self-describing.

---

## 4. Frei0r & FFmpeg filters — native video filters 🗺 (desktop-first)

- **[Frei0r](https://frei0r.dyne.org/):** the minimalist cross-application video-plugin API
  (`f0r_*` entry points in a shared library) used by FFmpeg, MLT/Shotcut, Kdenlive, PureData.
- **FFmpeg `-vf` filtergraphs:** the ubiquitous filter chain syntax.

**Plan.** On **desktop** (`:desktop`, JVM), load Frei0r `.so`/`.dll`/`.dylib` plugins and shell out to
FFmpeg filtergraphs as an export-time effect stage — this is where native plugins and a full FFmpeg
build are practical. On **Android** these are heavyweight (a bundled mobile FFmpeg + JNI Frei0r host),
so they're gated as an optional desktop-first capability rather than a default mobile dependency. Both
run entirely on-device.

---

## Contributing an extension

- **A LUT:** just export a `.cube` — it already works. Share packs anywhere.
- **A GL/ISF shader or GL-Transition:** track milestone 2; the target is that an unmodified
  gl-transitions/ISF shader loads as-is.
- **An MCP tool/plugin:** build against the `/mcp` surface today; the formal manifest lands with
  milestone 3.
- **A Frei0r/FFmpeg filter:** desktop-first, milestone 4.

Everything here is designed so the **existing worldwide libraries** of these formats are Guillotine's
ecosystem from day one — no waiting for a Guillotine-specific marketplace to fill up.
