# 修复超宽歌词行：恢复卡拉 OK 动效 + 水平滚动露出全文

## 1. 问题与根因（已由源码确认）

`src/main/java/com/miniplayer/MiniPlayerWindow.java:1135-1139` 的短路分支：

```java
if (fm.stringWidth(text) > maxW) {
    g.setColor(sung);
    g.drawString(ellipsize(text, fm, maxW), left, baseline);
    return;
}
```

整行超宽 → 直接画省略号并 `return`，永远不进 `refineCells` / `timelineEdge` / `paintEdge` 通道，所以卡拉 OK 逐字动效消失；同时省略号把超出内容丢掉了。短行不走这个分支，因此"没超过可视范围时正常"。

`MiniPlayerWindow.java:1123-1124` 的 Javadoc 记载了这条旧决策（"宁可没有动效，也不要画错位"），本次推翻它，并同步改写 Javadoc。

## 2. 目标与验收标准

- A1：整行宽度 > `maxW` 时，仍按 timeline（有逐字/时长数据）或 sweep 通道做逐字染色动效。
- A2：整行超宽时，随演唱推进水平平移绘制，使当前演唱边界与行尾最终都能进入可视区，不再出现省略号截断。
- A3：行宽 ≤ `maxW` 时行为与现在完全一致（零回归）。
- A4：副行（下一句）与标题/歌手的行为不变。
- 验收：`bash build.sh` 与 `bash preview.sh` 全绿；`LyricScrollProbe` 的像素断言证明超宽行左右染色不同、行尾可见。

## 3. 方案设计

### 3.1 滚动模型（跟随演唱边界，而非匀速跑马）

主行滚动量 `scroll`（像素，≥0）由"演唱边界离右边界还有多远"决定：

```
visibleRight = maxW - LYRIC_SCROLL_MARGIN        // 余量 24
scroll = clamp(min(edge - visibleRight, textW - maxW), 0, textW - maxW)
```

- `edge` 取 `smoothEdge` 之后的平滑值，单帧位移已有 ≤4px 的既有约束，因此滚动天然平滑。
- 上限 `textW - maxW` 保证滚到底时行尾贴住可视右边界，`edge` 永远落在可视区内 —— 动效不会"滚出屏幕"。
- 语义：唱到哪就看到哪，唱过的部分左移离场。

备选（不采用）：匀速跑马循环。它会让正在演唱的边界周期性移出屏幕，重新造成"失去效果"，故仅在无演唱边界时降级使用（见 3.4）。

### 3.2 坐标与绘制

全部滚动在 `paintLyric` 内部用**子画布**实现，不改全局 `translate`（`paintBody` :963 的 `layout.shadow` 平移保持原样）：

```java
Graphics2D lg = (Graphics2D) g.create();
lg.translate(-scroll, 0);
paintKaraokeLine(lg, text, left, baseline, edge, fmMain, lyricFade);
lg.dispose();
```

外层 `g.clipRect(left, 0, maxW, contentH)`（:1084）已把可视窗口限制在 maxW；`translate` 后 clip 仍保持原设备区域，文本被正确裁在窗口内、随 scroll 露出后续内容。`paintEdge` 内部的 sung/pending 裁剪矩形同样随 transform 一致偏移，不会错位。

**必须拆开"算边界"与"画边界"**：`scroll` 要在绘制前拿到，而现有 `paintKaraokeLine` 内部才调 `smoothEdge`。改造为：

- 新增 `private double lyricEdgeFor(LyricsLine line, String text, FontMetrics fm, int left)` —— 内含原 :1141-1157 的 `refineCells` / `timelineUsable` / `timelineEdge` / `sweepEdge` / `smoothEdge` 逻辑，继续维护 `karaokeByCells` 字段与路径切换日志，返回 `smoothEdge` 的结果（`NaN` 表示无动效）。
- 新增 `private void paintKaraokeLine(Graphics2D g, String text, int left, int baseline, double edge, FontMetrics fm, float alpha)` —— 纯绘制，直接调 `paintEdge`。
- 删除旧签名与超宽短路分支；改写 :1123-1124 Javadoc。

### 3.3 可单测的纯函数

```java
static final double LYRIC_SCROLL_MARGIN = 24d;
static int lyricScrollFor(double edge, int textW, int maxW)
```

`edge` 为 `NaN` 时返回 0；`textW <= maxW` 时返回 0。

### 3.4 无演唱边界时（`edge == NaN`）的降级

`edge` 为 `NaN` 只发生在 `endTime <= startTime`（拿不到行时长）的场景，此时整行实色、没有"唱到哪"的概念。用匀速循环露出全文：

- `phase = ((nowNs - lyricMarqueeStartNs) / 1e9 * SPEED) % 1`，`scroll = phase * (textW - maxW + LYRIC_MARQUEE_GAP)`，`SPEED` 沿用标题的 `0.028` px/ms，`LYRIC_MARQUEE_GAP = 40`。
- 由 `paintLyric` 计算，`lyricMarqueeStartNs` 在行切换时重置。
- `paintLyric` 设置 `private boolean lyricScrolling`（本帧是否发生滚动），供 `onTick` 的 `wantDelay` 使用：条件从 `hasLyric() && (advancing || controlsAlpha < 0.98f)` 扩为 `|| lyricScrolling`，确保降级滚动也跑 60fps；同时把 `lyricScrolling` 纳入 :738-740 的 `dirty → repaint()` 判定。

### 3.5 行切换重置

新增字段 `private String lyricScrollForLine;`。`paintLyric` 中若当前 `text` 与之不同：`scroll` 归零、`lyricMarqueeStartNs = System.nanoTime()`、更新 `lyricScrollForLine`。避免上一行的大 scroll 带到新行。

### 3.6 明确的取舍

- **副行不滚动**，继续 `ellipsize`（:1108-1111）。副行是"下一句"预告、不可唱，滚动只会造成两行反向运动的视觉噪声。
- **不新增配置项**（不加滚动速度开关），避免改 `PluginConfig.java` 的 `KEY_*` / `load` / `save` 与 `preference_config.json` 四处 + README 配置表。
- **不调整 `Layout`**：可视宽度 `maxW` 的语义不变。

## 4. 逐文件改动

### 4.1 `src/main/java/com/miniplayer/MiniPlayerWindow.java`
1. 字段区（约 :200-213）：新增 `private String lyricScrollForLine;`、`private long lyricMarqueeStartNs;`、`private boolean lyricScrolling;`；新增常量 `LYRIC_SCROLL_MARGIN = 24d`、`LYRIC_MARQUEE_GAP = 40f`、`LYRIC_MARQUEE_SPEED = 0.028f`。
2. 新增 `static int lyricScrollFor(double edge, int textW, int maxW)`。
3. 重构 `paintKaraokeLine`（:1126-1158）为 3.2 的两个方法；删除 :1135-1139 的 `ellipsize` 短路与 :1134 注释。
4. `paintLyric`（:1059-1114）：取 `fmMain` 后算 `textW = fmMain.stringWidth(text)`；调 `lyricScrollFor` / 降级相位得到 `scroll`，做行切换重置；主行绘制改为子画布平移调用（:1089-1095 与 :1105-1106 两条路径都要包上）；副行保持 `ellipsize` 不动。
5. `onTick`（:617-741）：:726-733 的 `wantDelay` 条件加 `|| lyricScrolling`；:738-740 的 `dirty` 判定纳入 `lyricScrolling`。
6. 保留 `ellipsize`（:1603-1623）供标题/歌手/副行使用。

### 4.2 新增 `preview-src/com/miniplayer/LyricScrollProbe.java`
沿用现有探针范式（反射取私有构造/字段/方法，失败计数 → `PASS`/`FAIL` 行 + `System.exit`）。断言：

- 纯函数 `lyricScrollFor`：`textW <= maxW` → 0；`edge = NaN` → 0；`edge` 在行首 → 0；`edge` 增长时单调不减；结果封顶 `textW - maxW`；且 `edge - scroll <= maxW`。
- 像素级（构造 `BufferedImage` + `Graphics2D`，反射调新的 `paintKaraokeLine(g, text, left, 0, edge, fm, 1f)`）：超长行 `edge` 取行中段、`scroll = 0` 时左段平均亮度显著高于右段（A1 证据）；同长度行 `edge ≈ textW`、`scroll = 上限` 时可视窗口最右列存在墨迹（A2 证据）；对照组短行整行 sung 色。

### 4.3 `preview-src/com/miniplayer/Preview.java`
新增超长行变体出图（`mini-lyric-overflow.png` 中段滚动、`mini-lyric-overflow-end.png` 行尾），并加入 `contactSheet`（:210-212）。

### 4.4 `preview.sh`
末尾断言链（`LyricSyncProbe` 之后）追加 `LyricScrollProbe`；`javac` 用 `*.java` 通配，无需改编译行。

### 4.5 `VERSION.txt` → `1.0.27`

### 4.6 `install-latest.bat`：:9 `SRC`、:11 `DEST`、:49 `%%~nxD` 三处硬编码版本号同步为 `1.0.27`。

### 4.7 `README.md`
- 第 44 行表格歌词行改为："超长自动水平滚动跟随演唱位置，不再截断带省略号"；
- 第 223 行删去"整行超宽要加省略号时不走逐字…"这半句，保留 `endTime <= startTime` 整行实色的描述，补一句"此时按匀速循环露出全文"。

### 4.8 本文件 `docs/superpowers/plans/2026-11-09-lyric-overflow-scroll.md`（项目原无 `docs/`，已 `mkdir -p`）。

## 5. 边界与失败模式

| 场景 | 预期 |
|---|---|
| 行宽 == maxW（临界） | `overflow` 为 false，`scroll = 0`，与现状一致 |
| `keepButtonsWithLyric()` 切换导致 `maxW` 变化 | `lyricScrollFor` 每次重算并 clamp，不会越界 |
| 用户拖动进度条回退 | `smoothEdge` 已对回退做吸附，`edge` 跳变 → `scroll` 跟随回落后再前进；不累积漂移（`scroll` 每帧由 `edge` 推导，非累加） |
| 换曲 / 换行 | `lyricScrollForLine` 不匹配 → 全部归零 |
| 行尾已滚到底但 `edge` 还在推进 | `scroll` 封顶，`edge` 在可视区内继续右移，动效有效 |
| `edge == NaN` | 降级匀速循环；`lyricScrolling = true` 保证 60fps |
| 暂停且控件已淡出 | `advancing` 为 false，`edge` 恒定 → `scroll` 恒定；降级分支仅在 `advancing` 时置 `lyricScrolling = true`，避免空转 60fps |

## 6. 假设

- 目标运行环境仍是 Windows 分层窗口 + Swing，`dispose()` 子画布不影响 `paintBody` 后续绘制。
- `KaraokeProbe` / `LyricSyncProbe` 只反射 `refineCells`、`timelineUsable`、`timelineEdge` 与行匹配逻辑，不改这些签名即可保持全绿。
- 仓库无 git，交付锚点为"改 `VERSION.txt` + `bash build.sh` + `bash preview.sh`"，不含提交步骤。

## 7. 实施步骤（TDD 顺序）

- [ ] 1. `mkdir -p docs/superpowers/plans` 并落盘本计划。
- [ ] 2. 先写 `LyricScrollProbe.java`（引用尚不存在的 `lyricScrollFor` 与 5 参 `paintKaraokeLine`），并把调用加入 `preview.sh` —— 留下失败基线。
- [ ] 3. `MiniPlayerWindow.java` 加常量、三个字段与 `lyricScrollFor`。
- [ ] 4. 拆分 `paintKaraokeLine` 为 `lyricEdgeFor` + 5 参绘制方法，删除超宽 `ellipsize` 短路，改写 :1123-1124 Javadoc。
- [ ] 5. 改造 `paintLyric`：子画布平移、行切换重置、降级匀速分支、`lyricScrolling` 置位。
- [ ] 6. 改 `onTick` 的 `wantDelay` 与 `dirty` 判定。
- [ ] 7. `Preview.java` 增两张超宽行出图 + `contactSheet`。
- [ ] 8. `VERSION.txt` → `1.0.27`，`install-latest.bat` 三处同步。
- [ ] 9. 更新 `README.md` 第 44、223 行。
- [ ] 10. 跑验证命令，人工核对 `preview/mini-lyric-overflow*.png`。

## 8. 验证命令

```bash
cd E:/AiTool/666/chajian2/spw-mini-player
bash build.sh
bash preview.sh
```

判定：`build.sh` 产出 `build/libs/plugin-com.miniplayer.desktop-mini-player-1.0.27.spmod`；`preview.sh` 中 `KaraokeProbe`、`LyricSyncProbe`、`LyricScrollProbe` 均打印 `PASS` 且退出码 0；目视 `preview/mini-lyric-overflow.png` 左亮右暗（卡拉 OK 生效）、`preview/mini-lyric-overflow-end.png` 能看到句末文字。
