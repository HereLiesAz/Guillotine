# Social platform integration — registration guide

How to register Guillotine with TikTok, Meta (Instagram + Facebook), and Google/YouTube so the app
can hand an exported video to each platform, and (later) publish directly.

Everything here was verified against official documentation in July 2026. Items the docs genuinely
do not answer are marked **UNVERIFIED** — check those against the live portal rather than trusting
this file.

---

## 1. What you actually get, and what it costs

The work splits cleanly into two tiers. **They are not the same project.**

### Tier 1 — hand off to the platform's own composer

The user finishes the post (captions, music, effects) inside TikTok/Instagram/Facebook. Because the
licensed music is added *there*, this sidesteps the music-licensing problem entirely.

| Platform | Mechanism | Review needed? | Backend? |
|---|---|---|---|
| TikTok | Share Kit (OpenSDK) | No — client key + fingerprints only | No |
| Instagram Reels / Stories | Bare Android `Intent` | No — but app must be **Live** | No |
| Facebook Reels / Stories | Bare Android `Intent` | No — but app must be **Live** | No |
| YouTube | **Does not exist** — see §5 | — | — |

**Realistic timeline: days.** No review queue for any of it.

### Tier 2 — direct publish from inside Guillotine

| Platform | Reality |
|---|---|
| TikTok | Needs a **backend** (client secret must never ship in an open-source APK). Unaudited apps can only post to *private* accounts, capped at 5 users/24h — so **upload-to-drafts is the only usable unaudited mode**. |
| Instagram | Works, including direct upload of an on-device file via resumable upload. Requires App Review **per permission** + Business Verification. Account must be Business/Creator. |
| Facebook | **Pages only.** Publishing to a personal profile has been impossible since 2018 (`publish_actions` removed) — there is no permission for it. |
| YouTube | Works, but **every upload is forced private** until the project passes YouTube's compliance audit. |

**Realistic timeline: weeks to months**, dominated by review round-trips.

> **Note:** the export screen already fires a generic `ACTION_SEND` chooser (`NleScreen.kt:166`), so
> users can *already* share to all four platforms today. Tier 1's value is attribution, deep
> features (e.g. TikTok green-screen), and not making the user hunt through a share sheet — not
> "enables sharing".

---

## 2. Certificate fingerprints — do this once, use everywhere

Every platform identifies the app by its **signing certificate**, and Guillotine has **up to four**:

| # | Certificate | Which builds carry it |
|---|---|---|
| 1 | **Play App Signing key** (Google's) | Everything installed from Play — the AAB is re-signed by Google |
| 2 | **Upload / release key** (`KEYSTORE_RAW`) | The `github`-flavor APK, signed directly |
| 3 | **Debug keystore** | Local dev builds when the release keystore isn't present |
| 4 | *(same as #2)* | Local dev builds *with* the release keystore — `app/build.gradle.kts` signs debug with the release config when available |

**This is the #1 cause of "works on my machine, broken in production."** Registering only your upload
key means auth works in debug and on the GitHub APK, then fails for every Play user.

### Getting them

```bash
# Local keys (release + debug), all variants at once:
./gradlew signingReport

# Or a specific keystore:
keytool -list -v -alias <alias> -keystore <path/to/keystore.jks>

# Debug keystore (password: android):
keytool -list -v -alias androiddebugkey -keystore ~/.android/debug.keystore

# Verify what a built artifact is actually signed with:
keytool -printcert -jarfile app-release.apk
```

**The Play App Signing fingerprints** come from the Play Console, *not* your machine:
**Play Console → Release → Setup → App Integrity → App signing key certificate**.
That page shows MD5, SHA-1 and SHA-256 — it is the source for all three platforms.

### Each platform wants a different format

| Platform | Hash | Format |
|---|---|---|
| **TikTok** — "App signature" | MD5 | Colons **stripped**, 32 chars (`75C869FCD56EEB1D0279A93F91BD5E5B`) |
| **TikTok** — "Signing certificate fingerprints" | SHA-256 | Colons **kept** |
| **Meta** — "Key Hashes" | SHA-1 | **base64**, 28 chars ending `=` — *not* colon-hex |
| **Google** — Android OAuth client | SHA-1 | Colon-separated hex, as `keytool` prints it |

Converting a Play Console hex SHA-1 to Meta's base64 form:

```bash
echo "AB:CD:12:...:AA" | tr -d ':\n' | xxd -r -p | openssl base64
```

Or straight from a keystore:

```bash
keytool -exportcert -alias <alias> -keystore <keystore> | openssl sha1 -binary | openssl base64
```

---

## 3. TikTok

Portal: <https://developers.tiktok.com>

> ⚠️ **TikTok has two live doc sets and search favours the obsolete one.** Use only the **v2** pages
> (`…-quickstart-v2`, SDK `com.tiktok.open.sdk:*`). The legacy pages (`TiktokOpenApi`,
> `opensdk-oversea-external`) describe a different API with no PKCE and will waste your time.

### Registration

1. Sign up, then create/join an **organization** (docs call this "highly recommended but not
   required").
2. Profile icon → **Manage apps** → **Connect an app** → select owner → **Confirm**.
3. Fill **Basic Information**: icon (1024×1024, ≤5 MB), name, category, description.
4. **Platforms → Android**: package name `com.hereliesaz.guillotine`, Play Store URL, **App
   signature** (MD5, no colons), **Signing certificate fingerprints** (SHA-256).
5. Add products (below), then submit for review.

**Credentials:** `Client key` and `Client secret`, under **Credentials** on the app page. The client
key is safe in the APK; **the secret is not** — TikTok explicitly says never to put it in an
open-source project.

**Sandbox:** up to 5 per app, 10 target users each, no review needed. Changes take **up to an hour**
to propagate. Note: *sandbox cannot test public Content Posting.*

**Review:** "several days to two weeks", requires 1–5 demo videos (≤50 MB each). The FAQ says mobile
apps must be on the App Store and/or Google Play — **your GitHub-only distribution likely will not
satisfy review on its own.**

### Share Kit — the easy win

```gradle
repositories { maven { url "https://artifact.bytedance.com/repository/AwemeOpenSDK" } }

dependencies {
    implementation 'com.tiktok.open.sdk:tiktok-open-sdk-core:2.2.0'
    implementation 'com.tiktok.open.sdk:tiktok-open-sdk-share:2.2.0'
    implementation 'com.tiktok.open.sdk:tiktok-open-sdk-auth:2.2.0'   // Login Kit only
}
```

- **No OAuth scopes. No backend. No Login Kit prerequisite.** Client key + matching fingerprints.
- Result activity must be `android:exported="true"`.
- Video: 1–360 s, `.mp4`, up to 35 items. Green Screen takes exactly 1.
- **TikTok ships under two package names.** Grant URI permission to *both*, and declare both for
  Android 11+ visibility — TikTok's own sample only handles the first, which silently breaks
  sharing for everyone on the other build:

```xml
<queries>
    <package android:name="com.zhiliaoapp.musically" />
    <package android:name="com.ss.android.ugc.trill" />
</queries>
```

### Login Kit

- Scope: `user.info.basic`. PKCE is mandatory.
- Support **both** `AuthMethod.TikTokApp` and `AuthMethod.ChromeTab` — not every user has TikTok
  installed.
- Redirect URI must be an **App Link** (`https`), meaning `/.well-known/assetlinks.json` on a domain
  you own. That file *does* accept an array of fingerprints, so one file covers all four certs.
- `authCode` + `codeVerifier` go to **your backend** for token exchange. Never on-device.
- ⚠️ The SDK can return `isSuccess: true, errorCode: 0` with **no auth code**. Always null-check
  `authCode` and inspect `authError` — do not branch on `isSuccess` alone.

### Content Posting API

| | Direct Post | Upload to inbox |
|---|---|---|
| Scope | `video.publish` | `video.upload` |
| Result | Straight to profile | Lands in the user's **drafts** |
| Unaudited | **Call is rejected outright if the account is public**; 5 users/24h; `SELF_ONLY` | Usable |

**Ship inbox/drafts first.** Direct Post is effectively unusable before the audit, and drafts suit a
video editor's workflow anyway. Use `FILE_UPLOAD` (not `PULL_FROM_URL`) for on-device content — the
guidelines require it and it avoids URL-ownership verification.

The audit rubric is the [content sharing guidelines](https://developers.tiktok.com/doc/content-sharing-guidelines)
— a demanding UX checklist: creator nickname shown, privacy selector with **no default** matching
`privacy_level_options` from the API, greyed-out Duet/Stitch toggles, commercial-content disclosure,
a verbatim Music Usage Confirmation line, and **no watermarks or burned-in branding**.

> ✅ Guillotine passes the watermark rule today — exports carry no burned-in branding. The only
> "Guillotine" string is the MediaStore folder name.

### UNVERIFIED — check these live
- **Whether one TikTok app accepts multiple fingerprints.** Undocumented everywhere. If it doesn't,
  you need **two app registrations** (Play-signed and GitHub-signed), selected per flavor via
  `buildConfigField` — doubling the review burden. *Highest-priority thing to confirm.*
- Case sensitivity of the MD5 field (docs show both cases).
- Whether Share Kit alone needs full review.
- Audit duration (the application URL requires auth; no published SLA).
- Share Kit docs state a **max frame size of 1100 px**, which would reject 1080p. Reads like a doc
  error or an images-only rule — verify before relying on it.

---

## 4. Meta — Instagram + Facebook

Portal: <https://developers.facebook.com>

### Registration

Create at `/apps/creation/`: app details → **use cases** → business portfolio → requirements.

> ⚠️ **Use cases cannot be removed after creation**, and `Manage everything on your Page` is
> **incompatible** with `Authenticate and request data from users with Facebook Login`. If you want
> both Facebook Login and Page publishing you may need **two Meta apps**. Confirm in the dashboard
> before committing — this is the biggest structural decision here. **UNVERIFIED.**

**Credentials** (Settings → Basic, except where noted):

| Credential | Ship in APK? |
|---|---|
| **App ID** | Yes — this is what the sharing intents need |
| **App Secret** | **Never** |
| **Client Token** (Settings → Advanced → Security) | Yes — client-safe substitute for the secret |

**Android platform setup:** Settings → Basic → **+ Platform → Android**. Package name
`com.hereliesaz.guillotine`, default activity class, and **Key Hashes** — which explicitly
**supports multiple entries** ("You can add multiple key hashes if you develop with multiple
machines"). Add all four certs. ⚠️ You must click **Save Changes**; the field looks saved when it
isn't.

### Tier 1 — the sharing intents (no review!)

Confirmed: these need **no permission, no App Review, and no Business Verification** — nothing is
requested that could be reviewed. But the Reels docs do require the app be **Live**, which means:

- Display Name, Contact Email, **Terms of Service URL**, App Icon (1024×1024), Category, App Purpose
- **Data Use Checkup** completed

…then flip to Live. Days, not weeks.

| Target | Action | Package | App-ID extra key |
|---|---|---|---|
| Instagram Reels | `com.instagram.share.ADD_TO_REEL` | `com.instagram.android` | `com.instagram.platform.extra.APPLICATION_ID` |
| Instagram Stories | `com.instagram.share.ADD_TO_STORY` | *(implicit)* | ⚠️ **`source_application`** |
| Facebook Reels | `com.facebook.reels.SHARE_TO_REEL` | *(implicit)* | `com.facebook.platform.extra.APPLICATION_ID` |
| Facebook Stories | `com.facebook.stories.ADD_TO_STORY` | `com.facebook.katana` | `com.facebook.platform.extra.APPLICATION_ID` |

⚠️ **Instagram Stories uses the bare `source_application` key**, not the namespaced one. Single most
common integration bug.

**No Facebook SDK needed** for any of these — they're plain `android.content.Intent`.

**`<queries>` is mandatory and Meta never mentions it.** Guillotine is `targetSdk 37`, and there is
currently **no `<queries>` block** in `app/src/main/AndroidManifest.xml`, so Meta's sample code
(`resolveActivity`) will return null and silently do nothing:

```xml
<queries>
    <package android:name="com.instagram.android" />
    <package android:name="com.facebook.katana" />
    <package android:name="com.zhiliaoapp.musically" />
    <package android:name="com.ss.android.ugc.trill" />
</queries>
```

**Media:** Reels 3–60 s, up to 1080p, device-fullscreen or smaller. IG Stories background min
720×1280, 9:16, ≤20 s.

> ✅ **No FileProvider needed.** `Exporter.export()` already returns a **MediaStore `content://`
> URI** (saved to `Movies/Guillotine`), which is exactly what these intents want. Meta's docs assume
> a `file://` path and tell you to build a FileProvider — that step does not apply here.

### Tier 2 — Instagram Content Publishing

Two paths; **Instagram Login** avoids needing a linked Facebook Page:

| | Instagram Login | Facebook Login |
|---|---|---|
| Host | `graph.instagram.com` | `graph.facebook.com` |
| Facebook Page required | **No** | Yes |
| Scopes | `instagram_business_basic`, `instagram_business_content_publish` | `instagram_basic`, `instagram_content_publish`, `pages_read_engagement`, `pages_show_list` |

Both require an Instagram **professional** (Business or Creator) account — consumer accounts cannot
be used at all.

**Direct on-device upload works** via resumable upload: `POST /<IG_USER_ID>/media` with
`upload_type=resumable` → `POST https://rupload.facebook.com/ig-api-upload/…` with the binary →
poll `status_code` until `FINISHED` → `POST /<IG_USER_ID>/media_publish`.
**UNVERIFIED:** whether resumable upload works against `graph.instagram.com` with Instagram-Login
tokens — the docs only show it for the Facebook-Login path.

**Reels specs:** MP4/MOV, H.264/HEVC, ≤1920px horizontal, 23–60 fps, 3 s–15 min, ≤300 MB.

⚠️ **Don't hardcode the publish quota** — the docs contradict themselves (100 vs 50 per 24 h). Query
`GET /<IG_USER_ID>/content_publishing_limit` and gate on `config.quota_total`. Unpublished
containers expire after 24 h.

Legacy scope names (`business_basic` etc.) were deprecated Jan 2025 — only the `instagram_`-prefixed
forms work. Instagram Basic Display API is **dead** (Dec 2024).

### Tier 2 — Facebook video

**Personal profiles are impossible.** `publish_actions` was removed in 2018 and no replacement
permission exists; `POST /me/feed` returns "You can't perform this operation on this endpoint."
`publish_video` is **live-streaming only**, despite the name.

**Pages work:** `pages_show_list`, `pages_read_engagement`, `pages_manage_posts`, plus a Page access
token from a user with the `CREATE_CONTENT` task. Reels: `POST /{page-id}/video_reels`, 3-phase,
3–90 s, 9:16, min 540×960, 30 posts/24 h.

⚠️ Use `graph.facebook.com` — `graph-video.facebook.com` is deprecated (Meta's own sample curl still
uses the dead host).

### Ongoing obligations
- **Data Use Checkup is annual** and blocks Live mode. Tier 1 pulls you into this permanently.
- **90 days of inactivity invalidates all access tokens** — no logins, no API calls, no webhooks. A
  real trap for a low-traffic app.
- **Advanced Access requires Business Verification**, and individual verification was removed in
  2023 — a solo developer must create and verify a business portfolio.

---

## 5. Google / YouTube

> **There is no share-to-composer hand-off.** Verified definitively: YouTube's Android offerings are
> the IFrame Player API (playback) and a *live-stream* deep link. Nothing accepts a recorded file.
> It's the full Data API or nothing. (A generic `ACTION_SEND` will surface YouTube in the system
> share sheet, but that's Android's framework, not a YouTube API — undocumented, no metadata
> control, no result callback. Fine as a stopgap, not a product feature.)

### The real blocker isn't quota

Two of the scary assumptions turned out **wrong**, and a third is worse than expected:

| Concern | Reality |
|---|---|
| ~~1600 units/upload → ~6 uploads/day~~ | **Obsolete.** Since Dec 2025 / Jun 2026, `videos.insert` has its own bucket: **100 uploads/day at 1 unit each.** |
| ~~Restricted scope → CASA security assessment~~ | **No.** `youtube.upload` is *sensitive*, not restricted. No pentest, no fee. ~10 business days. |
| **YouTube compliance audit** | **The actual gate.** Until the project passes, *every* video uploaded via the API is **forced private**. The feature is dead on arrival without it — not degraded, dead. |

### Setup

1. Google Cloud project → enable **YouTube Data API v3**. Note the **project number** (the audit
   form needs it).
2. **OAuth consent screen, user type External.** Requires: app name, logo, support email, **homepage
   and privacy policy on the same domain you own and have verified in Google Search Console**, ToS
   URL, developer contact.
3. **Three Android OAuth clients**, all package `com.hereliesaz.guillotine`, one per SHA-1:
   Play App Signing cert, your release key, your debug key. Multiple clients may share a package
   name as long as each SHA-1 differs.
4. Scope: **`youtube.upload` only** — it's the narrowest option, which is the easiest verification
   justification. Add `youtube.readonly` only if you want to show which channel is targeted.

**Skip the Web OAuth client** unless you want refresh tokens; the on-device
`Identity.getAuthorizationClient` flow returns a 1-hour token and needs only the Android client.
Google explicitly discourages storing refresh tokens on-device.

⚠️ **The 100-user unverified cap is lifetime and cannot be reset.** Don't burn it during testing.

### Shorts

No Shorts API and no `isShort` field. Classification is automatic: **square or vertical aspect ratio,
≤3 minutes, ≤1080p**. Target 1080×1920. `#Shorts` in the title is *not* required — and per Developer
Policy III.C.3 you may not silently append it to the user's text without disclosure and control.

Also note `status.containsSyntheticMedia` (added Oct 2024) — an editor with AI features may be
obliged to set it.

---

## 6. Recommended sequencing

**Phase 1 — ship Tier 1 (days).**
TikTok Share Kit + the four Meta intents. One TikTok app (client key + fingerprints), one Meta app
(App ID + key hashes + Live mode + Data Use Checkup). Add the combined `<queries>` block. No
backend, no review queue.

**Phase 2 — decide on a backend.**
TikTok Login/publish *requires* one (the client secret can't ship in an open-source APK). You have
precedent — the crash-relay Cloudflare Worker. Without a backend, Tier 2 is TikTok-less.

**Phase 3 — Tier 2, per platform, slowest first.**
YouTube's compliance audit gates everything and wants a *working demo account*, so the feature must
already function (uploading privates) before you can submit. Start that paperwork earliest even
though it ships last. Instagram needs App Review per permission plus Business Verification. TikTok
drafts-mode is usable unaudited; Direct Post needs the audit.

Ship Tier 2 behind a flag with an honest in-app notice while approvals are pending — "uploads
publish privately until YouTube approves this app" is far better than a silent surprise.

---

## 7. Everything marked UNVERIFIED

Confirm these against the live portals before relying on them:

- **TikTok:** multiple fingerprints per app; MD5 case sensitivity; whether Share Kit alone needs
  review; audit duration; the 1100 px frame-size claim; whether any fee applies.
- **Meta:** whether the Page/Login use-case incompatibility really forces two apps; Business
  Verification documents, fee, and timeline; whether Privacy Policy URL hard-blocks Live mode;
  whether IG resumable upload works on `graph.instagram.com`; the true publish quota.
- **Google:** the sensitive/restricted label Cloud Console actually shows for `youtube.upload` and
  `youtube.readonly` (Google no longer publishes a per-scope table); realistic audit turnaround.
