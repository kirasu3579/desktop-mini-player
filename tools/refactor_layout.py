import io, re

p = r"E:\AiTool\666\chajian2\spw-mini-player\src\main\java\com\miniplayer\MiniPlayerWindow.java"
src = io.open(p, encoding="utf-8").read()
lines = src.split("\n")

# 常量声明区（第 54..91 行，0-based 53..90）原样保留
KEEP_START = 53   # "/** 外围留白..." 之前的一行实际是 54 行 index53 -> '    /** 外围留白，用于绘制柔化投影。 */'
KEEP_END = 91     # index91 是 '    private static final int BAR_H_HOVER = 5;' 之后到 92 行

# 更稳妥：按内容定位声明块
decl_first = next(i for i, l in enumerate(lines) if l.strip() == "/** 外围留白，用于绘制柔化投影。 */")
decl_last = next(i for i, l in enumerate(lines) if l.strip() == "private static final int BAR_H_HOVER = 5;")

MAP = [
    ("BAR_H_HOVER", "layout.barHHover"),
    ("BAR_H", "layout.barH"),
    ("BAR_W", "layout.barW"),
    ("BAR_X", "layout.barX"),
    ("BAR_Y", "layout.barY"),
    ("CONTENT_W", "layout.contentW"),
    ("CONTENT_H", "layout.contentH"),
    ("WINDOW_W", "layout.windowW"),
    ("WINDOW_H", "layout.windowH"),
    ("COVER_SIZE", "layout.coverSize"),
    ("COVER_RADIUS", "layout.coverRadius"),
    ("COVER_X", "layout.coverX"),
    ("COVER_Y", "layout.coverY"),
    ("TEXT_X", "layout.textX"),
    ("TEXT_RIGHT", "layout.textRight"),
    ("TEXT_MAX_W", "layout.textMaxW"),
    ("TITLE_BASELINE", "layout.titleBaseline"),
    ("ARTIST_BASELINE", "layout.artistBaseline"),
    ("CTRL_Y", "layout.ctrlY"),
    ("PREV_CX", "layout.prevCx"),
    ("PLAY_CX", "layout.playCx"),
    ("NEXT_CX", "layout.nextCx"),
    ("PLAY_RADIUS", "layout.playRadius"),
    ("SIDE_BTN_BOX", "layout.sideBtnBox"),
    ("EXCLUSIVE_CX", "layout.exclusiveCx"),
    ("PIN_CX", "layout.pinCx"),
    ("CLOSE_CX", "layout.closeCx"),
    ("FUNC_BOX", "layout.funcBox"),
    ("SHADOW", "layout.shadow"),
]
PATTERN = re.compile(r"\b(" + "|".join(k for k, _ in MAP) + r")\b")
LOOKUP = dict(MAP)

out = []
for i, l in enumerate(lines):
    if decl_first <= i <= decl_last:
        out.append(l)
        continue
    out.append(PATTERN.sub(lambda m: LOOKUP[m.group(1)], l))

io.open(p, "w", encoding="utf-8", newline="\n").write("\n".join(out))
print("replaced; kept decl lines", decl_first + 1, "-", decl_last + 1)
