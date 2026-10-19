#!/usr/bin/env bash
# Build calm.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-30/android.jar"
export JAVA_HOME="${JAVA_HOME_17:-/opt/homebrew/opt/openjdk@17}"
export PATH="$JAVA_HOME/bin:$PATH"

rm -rf build && mkdir -p build/res build/gen build/classes build/dex

# Resources + manifest (+ bundled fallback wallpapers as assets)
"$BT/aapt2" compile --dir res -o build/res/res.zip
"$BT/aapt2" link -o build/unsigned.apk -I "$JAR" --manifest AndroidManifest.xml \
  -A assets --java build/gen build/res/res.zip --min-sdk-version 26 --target-sdk-version 30

# Java -> classes -> dex
javac -source 8 -target 8 -bootclasspath "$JAR:$BT/core-lambda-stubs.jar" -Xlint:-options -encoding UTF-8 \
  -d build/classes $(find src build/gen -name '*.java')
"$BT/d8" --release --min-api 26 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q ../unsigned.apk classes.dex)

# Align + sign with a persistent local key so updates install over the top
[ -f calm.keystore ] || keytool -genkeypair -keystore calm.keystore -storepass calmcalm -keypass calmcalm \
  -alias calm -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Calm Launcher" >/dev/null 2>&1
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks calm.keystore --ks-pass pass:calmcalm --key-pass pass:calmcalm \
  --out calm.apk build/aligned.apk
"$BT/apksigner" verify calm.apk
ls -lh calm.apk
