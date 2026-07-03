#!/usr/bin/env python3
"""Publish an AAB to Google Play across four tracks.

Google's Play Publishing API groups writes into "Edits" — you insert an edit,
add uploads and track releases to it, then commit once. Committing an edit is
atomic; uncommitted edits are throwaway.

Rollout policy (fixed):
  - internal      → completed  (published to internal testers immediately)
  - alpha         → draft      (closed testing, staged; promote by hand)
  - beta          → draft      (open testing, staged; promote by hand)
  - production    → draft      (staged; promote by hand)

Structure: one bootstrap edit that uploads the AAB AND releases to internal
(committed as the atomic critical path), then one small edit per remaining
track that just references the already-uploaded versionCode. If a later track
fails (e.g. Play returns FAILED_PRECONDITION because the track was never
initialised in the Play Console), internal is still live and the failure is
isolated to that track. The script exits 2 in that case so the workflow can:
  - persist the versionCode bump (it was consumed by Play; must not be reused);
  - still mark the job failed so the missing track is visible.

Exit codes: 0 = full success, 2 = internal published but ≥1 other track failed,
1 = upload or internal release failed (nothing published; versionCode NOT
consumed by Play).

Why we hit the REST endpoints directly through `requests`
(via `google.auth.transport.requests.AuthorizedSession`) rather than
`google-api-python-client`: the client library uses httplib2, which raises
`RedirectMissingLocation` on Play's 308 "Resume Incomplete" chunk acks and
never completes a resumable bundle upload. Plain `requests` handles 308.
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

EXIT_OK = 0
EXIT_FAIL = 1
EXIT_PARTIAL = 2


def _raise_for(resp: requests.Response, action: str) -> None:
    if not resp.ok:
        raise RuntimeError(f"{action} failed: HTTP {resp.status_code} {resp.text[:400]}")


def insert_edit(session: AuthorizedSession, package: str) -> str:
    r = session.post(f"{API}/{package}/edits", json={}, timeout=META_TIMEOUT_S)
    _raise_for(r, "Insert edit")
    return r.json()["id"]


def commit_edit(session: AuthorizedSession, package: str, edit_id: str) -> None:
    r = session.post(f"{API}/{package}/edits/{edit_id}:commit", timeout=META_TIMEOUT_S)
    _raise_for(r, f"Commit edit {edit_id}")


def delete_edit(session: AuthorizedSession, package: str, edit_id: str) -> None:
    try:
        session.delete(f"{API}/{package}/edits/{edit_id}", timeout=META_TIMEOUT_S)
    except requests.RequestException:
        pass


def update_track(session: AuthorizedSession, package: str, edit_id: str, track: str, status: str, version_code: int) -> None:
    body = {"releases": [{"status": status, "versionCodes": [str(version_code)]}]}
    r = session.put(
        f"{API}/{package}/edits/{edit_id}/tracks/{track}",
        json=body,
        timeout=META_TIMEOUT_S,
    )
    _raise_for(r, f"Track {track} update")


def upload_bundle(session: AuthorizedSession, package: str, edit_id: str, aab: str) -> int:
    file_size = os.path.getsize(aab)
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


def publish_internal(session: AuthorizedSession, package: str, aab: str) -> int:
    """Bootstrap edit: upload bundle + release to internal (completed). Commit."""
    edit_id = insert_edit(session, package)
    print(f"Opened edit {edit_id} (upload + internal)")
    try:
        version_code = upload_bundle(session, package, edit_id, aab)
        print(f"Uploaded bundle versionCode={version_code}")
        update_track(session, package, edit_id, "internal", "completed", version_code)
        commit_edit(session, package, edit_id)
        print(f"  internal    → completed (versionCode {version_code} LIVE)")
        return version_code
    except Exception:
        delete_edit(session, package, edit_id)
        raise


def stage_track(session: AuthorizedSession, package: str, track: str, status: str, version_code: int) -> None:
    """Small edit: reference the already-uploaded versionCode into another track. Commit."""
    edit_id = insert_edit(session, package)
    try:
        update_track(session, package, edit_id, track, status, version_code)
        commit_edit(session, package, edit_id)
    except Exception:
        delete_edit(session, package, edit_id)
        raise


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--package", required=True, help="applicationId, e.g. com.hereliesaz.guillotine")
    ap.add_argument("--aab", required=True, help="path to the signed AAB")
    ap.add_argument("--sa-json", required=True, help="path to service-account JSON")
    args = ap.parse_args()

    creds = service_account.Credentials.from_service_account_file(args.sa_json, scopes=SCOPES)
    session = AuthorizedSession(creds)

    # Critical path: internal must go live, or the whole thing failed.
    try:
        version_code = publish_internal(session, args.package, args.aab)
    except Exception as e:
        print(f"Play publish failed (internal): {e}", file=sys.stderr)
        return EXIT_FAIL

    # Best-effort staging on the remaining tracks. Each runs in its own edit so
    # a FAILED_PRECONDITION on one (e.g. the track was never initialised in the
    # Play Console) doesn't undo the successful ones.
    stage_plan = [
        ("alpha", "draft"),      # closed testing
        ("beta", "draft"),       # open testing
        ("production", "draft"),
    ]
    failures: list[tuple[str, str]] = []
    for track, status in stage_plan:
        try:
            stage_track(session, args.package, track, status, version_code)
            print(f"  {track:<11} → {status}")
        except Exception as e:
            failures.append((track, str(e)))
            print(f"  {track:<11} → FAILED: {e}", file=sys.stderr)

    if failures:
        names = ", ".join(t for t, _ in failures)
        print(
            f"::warning::Track staging failed: {names}. "
            "Internal WAS published; versionCode is burnt and must not be reused. "
            "The failed tracks typically need to be initialised once in the Play "
            "Console (create a manual release on each) before the API can post to "
            "them; then re-run the workflow.",
            file=sys.stderr,
        )
        return EXIT_PARTIAL

    print(f"All four tracks released. versionCode {version_code}.")
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())
