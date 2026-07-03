#!/usr/bin/env python3
"""Publish an AAB to Google Play across four tracks in ONE Edit.

Google's Play Publishing API groups all writes into an "Edit" — you insert an
Edit, add uploads and track releases to it, then commit once. Everything in the
commit becomes visible together; nothing does if any step fails. That property
is why this script exists: `r0adkll/upload-google-play` commits its own Edit per
call, so releasing the same bundle to four tracks would need four separate
uploads — but Play rejects re-uploading the same versionCode. One Edit is the
only way to hand one bundle to four tracks at once.

Rollout policy (fixed):
  - internal      → completed  (published to internal testers immediately)
  - alpha         → draft      (closed testing, staged; promote by hand)
  - beta         → draft      (open testing, staged; promote by hand)
  - production    → draft      (staged; promote by hand)

The alpha/beta/production tracks must already exist in the Play Console — the
API surfaces "track not found" as a 404 if they don't. Create each one manually
once (Play Console → Testing → Closed testing / Open testing / Production →
Create a new release) before enabling this script.

We talk to the REST endpoints directly through `requests` (via
`google.auth.transport.requests.AuthorizedSession`) rather than
`google-api-python-client`. The client library uses httplib2, which raises
`RedirectMissingLocation` on Play's 308 Resume-Incomplete responses during a
resumable bundle upload — the upload restarts every chunk and never completes.
Plain `requests` handles 308 as expected.
"""
from __future__ import annotations

import argparse
import os
import sys
import time

import requests
from google.auth.transport.requests import AuthorizedSession
from google.oauth2 import service_account

SCOPES = ["https://www.googleapis.com/auth/androidpublisher"]

API = "https://www.googleapis.com/androidpublisher/v3/applications"
UPLOAD_API = "https://www.googleapis.com/upload/androidpublisher/v3/applications"

# The signed AAB is ~200MB. Give each chunk PUT plenty of headroom for a slow leg;
# 10 minutes per chunk is generous but harmless — a healthy chunk finishes in seconds.
CHUNK_SIZE = 8 * 1024 * 1024  # 8 MiB
CHUNK_TIMEOUT_S = 600
META_TIMEOUT_S = 60
# Retry a transient chunk failure a few times before giving up — Google can drop a chunk
# under load and the resumable protocol lets us re-PUT the same range verbatim.
CHUNK_RETRIES = 5

TRACK_PLAN: list[tuple[str, str]] = [
    ("internal", "completed"),
    ("alpha", "draft"),
    ("beta", "draft"),
    ("production", "draft"),
]


def _raise_for(resp: requests.Response, action: str) -> None:
    if not resp.ok:
        raise RuntimeError(f"{action} failed: HTTP {resp.status_code} {resp.text[:400]}")


def upload_bundle(session: AuthorizedSession, package: str, edit_id: str, aab: str) -> int:
    file_size = os.path.getsize(aab)
    # Initiate the resumable session — Play answers with a Location header we PUT chunks to.
    init = session.post(
        f"{UPLOAD_API}/{package}/edits/{edit_id}/bundles?uploadType=resumable",
        headers={
            "X-Upload-Content-Type": "application/octet-stream",
            "X-Upload-Content-Length": str(file_size),
        },
        timeout=META_TIMEOUT_S,
    )
    _raise_for(init, "Bundle resumable init")
    upload_url = init.headers.get("Location")
    if not upload_url:
        raise RuntimeError("Bundle resumable init returned no Location header.")

    offset = 0
    with open(aab, "rb") as f:
        while offset < file_size:
            chunk = f.read(CHUNK_SIZE)
            end = offset + len(chunk) - 1
            headers = {"Content-Range": f"bytes {offset}-{end}/{file_size}"}
            attempt = 0
            while True:
                attempt += 1
                try:
                    resp = session.put(upload_url, data=chunk, headers=headers, timeout=CHUNK_TIMEOUT_S)
                except requests.RequestException as e:
                    if attempt > CHUNK_RETRIES:
                        raise RuntimeError(f"Chunk {offset}-{end} network error after {attempt} tries: {e}") from e
                    time.sleep(2 ** attempt)
                    continue
                if resp.status_code == 308:
                    # Server ack of chunk; keep uploading. Range header tells us the last-received
                    # byte, so we resync in case Play recorded less than we sent (rare but legal).
                    rng = resp.headers.get("Range")
                    if rng and rng.startswith("bytes=0-"):
                        offset = int(rng.split("-", 1)[1]) + 1
                    else:
                        offset = end + 1
                    break
                if resp.status_code in (200, 201):
                    version_code = int(resp.json()["versionCode"])
                    remaining = file_size - (end + 1)
                    if remaining > 0:
                        raise RuntimeError(
                            f"Play accepted the bundle at offset {end + 1} of {file_size} — "
                            f"{remaining} bytes never uploaded.",
                        )
                    return version_code
                if resp.status_code >= 500 and attempt <= CHUNK_RETRIES:
                    time.sleep(2 ** attempt)
                    continue
                raise RuntimeError(
                    f"Chunk {offset}-{end} failed: HTTP {resp.status_code} {resp.text[:400]}",
                )
    raise RuntimeError("Upload loop exited without a terminal response.")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--package", required=True, help="applicationId, e.g. com.hereliesaz.guillotine")
    ap.add_argument("--aab", required=True, help="path to the signed AAB")
    ap.add_argument("--sa-json", required=True, help="path to service-account JSON")
    args = ap.parse_args()

    creds = service_account.Credentials.from_service_account_file(args.sa_json, scopes=SCOPES)
    session = AuthorizedSession(creds)

    ins = session.post(f"{API}/{args.package}/edits", json={}, timeout=META_TIMEOUT_S)
    _raise_for(ins, "Insert edit")
    edit_id = ins.json()["id"]
    print(f"Opened edit {edit_id}")

    try:
        version_code = upload_bundle(session, args.package, edit_id, args.aab)
        print(f"Uploaded bundle versionCode={version_code}")

        for track, status in TRACK_PLAN:
            body = {"releases": [{"status": status, "versionCodes": [str(version_code)]}]}
            tr = session.put(
                f"{API}/{args.package}/edits/{edit_id}/tracks/{track}",
                json=body,
                timeout=META_TIMEOUT_S,
            )
            _raise_for(tr, f"Track {track} update")
            print(f"  {track:<11} → {status}")

        commit = session.post(f"{API}/{args.package}/edits/{edit_id}:commit", timeout=META_TIMEOUT_S)
        _raise_for(commit, "Commit edit")
        print(
            f"Committed edit {edit_id} — versionCode {version_code} live on internal, "
            "staged on alpha/beta/production.",
        )
        return 0
    except Exception as e:
        # Best-effort cleanup: an uncommitted edit expires on its own after ~7 days,
        # but deleting it now frees the Edit slot immediately so a rerun isn't blocked
        # by "an existing edit is already in progress".
        try:
            session.delete(f"{API}/{args.package}/edits/{edit_id}", timeout=META_TIMEOUT_S)
            print(f"Rolled back edit {edit_id}", file=sys.stderr)
        except requests.RequestException:
            pass
        print(f"Play publish failed: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
