# Audit remediation

Source: two glee audits (app/, and everything else). Branch `claude/funny-lamport-u1jads`.

## Pending decisions
- [x] Ads: no ads; remove the Ad-Free IAP
- [x] FFmpeg: desktop-only; hide it on Android

## Security
- [ ] FfmpegFilterGuard: reject positional options for filters with file-ish options (deshake, selectivecolor, …); add tests
- [ ] crash-relay worker: always fence the body, escape backticks, ignore client-supplied labels
- [ ] MCP token clipboard: mark sensitive (EXTRA_IS_SENSITIVE)

## Data loss
- [ ] SettingsBackup.import: only replace userTools when the key is present
- [ ] UserToolStore / LearnedConceptStore: on parse failure, move the file aside; never save over it
- [ ] NleScreen autosave: hold ON_PAUSE save until restore finishes
- [ ] SecurePrefs: wipe only on key-invalidation exceptions; rethrow transient ones
- [ ] ModelDownloadManager: clean up a failed extraction; write the marker last; 416 → treat as complete
- [ ] Desktop AtomicFiles: keep more than one .bak generation

## Broken features
- [ ] Exporter: WRITE_EXTERNAL_STORAGE maxSdk=28, plus a runtime request on API < 29
- [ ] FFmpeg on Android (per decision)
- [ ] Ad-Free IAP (per decision); purchaseAdFree must not fail silently
- [ ] MCP: per-flavor port; surface bind failure in Settings
- [ ] hf-space: make sdk_version match gradio pin

## Privacy / backup
- [ ] backup_rules + data_extraction_rules: exclude models, extensions, crashes, learned_concepts
- [ ] Reconcile PRIVACY.md, docs/privacy.html, README, fastlane: ads, cloud vision, HF Space, Leonardo, desktop keystore
- [ ] VoiceCapture comment

## Resources
- [ ] BillingManager.endConnection on dispose
- [ ] VoiceCapture: preallocated FloatArray chunks, max duration
- [ ] LiteRtLmTextEngine: release on onTrimMemory / assistant disabled
- [ ] MediaImport: release stale persisted grants

## Build / CI
- [ ] proguard: keep rules for Vosk/JNA, sherpa-onnx, litert-lm, NanoHTTPD
- [ ] Netty: single pin
- [ ] publish_play.py: "already been used" → fail unless versionCode verified live; fix docstring
- [ ] merged-build.yml: drop dead release job, dedupe triggers; lfs: true on checkout
- [ ] release-aab.yml: add concurrency block or fix comment
- [ ] dependabot: npm for tools/*
- [ ] desktop rpmLicenseType → AGPL-3.0
- [ ] Release pipeline red (central repo, logs not visible): needs your access

## Docs rot
- [ ] PLUGINS.md / TOOLS.md: loopback, desktop port 7865
- [ ] libs.versions.toml comments, DesktopMcpTools "stubs", BUILDING.md desktop section

## Tests
- [ ] McpServer auth, McpDispatcher, DesktopKeyStore, guard positional cases
- [ ] AssetPathGuardTest: skip with assumption instead of silent pass
