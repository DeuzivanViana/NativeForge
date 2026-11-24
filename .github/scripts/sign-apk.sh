#!/usr/bin/env bash
# Signs the release APK with the RELEASE_* secrets and writes it, with SHA256SUMS, to dist/.
# Usage: sign-apk.sh <unsigned.apk>
# release.yml keeps its own copy of these steps: it may build an old tag that predates this file.
# Env: KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD (empty KEYSTORE_BASE64:
# the APK is copied unsigned, which happens on pull requests from forks).
set -euo pipefail

unsigned=$1
version=$(sed -n 's/.*versionName = "\(.*\)"/\1/p' app/build.gradle.kts)
mkdir -p dist
if [ -n "${KEYSTORE_BASE64:-}" ]; then
  tools=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
  # Copying the secret from a phone or an editor can add line breaks or spaces.
  if ! printf '%s' "$KEYSTORE_BASE64" | tr -d ' \t\r\n' | base64 -d > "$RUNNER_TEMP/release.keystore" 2>/dev/null; then
    echo "::error::RELEASE_KEYSTORE_BASE64 is not valid base64. Paste the whole content of the .jks.base64 file (one long line, about 5,700 characters for a 4096-bit key)."
    exit 1
  fi
  if ! keytool -list -keystore "$RUNNER_TEMP/release.keystore" -storepass:env KEYSTORE_PASSWORD -alias "$KEY_ALIAS" > /dev/null 2>&1; then
    echo "::error::The keystore opened from RELEASE_KEYSTORE_BASE64 does not accept RELEASE_KEYSTORE_PASSWORD, or has no key named RELEASE_KEY_ALIAS."
    exit 1
  fi
  "$tools/zipalign" -f -p 4 "$unsigned" "$RUNNER_TEMP/aligned.apk"
  "$tools/apksigner" sign --ks "$RUNNER_TEMP/release.keystore" --ks-key-alias "$KEY_ALIAS" \
    --ks-pass env:KEYSTORE_PASSWORD --key-pass env:KEY_PASSWORD \
    --out "dist/NativeForge-$version.apk" "$RUNNER_TEMP/aligned.apk"
  "$tools/apksigner" verify --print-certs "dist/NativeForge-$version.apk" | grep SHA-256 >> "$GITHUB_STEP_SUMMARY"
  rm "$RUNNER_TEMP/release.keystore" "$RUNNER_TEMP/aligned.apk"
else
  cp "$unsigned" "dist/NativeForge-$version-unsigned.apk"
  echo "No signing secrets: the APK is unsigned." >> "$GITHUB_STEP_SUMMARY"
fi
(cd dist && sha256sum *.apk > SHA256SUMS)
