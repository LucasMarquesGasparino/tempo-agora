#!/data/data/com.termux/files/usr/bin/sh
# Testes JVM puros (sem android.* nem org.json): compila só as classes puras e roda LogicTest.
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUT="$PROJECT_DIR/build/tests"
rm -rf "$OUT"
mkdir -p "$OUT"

SOURCES=""
for name in Units Format WeatherCodes ForecastTime WidgetSizing; do
    file="$PROJECT_DIR/src/com/tempoagora/$name.java"
    if [ -f "$file" ]; then SOURCES="$SOURCES $file"; fi
done

javac --release 8 -encoding UTF-8 -d "$OUT" $SOURCES "$PROJECT_DIR/tests/LogicTest.java"
java -cp "$OUT" com.tempoagora.LogicTest
