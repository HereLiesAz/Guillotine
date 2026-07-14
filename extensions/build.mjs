// Build every azphalt package under src/ into a signed-container-ready `.azp` in dist/.
//
// Each src/<dir>/ holds a `manifest.json` (without the `files` map — writeAzp computes it) at its root
// and its payload laid out at the exact in-package paths the manifest references (code/main.js,
// ui/panel.json, assets/*.glsl, …). We hand those to `@azphalt/azp` writeAzp, which digests the
// payload, adds the LICENSE, and zips a reproducible `.azp`. With `--verify` we also run verifyAzp on
// each result so a broken package fails the build.
//
// The extensions themselves are authored against `@azphalt/azdk` (the code modules import it; the host
// provides it at runtime) — see src/*/code/main.js.
//
// Packaging normally uses `@azphalt/azp` `writeAzp`, but the published `@azphalt/azp@0.1.0` has a broken
// dependency on the renamed `@azphalt/sdk@0.1.0` (now `@azphalt/azdk`) and won't install. So we inline a
// spec-faithful `writeAzp`/`verifyAzp` over `fflate` here — byte-identical output (same EPOCH timestamp,
// entry sort, and 2-space manifest JSON as `@azphalt/azp`). Swap back to `import { writeAzp, verifyAzp }
// from "@azphalt/azp"` once it's republished (tracked upstream).
import { zipSync, unzipSync, strToU8, strFromU8 } from "fflate";
import { createHash } from "node:crypto";
import { readFileSync, readdirSync, statSync, mkdirSync, writeFileSync, rmSync, existsSync } from "node:fs";
import { join, relative, dirname } from "node:path";
import { fileURLToPath } from "node:url";

// Fixed archive timestamp so identical input yields identical bytes (matches @azphalt/azp EPOCH).
const EPOCH = new Date(2000, 0, 1);
const digest = (bytes) => "sha256-" + createHash("sha256").update(bytes).digest("hex");

function writeAzp({ manifest, payload, license }) {
  const digested = { ...payload, LICENSE: strToU8(license) };
  const files = {};
  for (const [path, bytes] of Object.entries(digested)) files[path] = digest(bytes);
  const full = { ...manifest, files };
  const entries = { ...digested, "manifest.json": strToU8(JSON.stringify(full, null, 2) + "\n") };
  const sorted = {};
  for (const k of Object.keys(entries).sort()) sorted[k] = entries[k];
  return { azp: zipSync(sorted, { mtime: EPOCH }), manifest: full };
}

function verifyAzp(bytes) {
  const errors = [];
  let files;
  try {
    files = unzipSync(bytes);
  } catch (e) {
    return { ok: false, errors: [e.message], signed: false };
  }
  const manifestRaw = files["manifest.json"];
  if (!manifestRaw) return { ok: false, errors: ["azp: manifest.json is missing"], signed: false };
  let manifest;
  try {
    manifest = JSON.parse(strFromU8(manifestRaw));
  } catch (e) {
    return { ok: false, errors: [`azp: manifest.json is not valid JSON: ${e.message}`], signed: false };
  }
  const payload = {};
  for (const [p, data] of Object.entries(files)) if (p !== "manifest.json") payload[p] = data;
  for (const p of Object.keys(payload)) {
    if (p.startsWith("/") || p.split("/").includes("..")) errors.push(`unsafe path: ${p}`);
  }
  if (!manifest.files) return { ok: false, errors: [...errors, "manifest.files is missing"], signed: false };
  for (const [p, want] of Object.entries(manifest.files)) {
    const data = payload[p];
    if (!data) errors.push(`missing payload for ${p}`);
    else if (digest(data) !== want) errors.push(`digest mismatch: ${p}`);
  }
  for (const p of Object.keys(payload)) {
    if (p !== "signature.json" && !Object.hasOwn(manifest.files, p)) errors.push(`unlisted payload: ${p}`);
  }
  return { ok: errors.length === 0, errors, signed: !!payload["signature.json"] };
}

const ROOT = dirname(fileURLToPath(import.meta.url));
const SRC = join(ROOT, "src");
const DIST = join(ROOT, "dist");
const VERIFY = process.argv.includes("--verify");

const LICENSE = `MIT License

Copyright (c) 2026 HereLiesAz

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
`;

/** Collect every file under `dir` keyed by its forward-slash path relative to `dir`. */
function collect(dir, base = dir, out = {}) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) collect(p, base, out);
    else out[relative(base, p).split("\\").join("/")] = readFileSync(p);
  }
  return out;
}

rmSync(DIST, { recursive: true, force: true });
mkdirSync(DIST, { recursive: true });

const dirs = readdirSync(SRC).filter((n) => statSync(join(SRC, n)).isDirectory()).sort();
let failed = 0;

for (const dirName of dirs) {
  const extDir = join(SRC, dirName);
  const manifestPath = join(extDir, "manifest.json");
  if (!existsSync(manifestPath)) continue;

  const manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
  const all = collect(extDir);
  delete all["manifest.json"];
  const payload = {};
  for (const [k, v] of Object.entries(all)) payload[k] = new Uint8Array(v);

  const { azp } = writeAzp({ manifest, payload, license: LICENSE });
  const outPath = join(DIST, `${manifest.id}.azp`);
  writeFileSync(outPath, azp);

  let note = "";
  if (VERIFY) {
    const v = verifyAzp(azp);
    if (!v.ok) {
      failed++;
      note = `  ✗ INVALID: ${v.errors.join("; ")}`;
    } else {
      note = "  ✓ verified";
    }
  }
  console.log(`${manifest.id.padEnd(46)} ${String(azp.length).padStart(6)} bytes${note}`);
}

console.log(`\n${dirs.length} package(s) → ${relative(ROOT, DIST)}/`);
if (failed) {
  console.error(`${failed} package(s) failed verification`);
  process.exit(1);
}
