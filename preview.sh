#!/usr/bin/env bash
# 出图脚本：编译全部探针，生成静态预览图与真机截图
set -euo pipefail

PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")" && { pwd -W 2>/dev/null || pwd; })"
ROOT="$(dirname "$PROJ")"
JDK="${JDK_HOME:-$ROOT/.tools/unz/jdk25.0.5_0}"
LIBS="${SPW_LIBS:-$ROOT/.refs}"

CP="$LIBS/spw-workshop-api.jar;$LIBS/lib/pf4j-3.12.0.jar;$LIBS/lib/kotlin-stdlib-2.4.20.jar;$LIBS/lib/annotations-13.0.jar"

rm -rf "$PROJ/build/preview"
mkdir -p "$PROJ/build/preview" "$PROJ/preview"

echo "[1/4] 编译 preview-src"
"$JDK/bin/javac.exe" -encoding UTF-8 -proc:none --release 25 \
  -cp "$CP;$PROJ/build/classes" -d "$PROJ/build/preview" \
  "$PROJ"/preview-src/com/miniplayer/*.java

cd "$PROJ"

echo "[2/4] 静态渲染（展开态 + 歌词态）"
"$JDK/bin/java.exe" -Dfile.encoding=UTF-8 \
  -cp "$CP;$PROJ/build/classes;$PROJ/build/preview" com.miniplayer.Preview

echo "[3/4] 真机截图（浅色 / 深色 / 停靠态 / 虚线框）"
"$JDK/bin/java.exe" -Dfile.encoding=UTF-8 \
  -cp "$CP;$PROJ/build/classes;$PROJ/build/preview" com.miniplayer.Demo light 1400 || true
"$JDK/bin/java.exe" -Dfile.encoding=UTF-8 \
  -cp "$CP;$PROJ/build/classes;$PROJ/build/preview" com.miniplayer.Demo dark 1400 || true
"$JDK/bin/java.exe" -Dfile.encoding=UTF-8 \
  -cp "$CP;$PROJ/build/classes;$PROJ/build/preview" com.miniplayer.ShowProbe || true
"$JDK/bin/java.exe" -Dfile.encoding=UTF-8 \
  -cp "$CP;$PROJ/build/classes;$PROJ/build/preview" com.miniplayer.IndicatorProbe || true

echo "[4/4] 断言类探针（失败即非零退出）"
"$JDK/bin/java.exe" -Dfile.encoding=UTF-8 \
  -cp "$CP;$PROJ/build/classes;$PROJ/build/preview" com.miniplayer.DockProbe
"$JDK/bin/java.exe" -Dfile.encoding=UTF-8 \
  -cp "$CP;$PROJ/build/classes;$PROJ/build/preview" com.miniplayer.HitProbe
"$JDK/bin/java.exe" -Dfile.encoding=UTF-8 \
  -cp "$CP;$PROJ/build/classes;$PROJ/build/preview" com.miniplayer.DragProbe
"$JDK/bin/java.exe" -Dfile.encoding=UTF-8 \
  -cp "$CP;$PROJ/build/classes;$PROJ/build/preview" com.miniplayer.CoverProbe
"$JDK/bin/java.exe" -Dfile.encoding=UTF-8 \
  -cp "$CP;$PROJ/build/classes;$PROJ/build/preview" com.miniplayer.LayerProbe
"$JDK/bin/java.exe" -Dfile.encoding=UTF-8 \
  -cp "$CP;$PROJ/build/classes;$PROJ/build/preview" com.miniplayer.ActivateProbe
"$JDK/bin/java.exe" -Dfile.encoding=UTF-8 \
  -cp "$CP;$PROJ/build/classes;$PROJ/build/preview" com.miniplayer.KaraokeProbe
"$JDK/bin/java.exe" -Dfile.encoding=UTF-8 \
  -cp "$CP;$PROJ/build/classes;$PROJ/build/preview" com.miniplayer.SimProbe
"$JDK/bin/java.exe" -Dfile.encoding=UTF-8 \
  -cp "$CP;$PROJ/build/classes;$PROJ/build/preview" com.miniplayer.LyricSyncProbe
"$JDK/bin/java.exe" -Dfile.encoding=UTF-8 \
  -cp "$CP;$PROJ/build/classes;$PROJ/build/preview" com.miniplayer.LyricScrollProbe
"$JDK/bin/java.exe" -Dfile.encoding=UTF-8 \
  -cp "$CP;$PROJ/build/classes;$PROJ/build/preview" com.miniplayer.LockProbe
"$JDK/bin/java.exe" -Dfile.encoding=UTF-8 \
  -cp "$CP;$PROJ/build/classes;$PROJ/build/preview" com.miniplayer.PausedHideProbe

ls -la "$PROJ/preview"
