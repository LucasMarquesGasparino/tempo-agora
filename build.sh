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
KEYSTORE="${ATEMPORAL_KEYSTORE:-$HOME/.android-keys/tempo-agora.keystore}"
KEYSTORE_DIR=$(dirname -- "$KEYSTORE")
VERSION_CODE=$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' "$PROJECT_DIR/AndroidManifest.xml")
VERSION_NAME=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$PROJECT_DIR/AndroidManifest.xml")

sh "$PROJECT_DIR/tests/run.sh"

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
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
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

# A chave de assinatura fica fora de build/ (que é apagado a cada compilação).
mkdir -p "$KEYSTORE_DIR"
chmod 700 "$KEYSTORE_DIR"
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -noprompt \
        -keystore "$KEYSTORE" \
        -storepass tempoagora-local \
        -keypass tempoagora-local \
        -alias tempoagora \
        -keyalg RSA -keysize 2048 -validity 3650 \
        -dname "CN=Tempo Agora, OU=Local, O=Tempo Agora, L=Local, ST=BR, C=BR"
fi

"$TOOLS_DIR/apksigner" sign \
    --ks "$KEYSTORE" \
    --ks-pass pass:tempoagora-local \
    --key-pass pass:tempoagora-local \
    --out "$OUT/$APK_NAME" \
    "$OUT/aligned.apk"
"$TOOLS_DIR/apksigner" verify --verbose "$OUT/$APK_NAME"
printf 'APK criado: %s\n' "$OUT/$APK_NAME"
ls -lh "$OUT/$APK_NAME"

# Entrega: pasta única de APKs no armazenamento compartilhado.
APK_DELIVERY_DIR="${ATEMPORAL_APK_DIR:-/sdcard/Documents/APK}"
mkdir -p "$APK_DELIVERY_DIR"
cp "$OUT/$APK_NAME" "$APK_DELIVERY_DIR/$APK_NAME"
printf 'APK copiado para: %s\n' "$APK_DELIVERY_DIR/$APK_NAME"
