import io
p = 'src/main/java/com/miniplayer/MiniPlayerWindow.java'
s = io.open(p, encoding='utf-8').read()
old = """        java.util.List<LyricsLine.Cell> cells = refineCells(line == null ? null : line.getLyricsCells());
        double target;
        if (timelineUsable(cells, text)) {
            karaokeByCells = true;
            target = timelineEdge(cells, fm, left);
        } else {
            karaokeByCells = false;
            target = sweepEdge(line, text, fm, left);
        }"""
new = """        java.util.List<LyricsLine.Cell> cells = refineCells(line == null ? null : line.getLyricsCells());
        double target;
        boolean byCells = timelineUsable(cells, text);
        if (byCells != karaokeByCells) {
            // 只在路径切换时记一行：忽而丝滑忽而匀速，通常就是这两条路在来回切
            karaokeByCells = byCells;
            String head = text.length() > 12 ? text.substring(0, 12) : text;
            MiniPlayerPlugin.log("karaoke path -> " + (byCells ? "timeline" : "sweep")
                    + " cells=" + cells.size() + " textLen=" + text.length()
                    + " head=" + head);
        }
        if (byCells) {
            target = timelineEdge(cells, fm, left);
        } else {
            target = sweepEdge(line, text, fm, left);
        }"""
assert old in s
s = s.replace(old, new)
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')
