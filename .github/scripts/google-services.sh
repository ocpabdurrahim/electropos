#!/usr/bin/env bash
# Writes app/google-services.json: the real project's, from the
# FIREBASE_GOOGLE_SERVICES secret (base64), or the demo one, which only works
# with the local Firebase emulators. Exports FIREBASE_PROJECT for later steps.
set -euo pipefail

if [ -n "${FIREBASE_GOOGLE_SERVICES:-}" ]; then
  echo "$FIREBASE_GOOGLE_SERVICES" | base64 --decode > app/google-services.json
  echo "Using the real Firebase project config."
else
  cp app/google-services.demo.json app/google-services.json
  echo "No FIREBASE_GOOGLE_SERVICES secret: using the demo config (sign-in works only against the emulators)."
fi

project=$(python3 -c 'import json; print(json.load(open("app/google-services.json"))["project_info"]["project_id"])')
echo "FIREBASE_PROJECT=$project" >> "$GITHUB_ENV"
echo "Firebase project: $project"
