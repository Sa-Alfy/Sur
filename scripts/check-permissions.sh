#!/usr/bin/env bash
# Fails if the merged release manifest requests a permission that is not in
# scripts/permissions-allowlist.txt. Unused allowlist entries only warn.
set -euo pipefail
cd "$(dirname "$0")/.."

MANIFEST="${1:-app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml}"
ALLOWLIST="scripts/permissions-allowlist.txt"

[ -f "$MANIFEST" ] || { echo "ERROR: merged manifest not found: $MANIFEST (run ./gradlew :app:processReleaseMainManifest)"; exit 2; }
[ -f "$ALLOWLIST" ] || { echo "ERROR: allowlist not found: $ALLOWLIST"; exit 2; }

# Tags can wrap over several lines, so flatten the file first.
found=$(tr '\r\n\t' '   ' < "$MANIFEST" \
  | grep -oE '<uses-permission(-sdk-23)?[^>]*>' \
  | grep -oE 'android:name="[^"]*"' \
  | sed -e 's/^android:name="//' -e 's/"$//' | sort -u)
allowed=$(sed -e 's/\r$//' -e 's/#.*//' -e 's/[[:space:]]//g' "$ALLOWLIST" | grep -v '^$' | sort -u)

[ -n "$found" ] || { echo "ERROR: no permissions parsed from $MANIFEST"; exit 2; }

bad=$(comm -23 <(echo "$found") <(echo "$allowed"))
unused=$(comm -13 <(echo "$found") <(echo "$allowed"))

if [ -n "$unused" ]; then
  echo "WARNING: allowlist entries no longer used in the release manifest:"
  echo "$unused" | sed 's/^/  /'
fi
if [ -n "$bad" ]; then
  echo "ERROR: permissions in the release manifest that are not in $ALLOWLIST:"
  echo "$bad" | sed 's/^/  /'
  echo "If intentional, add them to the allowlist in this PR and update the README permissions list."
  exit 1
fi
echo "Permission guard OK ($(echo "$found" | wc -l | tr -d ' ') permissions, all allowlisted)."
