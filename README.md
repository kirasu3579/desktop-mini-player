# 桌面迷你播放条 · Salt Player for Windows 插件

一个悬浮在桌面上的迷你播放条：不占任务栏、按住任意空白处即可拖动的无边框小窗，
实时显示当前曲目、进度与逐字歌词，并支持上一首 / 播放暂停 / 下一首 / 拖动进度。

- 插件 ID：`com.miniplayer.desktop-mini-player`
- 版本：见 `VERSION.txt`（每次改动 +1，构建脚本据此命名产物）
- 目标宿主：Salt Player for Windows（创意工坊 API `0.1.0-dev22`）
- 技术栈：Java 25 + Swing `JWindow` + Java2D（无第三方运行时依赖，只带 kotlin-stdlib / annotations）

## 安装

不想自己编译的话，直接从 [Releases](https://github.com/kirasu3579/desktop-mini-player/releases)
下载 `plugin-com.miniplayer.desktop-mini-player-<版本>.spmod`，从下面第 1 步开始做。

1. 关闭 Salt Player。
2. 把 `plugin-com.miniplayer.desktop-mini-player-<版本>.spmod` 解压到
   `%APPDATA%\Salt Player for Windows\workshop\plugins\com.miniplayer.desktop-mini-player-<版本>\`
   （也可直接把 `.spmod` 放进该目录，再用创意工坊或宿主插件页安装）。
   解压后目录结构应为：

   ```
   com.miniplayer.desktop-mini-player-<版本>/
   ├── META-INF/MANIFEST.MF      ← 只有 Manifest-Version
   ├── classes/                  ← 代码、真清单、extensions.idx、preference_config.json
   └── lib/                      ← kotlin-stdlib-2.4.20.jar、annotations-13.0.jar
   ```

   > **从旧版本升级**：插件 ID 已变更（旧 ID 里带了一个第三方产品名，从 1.1.0 起弃用）。
   > 装新版前请先删掉 `workshop\plugins` 下所有残留的 `plugin-com.miniplayer.*` 旧目录，
   > 否则 PF4J 会把新旧两个 ID 当成两个插件同时加载，桌面上出现两条播放条。
   > 旧配置（位置、主题、开关）不会自动迁移，装好后需重新设置一次。

3. 启动 Salt Player，插件会在启动时自动弹出迷你条（默认位于主屏右下角、避开任务栏）。

首次运行会申请 `library-read` 权限——**只用于读取封面图**。不授权也能正常播放控制，
只是封面位置显示占位音符（中性深灰方块，不跟随封面色）。

装好后显隐入口在 Salt Player 的**插件设置面板**里：「启用迷你播放器」这个开关改完立刻生效
（关掉悬浮条马上消失，打开马上出现，不用重启播放器），面板里另有「显示 / 隐藏迷你条」按钮。

> 为什么不把这一项加进 Salt Player 自己的托盘右键菜单：公开 API 里没有「往宿主菜单注册项」的
> 入口；Hook API 也够不到——宿主把菜单内容写死在 `AppTray` 的私有 Composable 里，而钩子解析器
> 要求目标类由插件的 `PluginClassLoader` 加载，宿主类不满足（会抛
> `Hook target must be visible to the host loader`）。因此显隐入口落在宿主本来就提供的设置面板上。

## 功能

| 区域 | 交互 |
| --- | --- |
| 封面 | 随曲目切换自动加载（需 `library-read`）；按设备缩放（1.5× 屏取 66px 位图）绘制，避免发虚；无权限/无封面时显示矢量音符占位。**单击封面唤起 Salt Player 主窗口**（最小化→还原、被丢到 -32000 屏外→拉回屏内、然后提到前台），悬停时封面光标变成手型。只认「可见 + 有标题 + 面积 ≥200×200 或在屏幕外」的 `SunAwtFrame`，所以关掉主界面后再点封面安静无事，不会凭空造出一个隐藏窗口 |
| 歌词 | 未悬停时双行显示：上行当前句（加粗）、下行下一句（更淡）；超宽时跟着演唱边界滚动到句末，行尾那截一定露出来，不截断；最后一句退回单行居中 |
| 卡拉 OK | 宿主给出逐字时间轴时，当前句按播放进度逐字点亮（已唱实色 / 未唱 42% / 正在唱的那个字按比例填充） |
| 歌名 / 歌手 | 悬停时显示，歌名超长自动跑马灯滚动，单行截断带省略号 |
| 上一首 / 播放暂停 / 下一首 | 左键单击；播放中显示暂停图标 |
| 进度条 | 悬停变粗并显示 `当前/总长` 时间气泡；左键拖动跳转播放位置 |
| 单击 | **只认封面**：单击封面唤起 Salt Player 主窗口。空白处不响应——播放/暂停与上下曲只由播控三键触发 |
| 进度推进 | 每秒向宿主拉一次真实位置，播放中以 16ms 节拍本地插值（`TICK_FAST_MS`，实测约 34fps 受绘制耗时限制），拖动时不跳变 |
| 拖动 | **除播控三键与进度条外，整条都能按住拖动**；指针位移 ≥4px 才算拖动，否则当点击 |
| 右键 | 锁定位置 / 暂停时隐藏 / 深色主题 / 从任务栏抽出 / 重置位置 / 隐藏迷你条。**锁定后右键菜单不再弹出**，拖动也一并失效，只留播控三键与进度条 |
| 显隐 | 设置面板的「启用迷你播放器」开关（**改完立刻生效**），或面板里的「显示 / 隐藏迷你条」按钮；另有可选「暂停时隐藏」——只藏暂停，缓冲中与播放结束都不藏 |

### 层级控制

任务栏 `Shell_TrayWnd` 本身是 TOPMOST 窗口，同层窗口的前后顺序由 `SetWindowPos` 的调用先后决定——用户一碰任务栏，它就排到迷你条前面，停靠的迷你条会被整个盖住。Java 的 `setAlwaysOnTop` 只有"全盖 / 全不盖"两态，做不到"压住任务栏但给全屏应用让位"。

现在交给 `WindowLayer`（`java.lang.foreign` 直调 Win32，宿主 cfg 已带 `--enable-native-access=ALL-UNNAMED`）：

- **常态**：迷你条挂 TOPMOST，于是能压住任务栏；
- **前台是真全屏时**（覆盖率 ≥0.995，或 ≥0.96 且无 `WS_CAPTION`；`SHQueryUserNotificationState` 返回 3/4 也算）：摘掉 TOPMOST，降到普通层被全屏窗口盖住。

1.0.32 起「总在最前」开关已按用户要求删除，这个语义被固定下来：**不盖全屏**，但常态压住任务栏。想改行为只能改代码，不再提供开关。

`syncLayer()` 在 `onTick` 里被调，但用 `LAYER_CHECK_MS = 1000` 节流：1 秒只做一次**只读**检查（`fullscreenForeground` + `trayCovers`），**只在两种情况才动手**：

- **结论翻转**（进出全屏）→ `setTopmost()` 换层；
- **真被任务栏压住**（`trayCovers` 为真）→ `raiseWithinTopmost()` 在 TOPMOST 层内提权。

其余时候一律什么都不做。提权成功即把计数归零，下一次检查就返回 false，所以是**自愈式**的，不会持续重排。另加熔断 `LAYER_RAISE_LIMIT = 5`：万一判据又出错，连续 5 次提不动就停手、只喊一次 `layer raise gave up`，把"每秒抖"限制成"抖 5 次"。

`trayCovers()` 沿 `GW_HWNDPREV` 上溯找 `Shell_TrayWnd`。实测两条基准线：**TOPMOST 时上溯 13–14 层到顶（不遇任务栏）**、**普通层时上溯 75–80 层遇到任务栏**。

这个判据被写错过两次，都记在这里免得再犯：

- 1.0.28 用对了判据，但把它当成"必须重排"的信号 → 每秒 `SetWindowPos` → 闪；
- 1.0.29 干脆删掉触发条件 → 被任务栏挤下去后**永久**沉底、不自愈；
- 1.0.30 改成「按 `EnumWindows` 返回顺序比较索引」，以为那个顺序就是 z 序 —— **错**。实测任务栏排第 10 位、普通层窗口排第 691 位，索引只反映创建顺序，判据恒为 true，于是又变回每秒重排。
- 1.0.31 改回 `GW_HWNDPREV` 上溯，并只在"真被压住"时才提权。

**提权必须用 `HWND_TOP`，不能用 `HWND_TOPMOST`**：对已经挂在 TOPMOST 层的窗口，`SetWindowPos(HWND_TOPMOST)` 是层内空操作，调用等于什么都没改，z 序不会动 —— 历史上"判到被压住 → 重新挂 TOPMOST"因此无限循环，用户看到的就是窗口一直待在任务栏下面还每秒抖一下。

`SetWindowBand`（`user32` 序号 888）看起来能把窗口插到任务栏正上方，实测 band 0/1/2/3/15/17/18/21 **全部返回 `hr=0` 而 `GetWindowBand` 返回 -1**，z 序毫无变化，现代 Win11 上这条路已废，不采用。

早先的写法是每 250ms 无条件 `toFront()`（`TOPMOST_KEEPALIVE_MS = 250`）。`toFront()` 等价于 `SetWindowPos(HWND_TOP)`，会重排同层窗口顺序——这正是用户报的"窗口一闪一闪"的来源。实测 TOPMOST 窗口在没有重复调用的情况下 z 序恒定（连续 2 秒 19 次采样不变，别的 TOPMOST 窗口反复前置、关闭邻居也不影响），保活本身是多余的，已整体删除。

右键菜单是独立的 heavyweight 弹层，展开/关闭会重排 TOPMOST 层。现在菜单关闭只清标志并把 `lastLayerCheckAt` 归零，让下一次 `syncLayer()` 立刻重新判断一次，不再靠强行 `setAlwaysOnTop(true)`。

`DropIndicator`（停靠虚线框）跟着主窗一起走 `followLayer()`，否则全屏时它会单独糊一条虚线框在画面上。

### 唤起宿主主窗口

宿主 API 里**没有**「打开主窗口」这种东西（`WorkshopApi.Playback` 只有播放控制、`Ui` 只有 toast），所以只能走 Win32：`EnumWindows` 筛出本进程的 `SunAwtFrame`，`ShowWindow` + `BringWindowToTop` + `SetForegroundWindow` 逐级唤醒。junique 的 `.port` 不走，因为那是内部协议、随版本就废。

**这条链路绝不能跑在 AWT EDT 上。** 1.0.30 因为跑在 EDT 上，被宿主判成 `TERMINATED_HANG / UNRESPONSIVE` 写进了 `safe-mode-session.properties`。根因是读窗口标题用的 `GetWindowTextW`：它对**非本线程拥有的窗口**会 `SendMessage(WM_GETTEXT)` 并同步等待对方线程，而宿主里 Compose/Skiko 的辅助帧、`TRAY`、`GDI+ Hook Window`、IME 宿主各由别的线程托管 —— EDT 等它们、它们又可能在等 EDT，就死锁了。

两刀切干净：

- 标题改读 `InternalGetWindowText`（不发任何消息，直接读窗口结构体），`GetWindowTextW` 的绑定已删除；
- `openHostWindow()` 只做 `AtomicBoolean` 去重 + 起 `miniplayer-host-activate` daemon 线程，EDT 上立刻返回（探针实测 2ms）。

另一个坑是宿主的「关闭主界面」：它不是 `IsIconic`，而是把窗口**移到 `-32000,-32000`**（Windows 老惯用法），此时 `IsWindowVisible` 仍为真、标题仍在，但面积只有 237×39。所以主窗识别的条件是「可见 + 有非空标题 + 类名 `SunAwtFrame` + 无属主 + （面积 ≥200×200 **或** 坐标在屏幕外）」，命中屏外时用 `MonitorFromWindow` 取 `rcWork` 居中拉回，再补一次 `SW_RESTORE`。

迷你条自己也有兜底：`onTick` 里发现 `!visibleAreaIntersects(getX(), getY())` 就 `recenter()` 并记日志。诊断日志补齐了 `window disposed` / `hide mini`，下次能从日志分清「窗口被销毁」与「窗口还在但看不见」。

### 拖动

除播控三键与进度条之外，**整条都能按住拖动**——封面、歌词、空白处都算。

最左侧原先有一条 10px 宽的三条竖杠手柄，已按用户要求删除，为它让出的 21px 留白也一起收掉：封面现在贴左边（`coverX = 3`，只留抗锯齿余量）。手柄从来只是视觉暗示，`hitDragArea` 早就放宽成"除播控三键与进度条外整条可拖"。

放宽热区不会把点击吞掉：按下时只记状态，松手时才看指针位移有没有到 4px（`DRAG_THRESHOLD_PX`），没到就按点击处理。

定位用的是「按下时的窗口位置 + 指针屏幕坐标总位移」，不是每帧拿 `getX()` 累加。后者在窗口跟着
指针移动之后，事件坐标又会相对窗口变化，等于把位移算了第二遍，越拖越快。

拖动热区判定只看事件坐标本身，不像其他命中测试那样遍历 `candidates(raw)`：候选点是给"HiDPI 下
坐标可能差一个缩放"兜底的，用在"排除按钮"上会自相矛盾——原始点落在播放键上被排除，缩放候选点
却落回空白处被判成可拖。

### 为什么"整条都能接事件"需要垫一层不可见的底

`hitDragArea` 只是第二道关。第一道关是**窗口能不能收到 `MouseEvent`**：Windows 对 per-pixel
透明窗口（`WS_EX_LAYERED`）做命中测试时只看像素 alpha，`alpha==0` 的像素把点击**直接穿透给
下面的窗口**，Java 侧根本收不到事件。早先 `paintBody` 完全不填底（只有封面、文字、图标这些
"真画了像素"的地方有 alpha），于是拖动、歌词态切换、上下曲都必须精确压在黑色图案上才能触发，
看上去像"那块区域判定不对"，其实是事件根本没进来。

现在 `paintBody` 第一件事就是 `g.setColor(new Color(0, 0, 0, 1)); g.fillRect(0, 0, layout.windowW,
layout.windowH);`——`alpha=1` 肉眼不可见，却让每个像素都不再是全透明。窗口形状同时从圆角改成
满矩形：被形状裁掉的像素同样收不到事件。`AlphaProbe` 把 `paintBody` 画到 ARGB 离屏图上逐像素
统计，断言整窗 0 个全透明像素、几个空白点 alpha 恰为 1、`getShape()` 覆盖整个窗口。

### 显示 / 隐藏

迷你条可以被完全隐藏（不是最小化，是 `setVisible(false)`，不再占用任务栏与桌面区域）：

- 设置面板的「**启用迷你播放器**」开关（改完立刻生效，不需要重启播放器）；
- 设置面板的「**显示 / 隐藏迷你条**」按钮（等价于切换上面那个开关）；
- 悬浮条右键菜单最底部的「**隐藏迷你条**」。

隐藏后状态仍保留（配置里的位置与停靠信息不改），下次显示时位置不变。

前两个入口走的是宿主的配置监听，数据流是这样的：`PluginConfig.installChangeListener()` 在插件
`start()` 里调 `WorkshopApi.manager().createConfigManager()`，把 `ConfigManager` 缓存下来（不缓存
就拿不到同一个实例、也就没法 `removeConfigChangeListener`），再 `addConfigChangeListener(...)`；
`stop()` 里注销。宿主用 `WatchService` 盯配置文件，设置面板一改就回调。

回调里有两件事必须小心：

- **回调不在 UI 线程**，所以只做 `PluginConfig.load()` 重读，然后交给
  `MiniPlayerWindow.applyConfig(fresh)` 投递到 EDT 执行；
- **回调里绝不写盘**。`applyConfigOnEdt` 只把新值拷进内存里的 `config`（不调 `markDirty()`），否则
  「写盘 → 监听器回调 → 再写盘」会自激。`config.enabled` 也必须跟着同步，不然窗口 `onTick` 的
  `isDirty()` 落盘会把旧的 `true` 写回去，开关看起来像弹回原状。

显隐只在 `enabled` **这个开关本身翻转**时才动，判据是 `config.enabled != fresh.enabled`，不能写成
`fresh.enabled != isShowingMini()`。后者在"用右键菜单隐藏了窗口、但 `enabled` 仍是 true"的情形下会
出错：用户此后改任何别的设置项（比如不透明度），都会被判成"该显示"，窗口自己弹回来。

为什么不把这一项加进 Salt Player 自己的托盘菜单：公开 API 里没有「注册宿主菜单项」的入口；Hook
API 也够不到——宿主把菜单内容写死在 `AppTray`（混淆后是 `androidx/compose/ui/ल`）的私有静态
Composable `Ϳ(String,String,String,State,…,ComposableTrayMenuScope,Composer,int)` 里，
`ComposableTrayMenuScope` 只能在该方法内部拿到，公开的 `HookRegistrar` 只能 `appendContent` 到
`Function1` 参数上（宿主菜单方法没有这种参数）。更硬的一条限制在钩子解析器 `Ԫ` 的
`private Class<?> Ϳ(String)`：它要求目标类的 classloader 必须是 `org.pf4j.PluginClassLoader` 实例，
宿主的 `androidx.compose.ui.*` 不满足，直接抛 `Hook target must be visible to the host loader`。

### 停靠任务栏

按住迷你条的任意空白处（除播控三键与进度条）拖动时，任务栏位置会出现一个与目标尺寸等大的虚线框；继续靠近（24 逻辑像素内）
虚线框转为高亮，松手即停靠：

- 迷你条嵌进任务栏区域，**垂直居中、宽度不变**；
- 停靠位置水平跟随拖动位置，并夹在任务栏左右边界内；
- 拖离任务栏就**立即**解除停靠（不用等松手），手感像"抽出来"；
- 停靠状态写入配置，下次启动自动恢复；任务栏被隐藏或移走后自动解除停靠。

恢复用的是配置里记下的 `windowX/windowY`，不是构造窗口那一刻的 `getX()`——那时还没
`setLocation`，坐标恒为 0，取它会让停靠目标被夹到任务栏最左边（旧版的"每次重开跑到最左"
就是这个原因）。没记住坐标的首次停靠落在任务栏中部，也不会贴最左。

**尺寸只有一种**：高度恒等于任务栏高度（本机 48px，内容区 40px），宽度 312px。
停靠与拖出**不会改变大小**，只改变位置——所以从任务栏拖出来时控件不会突然变大。

右键菜单只留随时会切换的项（锁定 / 暂停时隐藏 / 主题 / 抽出 / 重置 / 隐藏）。像「自动收起」这类设一次就不再动的偏好，全部只在设置面板里维护，菜单里不再重复一遍。1.0.32 起「贴边吸附」与「可停靠到任务栏」两个开关也从面板里删掉了——吸附与停靠一律开启，不再可关。

> 说明：这里不是 Win32 的 `SetParent` 真嵌入。`JWindow` 无法作为 `Shell_TrayWnd` 的子窗口
> （需要 JNA 且会破坏透明/圆角绘制），所以表现为**窗口浮在任务栏区域、尺寸等于任务栏高度**。

### 自动收起

鼠标移开 0.45 秒后淡入歌词态（封面 + 双行歌词），鼠标移回立即恢复完整布局。
右键菜单打开期间不会收起。可在设置面板关闭该行为（关掉后始终显示完整布局）。

### 视觉

- 内容是上下两层结构：上层封面 + 两行文字，下层播控 + 进度条。
- **视觉上没有背景**：不画卡片底、描边、柔光或投影，只有封面、文字、播控和
  进度条浮在桌面上）。底层垫了一层不可见的 `alpha=1` 黑底，
  只为实现"整窗都能接到鼠标事件"，见下。因此前景色必须自己保证可读，分两套：
  浅色主题（`light` / 跟随系统且系统为浅色）用近黑 `#1F1F24`，深色主题用白。
- 进度条：底轨是半透明前景色（浅色淡黑 / 深色淡白），已播放段取**封面主色**
  （HSB 收敛，浅色压到 B=0.44、深色抬到 B=0.74），无背景时靠这一抹颜色保留辨识度。
- 播放/暂停键是**半透明圆底 + 反向明暗字形**（浅色：淡黑底深字形；深色：淡白底白字形），
  不再用实心白圆——没背景时白圆在浅色桌面上会糊成一块。
- 无封面时占位块用中性深灰渐变 + 白色音符，不跟随封面色。
- 尺寸：**只有一种**——高度恒等于任务栏高度，宽度 304px 内容区 / 312px 窗口
  （本机任务栏 48px，所以内容区 40px、窗口 312×48）。停靠与拖出尺寸相同。
  高 DPI 下由 Java 自动按比例放大（本机 1.5× 缩放实测渲染为 537×72 物理像素）。
- 播控组右对齐（右边缘留 12px），文字区从封面右侧 8px 起、一直到播控组左侧。
- 4px 边距（`SHADOW`）只给抗锯齿留余量，不画投影。
- **整窗垫一层 `alpha=1` 的黑底**（`paintBody` 开头 `fillRect`）。Windows 对 per-pixel 透明窗口
  做命中测试时只看像素 alpha，`alpha==0` 的像素会把点击**直接穿透给桌面窗口**，Java 侧收不到
  事件——不垫底时整条只有"真的画了像素"的地方（黑色歌词、图标、封面）能命中，空白处点不动，
  表现就是拖动、歌词态切换、上下曲都必须精确压在图案上。`alpha=1` 肉眼不可见，但每个像素都不再
  是全透明，整条从此都在命中范围内。

### 歌词态 ↔ 完整布局

默认只显示「封面 + 双行歌词」：上行是当前演唱的那句（加粗、前景色），下行是下一句预告
（常规字重、约 0.88 倍不透明度），整体在内容区里垂直居中。已经是最后一句时没有副行，
退回单行居中。

下一句怎么来：宿主一次性推来整首歌词（`onLyricsLinesUpdated`）时，按对象身份定位当前行、
取其下第一条有文字的行；只有 `onLyricsLineUpdated`（单行推送）而拿不到整首歌词时，没有副行，
自然就是单行。空行（间奏占位）会被跳过。

鼠标移入后交叉淡入完整布局（封面、歌名、歌手、播控三键、进度条），移开 0.45 秒再淡回歌词态。
文字区用 `controlsAlpha` 在「歌名+歌手」与「歌词」之间反向插值，不会硬跳。
**拿不到歌词时保持完整布局**，否则整条只剩一个封面会显得空。

### 卡拉 OK 逐字

主行有一条填充边界，从第一个字扫到最后一个字：

- 已经扫过的部分 —— 实色前景；
- 还没扫到的部分 —— 同色 42% 不透明度；
- 边界所在的那个字 —— 按像素裁剪，左半实右半虚。

边界每帧前进一点，是连续推进，不会"一次亮好几个字"。

**边界整体提前 200ms**（`KARAOKE_LEAD_MS`）：宿主的逐字时间轴普遍比实际人声晚一点，
照它画就是"每句最后一个字唱完了才开始滚动"。把求边界用的位置整体前移，只影响句内边界，
不影响换行时机。如果反过来觉得"抢在声音前"，把这个常量降到 120~150；仍偏慢就继续加。

**画哪一行由播放位置决定，不由回调决定**。宿主的 `onLyricLineUpdated` 可能滞后几帧，也可能
提前把下一行推来。位置已经到了推送行的起点时才换行——否则提前推来的行会让上一行刚亮到
最后一个字就切走，来不及亮满。宿主滞后时则用位置匹配的行追上进度，避免把上一行算成
100% 后整句突然全亮、等回调到了再跳回行首重扫。

**关键在「格内插值」**：边界位置 = 当前落在第几个 cell 的 x 坐标 + 该 cell 内部的时间比例 ×
这一格的宽度。所以哪怕宿主给的是「3 个字一个 cell」，边界也会在这 3 个字的宽度里平滑扫过去，
而不是等整格时间到点再跳一格。这也意味着**不再要求 cells 一字一格**——粒度粗到词组都能用，
只要 cells 拼起来正好是这一行的文字、时间单调。

1.1.0 起，这条 x 坐标最后还要**按整行宽度归一**：`left + (x - left) × textW / Σwidth(piece)`。
因为宿主的 cell 常带首尾空格、或把标点单独成格，逐格宽度之和与整串宽度不一定相等；边界照逐格累加
推进，行末就只走到整行宽度的 95%~97%，表现为「句尾最后一个字只亮一部分就换行」。归一只缩放总长，
不改格与格之间的时间节奏，标点和长音的停顿感还在。

没有 cells 时退回**整行匀速推进**（用 `getStartTime() / getEndTime()`），普通 `.lrc` 也有动效。

拆分只为更贴人声：一个 cell 装多个汉字时，按**权重**把它的时长分给每个字——汉字 1 份、
标点与空格 0.25 份（中文逗号占掉一整个字的时长会让边界在标点处停一下）。含拉丁字母的
cell 不拆，否则 `hello` 会变成打字机。

整行超宽时**不截断**：跟着演唱边界把绘制坐标整体左移，位移由
`lyricScrollFor(edge, textW, maxW, lookahead)` 算出（`lookahead = FontMetrics.charWidth('字')`）。

1.0.36 起是**双下限**：触发点 `trigger = maxW - lookahead`（边界距右缘还剩一个字）之前
位移恒为 0；触发点之后取 `max(byEdge, byRatio)` 两项的较大值——

- `byEdge = edge + lookahead - maxW`：让边界始终留在可视区右缘（唱到哪跟到哪）；
- `byRatio = span × (edge - trigger) / (textW - trigger)`：从触发点起按「已走/剩余」线性
  分摊整个 `span`，保证句末一定滚到 `textW - maxW`，尾部那截才露得出来。

两项取较大值的好处是速度和边界都跟得上，且不出现末尾冲刺。`floor` 保留
（宁可少滚半像素，也别把边界挤出右缘）。

这个方向是四轮反馈才定下来的。早期留**余量**（`LYRIC_SCROLL_MARGIN` 24 → 8 → 0）：
余量让边界提前刹车，句末标点只占 0.25 个字的时间，来不及滚进窗口就换行——用户看到
「差不多半个字没滚到就下一句了」。1.0.33 换成末尾 400ms 按时间比例把位移硬顶到上限
（`lyricFinishScroll`），结果整行 100 多像素一次性窜过去，用户反馈「改成了提前进入
下一句歌词了，还没唱完本句」。1.0.34 改成只加**提前量**，但真机日志显示 104 行诊断里
`scroll` **全为 0**：超宽行要等边界追到右缘才启动，而这个阈值（`edge > maxW - lookahead`）
在长句里往往到句末才越过，于是整行从头到尾一次都没滚过、尾部被 `clipRect` 直接裁掉——
这才是「最后一截不会滚动到」的真因，之前理解的「差半个字」其实是「尾部整段从未出现」。
1.0.36 因此在触发点之后补了按比例的 `byRatio` 兜底（见上）。

没有时间轴（`edge` 为 NaN）时改用匀速循环（速度沿用歌名跑马灯的 0.028 px/ms），
保证句末一定滚得进窗口。
副行（下一句）仍然截断带省略号，不参与滚动。
连行时长都拿不到（`endTime <= startTime`）时整行实色，不做假动效。

进度本身也是平滑的：宿主上报的位置只当**锚点**，每帧按本地流逝时间外推成目标值，
再让进度限速追上去。所以宿主回调频率再低、值再来回抖，进度也不会每秒倒一下。
日志里每 5 秒有一行 `karaoke fps=… edgeJumpMax=… posBackward=… path=timeline|sweep`，
用来判断到底是"画得慢"还是"数据在跳"。滚动本身另有一行诊断（换行必打，之后每 2 秒一行）
`lyric scroll pos=… edge=… scroll=… textW=… maxW=… span=… line=[…] cellEnd=… cells=… text=…`：
`edge` 停住说明演唱边界不再推进（逐字时间轴那一段有问题），`edge` 在涨而 `scroll` 不动
说明位移已经贴到 `span` 上限（正常滚到底）。

播控三键用的是另一份透明度 `buttonsAlpha`。1.0.33 起歌词态**固定保留**上一首 / 播放暂停 /
下一首（用户要求"歌词态也需要显示上下曲、暂停控件"），原先那个「收起时也显示播控」开关
已删除；此时歌词的可用宽度会自动让出右侧按钮那一块。

## 配置

宿主设置内的「插件配置」有独立面板（`src/main/resources/preference_config.json`）：

| 键 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `enabled` | switch | `true` | 是否显示迷你条，**即时生效**（关掉立刻隐藏，打开立刻出现） |
| `hideWhenPaused` | switch | `false` | 暂停时隐藏。播放中显示；一旦暂停就整条收起，恢复播放自动出现。**缓冲中与播放结束都不隐藏**——缓冲时藏起来像插件崩了，播完藏起来让人以为被卸载 |
| `themeMode` | list | `system` | `system` / `light` / `dark` |
| `locked` | switch | `false` | 锁定位置：整条不能拖动，**右键菜单也不再弹出**；播控三键与进度条照常可用 |
| `autoHideControls` | switch | `true` | 鼠标离开 0.45 秒后收起为歌词态 |
| `docked` | (内部) | `false` | 上次退出时是否停靠在任务栏 |
| `opacity` | seekbar | `100` | 不透明度 60~100 |
| `windowX` / `windowY` | (内部) | `-1` | 上次位置，`-1` 表示未记录 |

1.0.32 起从面板里删掉了三个开关，行为改为固定：`snapEdges`（贴边吸附，现在恒定开启）、
`dockToTaskbar`（可停靠到任务栏，现在恒定开启）、`coverFullscreen`（总在最前／盖住全屏窗口，
现在恒定为不盖全屏）。`save()` 仍会把 `alwaysOnTop=false`、`snapEdges=true`、`dockToTaskbar=true`
写回配置，只是不再读——这样降级回旧版本时行为不会跳变。

1.0.33 又删掉了「收起时也显示播控」（`showControlsInLyric`）：歌词态现在**固定保留**
上一首 / 播放暂停 / 下一首，没有开关。`save()` 同样只写 `showControlsInLyric=true` 供降级兼容。

面板里还有三个按钮：**重置窗口位置**、**显示当前设置**、**显示 / 隐藏迷你条**（`PluginConfig.resetWindowPosition` /
`PluginConfig.showCurrentSettings` / `PluginConfig.toggleMiniPlayer`，宿主要求 `on_click` 指向公开静态无参方法）。
最后那个和上面的「启用迷你播放器」开关等价。

`enabled` 是即时生效的：面板上一改，宿主立刻回调配置监听，悬浮条当场显示或隐藏，不需要重启播放器。

配置写在 `%APPDATA%\Salt Player for Windows\workshop\data\com.miniplayer.desktop-mini-player\config.json`。

## 已知边界（宿主 API 决定，非本插件可解）

- **音量**：`WorkshopApi` 没有音量接口，做不了音量按钮与滚轮调音量。
- **循环 / 随机 / 音质**：无接口。
- **收藏**：`MediaItem.isFavorite()` 只读，不能写，所以爱心图标只显示状态、不做点击。
- **播放列表**：`getLyricsLines()` 可用但没有播放入口，未实现列表。
- **真 Mica / Acrylic**：Swing 无法直接调 DWM；而且当前版本**不要背景**，只绘制前景元素，
  窗口其余区域是 `alpha=1` 的近全透明（透出桌面/任务栏本身，但不再是像素级 0 alpha，
  否则 Windows 会把鼠标事件穿透掉）。

## 构建

宿主环境无法运行 Gradle（沙箱限制：`CreatePipe error=5`），因此本工程用 `javac` + `jar` 手工打包：

```bash
bash build.sh     # 产出 build/libs/plugin-com.miniplayer.desktop-mini-player-<版本>.spmod
bash preview.sh   # 出一套静态预览图 + 两张真机截图到 preview/
```

发版（PowerShell，用已登录的 Git Credential Manager 令牌，无需额外配置）：

```powershell
pwsh -File tools\release.ps1 -NotesPath docs\releases\v<版本>.md -DryRun   # 先自检版本号/产物/SHA256
pwsh -File tools\release.ps1 -NotesPath docs\releases\v<版本>.md           # 建 tag、传附件、发布
```

发布说明放在 `docs/releases/v<版本>.md`，正文会原样成为 Release 描述；脚本对已存在的同名
release 直接报错而不是覆盖——发布过的版本不该被一次误跑悄悄替换。

探针（`preview-src/`，编译后 `java -cp "..." com.miniplayer.<名字>`）：

| 探针 | 作用 |
| --- | --- |
| `Preview` | 离屏渲染各类状态（展开态 / 歌词态 / 卡拉 OK / 超宽滚动 / 不同封面），出 `preview/sheet.png` |
| `Demo` | 真机弹窗截图（浅色/深色） |
| `ShowProbe` | 走生产路径建窗并截屏，附停靠态 `preview/live-docked.png` |
| `IndicatorProbe` | 把拖动时的任务栏虚线框真画出来截屏 |
| `HitProbe` | 命中判定（三键 + 进度条），坐标取自 `currentLayout()`；额外断言**歌词文字区不会被判成播控三键**（1.0.28 的误触现场） |
| `DockProbe` | 任务栏矩形、停靠目标、停靠不改尺寸、幂等 |
| `BuildProbe` | 非 EDT 线程建窗（复现宿主线程环境） |
| `DispatchProbe` | 合成 MouseEvent 直接派发到 Surface |
| `DragProbe` | 合成拖动：文字区/封面区（含贴边处）都能拖、连发 dragged 不累加、播控键与进度条不接管、三种光标 |
| `CoverProbe` | 封面单击契约：封面贴边（`coverX <= 4`）、`hitCoverAt` 只命中封面不命中文字/按钮/进度条、封面仍可拖、单击封面或空白处都不改变播放状态；额外断言**单击封面调度耗时 <200ms**（否则说明唤起链路又回到 EDT 同步执行） |
| `LayerProbe` | `WindowLayer` 的 FFM 绑定：`available()`、HWND 解析（含缩放换算）、`trayCovers`/`fullscreenForeground` 不抛、`setTopmost` 可挂可摘、主窗查询不把自己当主窗、`syncLayer` 跑通后窗口仍在；额外断言 `trayCovers` 的 z 序语义（挂 TOPMOST 时为假、摘掉后为真）、`raiseWithinTopmost` 成功且提权后 `WS_EX_TOPMOST` 仍在、连续提权 3 次仍稳定、标题读取 <200ms（否则说明又走回会阻塞的 `GetWindowTextW`） |
| `ActivateProbe` | 唤起宿主防死锁：在 EDT 上调 `openHostWindow()` 必须立即返回（`CountDownLatch` + 200ms watch dog，超时即失败）、连点两次不阻塞 EDT、找不到主窗时 `HostWindow.activate` 安静返回 false 且不抛。宿主曾把 1.0.30 判成 `TERMINATED_HANG / UNRESPONSIVE`，这个探针就是那次的回归防线 |
| `AlphaProbe` | 把 `paintBody` 画到 ARGB 离屏图上逐像素统计，断言整窗没有全透明像素（否则 Windows 会把鼠标事件穿透给桌面） |
| `ConfigProbe` | 设置面板契约：开关键集合必须与代码一致、每个 `on_click` 都是 public static 无参、说明文字里不许回流已删功能（USB 独占 / 托盘 / 快捷键 / key-bindings） |
| `ConfigApplyProbe` | 配置热更新的显隐判据：手工隐藏后改无关项不许弹回窗口、只有 `enabled` 真翻转才显示/隐藏、无关项本身仍要生效 |
| `KaraokeProbe` | 逐字时间轴契约：cell 细化（多汉字拆单字、标点 0.25 权重、拉丁不拆）、`timelineUsable`、格内插值、提前量、逐帧单调且单帧不跳字、**行末边界覆盖整行宽度**（1.1.0 回归：cell 带首尾空格或标点单独成格时，Σ 逐格宽度 < 整串宽度，边界只走到 95%~97% 就换行，句尾最后一字只亮一部分） |
| `LyricSyncProbe` | 画哪一行由播放位置决定（推送滞后/超前一行的取舍） |
| `SimProbe` | 位置外推与进度限速的仿真（宿主刷新慢、位置回抖时不倒退） |
| `LyricScrollProbe` | 超宽歌词的滚动契约：`lyricScrollFor` 的 0/上限/单调/不越界、**边界到行中时已滚过半程**（1.0.36 回归：否则超宽行要等边界追到右缘才启动，整行一次都滚不动）、**边界还差一个字到行尾时位移已顶在上限**（否则句末那最后一截会被换行抢在前面），以及像素级「已唱段更实」「滚到上限后右边缘仍在窗口内」「边界还剩一个字时行尾已在可视区内」 |
| `LockProbe` | 锁定契约：未锁时右键不误触发拖动/进度条；**锁定后按下与松开两种 popup 触发路径都不弹菜单**；锁定不影响播控三键与进度条 |
| `PausedHideProbe` | 「暂停时隐藏」的边界：没播过不藏、播放中显示、暂停才藏、恢复播放自动回来、**缓冲中不藏、播放结束不藏**、手动隐藏不被恢复播放弹回、开关关掉立刻显示 |

依赖（编译期，均在 `.refs/`）：

- `spw-workshop-api.jar`（`com.github.Moriafly.spw-workshop-api:0.1.0-dev22`）
- `pf4j-3.12.0.jar`、`kotlin-stdlib-2.4.20.jar`、`annotations-13.0.jar`
- 校验器额外需要 `slf4j-api-2.0.16.jar`、`slf4j-simple-2.0.16.jar`

默认约定：JDK 在工程上一级的 `.tools/unz/jdk25.0.5_0/`，依赖在上一级的 `.refs/`。
两个目录都不进版本库（见 `.gitignore`），需要自己准备，或用环境变量指到别处：

```bash
JDK_HOME=/path/to/jdk25 SPW_LIBS=/path/to/refs bash build.sh
JDK_HOME=/path/to/jdk25 SPW_LIBS=/path/to/refs bash tools/run_probes.sh
```

`build.sh` 的关键点：

- 真清单必须写在 `classes/META-INF/MANIFEST.MF`，外层 `META-INF/MANIFEST.MF` 只留 `Manifest-Version`。
- `jar --create` 必须带 `--no-manifest`，否则它生成的空清单会覆盖手写清单。
- class 与资源平铺进 `classes/`，运行期依赖 jar 进 `lib/`——与官方 `WorkshopPluginTask` 一致。

## 目录

```
desktop-mini-player/
├── build.sh                     手工构建（5 步）
├── preview.sh                   静态预览 + 真机截图
├── PLAN.md                      设计稿与取舍记录
├── src/main/java/com/miniplayer/
│   ├── MiniPlayerPlugin.java        插件入口（生命周期全兜底）
│   ├── MiniPlayerPlaybackExtension.java 播放状态回调 → 窗口
│   ├── MiniPlayerWindow.java            悬浮窗：绘制、交互、定时器
│   ├── WindowLayer.java                 Win32 层级控制（FFM）：TOPMOST 切换、全屏判定、z 序探测
│   ├── HostWindow.java                  唤起宿主主窗口（屏外拉回 + 还原 + 提到前台）
│   ├── Layout.java                      唯一布局参数（高度=任务栏高，宽 304）
│   ├── PlayerState.java                 不可变播放快照
│   ├── PluginConfig.java                配置读写、明暗判定、配置面板入口
│   ├── Theme.java                       前景色（浅色近黑 / 深色白）、进度条配色、封面主色提取
│   └── Icons.java                       全部矢量图标
├── src/main/resources/
│   ├── META-INF/extensions.idx
│   └── preference_config.json
├── preview-src/                 离线预览与真机 Demo（不参与打包）
├── tools/release.ps1            发版：建 tag + 传 spmod + 发布 Release
├── docs/releases/v<版本>.md     各版本发布说明（正文即 Release 描述）
└── verify-src/                  离线 PF4J 描述符/扩展校验器
```

## 稳定性约定

插件生命周期里的任何异常都会冒泡到宿主，可能导致整个应用退出，因此：

- `start()` / `stop()` / `update()` 全部 try/catch，失败只写日志（`[MiniPlayer]` 前缀）。
- 建窗失败不重试，插件退化为空转，不影响宿主播放。
- `stop()` 先停定时器、记忆坐标、落盘，再 dispose 窗口。
- 配置读写、权限未授予、封面加载失败一律静默降级。

## 排查问题

出问题先看日志：`%TEMP%\mini-player.log`（stdout 里也有同样内容，但宿主以 GUI 启动时看不见）。
文件超过 512KB 会自动清空，所以只保留最近一段。

## 许可

MIT。这是**非官方**第三方插件，与 Salt Player 及其作者无隶属关系，
仅通过宿主公开的创意工坊 API 工作；不修改宿主文件，也不内置任何第三方资源。
