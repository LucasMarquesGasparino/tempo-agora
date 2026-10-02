#!/data/data/com.termux/files/usr/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SDK_DIR=/data/data/com.termux/files/home/.cache/android-api/android-35
RESOURCE_SDK_DIR=/data/data/com.termux/files/home/.cache/android-api/android-9
TOOLS_DIR=/data/data/com.termux/files/usr/bin
OUT="$PROJECT_DIR/build"
RES_COMPILED="$OUT/res-compiled.zip"
RES_APK="$OUT/resources.apk"
GEN="$OUT/gen"
CLASSES="$OUT/classes"
DEX="$OUT/dex"
APK_NAME="Atemporal.apk"

rm -rf "$GEN" "$CLASSES" "$DEX" "$RES_COMPILED" "$RES_APK" \
    "$OUT/classes.jar" "$OUT/unsigned.apk" "$OUT/aligned.apk" \
    "$OUT/Tempo-Agora.apk" "$OUT/$APK_NAME"
mkdir -p "$GEN" "$CLASSES" "$DEX"

"$TOOLS_DIR/aapt2" compile --dir "$PROJECT_DIR/res" -o "$RES_COMPILED"
"$TOOLS_DIR/aapt2" link \
    -I "$RESOURCE_SDK_DIR/android.jar" \
    --manifest "$PROJECT_DIR/AndroidManifest.xml" \
    --java "$GEN" \
    --min-sdk-version 24 \
    --target-sdk-version 35 \
    --version-code 1 \
    --version-name 1.0.0 \
    --auto-add-overlay \
    -o "$RES_APK" -R "$RES_COMPILED"

find "$PROJECT_DIR/src" "$GEN" -type f -name '*.java' -print > "$OUT/sources.list"
javac --release 8 -encoding UTF-8 \
    -classpath "$SDK_DIR/android.jar" \
    -d "$CLASSES" \
    @"$OUT/sources.list"

TOOL_CLASSES="$OUT/tool-classes"
mkdir -p "$TOOL_CLASSES"
javac --release 8 -d "$TOOL_CLASSES" "$PROJECT_DIR/tools/StripMethodParameters.java"
java -cp "$TOOL_CLASSES" StripMethodParameters "$CLASSES"

jar cf "$OUT/classes.jar" -C "$CLASSES" .
"$TOOLS_DIR/d8" --release --min-api 24 --lib "$SDK_DIR/android.jar" \
    --output "$DEX" "$OUT/classes.jar"

cp "$RES_APK" "$OUT/unsigned.apk"
jar uf "$OUT/unsigned.apk" -C "$DEX" classes.dex
"$TOOLS_DIR/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f "$OUT/tempo-agora-local.keystore" ]; then
    keytool -genkeypair -noprompt \
        -keystore "$OUT/tempo-agora-local.keystore" \
        -storepass tempoagora-local \
        -keypass tempoagora-local \
        -alias tempoagora \
        -keyalg RSA -keysize 2048 -validity 3650 \
        -dname "CN=Tempo Agora, OU=Local, O=Tempo Agora, L=Local, ST=BR, C=BR"
fi

"$TOOLS_DIR/apksigner" sign \
    --ks "$OUT/tempo-agora-local.keystore" \
    --ks-pass pass:tempoagora-local \
    --key-pass pass:tempoagora-local \
    --out "$OUT/$APK_NAME" \
    "$OUT/aligned.apk"
"$TOOLS_DIR/apksigner" verify --verbose "$OUT/$APK_NAME"
printf 'APK criado: %s\n' "$OUT/$APK_NAME"
ls -lh "$OUT/$APK_NAME"
