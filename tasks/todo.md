# Audit remediation

Source: two glee audits (app/, and everything else). Branch `claude/funny-lamport-u1jads`.

## Pending decisions
- [x] Ads: no ads; remove the Ad-Free IAP
- [x] FFmpeg: desktop-only; hide it on Android

## Security
- [x] FfmpegFilterGuard: reject positional options for filters with file-ish options (deshake, selectivecolor, …); add tests
- [x] crash-relay worker: always fence the body, escape backticks, ignore client-supplied labels
- [x] MCP token clipboard: mark sensitive (EXTRA_IS_SENSITIVE)

## Data loss
- [x] SettingsBackup.import: only replace userTools when the key is present
- [x] UserToolStore / LearnedConceptStore: on parse failure, move the file aside; never save over it
- [x] NleScreen autosave: hold ON_PAUSE save until restore finishes
- [x] SecurePrefs: wipe only on key-invalidation exceptions; rethrow transient ones
- [x] ModelDownloadManager: clean up a failed extraction; write the marker last; 416 → treat as complete
- [x] Desktop AtomicFiles: keep more than one .bak generation

## Broken features
- [x] Exporter: WRITE_EXTERNAL_STORAGE maxSdk=28, plus a runtime request on API < 29
- [x] FFmpeg on Android (per decision)
- [x] Ad-Free IAP (per decision); purchaseAdFree must not fail silently
- [x] MCP: per-flavor port; surface bind failure in Settings
- [x] hf-space: make sdk_version match gradio pin

## Privacy / backup
- [x] backup_rules + data_extraction_rules: exclude models, extensions, crashes, learned_concepts
- [x] Reconcile PRIVACY.md, docs/privacy.html, README, fastlane: ads, cloud vision, HF Space, Leonardo, desktop keystore
- [x] VoiceCapture comment

## Resources
- [x] BillingManager.endConnection on dispose
- [x] VoiceCapture: preallocated FloatArray chunks, max duration
- [x] LiteRtLmTextEngine: release on onTrimMemory / assistant disabled
- [x] MediaImport: release stale persisted grants

## Build / CI
- [x] proguard: keep rules for Vosk/JNA, sherpa-onnx, litert-lm, NanoHTTPD
- [x] Netty: single pin
- [x] publish_play.py: "already been used" → fail unless versionCode verified live; fix docstring
- [x] merged-build.yml: drop dead release job, dedupe triggers; lfs: true on checkout
- [x] release-aab.yml: add concurrency block or fix comment
- [x] dependabot: npm for tools/*
- [x] desktop rpmLicenseType → AGPL-3.0
- [ ] Release pipeline red (central repo, logs not visible): needs your access

## Docs rot
- [x] PLUGINS.md / TOOLS.md: loopback, desktop port 7865
- [x] libs.versions.toml comments, DesktopMcpTools "stubs", BUILDING.md desktop section

## Tests
- [x] McpServer auth, McpDispatcher, DesktopKeyStore, guard positional cases
- [x] AssetPathGuardTest: skip with assumption instead of silent pass
