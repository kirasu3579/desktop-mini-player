#!/usr/bin/env bash
set -euo pipefail
# 工程根 = 本脚本所在目录（tools/）的上一级；JDK 与依赖默认在其上一级，可用环境变量覆盖
PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && { pwd -W 2>/dev/null || pwd; })"
ROOT="$(dirname "$PROJ")"
JDK="${JDK_HOME:-$ROOT/.tools/unz/jdk25.0.5_0}"
LIBS="${SPW_LIBS:-$ROOT/.refs}"
CP="$LIBS/spw-workshop-api.jar;$LIBS/lib/pf4j-3.12.0.jar;$LIBS/lib/kotlin-stdlib-2.4.20.jar;$LIBS/lib/annotations-13.0.jar"
cd "$PROJ"
rm -rf build/preview
mkdir -p build/preview
echo "[compile]"
"$JDK/bin/javac.exe" -encoding UTF-8 -proc:none --release 25 \
  -cp "$CP;$PROJ/build/classes" -d "$PROJ/build/preview" \
  "$PROJ"/preview-src/com/miniplayer/*.java
echo "[SimProbe]"
"$JDK/bin/java.exe" -Dfile.encoding=UTF-8 -cp "$CP;$PROJ/build/classes;$PROJ/build/preview" com.miniplayer.SimProbe
echo "[KaraokeProbe]"
"$JDK/bin/java.exe" -Dfile.encoding=UTF-8 -cp "$CP;$PROJ/build/classes;$PROJ/build/preview" com.miniplayer.KaraokeProbe
echo "[LyricSyncProbe]"
"$JDK/bin/java.exe" -Dfile.encoding=UTF-8 -cp "$CP;$PROJ/build/classes;$PROJ/build/preview" com.miniplayer.LyricSyncProbe
echo "[DockProbe]"
"$JDK/bin/java.exe" -Dfile.encoding=UTF-8 -cp "$CP;$PROJ/build/classes;$PROJ/build/preview" com.miniplayer.DockProbe
echo "[DragProbe]"
"$JDK/bin/java.exe" -Dfile.encoding=UTF-8 -cp "$CP;$PROJ/build/classes;$PROJ/build/preview" com.miniplayer.DragProbe
echo "[HitProbe]"
"$JDK/bin/java.exe" -Dfile.encoding=UTF-8 -cp "$CP;$PROJ/build/classes;$PROJ/build/preview" com.miniplayer.HitProbe
echo "[AlphaProbe]"
"$JDK/bin/java.exe" -Dfile.encoding=UTF-8 -cp "$CP;$PROJ/build/classes;$PROJ/build/preview" com.miniplayer.AlphaProbe
echo "[ConfigProbe]"
"$JDK/bin/java.exe" -Dfile.encoding=UTF-8 -cp "$CP;$PROJ/build/classes;$PROJ/build/preview" com.miniplayer.ConfigProbe
echo "[ConfigApplyProbe]"
"$JDK/bin/java.exe" -Dfile.encoding=UTF-8 -cp "$CP;$PROJ/build/classes;$PROJ/build/preview" com.miniplayer.ConfigApplyProbe
