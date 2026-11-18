#!/usr/bin/env bash
set -euo pipefail

# Builds standalone PRANAV.dex + SHA-256 + dex-manifest.json (NEVER a DEX APK).
ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
DEX_MODULE_DIR="$ROOT_DIR/pranav-dex"
DIST_DIR="$DEX_MODULE_DIR/dist"
CLASSES_DIR="$DEX_MODULE_DIR/build/dex-classes"

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}"
D8_BIN="$(find "$SDK_ROOT/build-tools" -maxdepth 2 -name d8 -type f | sort -V | tail -n 1 || true)"
ANDROID_JAR="$(find "$SDK_ROOT/platforms" -maxdepth 2 -name android.jar -type f | sort -V | tail -n 1 || true)"

if [ -z "$D8_BIN" ] || [ -z "$ANDROID_JAR" ]; then
  echo "ERROR: Missing Android SDK d8 or android.jar under $SDK_ROOT" >&2
  exit 1
fi

rm -rf "$CLASSES_DIR" "$DIST_DIR"
mkdir -p "$CLASSES_DIR" "$DIST_DIR"

javac -source 11 -target 11 \
  -classpath "$ANDROID_JAR" \
  -d "$CLASSES_DIR" \
  "$DEX_MODULE_DIR/src/main/java/com/pranav/dex/DexMetadata.java" \
  "$DEX_MODULE_DIR/src/main/java/com/pranav/dex/PranavDexEntry.java"

CLASS_FILES=()
while IFS= read -r -d '' f; do
  CLASS_FILES+=("$f")
done < <(find "$CLASSES_DIR" -name '*.class' -print0)

"$D8_BIN" --min-api 24 --output "$DIST_DIR" "${CLASS_FILES[@]}"

if [ ! -f "$DIST_DIR/classes.dex" ]; then
  echo "ERROR: d8 did not produce classes.dex" >&2
  exit 1
fi

mv "$DIST_DIR/classes.dex" "$DIST_DIR/PRANAV.dex"
SHA256="$(sha256sum "$DIST_DIR/PRANAV.dex" | awk '{print $1}')"
SIZE_BYTES="$(wc -c < "$DIST_DIR/PRANAV.dex" | tr -d ' ')"
BUILT_AT="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"

printf '%s  PRANAV.dex\n' "$SHA256" > "$DIST_DIR/PRANAV.dex.sha256"

cat > "$DIST_DIR/dex-manifest.json" <<EOF
{
  "artifact": "PRANAV.dex",
  "format": "standalone-dex",
  "isApk": false,
  "version": "1.0.0",
  "buildId": "pranav-dex-2026.1",
  "minSdk": 24,
  "targetSdk": 36,
  "entrypoints": [
    "com.pranav.dex.PranavDexEntry",
    "com.pranav.dex.DexMetadata"
  ],
  "sha256": "$SHA256",
  "sizeBytes": $SIZE_BYTES,
  "builtAtUtc": "$BUILT_AT"
}
EOF

echo "Built $DIST_DIR/PRANAV.dex ($SIZE_BYTES bytes, sha256=$SHA256)"
