#!/usr/bin/env bash
# Builds GpsJamLogger.apk without Android Studio, using Debian/Ubuntu packages:
#   sudo apt-get install -y openjdk-17-jdk aapt apksigner zipalign dalvik-exchange
# and an android.jar (API 30) downloaded next to this script:
#   curl -L -o android-30.jar https://raw.githubusercontent.com/Sable/android-platforms/master/android-30/android.jar
set -euo pipefail
cd "$(dirname "$0")"

ANDROID_JAR=${ANDROID_JAR:-android-30.jar}
BT=/usr/lib/android-sdk/build-tools/debian
SRC=app/src/main
OUT=build
rm -rf "$OUT" && mkdir -p "$OUT/classes"

# The in-app street map is the website's map page, bundled into the app.
mkdir -p "$SRC/assets"
if [ -f ../index.html ]; then cp ../index.html "$SRC/assets/map.html"; fi

echo "1/5 Linking manifest"
"$BT/aapt2" link -o "$OUT/unsigned.apk" -I "$ANDROID_JAR" -A "$SRC/assets" \
  --manifest "$SRC/AndroidManifest.xml" --min-sdk-version 24 --target-sdk-version 34 \
  --version-code 5 --version-name 1.4

echo "2/5 Compiling Java"
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" $(find "$SRC/java" -name '*.java')

echo "3/5 Converting to Android bytecode (dex)"
dalvik-exchange --dex --min-sdk-version=24 --output="$OUT/classes.dex" "$OUT/classes"
(cd "$OUT" && zip -q -j unsigned.apk classes.dex)

echo "4/5 Aligning"
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "5/5 Signing"
KS=debug.keystore
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias debug \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=GPS Jam Logger debug" >/dev/null 2>&1
fi
apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out GpsJamLogger.apk "$OUT/aligned.apk" && rm -f GpsJamLogger.apk.idsig
apksigner verify GpsJamLogger.apk && echo "Built $(pwd)/GpsJamLogger.apk"
