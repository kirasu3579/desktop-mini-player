# 删除左侧拖动条 + 封面双击唤起宿主 + 重做置顶语义（压任务栏 / 让位全屏）

## 1. 问题与根因（已由源码确认）

### 1.1 左侧拖动条

`miniplayer/Layout.java:129-133` 给最左侧留了 21px 的手柄位：

```java
int gripX = 5;
int gripW = 10;
int gripH = Math.max(10, (int) Math.round(contentH * 0.46));
int coverX = gripX + gripW + 6;   // = 21
```

`MiniPlayerWindow.java:1019` 的 `paintGrip` 画三条竖杠；`paintBody` :1001 调它。命中测试在 `hitGrip`（:2054），只用于两件事——手柄加亮（`hoverGrip`）与区分「按住手柄双击」和「双击空白处」。真正决定能不能拖的是 `hitDragArea`（:2078），它早就放宽成「除播控三键与进度条外整条可拖」。所以**手柄只是视觉暗示，删掉零功能损失**，但 21px 的留白必须一起收掉。

### 1.2 空白处双击播/暂

`MiniPlayerWindow.java:1864-1870`：

```java
if (hit == HIT_NONE) {
    if (!wasGrip && sameSpot && e.getClickCount() >= 2) {
        togglePlay();
    }
    return;
}
```

用户要求「不需要空白处播放暂停上下曲，仅有控件操控即可」，这段要删；封面的双击改为唤起宿主。

### 1.3 窗口被任务栏盖住 / 一闪一闪

`keepTopmost()`（:2133）每 `TOPMOST_KEEPALIVE_MS=250` 毫秒被 `onTick` :745 调一次，条件是窗口与任务栏相交：

```java
if (now - lastTopmostAt >= TOPMOST_KEEPALIVE_MS) { lastTopmostAt = now; keepTopmost(); }
...
toFront();
```

`toFront()` 在 Windows 上等价于一次 `SetWindowPos(HWND_TOP)` 的 z 序重排。**每秒 4 次无条件重排，就是"一闪一闪"的来源**；而它本身也压不住任务栏——任务栏 `Shell_TrayWnd` 同属 TOPMOST 层，谁最后一次 `SetWindowPos` 谁在上面，于是用户一碰任务栏仍然被盖住。

探针实测（`build/probe3/WinProbe3..7.java`）：**TOPMOST 窗口不做任何保活，连续 2 秒采样 z 序恒定**。也就是说 250ms 保活完全是多余的、纯有害的。

右键菜单期间更糟：`onMenuClosed()`（:2694）与 `topItem` 监听（:2737）各自 `setAlwaysOnTop(...)`，每次都触发一次 `SetWindowPos(HWND_TOPMOST)`，叠加在保活节拍上，表现为"点开菜单、再点任务栏，窗口被盖住 / 抖一下"。

### 1.4 「总在最前」语义错位

现状是 Java 的 `Window.setAlwaysOnTop(boolean)` 二态：勾选＝压住一切（含全屏），不勾选＝连任务栏都被压住。用户要的是第三态：

- 不勾选：**压住任务栏**，但遇到全屏窗口就让位（降到普通层级、被全屏盖住，仍留在桌面上）。
- 勾选：「总在最前」按字面意思——连全屏窗口也盖住。

Windows 的 TOPMOST 层与普通层之间没有"压任务栏但不压全屏"的原生档位，只能自己算：常态挂 TOPMOST，检测到全屏应用就临时降到普通层，退出全屏再回 TOPMOST。全屏判定口径已由探针验证（见 1.5）。

### 1.5 唤起宿主的可行性（探针结论）

- 宿主 `WorkshopApi` 没有任何"打开主窗口"接口（`Ui` 只有 `toast`），只能走 Win32。
- 宿主 cfg 带 `--enable-native-access=ALL-UNNAMED` 与 `--add-opens=java.desktop/sun.awt.windows=ALL-UNNAMED`，**`java.lang.foreign` 可用**，不需要 JNA，不需要新依赖。
- 反射读 `Component.peer` 拿 HWND **不可行**（`java.desktop` 未对 unnamed module 开放 `java.awt`），必须 `EnumWindows` + `GetWindowThreadProcessId == 本进程 pid` + 面积最大。
- 探针环境 `SetForegroundWindow` 恒返 0，因为该会话没有交互式输入桌面；真实场景中「用户双击封面」本身就是输入授权，`SetForegroundWindow` 允许成功。所以实现写成多级兜底并记日志，不假设一定成功。

## 2. 目标与验收标准

- **A1** 迷你条左侧不再出现三条竖杠；左侧 21px 留白一并取消，封面左移贴边，文字区与进度条同步变宽。
- **A2** 除播控三键与进度条外，整条仍可按住拖动；锁定位置时仍不可拖（零回归）。
- **A3** 双击封面唤起 Salt Player 主窗口：主窗最小化则还原，不在前台则提上来。失败只记日志，不抛异常、不影响播放。
- **A4** 双击空白处、歌词区、标题区**不再**切换播放/暂停，也不触发上下曲；播放控制只由三键完成。
- **A5** 不再有周期性 `toFront()`：日志里不再出现每 250ms 一次的层级重排，任务栏连续交互时窗口不闪。
- **A6** 未勾选「总在最前」时，迷你条压在任务栏之上（点任务栏、开开始菜单也不会被盖住）。
- **A7** 未勾选「总在最前」时，前台出现真全屏窗口（全屏视频/游戏/独占全屏/浏览器 F11）→ 迷你条降到普通层被盖住；退出全屏后自动回到压住任务栏的状态。
- **A8** 勾选「总在最前」时，迷你条保持 TOPMOST，全屏窗口也盖住（用户显式选择的档位）。
- **A9** 右键菜单开合不再重排层级：菜单关闭后只在确实被压住时才重排一次。
- 验收：`bash build.sh` 与 `bash preview.sh` 全绿；新增/改写的探针断言覆盖 A1/A2/A4/A6 的几何与命中逻辑；`VERSION.txt` 1.0.28 与 `install-latest.bat` 三处硬编码同步。

## 3. 方案设计

### 3.1 删除拖动条（几何）

`Layout.java` 去掉 `gripX/gripW/gripH` 三个字段与构造参数，`coverX` 直接取贴边值：

```java
// 左侧不再有拖动手柄，封面直接贴住内容区左缘（留 3px 给圆角抗锯齿）
int coverX = 3;
```

连带变化（全部由 `coverX` 推导，无需另写）：`textX = coverX + coverSize + 8` 左移 18px、`barX = coverX`、`barW = contentW - coverX - 8` 变宽 18px。

备选（不采用）：保留 `gripX/gripW` 字段但不再绘制。字段留着就会有人再画回来，且 21px 留白仍在，与「不要了」相悖。

`MiniPlayerWindow.java` 删除：`paintGrip` 方法、`paintBody` 中的调用、`hoverGrip` / `pressOnGrip` 字段、`hitGrip` 方法、`mouseMoved` 里的 grip 分支、`mouseExited` 里的 `hoverGrip = false`、`mouseReleased` 里的 `wasGrip` 局部变量。

### 3.2 封面双击唤起宿主

新增命中测试，和现有 `hitButtonAt` / `hitProgressBarAt` 同一坐标系（减 `layout.shadow`）：

```java
private boolean hitCoverAt(Point p) {
    double x = p.x - layout.shadow;
    double y = p.y - layout.shadow;
    return x >= layout.coverX - 2 && x <= layout.coverX + layout.coverSize + 2
            && y >= layout.coverY - 2 && y <= layout.coverY + layout.coverSize + 2;
}
```

`mouseReleased` 的 `HIT_NONE` 分支整段替换为封面判定：

```java
if (hit == HIT_NONE) {
    // 空白处、歌词区、标题区都不再响应双击：播放控制只走三键。
    // 封面双击是"把宿主主窗口叫到前面"的入口。
    if (sameSpot && e.getClickCount() >= 2 && hitCover(eventPoint(e))) {
        HostWindow.activate();
    }
    return;
}
```

新增 `HostWindow.java`（FFM，`java.lang.foreign`）：

- `static void activate()`：取本进程主窗 HWND（`EnumWindows` → pid 相同 → class `SunAwtFrame` → `GW_OWNER` 为空 → 面积最大 → 排除迷你条自己的 hwnd），依次 `ShowWindow(SW_RESTORE=9)`、`BringWindowToTop`、`SetForegroundWindow`，逐步记日志。
- `static long hwndOf(Window w)`：按 pid + 物理尺寸（`Math.round(logical * gc.getDefaultTransform().getScaleX())`）匹配，供层级引擎复用。
- 全部包 try/catch `Throwable`，任何一步失败只写日志。

备选（不采用）：junique 的 `.port`（`13385`）走宿主单实例 socket。那是 junique 内部协议，无公开格式，靠猜字节格式极易随宿主版本失效。

备选（不采用）：反射 `Component.peer` → `WComponentPeer.getHWnd()`。探针实测抛 `InaccessibleObjectException: module java.desktop does not "opens java.awt" to unnamed module`。

### 3.3 层级引擎

新建 `WindowLayer.java`（FFM），只暴露四个动作，全部无副作用、可重复调用：

| 方法 | 实现 |
| --- | --- |
| `static long hwnd(Window w)` | `EnumWindows` + pid + 物理尺寸匹配，缓存 hwnd 到 window 关闭 |
| `static boolean trayCovers(long hwnd)` | `GetWindow(hwnd, GW_HWNDPREV=3)` 逐级上溯，遇到 `FindWindowW("Shell_TrayWnd")` 即 true |
| `static void setTopmost(long hwnd, boolean on)` | `SetWindowPos(hwnd, on ? HWND_TOPMOST : HWND_NOTOPMOST, 0,0,0,0, SWP_NOMOVE\|SWP_NOSIZE\|SWP_NOACTIVATE)` |
| `static boolean fullscreenForeground(long selfHwnd)` | 见 3.4 |

驱动逻辑替换原 `keepTopmost()`，改为 `syncLayer()`：

```java
private static final int LAYER_CHECK_MS = 1000;

private void syncLayer() {
    if (!isVisible() || dragging || menuOpen) return;
    long hwnd = WindowLayer.hwnd(this);
    if (hwnd == 0L) return;
    boolean fullscreen = !config.coverFullscreen && WindowLayer.fullscreenForeground(hwnd);
    boolean wantTopmost = !fullscreen;
    if (wantTopmost != layerTopmost) {
        WindowLayer.setTopmost(hwnd, wantTopmost);
        layerTopmost = wantTopmost;
        MiniPlayerPlugin.log("layer.topmost=" + wantTopmost + " fullscreen=" + fullscreen);
        return;
    }
    // 已经在正确层级时，只有确实被任务栏压住才重排一次。
    if (wantTopmost && WindowLayer.trayCovers(hwnd)) {
        WindowLayer.setTopmost(hwnd, true);
    }
}
```

要点：
- **每天花一次廉价检测，只有状态翻转或真被压住才 `SetWindowPos`**。彻底没有周期性 `toFront()`。
- `setAlwaysOnTop` 不再由 Java 侧驱动（Java 的 `setAlwaysOnTop(true)` 会给窗口加 `WS_EX_TOPMOST`，与 FFM 争同一状态，两边来回设就是闪烁的另一来源）。改由 FFM 独占层级控制，`setAlwaysOnTop(false)` 只在建窗时调用一次保证初始非 TOPMOST，之后全走 `SetWindowPos`。
- `tickTimer` 的 250ms 节拍删除（`TOPMOST_KEEPALIVE_MS`、`lastTopmostAt`、`topmostRaises` 一并删），`LAYER_CHECK_MS` 用独立的毫秒时间戳判断，只在 60fps 帧里做一次比较，开销可忽略。
- `onMenuClosed()` 只清标志 + 强制下一次 `syncLayer()`（`layerCheckedAt = 0L`），不再 `setAlwaysOnTop(true) + keepTopmost()`。
- `DropIndicator` 跟随主窗层级：主窗降到普通层时它也 `setAlwaysOnTop(false)`，避免拖动预览框在全屏上方闪一下虚线。

### 3.4 全屏判定（探针口径）

```java
long fg = GetForegroundWindow();
if (fg == 0L || fg == selfHwnd) return false;
Rectangle r = GetWindowRect(fg);
long mon = MonitorFromWindow(fg, MONITOR_DEFAULTTONEAREST=2);
Rectangle monRect = GetMonitorInfoW(mon).rcMonitor;
double cover = area(r ∩ monRect) / (double) area(monRect);
int style = GetWindowLongW(fg, GWL_STYLE=-16);
boolean caption = (style & WS_CAPTION /*0x00C00000*/) == WS_CAPTION;
return cover >= 0.995 || (cover >= 0.96 && !caption);
```

补充判据：`SHQueryUserNotificationState()` 返回 `QUNS_RUNNING_D3D_FULL_SCREEN(3)` 或 `QUNS_PRESENTATION_MODE(4)` 时直接判全屏。

实测校准：本机浏览器最大化 `rect=-11,-11,2571,1379` vs 屏 `0,0,2560,1440` → `cover=0.974`、`caption=true` → **判为非全屏**（正确）。真全屏（独占/F11）`caption=false` 且 `cover≥0.995` → 判为全屏。

### 3.5 配置迁移

`PluginConfig`：

- 删 `alwaysOnTop`，新增 `public boolean coverFullscreen = false;`，键 `coverFullscreen`。
- `load()` 不读旧键 `alwaysOnTop`（旧值 true 正是用户抱怨的"盖住全屏"，照着读会让升级后行为不变，等于没修）。
- `save()` 仍同步写回 `alwaysOnTop = coverFullscreen`，保证降级到旧版插件时外观不跳变（与现有 `followSystemTheme`/`darkTheme` 的兼容写法一致）。
- `preference_config.json` 的「默认置顶」项改为键 `coverFullscreen`、标题「总在最前（盖住全屏窗口）」、`default_value: false`、summary 写明「不勾选时迷你条仍压在任务栏之上，但遇到全屏窗口会让位；勾选后连全屏窗口也盖住」。
- 右键菜单项标题保持「总在最前」，勾选态绑 `config.coverFullscreen`。
- `showCurrentSettings()` 的 toast 文案 `"置顶="` 改为 `"盖住全屏="`。

### 3.6 探针改造

- `preview-src/com/miniplayer/DragProbe.java`：`:63` 的 `grip x=/gripW=` 删除，改打印 `coverX=/coverSize=`；`:71` 与 `:117` 由「手柄区可拖」改为「封面左侧边缘可拖」；`:117` 后的 MOVE 光标断言保留（该处仍是可拖区域）。
- 新增 `preview-src/com/miniplayer/CoverProbe.java`：断言 `hitCoverAt` 在封面中心为真、在文字区为假；断言 `hitDragArea` 在封面区域为真（封面仍可拖）；断言 `Layout` 的 `coverX` ≤ 4（贴边）。
- 新增 `preview-src/com/miniplayer/LayerProbe.java`：断言 `WindowLayer.hwnd(...)` 对本进程窗口返回非 0（preview 环境下用 `JWindow` 即可）；断言 `trayCovers` / `fullscreenForeground` 调用不抛异常（返回值依环境，不作硬断言）。
- `preview.sh` 增量编译并串上这两个新探针。

## 4. 风险与缓解

| 风险 | 缓解 |
| --- | --- |
| FFM 在插件 classloader 下不可用 | 宿主 cfg 已带 `--enable-native-access=ALL-UNNAMED`；探针在同类加载器下已验证。即便如此，所有 FFM 入口都包 `try/catch Throwable`，失败退化为 Java `toFront()`，功能降级但不崩 |
| `SetForegroundWindow` 抢不到前台 | 多级兜底（`ShowWindow` 还原 → `BringWindowToTop` → `SetForegroundWindow`），每级记日志；最差情况是主窗被还原但仍在后面，用户再点一下就出来 |
| 全屏判定误判导致迷你条突然消失 | 判定要求 `cover≥0.995` 或 `cover≥0.96 且无标题栏`，普通最大化（`cover=0.974` 且有 caption）不会被误判；且降级不隐藏窗口，只换层级 |
| 用户已有配置 `alwaysOnTop=true` | 刻意不迁移（见 3.5），升级即得到新默认行为 |
| 物理/逻辑像素换算错 | 探针已确认 `HWND 尺寸 = 逻辑 × scale`；`hwnd()` 用 `Math.round` 换算后再匹配，匹配失败返回 0 而不是猜一个窗口 |

## 5. 验证清单

1. `bash build.sh` → 产出 `plugin-com.miniplayer.desktop-mini-player-1.0.28.spmod`。
2. `bash preview.sh` → 全部探针绿；`preview/` 出图确认左侧无竖杠、封面贴边。
3. `VERSION.txt` = `1.0.28`；`install-latest.bat` 三处版本号同步。
4. 人工核对清单（装到宿主后）：
   - 左侧没有三条竖杠，封面贴左，文字区更宽。
   - 按住封面/文字/空白都能拖，三键与进度条照旧。
   - 双击封面 → 宿主主窗还原并前置；双击空白处 → 什么都不发生。
   - 不勾选「总在最前」：点任务栏、开开始菜单，迷你条不被盖；打开全屏视频 → 迷你条被盖住；退出全屏 → 重新压住任务栏。
   - 勾选「总在最前」：全屏时仍盖住。
   - 反复点任务栏 + 右键菜单开合：不闪、不消失。

## 6. 实施记录（落地时与计划的偏差）

计划里写的 `WindowLayer.hwnd(Window)` 实际落成 `WindowLayer.hwndOf(Window)`，避免与方法名 `hwnd` 和局部变量混淆。

FFM 落地踩到两个只在运行时炸、编译期毫无提示的坑，均已修复并在源码注释里留了警告：

1. **`invokeExact` 的返回值必须接住**。`EnumWindows` 与 `GetWindowThreadProcessId` 返回 `int`，写成语句（`hXxx.invokeExact(...)`）会被解析成返回 `void` 的方法类型，运行时抛 `WrongMethodTypeException: handle's method type (MemorySegment,MemorySegment)int but found (MemorySegment,MemorySegment)void`。而这两处原本都在 `catch (Throwable)` 里静默返回空，症状表现为「枚举到 0 个窗口」，极难定位。
2. **三元表达式会把 `MemorySegment` 提升成 `Object`**。`hSetWindowPos.invokeExact(h, on ? HWND_TOPMOST : HWND_NOTOPMOST, ...)` 的方法类型变成 `(MemorySegment,Object,...)`，与 `FunctionDescriptor` 的 `(MemorySegment,MemorySegment,...)` 不匹配，同样抛 `WrongMethodTypeException`——表现是「`setTopmost` 恒返回 false」，但直接用 FFM 调一次同样参数却返回 1。修法是先赋给 `MemorySegment after = on ? ... : ...;` 再传。

另外 `DropIndicator.showAt` 原本每帧 `toFront()`，而它在拖动时每帧都会被调用——这与主窗「250ms `toFront()` 导致闪烁」是同一类问题。改为只在首次由不可见转可见时抬一次。

## 7. 1.0.28 试装后的三条回修（2026-11-12）

用户把 1.0.28 导进真实宿主后报了三条新问题，根因全部落在同一批"看起来更宽松更稳"的改动上：

### 7.1 点歌词空白处会切歌 / 暂停

日志（`%TEMP%mini-player.log`）里 68 条 `click raw` 中，`x=139..201`、`y=15..27` 这些落在文字区（`textX≈33`、`textRight≈216`）的点被判成 `hit=1/2/3`。

两处叠加：

1. `candidates(Point raw)` 里有个 `raw * scale` 候选。本机 `scale=1.5`，点歌词区 `x=166` 被放大成 `249`，正好贴到 `playCx=252` → 一次点击变成"暂停"。**已删掉该候选**，只保留 `raw` 与 `raw / scale`。
2. `hitButtonAt` 的兜底带原为 `y ∈ ctrlY±(playRadius+6)`、`dx <= halfs[i]+6`，在 40px 高的条上等于"整个高度、从 `prevCx-14` 一直到右边缘"全是按钮。收紧到 `±2`。
3. 另外 `prev/next` 的圆形热区半径原为 `sideBtnBox/2 + 8 = 16`，而 `textRight = prevCx - 16` —— 文字区右端与"上一首"热区相切。收到 `+5`（半径 13），留出 3px 缓冲。

`HitProbe` 新增断言：文字区若干点（含 `textRight` 本身）必须判成 `HIT_NONE`，并因此从"纯输出型"探针升级为会失败退出的断言探针。

### 7.2 单击封面没反应 + 封面各处手感不一致

`mouseReleased` 的 `HIT_NONE` 分支原本要求 `e.getClickCount() >= 2`，单击毫无反馈；而单击封面又会进 `dragging=true`（因为 `hitDragArea` 认为封面可拖），光标是 MOVE。两条叠加就是用户说的"有些位置是点击，有些是拖动"。

- 去掉 `clickCount >= 2`：封面单击即唤起。封面上没有别的动作可做，且 `sameSpot` 仍保证"按住拖"不被误判成点击。
- `mouseMoved` 给封面单独判 HAND 光标（`onCover`），其余可拖区域仍是 MOVE。

### 7.3 跟任务栏其他组件交互仍闪烁

`syncLayer()` 原判据是 `if (flip || trayAbove)`，其中 `trayAbove = wantTop && WindowLayer.trayCovers(selfHwnd)`。而 `trayCovers` 沿 `GW_HWNDPREV` 上溯找 `Shell_TrayWnd`——Win11 的任务栏本身在 TOPMOST 层，这个判据在"迷你条与任务栏图标区不重叠"的常见布局下**恒为真**。日志里 `layer topmost=true fs=false flip=false trayAbove=true count=1..23` 连续 23 次，就是每秒一次 `SetWindowPos`。

改为 `if (flip)`：只在期望值真的翻转（进出全屏、切换勾选）时才动。`trayAbove` 保留但只进日志当诊断。

### 7.4 版本与验证

`1.0.29`。`bash build.sh` 通过（1808228 字节），`bash preview.sh` 退出码 0，9 个探针全绿（`dock / hit / drag / cover / layer / karaoke / sim / lyric sync / lyric scroll`），`verify.VerifySpmod` 校验通过。

## 8. 1.0.29 试装后的两条回修（2026-11-12）

用户装 1.0.29 后确认"单击任务栏不再被覆盖"，但暴露出两个新问题。7.3 那条"改成 `if (flip)`"其实是**把一个坏判据整个关掉**，而不是修好它——这直接导致了 8.1。

### 8.1 迷你条被任务栏盖住

7.3 把 `trayAbove` 从触发条件里拿掉之后，迷你条就再也不自愈了：用户点任务栏，任务栏抢到 z 序顶端，迷你条**永久**沉到它下面，直到下次进出全屏或切换勾选才会重排。

真正的问题不在"用不用 `trayAbove`"，而在**判据本身是错的**：

```java
// 旧实现（错）
long cur = hwnd;
for (int i = 0; i < 256; i++) {
    cur = GetWindow(cur, GW_HWNDPREV).address();  // 上一个（更靠前的）窗口
    if (cur == trayHwnd) return true;
}
```

`GW_HWNDPREV` 链是**全局连续**的：从我们的窗口往上走会先穿过整个 TOPMOST 层、再进入普通层、最后到顶，而 Win11 的任务栏就挂在最顶上。所以"途中遇到任务栏"与"我们有没有压住它"无关，恒为真。

改成**按 z 序索引比较**——`EnumWindows` 的返回顺序就是 z 序（从最顶上一个往下），谁先出现谁在上面：

```java
// 新实现（对）
for (long h : enumWindows(arena)) {
    if (h == trayHwnd) return true;   // 任务栏在更上层
    if (h == hwnd)     return false;  // 我们排在任务栏前面
}
return false;
```

于是 `syncLayer()` 可以恢复 `if (flip || trayAbove)`：正常"压住任务栏"时 `trayAbove` 返回 false，不重排；真被挤下去时才补一次 `SetWindowPos`，补完即恢复 false，**自愈且不抖**。这也正好是"点任务栏后不消失"与"交互时不闪"两个要求的交点。

`LayerProbe` 新增两条断言把语义钉住：挂 TOPMOST 时 `trayCovers` 必须为假、摘掉后必须为真。

### 8.2 点封面打开一个"奇怪的新窗口"

日志：

```
11:40:00.056 click raw=Point[x=21,y=19] ... hit=0 sameSpot=true drag=true
11:40:00.057 cover click: open host window
11:40:00.123 host.activate hwnd=0x2d0560 iconic=0 show=0
11:40:00.128 host.activate bringToTop=1 foreground=1
```

`show=0` 是决定性的：`ShowWindow(SW_RESTORE)` 返回 0 表示**这个窗口在调用前是隐藏的**——我们把它显示了出来。而且每次点封面 hwnd 都不一样（`0x191188` → `0x66707ba` → `0x2d0560`）。

根因在 `findMainWindowHwnd` 的筛选条件：只看「类名 `SunAwtFrame` + 无属主 + 面积最大」。Compose/Skiko 会在宿主进程里生成一批辅助 `SunAwtFrame`（拖拽代理、输入法宿主、离屏合成代理），它们无属主、面积时大时小，谁撞上"最大"就被挑中，然后被 `SW_RESTORE` 硬显示出来。

两条硬条件补上：

- **必须当前可见**（`IsWindowVisible`）——隐藏的窗口不是用户认知里的"软件窗口"，显示它等于凭空造窗；用户把主界面关掉后再点封面，应当安静无事，日志记 `host.activate skipped: ... not visible`。
- **必须有非空标题**（新增 `GetWindowTextW` 绑定）+ **面积 ≥ 200×200**（`MAIN_WINDOW_MIN_AREA`）——辅助帧要么无标题，要么只有几十像素见方。

`restoreAndActivate` 也改成只在 `IsIconic` 为真时才调 `SW_RESTORE`；正常显示的窗口只做 `BringWindowToTop` + `SetForegroundWindow`，日志区分 `already shown` 与 `iconic=… show=…`。

定位失败时（`best == 0L`）会 dump 本进程所有顶层窗口的 class / title / visible / owner / rect，下次真机验证可以直接看到宿主的真实窗口结构，不用再猜。

### 8.3 版本与验证

`1.0.30`。`bash build.sh` 通过（1808925 字节），`bash preview.sh` 退出码 0，9 个探针全绿，`verify.VerifySpmod` 校验通过（`version = 1.0.30`）。真机行为待用户复装后确认。

## 9. 第四轮：`trayCovers` 判据二修、层内提权与 EDT 死锁

1.0.30 在真机上把**两处修法都做错了一遍**，这一节是实测纠正。用户原话（m01450）：

> 悬浮窗消失的问题还是没怎么改善，可以通过更换技术栈，解决吗
> 双击封面不会打开软件窗口，反而会导致其卡死

### 9.1 要不要换技术栈

**不换。** 三条症状全部来自**具体 Win32 API 的用法错误**，与 GUI 框架无关：

- z 序判据错 → 任何技术栈都得自己判"我是不是被任务栏压住了"，都会遇到同一个陷阱；
- `GetWindowTextW` 跨线程同步等待 → 这是 `SendMessage(WM_GETTEXT)` 的定义行为，换成 Electron（`EnumWindows` + `GetWindowText`）、Tauri（`windows` crate）、WPF（`WindowInteropHelper`）会一行不差地重犯；
- 屏外"假最小化"到 `-32000` → 宿主自身的行为，任何外部唤起方都要处理。

换栈还会额外赔上：宿主是 Java + Compose/Skiko，只有同进程插件能拿到 `Playback`/`Lyrics`/`MediaItem` 这些真实状态；外部程序只能靠窗口标题与进程内存去猜，功能面立刻塌一半。**结论：把 API 用对，比换栈的收益大一个数量级。**

### 9.2 `trayCovers` 的两次错法，与正确判据

| 版本 | 实现 | 结果 |
| --- | --- | --- |
| 1.0.27 及以前 | `GW_HWNDPREV` 上溯找 `Shell_TrayWnd` | 判据其实是对的，但被当成"必须重排"的信号 → 每秒 `SetWindowPos` → 闪 |
| 1.0.29 | 干脆删掉触发条件 | 不自愈，被任务栏挤下去后**永久**沉底 |
| 1.0.30 | 按 `EnumWindows` 返回顺序比较索引 | **恒为 true**，仍是每秒重排 → 还是闪 |

1.0.30 错了是因为一个想当然的假设：「`EnumWindows` 的返回顺序就是 z 序」。实测（`build/probe4/ZOrderProbe.java`）直接推翻：

```
tray=0x101a8 enum total=963 trayIdx=10 progmanIdx=691
```

任务栏排在第 10 位，而普通层的一个窗口排在第 691 位 —— 索引顺序只反映创建顺序，与 z 序无关。

正确的判据反而是最初那个 `GW_HWNDPREV` 上溯链，`ZProbe2.java` 用**物理坐标+物理尺寸精确匹配**自身窗口后测出两条基准线：

```
[普通]      exstyle=0x8080000 上溯 80 层遇到任务栏（任务栏在上）
[TOPMOST]   exstyle=0x8080008 上溯 13 层到顶，未遇任务栏（我们在任务栏之上）
```

之前误判「上溯链恒真」，是因为探针用"宽 200–500px"这种模糊条件匹配窗口，选中了 `SunAwtToolkit`（202×56）这个噪声窗口 —— **探针自己选错了被测对象**。教训：匹配自身 HWND 必须用 `getLocationOnScreen()` × scale 换成物理矩形后精确比对。

### 9.3 为什么"重新挂 TOPMOST"治不了被压

`SetWindowPos(hwnd, HWND_TOPMOST, ...)` 对**已经挂在 TOPMOST 层**的窗口是层内空操作：层级属性本来就是 topmost，调用等于什么都没改，z 序也不会动。所以「判到被压住 → 重新挂 TOPMOST」会无限循环，用户看到的就是"窗口一直待在任务栏下面，还每秒抖一下"。

层内提权要用 `HWND_TOP`（`MemorySegment.ofAddress(0L)`）：

```java
// WindowLayer.raiseWithinTopmost
if (raiseWith(hwnd, HWND_TOP) && !trayCovers(hwnd)) return true;
// 退路：万一 HWND_TOP 不生效，摘掉再挂回去也能把 z 序顶起来
raiseWith(hwnd, HWND_NOTOPMOST);
raiseWith(hwnd, HWND_TOPMOST);
return !trayCovers(hwnd);
```

`StableProbe.java` 同时证明了一件对本设计很关键的事：**TOPMOST 一旦设置就永久自持**，别的 TOPMOST 窗口前置、静置 5 秒、关闭邻居窗口都不会把它挤下去：

```
--- 另一个 TOPMOST 窗口反复前置 ---
  第1..4次后: 在任务栏之上(上溯 14 层到顶)
--- 静置 5 秒，每秒采样一次（不调任何 API）---
  第1..5秒: exstyle=0x8080008 在任务栏之上(上溯 13 层到顶)
```

所以 `syncLayer()` 只做**只读检查**（1 秒一次），只有两种情况才动手：

- `flip`：期望层级翻转（进出全屏、切换勾选）→ `setTopmost()`；
- `trayAbove`：真被挤下去了 → `raiseWithinTopmost()`，成功后计数归零，下一次检查即返回 false，**不持续重排**。

另加熔断 `LAYER_RAISE_LIMIT = 5`：万一判据又出错，连续 5 次提不动就停手并只喊一次 `layer raise gave up`，把"每秒抖"限制成"抖 5 次"，不会再出现 1.0.28 那种持续闪烁。

顺手排掉的另一条路：`SetWindowBand`（`user32` 序号 888）看起来能把窗口插到任务栏正上方，实测 band 0/1/2/3/15/17/18/21 **全部返回 `hr=0` 而 `GetWindowBand` 返回 -1**，z 序毫无变化 —— 现代 Win11 上这条路已经废了，不采用。

### 9.4 卡死：`GetWindowTextW` 在 EDT 上等别人的线程

宿主自己写下的判决书（`workshop/plugins/safe-mode-session.properties`）：

```
failure.kind=TERMINATED_HANG
failure.pluginId=com.miniplayer.desktop-mini-player
outcome=UNRESPONSIVE
```

时间点对得很准：日志停在 `dumpOwnFrames` 的最后一行 `frame 0x4b08a4 class=GDI+ Hook Window Class ...`，24 秒后宿主判定 hang。

机理：`GetWindowTextW` 对**非本线程拥有的窗口**会 `SendMessage(WM_GETTEXT)` 并同步等待对方线程处理。这条链路当时整个跑在 AWT EDT 上（`mouseReleased` → `openHostWindow` → `findMainWindowHwnd` → `dumpOwnFrames` → 逐个窗口读标题），而宿主里 Compose/Skiko 的辅助帧、`TRAY`、`GDI+ Hook Window`、IME 宿主各由别的线程托管 —— EDT 在等它们，它们又可能在等 EDT，于是死锁。

两刀切干净：

1. **标题改读 `InternalGetWindowText`**（`user32` 导出表序号 554，已用 PE 解析确认带名字）。它直接读窗口结构体的标题、**不发任何消息**，不会阻塞。`GetWindowTextW` 的绑定直接删除，常量区留注释「任何在 EDT 上运行的路径都不得调用」。探针实测标题读取耗时 5ms。
2. **整条唤起链路移出 EDT**：`openHostWindow()` 只做一件事——`AtomicBoolean` 去重 + 起 `miniplayer-host-activate` daemon 线程，然后立刻返回。EDT 上实测耗时 2ms。

`dumpOwnFrames`（全进程遍历 + 每行一次 open/write/close 的日志 IO）也改成默认关闭，只有 `-Dminiplayer.debug.frames=true` 时才输出。

### 9.5 顺带修掉的"消失"

用户说的"消失"有两个来源，之前都没有兜底：

- **假最小化**：宿主关闭主界面不是 `IsIconic`，而是把窗口移到 `-32000,-32000`（Windows 老惯用法）。日志里 `frame 0x3212f8 title=[夏天的风 - 温岚] visible=true rect=-32000,-32000,-31763,-31961`。旧逻辑：`isVisible` 为真、面积 237×39 又过不了 200×200 门槛 → `host.main not found` → 点封面静默无动作。现在 `findMainWindowHwnd` 把"坐标在屏幕外"当成合法候选，`restoreAndActivate` 新增一级：`MonitorFromWindow` 取 `rcWork`、居中 `SetWindowPos(..., HWND_TOP, x, y, 0, 0, SWP_NOSIZE | SWP_SHOWWINDOW)` 拉回屏内，再补一次 `SW_RESTORE`。
- **迷你条自己被丢到屏外**：新增 `onTick` 的"5b) 离屏自愈"，`!visibleAreaIntersects(getX(), getY())` 时记日志并 `recenter()`。

诊断日志也补齐了：`disposeWindow()` 记 `window disposed`、`hideMini()` 记 `hide mini`，下次能从日志分清"窗口被销毁"与"窗口还在但看不见"。

### 9.6 探针与验证

`LayerProbe` 新增：`raiseWithinTopmost` 返回成功、提权后 `WS_EX_TOPMOST`(0x8) 仍在、提权后 `trayCovers` 为假、连续提权 3 次仍稳定、标题读取 <200ms。

`CoverProbe` 新增：单击封面**调度耗时 <200ms**（否则说明又回到 EDT 同步链路），实测 23ms。

新建 `ActivateProbe`：在 EDT 上跑 `openHostWindow()`，用 `CountDownLatch` + watch dog 200ms 卡住即判失败；再断连续点两次不阻塞 EDT、`HostWindow.activate` 找不到主窗时安静返回 false。实测 2ms / 2ms。

`VERSION.txt` → `1.0.31`，`install-latest.bat` 三处同步。`bash build.sh` 通过（1810723 字节），`bash preview.sh` 退出码 0（10 个探针全绿），`verify.VerifySpmod` 校验通过（`version = 1.0.31`）。

真机验收项（用户复装后确认）：

- **A1** 反复点任务栏、任务栏图标、时钟、托盘、开始菜单 → 迷你条始终压在任务栏之上，不消失；
- **A2** 全程无闪烁，尤其无"每秒一下"的规律抖动；
- **A3** 单击封面 → 主窗在屏内则提到前台、最小化则还原、被丢到 -32000 则**拉回屏内**并提到前台；
- **A4** 单击封面绝不卡死（宿主不再写 `TERMINATED_HANG`），响应 <300ms；
- **A5** 主窗确实不存在时安静无事（日志 `host.main not found (candidates=N)`）；
- **A6** 不勾选「总在最前」时压任务栏但不盖全屏，勾选后盖全屏；
- **A7** 关掉宿主主界面后迷你条仍在、仍压任务栏。

## 10. 第五轮（1.0.32）：歌词句末差半个字、暂停时隐藏、锁定收口、删三个开关

用户 2026-11-20 提了五条（原文）：

> 1、卡拉ok歌词总有差不多半个字没滚到就下一句了
> 2、增加一个可选选项，播放时显示、未播放时隐藏。
> 3、增加锁定选项，关闭右键菜单和拖动的交互。
> 4、插件配置页的贴边吸附、可停靠到任务栏两个选项不需要了
> 5、删除"总在最前"选项

两个边界由用户在提问后确认：**②只藏"暂停"**（缓冲中、播放结束、没有曲目都不藏）；**④⑤只删开关、行为固定**（吸附与停靠恒定开启，「总在最前」删掉后固定为"不盖全屏"）。

### 10.1 歌词句末差半个字（第 1 条）

`lyricScrollFor(edge, textW, maxW)` 原本是：

```java
double want = Math.min(edge - (maxW - LYRIC_SCROLL_MARGIN), span);
```

`LYRIC_SCROLL_MARGIN = 24` 的意思是"演唱边界距可视区右缘还有 24px 就不再滚了"。24px 在这个字号下约等于两个字宽，而句末最后那段往往是标点，按 `refineCells` 的权重只占 0.25 个字的时间——于是最后那半个字永远赶不上，换行先到。

三处改动：

- `LYRIC_SCROLL_MARGIN` 24 → **8**（只留一点点余量，够抗锯齿用）；
- `lyricScrollFor` 增加句末兜底：`if (edge >= textW - 2) { return span; }` —— 边界一旦贴到行尾，直接滚到底，不再受余量限制；
- `LyricScrollProbe` 的 `MARGIN` 同步为 8，并新增断言「边界贴到行尾时强制滚到底」。

实测度量（`LyricScrollProbe`）：`maxW=249 longW=352 midScroll=40 endScroll=103 rightAfter=291`（= `left + maxW - 8` 附近），说明滚到上限后右边缘确实还在窗口内。

### 10.2 暂停时隐藏（第 2 条）

`PlayerState` 只有 `playing` / `buffering` 两个布尔，分不出"用户按了暂停"与"播完自然停"。所以新增三个字段：

- `private State lastHostState = State.Idle;` —— `onStateChanged` 里存原始枚举（宿主只有 `Idle/Buffering/Ready/Ended` 四值）；
- `private boolean hasEverPlayed;` —— 没播过谈不上"暂停"；
- `private boolean hidePausedHandled;` —— 只撤销"因暂停而藏"的那一次。

`applyPausedVisibility()` 的判据：

```java
boolean paused = hasEverPlayed && !state.playing && !state.buffering && lastHostState != State.Ended;
```

首行是 `if (!isVisible() && !hidePausedHandled) return;` —— 用户自己按"隐藏迷你条"藏起来的，绝不能被恢复播放弹回来（这是 `ConfigApplyProbe` 早就守住的同类契约）。开关关掉时若当前隐藏是它造成的，立刻 `showWindow()` 补回来。

### 10.3 锁定收口（第 3 条）

锁定以前只挡拖动（`mousePressed` 里 `if (config.locked) return;` 位于 `showMenu` **之后**），右键菜单照弹。现在：

- `mousePressed` 的 `isPopupTrigger()` 分支首行加 `if (config.locked) return;`；
- `mouseReleased` 的同名分支也加一遍（Windows 的 popup 可能在按下或松开任一侧触发，两条都要拦）；
- `toggleLock()` 顺手 `setCursor(Cursor.getDefaultCursor())`，否则锁定后光标还留着 MOVE 手型，看着像还能拖；
- 设置面板补 `locked` 开关（原本只有右键菜单里能切）。

`LockProbe` 是新的回归防线：未锁时右键不误触发拖动/进度条；锁定后按下与松开两条 popup 路径都不弹菜单；锁定不影响播控三键与进度条。

### 10.4 删三个开关（第 4、5 条）

`preference_config.json` 11 项 → 9 项，删 `coverFullscreen`（总在最前）、`snapEdges`（贴边吸附）、`dockToTaskbar`（可停靠到任务栏）。`MiniPlayerWindow` 里对应的分支全部拆掉：

- `snapToEdges()` 首行 `if (!config.snapEdges) return;` 删掉；
- `updateDockPreview()` 里 `if (!config.dockToTaskbar) { hideIndicator(); ... return; }` 整段删掉；
- `mouseReleased` 的 `Rectangle bar = config.dockToTaskbar ? taskbarRect() : null;` → `Rectangle bar = taskbarRect();`；
- `syncLayer()` 的 `wantTop = !fs || coverFullscreen` → `wantTop = !fs`，hwnd 取不到时的退化分支也从 `setAlwaysOnTop(coverFullscreen)` 改为 `setAlwaysOnTop(false)`（宁可让全屏盖住，也不要糊在画面上）；
- `togglePin()` 整个方法删除，右键菜单里的「总在最前（盖住全屏窗口）」整段删除。

`PluginConfig.save()` 仍写 `alwaysOnTop=false` / `snapEdges=true` / `dockToTaskbar=true`，只是不再读——降级回旧版本时行为不跳变。

### 10.5 验证

`VERSION.txt` → `1.0.32`，`install-latest.bat` 三处同步。`bash build.sh` 通过（1810724 字节），`bash preview.sh` 退出码 0（**12 个探针全绿**：dock / hit / drag / cover / layer / activate / karaoke / sim / lyric sync / lyric scroll / lock / paused-hide），`verify.VerifySpmod` 校验通过（`version = 1.0.32`、`extensions = [com.miniplayer.MiniPlayerPlaybackExtension]`）。

真机验收项（用户复装后确认）：

- **B1** 长句歌词唱到句末时，最后那个字（含标点）必须滚进可视区才换行；
- **B2** 勾上「暂停时隐藏」：暂停立刻收起、恢复播放立刻出现；缓冲中与播放结束都不收起；手动隐藏后不会被播放弹回；
- **B3** 勾上「锁定位置」：拖不动、右键不弹菜单，但播控三键与进度条照常可用；取消锁定后两者恢复；
- **B4** 设置面板只剩 9 项，没有「总在最前」「贴边吸附」「可停靠到任务栏」；功能上吸附与停靠仍然生效；
- **B5** 全屏视频/游戏时迷你条被盖住（「总在最前」语义固定为不盖全屏）。

## 11. 第六轮（1.0.33）：歌词句末真的滚到底、歌词态播控常显

用户原话（m03037）：「1、是到下一句了但还有一点点不会滚到。 2、歌词态也需要显示上下曲、暂停控件。」

### 11.1 为什么 1.0.32 的「余量 24→8 + 句末强制到底」还不够

1.0.32 做了两件事：把 `LYRIC_SCROLL_MARGIN` 从 24 降到 8，以及在 `lyricScrollFor` 里加了
`if (edge >= textW - 2) return span;`。用户复测仍然「还有一点点不会滚到」，原因在**边界本身追不上**：

`lyricEdgeFor` → `smoothEdge` 是系数 0.85 的指数平滑：

```java
edgeShown += (target - edgeShown) * 0.85;
if (Math.abs(target - edgeShown) < 0.4) edgeShown = target;
```

它永远落在目标后面。句末最后那段通常是标点，按 `refineCells` 的权重只占 0.25 个字的时间，
目标在这段时间里推进的像素本来就少，滞后量却按比例存在——于是 `edge >= textW - 2` 这个
「贴到行尾」的条件在换行前那几个 tick 里始终没成立，滚动量停在差十几像素的地方。

**结论：靠边界判断收尾不可靠，必须引入与边界无关的时钟判据。**

### 11.2 修法：`lyricFinishScroll` 收尾曲线

```java
private static final long LYRIC_SCROLL_FINISH_MS = 400L;

private int lyricFinishScroll(LyricsLine line, int textW, int maxW) {
    int span = textW - maxW;
    if (span <= 0 || line == null) return 0;
    long te = line.getEndTime();
    if (te <= 0L) return 0;
    long remain = te - karaokePosMs();
    if (remain <= 0L) return span;
    if (remain >= LYRIC_SCROLL_FINISH_MS) return 0;
    double late = remain / (double) LYRIC_SCROLL_FINISH_MS;
    double ratio = 1d - late * late;
    return (int) Math.min(span, Math.floor(span * ratio));
}
```

`paintLyric` 里与边界驱动的结果取较大值，两边都不越界：

```java
lyricScroll = Math.max(lyricScroll, lyricFinishScroll(line, textW, maxW));
```

两个设计要点：

- **曲线取平方而不是线性**。收尾窗口只有 400ms，线性到换行那一刻还剩 0%（理论上是够的），
  但实测「临近换行（差 40ms）」时线性只到 94%，对一个 103px 的 `span` 就是差 6px——
  用户说的正是这 6px。`1 - late²` 在最后 100ms 冲完，换行前一帧就贴到上限。
  （第一次实现用的就是线性，`LyricScrollProbe` 的「临近换行时几乎滚到底」当场 FAIL，说明断言有效。）
- **时钟用 `karaokePosMs()` 而不是 `state.positionMs`**。演唱边界本身就提前了
  `KARAOKE_LEAD_MS = 200`，收尾曲线若按原始位置算，就会比边界晚 200ms 才走完，
  正好卡在换行那一刻——等于把「全亮」的停顿吃掉。探针喂给 `lyricFinishScroll` 的
  `positionMs` 因此要再提前 200ms，这条偏移也写进了 `LyricScrollProbe` 的注释。

`LYRIC_SCROLL_MARGIN` 同时从 8 改成 0：余量归零后边界可以贴到右缘，正常的长句滚动也不
再留那 8px 的「提前刹车」。

### 11.3 歌词态播控常显（第 2 条）

`keepButtonsWithLyric()` 从

```java
return config != null && config.showControlsInLyric && hasLyric();
```

改为

```java
return hasLyric();
```

—— 歌词态固定保留上一首 / 播放暂停 / 下一首，`showControlsInLyric` 开关从设置面板、
`PluginConfig`、`applyConfigOnEdt` 里全部删除。`save()` 仍写 `showControlsInLyric=true`
供降级兼容（与 `alwaysOnTop` / `snapEdges` / `dockToTaskbar` 同一处理方式）。

面板 9 项 → 8 项。`Preview` 的出图也顺势收敛：原先「歌词态（无播控）」与「歌词态 + 播控」
两组共四张图，现在歌词态本身就是带播控的，只剩 `mini-lyric.png` / `mini-lyric-dark.png` /
`mini-lyric-karaoke.png` / `mini-lyric-karaoke-dark.png`，`sheet.png` 从 13 格减到 11 格。

### 11.4 验证

`VERSION.txt` → `1.0.33`，`install-latest.bat` 三处同步。`bash build.sh` 通过
（1810777 字节），`bash preview.sh` 退出码 0（**12 个探针全绿**），`verify.VerifySpmod`
校验通过（`version = 1.0.33`、`extensions = [com.miniplayer.MiniPlayerPlaybackExtension]`）。

`LyricScrollProbe` 新增「1b. 句末收尾加速」8 条断言（离句末还早不介入、窗口外不介入、
收尾过半已过一半、不越界、临近换行几乎滚到底、已过句末给满、不超宽不介入、无时间轴不介入）。

真机验收项（用户复装后确认）：

- **C1** 长句唱到句末时，行尾最后一个字（含标点）必须完整露出来再换行——不再「差一点点」；
- **C2** 鼠标移开进入歌词态后，上一首 / 播放暂停 / 下一首三个键仍然在，歌词自动为它们让出右边距；
- **C3** 设置面板只剩 8 项，没有「收起时也显示播控」。

## 12. 第七轮（1.0.34）：把「余量」换成「提前量」

### 12.1 1.0.33 为什么是错的

用户复装 1.0.33 后的原话：「你改成了提前进入下一句歌词了，还没唱完本句。我需要说的
问题是一句歌词中最后一截不会滚动到。」

`lyricFinishScroll` 在最后 400ms 按 `1 - (remain/400)²` 把位移顶向 `textW - maxW`。
超宽行 `span` 通常 90–110px，这在 400ms 内一次性窜完，观感就是「整行往前一冲」——
用户读成「提前进了下一句」。更根本的是修错了方向：**问题从来不是「最后没滚完」，
而是「最后那一截在这行的时间里没被看见」**。

机制：最后一个 cell 的 `endTime` 就等于本行结束时间，边界要到那一刻才推进到行尾，
而画面在同一刻已经换行。所以按「边界贴住右缘」这种**跟着边界**的策略，行尾那一截
在本行的时间预算里压根没有出场机会。留余量（24 → 8）只会让这件事更晚发生。

### 12.2 修法：位移 = 边界 + 一个汉字宽

```java
static int lyricScrollFor(double edge, int textW, int maxW, int lookahead) {
    int span = textW - maxW;
    if (span <= 0 || Double.isNaN(edge)) return 0;
    double want = Math.min(edge + Math.max(0, lookahead) - maxW, span);
    if (want <= 0d) return 0;
    return (int) Math.min(span, Math.floor(want));
}
```

`lookahead` 取 `FontMetrics.charWidth('字')`（`lyricLookahead(fm)`），不写死像素：
字号随任务栏高度变化（`applyFonts()`），写死会在大任务栏上不够、小任务栏上过头。

效果：镜头走在边界前面一格。边界还差一个字到行尾时，位移已经顶在 `span` 上——
**行尾完整露出来并停住，最后一个字是边亮边被看清的**，然后才换行。全程滚动速度不变，
没有冲刺，也不会「提前进下一句」。

删除的东西：
- `LYRIC_SCROLL_FINISH_MS = 400L` 与整个 `lyricFinishScroll(...)`；
- `lyricScrollFor` 里的 `edge >= textW - 2 → return span` 兜底（提前量已覆盖这条路径）；
- `LYRIC_SCROLL_MARGIN` 保留为 `0d` 但降级成纯粹的语义文档（真实提前量走参数）。

### 12.3 探针

`LyricScrollProbe` 大改：`scroll(...)` 辅助多一个 `lookahead` 实参，反射签名改为
`(double,int,int,int)`；删除「1b. 句末收尾加速」8 条与 `finishScroll(...)` 辅助；
核心断言换成：

- 「边界距右缘还有一个字时不滚动」「边界刚好吃掉提前量时不滚动」——提前量的触发点；
- **「边界还差一个字到行尾时已滚到底」**——本轮的回归断言；
- 「提前量为 0 时退化成贴着边界滚」——保证参数化没有破坏旧语义；
- 三重循环里新增「边界进入最后一格后位移恒定在上限」（`tailInTime`）；
- 像素级新增「边界还剩一个字时行尾已在可视区内」：把实现给的位移喂给
  `render(..., clip=true)`，断言最右侧墨迹落在 `left+maxW-8 .. left+maxW+2`。

`Preview.renderLyricOverflow` 的采样点与注释同步（78.9s 出图 = 边界已进最后一格、
位移顶在上限）。`sheet.png` 仍是 11 格。

### 12.4 验证

`VERSION.txt` → `1.0.34`，`install-latest.bat` 三处同步。`bash build.sh` 通过
（`plugin-com.miniplayer.desktop-mini-player-1.0.34.spmod`，1810645 字节），
`bash preview.sh` 退出码 0（**12 个探针全绿**），`verify.VerifySpmod` 校验通过
（`version = 1.0.34`、`extensions = [com.miniplayer.MiniPlayerPlaybackExtension]`）。

`LyricScrollProbe` 度量：`maxW=249 longW=352 shortW=66 midScroll=40 endScroll=103
headInk=126225 restInk=52578 rightBefore=394 rightAfter=291 tailInk=9945`。

真机验收项：

- **D1** 长句唱到句末时，最后那一截（含句末标点）在本行结束前就完整出现并停住；
- **D2** 全程滚动速度均匀，没有末尾突然加速「窜过去」；
- **D3** 换行时机不变——仍然是这一行唱完才换，不提前。

---

## 13. 第八轮（1.0.35）：右键菜单补「暂停时隐藏」+ 歌词滚动诊断埋点

### 13.1 用户反馈

1.0.34 装到真机后，用户（2026-11-20 18:18）报：

1. 「我自己实际播放时时这样的，到这个位置直接就不动了」——两张截图分别是
   「如同我是过眼云烟」与「如直呼一面之缘的名字」，都停在句中偏右、行尾还差约一个字；
2. 「把暂停时隐藏选项加入右键菜单」。

第 2 条是纯功能补充；第 1 条在离线探针里复现不出来——`LyricScrollProbe` 的纯函数断言
和像素渲染断言全绿，说明 `lyricScrollFor` 本身没问题，问题出在**运行时喂给它的 `edge`
突然不涨了**。所以这一轮不猜，先埋诊断。

### 13.2 右键菜单补「暂停时隐藏」

`showMenu(MouseEvent e)` 里在 `lockItem`（锁定位置）之后、`darkItem`（深色主题）之前插入：

```java
JCheckBoxMenuItem pausedItem = new JCheckBoxMenuItem("暂停时隐藏", config.hideWhenPaused);
pausedItem.addActionListener(a -> {
    config.hideWhenPaused = pausedItem.isSelected();
    config.save();
    hidePausedHandled = false;
    applyPausedVisibility();
});
menu.add(pausedItem);
```

`hidePausedHandled = false` 是必须的：这个标志只记「本次隐藏是插件因暂停而做的」，
从菜单改开关时上一次的记账已经失效，清零后重算一遍，暂停中打开开关会立刻藏，
关掉开关会立刻回来。

### 13.3 滚动诊断埋点

`paintLyric` 在算出 `lyricScroll` 之后追加一行 `diagLyricScroll(text, textW, maxW, edge, lyricScroll, line)`，
实现（换行必打，同一行之后每 2 秒一行）：

```java
private void diagLyricScroll(String text, int textW, int maxW, double edge, int scroll, LyricsLine line) {
    long now = System.currentTimeMillis();
    boolean newLine = !text.equals(diagScrollForLine);
    boolean stale = now - diagScrollAt >= 2_000L;
    if (!newLine && !stale) return;
    diagScrollForLine = text;
    diagScrollAt = now;
    java.util.List<LyricsLine.Cell> cells = refineCells(line == null ? null : line.getLyricsCells());
    long firstCell = ...; long lastCell = ...;
    MiniPlayerPlugin.log(String.format(Locale.ROOT,
        "lyric scroll pos=%d edge=%.1f scroll=%d textW=%d maxW=%d span=%d line=[%d,%d] cellEnd=%d cells=%d text=%s",
        karaokePosMs(), edge, scroll, textW, maxW, Math.max(0, textW - maxW),
        line == null ? -1 : line.getStartTime(), line == null ? -1 : line.getEndTime(),
        lastCell, cells.size(), head));
}
```

读法（也写进了 README）：

- `edge` 停住、`pos` 还在涨 → **演唱边界不再推进**。首要嫌疑是 `timelineEdge` 里的
  `if (pos <= start || end <= start) break;`：任一 cell 的 `start` 晚于当前 `karaokePosMs()`
  就整体冻结。二次嫌疑是 `timelineUsable` 判假退回 `sweepEdge` 却又 `te <= ts` 返回 `NaN`，
  那样会走跑马灯循环而不是静止。
- `edge` 在涨、`scroll` 不动 → 位移已贴 `span` 上限，属正常滚到底。
- `cells=0` 或 `cellEnd=0` → 时间轴缺失，走的是整行匀速。

### 13.4 验证

`VERSION.txt` → `1.0.35`，`install-latest.bat` 三处（`SRC` / `DEST` / 清理循环的
`%%~nxD`）同步。`bash build.sh` 通过（`plugin-com.miniplayer.desktop-mini-player-1.0.35.spmod`，
1811245 字节），`bash preview.sh` 退出码 0（**12 个探针全绿**），
`verify.VerifySpmod` 校验通过（`version = 1.0.35`、`extensions =
[com.miniplayer.MiniPlayerPlaybackExtension]`，注意必须用 JDK25 的 `java.exe`，
系统 `java` 是 17，会报 `UnsupportedClassVersionError` 类文件版本 69.0）。

顺手删掉 `preview/mini-lyric-buttons.png` 与 `preview/mini-lyric-buttons-dark.png`
（1.0.33 起 `Preview.java` 已不再产出这两张对照图）。

真机验收项：

- **E1** 右键菜单出现「暂停时隐藏」，勾选后若正暂停则立刻收起，取消勾选立刻回来；
- **E2** 长句播放到文中卡住时，日志里出现 `lyric scroll pos=… edge=… scroll=… span=… cellEnd=…`
  行，据此判定 `edge` 是否停增，再决定下一版改 `timelineEdge` 还是改时间轴合流。

## 14. 第九轮（1.0.36）：长句其实从未滚动过 —— `lyricScrollFor` 改双下限

### 14.1 诊断埋点给出的结论，与前三轮猜测都不同

1.0.35 加了 `diagLyricScroll`，本来想区分「边界不再推进」和「位移被 `span` 钳住」。
用户装后复现，日志 `%TEMP%mini-player.log`
里 `lyric scroll` 共 104 行，结果第三种可能才是真相：

- **`scroll` 全部为 0，一行 `>0` 都没有** —— 长句从头到尾一次都没滚过；
- 104 行里 98 行 `span=0`（`textW <= maxW=157`，确实不该滚）；
- 仅 6 行 `span>0`：`textW=163/span=6/edge=68.5`、`textW=169/span=12/edge=55.6`、
  `textW=169/span=12/edge=186.2`、`textW=317/span=160/edge=126.8`、
  `textW=171/span=14/edge=120.9`、`textW=171/span=14/edge=116.9` —— 这 6 行 `scroll` 也是 0。

`edge` 是含 `left`（≈43）的绝对坐标。旧式 `want = relEdge + lookahead(11) - maxW`
要 `relEdge > 146`（即 `edge > 189`）才启动。第二长的 `textW=317` 那行到 `edge=126.8`
时 `want = 126.8 - 43 + 11 - 157 = -62` —— 差得远。**触发阈值太靠后，长句根本等不到。**

于是前三轮的三个假设全都不成立：

| 版本 | 做法 | 为什么不对 |
| --- | --- | --- |
| 1.0.32 | 余量 `LYRIC_SCROLL_MARGIN` 24 → 8 | 余量只影响刹车点，与"滚不滚得动"无关 |
| 1.0.33 | 句末 400ms 按时间顶到上限 | 确实会滚，但整行一次性窜过去，像提前进下一句 |
| 1.0.34 | 只加提前量 `lookahead` | 提前量把触发点前移一格，长句依旧要等到句末才越过阈值 |
| 1.0.36 | 触发点之后补按比例兜底 | 句中就开始滚，句末必然到底 |

之前理解的「差半个字」其实是「尾部整段从未出现」——尾部被 `clipRect` 直接裁掉了。

### 14.2 修法：`max(byEdge, byRatio)` 双下限

`spw-mini-player/src/main/java/com/miniplayer/MiniPlayerWindow.java` 的
`static int lyricScrollFor(double edge, int textW, int maxW, int lookahead)` 整体替换：

```java
int span = textW - maxW;
if (span <= 0 || Double.isNaN(edge)) return 0;
int ahead = Math.max(0, lookahead);
double trigger = maxW - ahead;
double want;
if (edge <= trigger) {
    want = 0d;
} else {
    double room = textW - trigger;
    double travelled = edge - trigger;
    double byEdge = edge + ahead - maxW;
    double byRatio = room > 0d ? span * (travelled / room) : span;
    want = Math.max(byEdge, byRatio);
}
if (want <= 0d) return 0;
return (int) Math.min(span, Math.floor(want));
```

- `byEdge`：边界始终留在可视区右缘（唱到哪跟到哪）；
- `byRatio`：从触发点起按「已走 / 剩余」线性分摊 `span`，保证句末一定到 `span`；
- 取较大值：速度均匀，不出现 1.0.33 那种末尾冲刺；
- `floor` 保留：宁可少滚半像素，也别把边界挤出右缘。

### 14.3 回归断言

`spw-mini-player/preview-src/com/miniplayer/LyricScrollProbe.java` 新增：

```java
int span0 = longW - maxW;
int halfway = scroll(scrollFor, (maxW + longW) / 2d, longW, maxW, lookahead);
check("边界到行中时已滚过半程", halfway > span0 / 2);
```

注释直接引用 1.0.36 真机日志 `textW=317/maxW=157/span=160` 却 `scroll=0` 的教训。
原有「边界还差一个字到行尾时已滚到底」保留，两条一起把"句中要动、句末要到底"钉住。

### 14.4 验证

- 探针实测：`textW=352 / span=195` 的长句在 `edge=303.3` 时 `scroll=114`（≈58%），
  修复前同位置是 `scroll=0`；`edge=395.0` 时 `scroll=195`（到上限）。
- `bash build.sh` → `build/libs/plugin-com.miniplayer.desktop-mini-player-1.0.36.spmod`（1811361 字节）。
- `bash preview.sh` EXIT=0，12 个断言探针全绿（dock / hit / drag / cover / layer / activate /
  karaoke / sim / lyric sync / lyric scroll / lock / paused-hide）。
- `verify.VerifySpmod` 通过：`version = 1.0.36`、
  `extensions = [com.miniplayer.MiniPlayerPlaybackExtension]`、「校验通过」。

真机验收项：

- **F1** 长句从第一个字唱到句末，画面持续左移，句末行尾那截完整露在窗口内；
- **F2** 滚动速度均匀，句中不会长时间停住、句末也不会突然窜一下；
- **F3** 不超宽的短句仍然完全不滚（`span=0`）；
- **F4** 日志里 `lyric scroll` 行的 `scroll` 出现大于 0 的值，且随 `edge` 单调增长到 `span`。
