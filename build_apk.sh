#!/data/data/com.termux/files/usr/bin/bash
set -e
cd "$(dirname "$0")"
export PATH="$PREFIX/bin:$PATH"
SDK=build/android.jar
OUT=build/out
rm -rf "$OUT"; mkdir -p "$OUT"

echo "[1/6] aapt2 link"
aapt2 link -o "$OUT/base.apk" -I "$SDK" --manifest AndroidManifest.xml --java "$OUT/gen" --min-sdk-version 21 --target-sdk-version 30

echo "[2/6] javac"
mkdir -p "$OUT/classes"
javac -source 1.8 -target 1.8 -nowarn -classpath "$SDK" -d "$OUT/classes" src/com/projector/tvremote/*.java "$OUT/gen/com/projector/tvremote/R.java"

echo "[3/6] d8"
mkdir -p "$OUT/dex"
find "$OUT/classes" -name '*.class' > "$OUT/classlist.txt"
d8 --release --lib "$SDK" --output "$OUT/dex" @"$OUT/classlist.txt"

echo "[4/6] zip dex ke apk"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
zip -q -j "$OUT/unsigned.apk" "$OUT/dex/classes.dex"

echo "[5/6] sign"
KS=build/tvremote.jks
[ -f "$KS" ] || keytool -genkeypair -v -keystore "$KS" -alias tvremote -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname "CN=TV Remote" >/dev/null 2>&1
apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --out "$OUT/tvremote.apk" "$OUT/unsigned.apk"

echo "[6/6] verify"
apksigner verify --print-certs "$OUT/tvremote.apk" 2>&1 | head -4
ls -la "$OUT/tvremote.apk"
echo "BUILD OK"
