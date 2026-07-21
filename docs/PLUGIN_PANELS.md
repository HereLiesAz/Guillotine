# Plugin panels — contributing UI to the clip-properties panel

Guillotine's **clip-properties panel** is the standard place an extension/plugin puts its own controls,
options, or preview. It's the docked region beside (wide) or below (tall) the video preview, rendered by
[`AdvancedToolView`](../app/src/main/java/com/hereliesaz/guillotine/ui/AdvancedToolView.kt). The panel is
already **resizable, collapsible, re-orientable** (double-tap the grip), and its placement **persists**
across sessions ([`PanelLayoutPrefs`](../app/src/main/java/com/hereliesaz/guillotine/ui/PanelLayoutPrefs.kt)) —
anything hosted in it inherits all of that for free.

This is the same surface both the built-in per-clip tools and (later) azphalt UI-schema sections use.
See [ECOSYSTEM.md §3b](ECOSYSTEM.md) for where this sits on the azphalt adoption path.

## The contract

A plugin contributes one or more **sections** by implementing
[`ClipPanelContribution`](../app/src/main/java/com/hereliesaz/guillotine/ui/ClipPanelContribution.kt) and
registering it once at startup:

```kotlin
interface ClipPanelContribution {
    val id: String                                            // stable, reverse-DNS (e.g. the .azp id)
    val title: String                                         // default section heading
    fun appliesTo(clip: TimelineClip, state: EditorUiState): Boolean
    @Composable fun Content(vm: EditorViewModel, clip: TimelineClip)
}
```

Rules that keep the panel consistent and the on-device boundary intact:

- **Scope with `appliesTo`.** Return `true` only for the clip your section is for (by `ClipType`, and
  later by azphalt capability / `mediaDomains`). It runs on every recomposition, so keep it a cheap,
  synchronous check — do any expensive lookup (scanning installed packages, decoding a preview) inside
  `Content`, and render nothing when there's nothing to show.
- **Drive edits only through `EditorViewModel`.** Mutate the clip with the same operations the MCP tools
  use (`vm.updateClipFilters`, `vm.applyCaptionMotion`, …). A contribution never receives raw media, an
  `ExoPlayer`, the engine, sensors, or the filesystem — that's azphalt's "never-list" and Guillotine's
  on-device invariant. Your section exchanges *intent*, not media.
- **Wrap your UI in `ClipPanelSection(title) { … }`** so it reads like the built-in tools. You may render
  a preview inside your section (a thumbnail, an effect swatch); it stays inside the panel and never
  touches the main video preview.

## Registering

Registration is one call, once, at app start
([`GuillotineApplication`](../app/src/main/java/com/hereliesaz/guillotine/GuillotineApplication.kt)):

```kotlin
ClipPanelContributions.register(KineticTypographyContribution())
```

The registry is an observable snapshot list, so a contribution registered later — e.g. right after an
Azphalt Store install — makes the panel recompose. Duplicate `id`s replace in place; `unregister(id)`
removes.

[`InlineClipTools`](../app/src/main/java/com/hereliesaz/guillotine/ui/InlineClipTools.kt) renders the
built-in tools for the selection and then calls `ClipPanelHost(vm, state)`, which draws each registered
contribution that applies — once, for the first selected clip it matches.

## Worked example — the kinetic-typography picker

[`KineticTypographyContribution`](../app/src/main/java/com/hereliesaz/guillotine/ui/KineticTypographyContribution.kt)
is the first built-in contribution and the reference for a real one:

- `appliesTo` = the selected clip is `ClipType.TEXT` (cheap type gate).
- `Content` = `KineticTypeToolInline`, which lists installed motion `.azp`s off the main thread, renders
  **nothing** when none are installed, and otherwise shows a `ClipPanelSection("Kinetic type")` whose rows
  bake or clear the caption's animation via `EditorViewModel`.

An azphalt UI-schema renderer will be *another* `ClipPanelContribution` whose `Content` interprets a
package's declarative UI — no special-casing in the panel, just one more registration.

## azphalt UI-schema sections

An azphalt asset package can ship a **declarative control panel** — azphalt `spec/ui-schema.md`, a
`{ "controls": [ … ] }` document referenced by the asset's `ui` field — so its controls render as native
widgets in every conforming host. Guillotine renders these through the same panel seam:

- [`AzpUiSchema.parse`](../shared/src/main/kotlin/com/hereliesaz/guillotine/azphalt/AzpUiSchema.kt)
  (`:shared`, pure Kotlin) parses the schema into typed controls — `slider`, `number`, `toggle`,
  `select`, `color`, `text`, `button`, `group`. Unknown types are skipped (forward-compatible).
- [`AzpUiSchemaControls`](../app/src/main/java/com/hereliesaz/guillotine/ui/AzpUiSchemaControls.kt)
  renders each control as a native Compose widget, reading/writing raw azphalt `params` JSON.
- [`AzpInstalledUi.list`](../shared/src/main/kotlin/com/hereliesaz/guillotine/azphalt/AzpInstalledUi.kt)
  finds installed packages (scoped to this host) that ship a `ui`, and
  [`AzpAssetContribution`](../app/src/main/java/com/hereliesaz/guillotine/ui/AzpAssetContribution.kt)
  renders each as a panel section, persisting values with
  [`AzpParamStore`](../app/src/main/java/com/hereliesaz/guillotine/ui/AzpParamStore.kt).

**Applying asset extensions.** For the types Guillotine renders natively — a GLSL **shader**, a `.cube`
**LUT** — the section shows an **Apply to clip** button. [`AzpAssetApplier`](../app/src/main/java/com/hereliesaz/guillotine/ui/AzpAssetApplier.kt)
re-reads and integrity-verifies the `.azp`, writes the asset into the clip's real render filters
(`shaderPath` / `lutPath`), and — for a shader — seeds `shaderParams` from the schema defaults. From then
on the schema's sliders drive the clip's live `shaderParams`, so a move changes the picture in **preview
and export**. No sandbox needed: the asset is declarative data the host already knows how to render.

**`code` extensions.** A `code`-kind package (a JS/WASM filter) needs a sandbox to run. The capability
boundary is live — [`AzpCodeRuntime.missingGrants`](../shared/src/main/kotlin/com/hereliesaz/guillotine/azphalt/AzpCodeRuntime.kt)
enforces least-privilege grants — but the shipped runtime is `Unavailable`: it **refuses to execute
rather than fake a result** until a WASM engine is bundled (azphalt jobs #2–#5). Non-shader/LUT panels
therefore store their params as config, clearly labeled, ready for that runtime.

## What's next

- The **WASM sandbox** (jobs #2–#5) so `code` extensions run behind `AzpCodeRuntime` under granted
  capabilities — the last step to a full conforming host.
- A per-section collapse toggle (the panel already collapses as a whole).
- Clip-type-aware `appliesTo` once azphalt `mediaDomains` are read on install.
