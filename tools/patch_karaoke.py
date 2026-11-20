import io
import sys

p = r'E:\AiTool\666\chajian2\spw-mini-player\src\main\java\com\miniplayer\MiniPlayerWindow.java'
s = io.open(p, encoding='utf-8').read()

start_marker = "    private void paintKaraokeLine(Graphics2D g, LyricsLine line, String text,"
end_marker = "     * 决定当前该显示哪一行歌词。"

si = s.find(start_marker)
ei = s.find(end_marker)
if si < 0 or ei < 0 or ei <= si:
    sys.exit("markers not found: start=%d end=%d" % (si, ei))
# 往前退到该注释块的 /**
j = s.rfind("    /**", si, ei)
if j < 0:
    sys.exit("doc comment start not found")
ei = j

new = r'''    private void paintKaraokeLine(Graphics2D g, LyricsLine line, String text,
                                  int left, int baseline, int maxW, float alpha) {
        FontMetrics fm = g.getFontMetrics();
        Color sung = Theme.fade(Theme.titleColor(isDark()), alpha);
        // 未唱的字要一直在那儿，只是比已唱的暗一档。对比度太低，看到的就不是
        // "一条边界扫过去"，而是"整句一起变亮"。
        Color pending = Theme.fade(Theme.titleColor(isDark()), alpha * 0.42f);

        // 超宽要加省略号，省略号之后的坐标和逐字对不上，干脆整行单色
        if (fm.stringWidth(text) > maxW) {
            g.setColor(sung);
            g.drawString(ellipsize(text, fm, maxW), left, baseline);
            return;
        }

        java.util.List<LyricsLine.Cell> cells = refineCells(line == null ? null : line.getLyricsCells());
        double target;
        if (timelineUsable(cells, text)) {
            karaokeByCells = true;
            target = timelineEdge(cells, fm, left);
        } else {
            karaokeByCells = false;
            target = sweepEdge(line, text, fm, left);
        }
        paintEdge(g, text, left, baseline, smoothEdge(text, target), fm, sung, pending);
    }

    /**
     * 按 cell 时间轴求边界坐标。
     *
     * <p>关键是<b>在格内插值</b>：先找到当前位置落在第几个 cell，再按该 cell 内部的时间
     * 比例在这几个字的宽度里插值。所以宿主给的是 3 个字一个 cell，边界也会在这 3 个字的
     * 宽度内连续扫过去，而不是等整格时间到了才一次跳一格。</p>
     */
    private double timelineEdge(java.util.List<LyricsLine.Cell> cells, FontMetrics fm, int left) {
        long pos = state.positionMs;
        double x = left;
        for (LyricsLine.Cell cell : cells) {
            String piece = cell.getText();
            if (piece == null || piece.isEmpty()) {
                continue;
            }
            double w = fm.stringWidth(piece);
            long start = cell.getStartTime();
            long end = cell.getEndTime();
            if (pos >= end) {
                x += w;
                continue;
            }
            if (pos <= start || end <= start) {
                break;
            }
            return x + w * ((pos - start) / (double) (end - start));
        }
        return x;
    }

    /**
     * 没有可用时间轴时，退回「整行按行时间匀速推进」。
     *
     * @return 边界 x；{@link Double#NaN} 表示完全没有行时长，应当整行实色不做动效
     */
    private double sweepEdge(LyricsLine line, String text, FontMetrics fm, int left) {
        long ts = line == null ? 0L : line.getStartTime();
        long te = line == null ? 0L : line.getEndTime();
        if (te <= ts) {
            return Double.NaN;
        }
        double progress = (state.positionMs - ts) / (double) (te - ts);
        progress = Math.max(0d, Math.min(1d, progress));
        return left + fm.stringWidth(text) * progress;
    }

    /**
     * 把边界位置平滑一下。
     *
     * <p>换行、seek 回退、位置非法时直接吸附——这些场景用户本来就不期待连续。
     * 正常前进时用指数平滑吃掉 1–2px 的残余抖动，代价是边界比真实进度慢约 30ms，
     * 肉眼看不出来。</p>
     */
    private double smoothEdge(String text, double target) {
        if (Double.isNaN(target)) {
            edgeShown = -1d;
            edgeForLine = text;
            return target;
        }
        if (edgeForLine == null || !edgeForLine.equals(text) || edgeShown < 0d || target < edgeShown) {
            edgeShown = target;
            edgeForLine = text;
            return edgeShown;
        }
        double before = edgeShown;
        edgeShown += (target - edgeShown) * 0.55;
        if (Math.abs(target - edgeShown) < 0.4) {
            edgeShown = target;
        }
        double jump = Math.abs(edgeShown - before);
        if (jump > diagEdgeJumpMax) {
            diagEdgeJumpMax = jump;
        }
        return edgeShown;
    }

    /** 先整行铺淡色，再把边界左侧用实色重画一遍。 */
    private void paintEdge(Graphics2D g, String text, int left, int baseline, double edge,
                           FontMetrics fm, Color sung, Color pending) {
        if (Double.isNaN(edge)) {
            g.setColor(sung);
            g.drawString(text, left, baseline);
            return;
        }
        g.setColor(pending);
        g.drawString(text, left, baseline);
        if (edge <= left) {
            return;
        }
        Shape oldClip = g.getClip();
        g.clip(new java.awt.geom.Rectangle2D.Double(left, 0, edge - left, layout.contentH));
        g.setColor(sung);
        g.drawString(text, left, baseline);
        g.setClip(oldClip);
    }

    /**
     * 时间轴能不能用：cells 拼起来必须正好是这一行的文字，且时间单调。
     *
     * <p>这里<b>不再</b>要求"一字一格"或"各格时长铺满整行"——粒度粗到词组也能用，
     * 因为插值发生在格内部。只挡住拼接结果对不上的情况（例如宿主把译文也塞进 cells），
     * 那种硬按 cells 画会串行。</p>
     */
    private static boolean timelineUsable(java.util.List<LyricsLine.Cell> cells, String text) {
        if (cells == null || cells.isEmpty() || text == null || text.isEmpty()) {
            return false;
        }
        StringBuilder sb = new StringBuilder();
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        for (LyricsLine.Cell cell : cells) {
            String piece = cell.getText();
            if (piece == null) {
                return false;
            }
            sb.append(piece);
            long start = cell.getStartTime();
            long end = cell.getEndTime();
            if (end < start) {
                return false;
            }
            if (start < first) {
                first = start;
            }
            if (end > last) {
                last = end;
            }
        }
        if (last <= first) {
            return false;
        }
        return stripSpace(sb.toString()).equals(stripSpace(text));
    }

    private static String stripSpace(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c) && c != '\u3000') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 把逐字单元细化到「单字」。
     *
     * <p>宿主给的 cell 粒度不一定是一字一个：常见的是 2–4 个字的词组，甚至整句一个 cell。
     * 细化之后每个字的停留时长更接近真实演唱，扫过去的节奏才像跟唱。</p>
     *
     * <p>时长按<b>权重</b>分而不是等分：汉字算 1，标点/空格只算 0.25——中文逗号占掉
     * 一整个字的时长会让边界在标点处明显停顿一下。含拉丁字母的 cell 原样保留，
     * 把 {@code hello} 拆成五个字母会变成打字机。</p>
     */
    private static java.util.List<LyricsLine.Cell> refineCells(java.util.List<LyricsLine.Cell> cells) {
        if (cells == null || cells.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        java.util.List<LyricsLine.Cell> out = new java.util.ArrayList<>(cells.size() * 2);
        for (LyricsLine.Cell cell : cells) {
            String piece = cell.getText();
            long start = cell.getStartTime();
            long end = cell.getEndTime();
            if (piece == null || piece.isEmpty()) {
                continue;
            }
            int n = piece.codePointCount(0, piece.length());
            if (n <= 1 || end <= start || !noLatin(piece)) {
                out.add(cell);
                continue;
            }
            double[] weights = new double[n];
            double total = 0d;
            for (int i = 0; i < n; i++) {
                int cp = piece.codePointAt(piece.offsetByCodePoints(0, i));
                weights[i] = isPunctuationOrSpace(cp) ? 0.25d : 1d;
                total += weights[i];
            }
            long span = end - start;
            double acc = 0d;
            for (int i = 0; i < n; i++) {
                int from = piece.offsetByCodePoints(0, i);
                int to = piece.offsetByCodePoints(0, i + 1);
                long s = start + (long) (span * acc / total);
                acc += weights[i];
                long e = start + (long) (span * acc / total);
                if (e <= s) {
                    e = s + 1L;
                }
                out.add(new LyricsLine.Cell(s, e, piece.substring(from, to)));
            }
        }
        return out;
    }

    private static boolean isPunctuationOrSpace(int cp) {
        if (Character.isWhitespace(cp) || cp == '\u3000') {
            return true;
        }
        int type = Character.getType(cp);
        return type == Character.OTHER_PUNCTUATION
                || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION;
    }

    /** 不含拉丁字母才允许按字拆分（中文、日文、韩文、标点都可以拆）。 */
    private static boolean noLatin(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }

'''

s = s[:si] + new + s[ei:]
io.open(p, 'w', encoding='utf-8').write(s)
print('replaced %d chars with %d' % (ei - si, len(new)))
