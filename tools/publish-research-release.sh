#!/bin/zsh
set -euo pipefail

REPO_ROOT="${0:A:h:h}"
REPOSITORY="jorron87/FreeFCC"
GRADLE_FILE="$REPO_ROOT/app/build.gradle.kts"
APK_PATH="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"
NOTES="${1:-Research telemetry update}"

VERSION="$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' "$GRADLE_FILE" | head -n 1)"
if [[ -z "$VERSION" ]]; then
    print -u2 "Could not read versionName from $GRADLE_FILE"
    exit 1
fi

TAG="v$VERSION"
ASSET_NAME="FreeFCC-$VERSION.apk"

if gh release view "$TAG" --repo "$REPOSITORY" >/dev/null 2>&1; then
    print -u2 "Release $TAG already exists. Bump versionName and versionCode first."
    exit 1
fi

cd "$REPO_ROOT"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home}" \
ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}" \
    ./gradlew test assembleDebug

SHA256="$(shasum -a 256 "$APK_PATH" | awk '{print $1}')"
BODY="$NOTES

SHA-256: $SHA256

Bench-only telemetry research build. Propellers off; stop immediately on DJI Fly reconnect or control-link warnings."

gh release create "$TAG" \
    "$APK_PATH#$ASSET_NAME" \
    --repo "$REPOSITORY" \
    --target "$(git rev-parse HEAD)" \
    --title "$TAG" \
    --notes "$BODY"

print "Published $TAG to https://github.com/$REPOSITORY/releases/tag/$TAG"
