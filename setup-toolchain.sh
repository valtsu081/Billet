#!/usr/bin/env bash
set -euo pipefail
ROOT="$HOME/.local/share/billet-android"
mkdir -p "$ROOT/dl" "$ROOT/sdk/cmdline-tools"
cd "$ROOT"
if [ ! -x "$ROOT/jdk/bin/javac" ]; then
  echo "== JDK"; curl -fsSL -o dl/jdk.tar.gz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
  mkdir -p jdk && tar -xzf dl/jdk.tar.gz -C jdk --strip-components=1
fi
export JAVA_HOME="$ROOT/jdk" PATH="$ROOT/jdk/bin:$PATH"
if [ ! -x "$ROOT/sdk/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "== cmdline-tools"; curl -fsSL -o dl/cmdline.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  rm -rf sdk/cmdline-tools/tmp && unzip -q dl/cmdline.zip -d sdk/cmdline-tools/tmp && mv sdk/cmdline-tools/tmp/cmdline-tools sdk/cmdline-tools/latest && rm -rf sdk/cmdline-tools/tmp
fi
SDKM="$ROOT/sdk/cmdline-tools/latest/bin/sdkmanager"
echo "== licences"; (yes 2>/dev/null || true) | "$SDKM" --sdk_root="$ROOT/sdk" --licenses > /dev/null || true
echo "== build-tools + platform"; "$SDKM" --sdk_root="$ROOT/sdk" "build-tools;34.0.0" "platforms;android-34" > "$ROOT/dl/sdkmanager.log" 2>&1
rm -rf dl
"$ROOT/jdk/bin/java" -version 2>&1 | head -1
ls "$ROOT/sdk/build-tools/34.0.0" | grep -E '^(aapt2|d8|zipalign|apksigner)$'
ls -la "$ROOT/sdk/platforms/android-34/android.jar"
du -sh "$ROOT"
echo TOOLCHAIN-READY
