#!/usr/bin/env bash
# TTSNover Android 构建脚本：aapt2 -> javac -> d8 -> zipalign -> apksigner
# 不依赖 Gradle / AndroidX，全部走 Android SDK 自带工具，离线可构建。
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
# d8 34.0.0 在 Java 21 上 dex 多类时会内部 NPE，35.0.0 正常
BT="$SDK/build-tools/35.0.0"
[ -d "$BT" ] || BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
KS="keystore/ttsnover.jks"
KS_PASS="ttsnover2026"
VERSION_CODE="${VERSION_CODE:-2}"
VERSION_NAME="${VERSION_NAME:-1.1.0}"
OUT="dist/TTSNover-${VERSION_NAME}.apk"

for f in "$BT/aapt2" "$PLATFORM" "$KS"; do
  [ -e "$f" ] || { echo "缺少：$f"; exit 1; }
done

echo "== 1/6 清理"
rm -rf build
mkdir -p build/gen build/classes build/dex dist

echo "== 2/6 aapt2 compile"
"$BT/aapt2" compile --dir res -o build/res.zip

echo "== 3/6 aapt2 link"
"$BT/aapt2" link -o build/base.apk -I "$PLATFORM" \
  --manifest AndroidManifest.xml -R build/res.zip \
  --java build/gen --auto-add-overlay \
  --min-sdk-version 21 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"

echo "== 4/6 javac"
find java build/gen -name '*.java' > build/srcs.txt
JARS=$(ls libs/*.jar 2>/dev/null | tr '\n' ':')
javac --release 8 -nowarn -encoding UTF-8 -cp "$PLATFORM:$JARS" -d build/classes @build/srcs.txt

echo "== 5/6 d8"
find build/classes -name '*.class' > build/classes.txt
"$BT/d8" --lib "$PLATFORM" --min-api 21 --release --output build/dex @build/classes.txt libs/*.jar

cp build/base.apk build/unsigned.apk
( cd build/dex && zip -q ../unsigned.apk classes.dex )
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk

echo "== 6/6 签名"
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$OUT" build/aligned.apk
"$BT/apksigner" verify --print-certs "$OUT" | head -6

echo
echo "APK: $(pwd)/$OUT"
ls -lh "$OUT" | awk '{print "大小:", $5}'
