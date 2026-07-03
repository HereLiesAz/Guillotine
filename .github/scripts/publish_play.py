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
  - beta          → draft      (open testing, staged; promote by hand)
  - production    → draft      (staged; promote by hand)

The alpha/beta/production tracks must already exist in the Play Console — the
API surfaces "track not found" as a 404 if they don't. Create each one manually
once (Play Console → Testing → Closed testing / Open testing / Production →
Create a new release) before enabling this script.
"""
from __future__ import annotations

import argparse
import sys

from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError
from googleapiclient.http import MediaFileUpload

SCOPES = ["https://www.googleapis.com/auth/androidpublisher"]

TRACK_PLAN: list[tuple[str, str]] = [
    ("internal", "completed"),
    ("alpha", "draft"),
    ("beta", "draft"),
    ("production", "draft"),
]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--package", required=True, help="applicationId, e.g. com.hereliesaz.guillotine")
    ap.add_argument("--aab", required=True, help="path to the signed AAB")
    ap.add_argument("--sa-json", required=True, help="path to service-account JSON")
    args = ap.parse_args()

    creds = service_account.Credentials.from_service_account_file(args.sa_json, scopes=SCOPES)
    svc = build("androidpublisher", "v3", credentials=creds, cache_discovery=False)
    edits = svc.edits()

    edit = edits.insert(packageName=args.package, body={}).execute()
    edit_id = edit["id"]
    print(f"Opened edit {edit_id}")

    try:
        bundle = edits.bundles().upload(
            packageName=args.package,
            editId=edit_id,
            media_body=MediaFileUpload(args.aab, mimetype="application/octet-stream", resumable=True),
        ).execute()
        version_code = int(bundle["versionCode"])
        print(f"Uploaded bundle versionCode={version_code}")

        for track, status in TRACK_PLAN:
            body = {
                "releases": [
                    {
                        "status": status,
                        "versionCodes": [str(version_code)],
                    }
                ]
            }
            edits.tracks().update(
                packageName=args.package,
                editId=edit_id,
                track=track,
                body=body,
            ).execute()
            print(f"  {track:<11} → {status}")

        edits.commit(packageName=args.package, editId=edit_id).execute()
        print(f"Committed edit {edit_id} — versionCode {version_code} live on internal, staged on alpha/beta/production.")
        return 0
    except HttpError as e:
        # Best-effort cleanup: an uncommitted edit expires on its own after ~7 days,
        # but deleting it now frees the Edit slot immediately so a rerun isn't blocked
        # by "an existing edit is already in progress".
        try:
            edits.delete(packageName=args.package, editId=edit_id).execute()
            print(f"Rolled back edit {edit_id}", file=sys.stderr)
        except HttpError:
            pass
        print(f"Play API error: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
