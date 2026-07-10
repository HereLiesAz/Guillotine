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

## 2. GLSL / ISF shader effects ✅ · GL-Transitions 🗺 (needs a compositor)

Two widely-used open GLSL standards:

- **[ISF](https://isf.video/) (Interactive Shader Format):** a JSON-header-over-GLSL convention for
  per-clip effect shaders with declared `INPUTS`, used by VDMX, Resolume, and others.
- **[GL Transitions](https://gl-transitions.com/):** a community library of *transition* shaders, each a
  `vec4 transition(vec2 uv)` sampling both `getFromColor` and `getToColor`.

**Shipped — single-input shader effects.** Drop a standard **`.isf`** shader (or a raw `.fs`/`.glsl`
fragment) onto a clip via **Filters → Shader**, or the `apply_shader` / `clear_shader` MCP tools. It
runs on every frame in **both preview and export**.
[`GlslShader`](../shared/src/main/kotlin/com/hereliesaz/guillotine/media/GlslShader.kt) (shared, pure
Kotlin) parses the ISF `/*{…}*/` header, maps `INPUTS` to uniforms, and rewrites ISF built-ins
(`IMG_THIS_PIXEL`, `IMG_NORM_PIXEL`, `isf_FragNormCoord`, `RENDERSIZE`, `TIME`) to Media3's conventions;
[`GlslEffect`](../app/src/main/java/com/hereliesaz/guillotine/media/GlslEffect.kt) is a Media3
`BaseGlShaderProgram` (1-in/1-out). **Supported subset:** single-pass, single-image ISF filters
(`float`/`bool`/`long`/`color`/`point2D`/`event` inputs, applied at their defaults for now) and raw
single-input fragments. **Rejected** (Media3's per-clip effect is 1-in/1-out): multi-pass, persistent/
feedback buffers, audio inputs, and multiple image inputs.

**Transitions between clips — shipped via FFmpeg `xfade` ✅.** True gl-transition *warps* sample two
frames per pixel, which Media3's per-clip effect chain can't express (single-input; `DefaultVideoCompositor`
only does fixed alpha-over). Rather than rewrite the render core, transitions are delivered through
FFmpeg's **`xfade`** filter, which ships ~50 GL-style transitions: `apply_transition(from_clip_id,
to_clip_id, type, duration_sec)` (MCP) bakes the two clips into one transitioned clip — `fade`,
`wipeleft/right/up/down`, `slide*`, `circleopen/close`, `dissolve`, `pixelize`, `radial`, `smoothleft`,
`distance`, and more. Runs on-device (requires the same ffmpeg binary as §4). A Media3-native, live-preview
compositor for the *exact* gl-transitions GLSL catalog (custom `VideoCompositor` + `CompositionPlayer`)
remains a possible follow-up; parameter-control UI for single-input shader `INPUTS` ships (FLOAT sliders).

---

## 3. MCP plugin protocol — AI-drivable tools 🛠 (foundation ✅ · documented in [PLUGINS.md](PLUGINS.md))

Guillotine's editor is already a standard **[Model Context Protocol](https://modelcontextprotocol.io)**
server (JSON-RPC 2.0, protocol `2024-11-05`, `tools/list` + `tools/call`, bearer auth on `/mcp`:6274):
every capability (cut, filter, LUT, denoise, generate, …) is an MCP tool, and controller LLMs drive the
editor purely through those tools — exchanging **text only**, never media. **See
[PLUGINS.md](PLUGINS.md)** for the full protocol, how to connect a client, user-defined tool packs, and
the draft distributable-manifest proposal.

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

## 4. Frei0r & FFmpeg filters — native video filters ✅ (advanced, bring-your-own ffmpeg)

- **FFmpeg `-vf` filtergraphs:** the ubiquitous filter-chain syntax.
- **[Frei0r](https://frei0r.dyne.org/):** the cross-application video-plugin API (`f0r_*` entry points),
  reached **through FFmpeg's `frei0r=<name>:<params>` filter** — so one path covers both ecosystems.

**Shipped — bake an FFmpeg/Frei0r filtergraph.** Point **Settings → AI Analyzer → FFmpeg filters** at an
`ffmpeg` executable, then `apply_ffmpeg_filter(clip_id, filter)` (MCP) bakes a standard `-vf` graph (e.g.
`hue=s=0, gblur=sigma=2`, or `frei0r=cartoon`) onto the clip and adds the result as a new clip.
[`FfmpegFilter`](../app/src/main/java/com/hereliesaz/guillotine/media/FfmpegFilter.kt) runs the process
on local files only — **on-device, nothing leaves the device**. This is a **bake-to-new-clip** step, not
a live filter (FFmpeg can't drive GL preview), and **requires the user to supply an ffmpeg binary**
(desktop-first; on Android, a bundled/downloaded ARM build) — so it's an optional advanced capability,
not a default mobile dependency. A live-filter (Media3-native) subset of common FFmpeg filters, and a
`:desktop` Frei0r host that loads `.so`/`.dll`/`.dylib` directly, are possible follow-ups.

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
