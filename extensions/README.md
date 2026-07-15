# Guillotine extensions & assets

azphalt extensions and assets that expand [Guillotine](https://github.com/HereLiesAz/Guillotine),
authored with the **[`@azphalt/azdk`](https://www.npmjs.com/package/@azphalt/azdk)** SDK and packaged
as portable `.azp` files. Each installs into any conforming azphalt host (Guillotine included) via
**Settings → Install AI model / extension (.azp)**.

Everything here is **MIT** licensed.

## What's in the box

### Code extensions — new per-frame processing (`kind: code`, `runtime: js`)

Written against `@azphalt/azdk` (`defineFilter`); the host injects only the granted capabilities
(`bitmap`, `params`, `canvas`) and provides the SDK at runtime.

| Package | What it does |
| --- | --- |
| `com.hereliesaz.guillotine.duotone` | **Duotone** — map luminance to a shadow→highlight colour ramp. |
| `com.hereliesaz.guillotine.chromakey` | **Chroma Key** — green/blue-screen keyer to transparency, with a soft edge. |
| `com.hereliesaz.guillotine.crt` | **CRT / Retro** — scanlines, RGB fringing, and a vignette. |

### Open-source transitions & filters (`kind: asset`)

Five reusable, MIT-licensed assets — three transitions and two shader filters.

| Package | Type / format | What it does |
| --- | --- | --- |
| `…transition.crossfade` | `transition` · gl-transition | Linear dissolve. |
| `…transition.wipe` | `transition` · gl-transition | Directional wipe with a soft edge. |
| `…transition.iris` | `transition` · gl-transition | Circle/iris reveal from the centre. |
| `…filter.film-grain` | `shader` · ISF | Animated film grain + vignette. |
| `…filter.chromatic-aberration` | `shader` · ISF | Edge-weighted RGB channel split. |

## Build

```sh
cd extensions
npm install
npm run build      # emits dist/*.azp
npm run verify     # build + integrity-check each package
```

The pre-built `.azp` files are committed under [`dist/`](dist/), so you can install them directly.

## A note on the packager

Packaging normally uses `@azphalt/azp` `writeAzp`. The published `@azphalt/azp@0.1.0` currently has a
broken dependency on the renamed `@azphalt/sdk@0.1.0` (now `@azphalt/azdk`) and won't `npm install`, so
`build.mjs` inlines a spec-faithful `writeAzp`/`verifyAzp` over `fflate` — **byte-identical** output
(same fixed archive timestamp, entry ordering, and 2-space manifest JSON), cross-checked against the
reference `@azphalt/azp` `verifyAzp`. Swap back to the published package once it's republished.
