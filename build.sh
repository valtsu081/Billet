#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

VERSION_CODE=8
VERSION_NAME=$(sed -n "s/^const APP_VERSION = '\([^']*\)';.*/\1/p" web/billet.html)
if [ -z "$VERSION_NAME" ]; then echo "APP_VERSION not found in web/billet.html" >&2; exit 1; fi
ROOT="${BILLET_TOOLCHAIN:-$HOME/.local/share/billet-android}"
if [ ! -x "$ROOT/jdk/bin/javac" ] || [ ! -d "$ROOT/sdk/build-tools/34.0.0" ]; then
  echo "Toolchain missing. Run ./setup-toolchain.sh first (downloads about 550 MB)." >&2
  exit 1
fi
export JAVA_HOME="$ROOT/jdk" PATH="$ROOT/jdk/bin:$PATH"
BT="$ROOT/sdk/build-tools/34.0.0"
PLATFORM="$ROOT/sdk/platforms/android-34/android.jar"

rm -rf build && mkdir -p build/assets build/classes build/dex

echo "== Page and fonts"
python3 -I tools/make_assets.py web/billet.html build/assets

echo "== Resources and manifest"
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$PLATFORM" --manifest AndroidManifest.xml -A build/assets \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" build/res.zip

echo "== Code"
javac -source 8 -target 8 -bootclasspath "$PLATFORM" -Xlint:-options -Xlint:-deprecation \
  -d build/classes $(find src -name '*.java')
"$BT/d8" --release --min-api 26 --lib "$PLATFORM" --output build/dex $(find build/classes -name '*.class')
python3 -I tools/add_dex.py build/base.apk build/dex/classes.dex build/unsigned.apk

echo "== Align and sign"
"$BT/zipalign" -p -f 4 build/unsigned.apk build/aligned.apk
if [ ! -f signing/billet.jks ]; then
  mkdir -p signing
  head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' > signing/password.txt
  keytool -genkeypair -keystore signing/billet.jks -alias billet -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$(cat signing/password.txt)" -keypass "$(cat signing/password.txt)" -dname "CN=Billet" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks signing/billet.jks --ks-key-alias billet \
  --ks-pass file:signing/password.txt --out Billet.apk build/aligned.apk
"$BT/apksigner" verify Billet.apk
rm -f Billet.apk.idsig
ls -la Billet.apk
