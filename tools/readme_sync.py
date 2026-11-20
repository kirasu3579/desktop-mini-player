import io
p = 'README.md'
s = io.open(p, encoding='utf-8').read()
old = """**关键在「格内插值」**"""
new = """**画哪一行由播放位置决定，不由回调决定**。宿主的 `onLyricLineUpdated` 可能滞后几帧，也可能
提前把下一行推来。取「时间上更靠后的那一行」：滞后时用位置匹配的行追上进度，提前推来的行
也不会被打回上一行。否则滞后那几帧会把上一行算成 100%，整句突然全亮、等回调到了再跳回
行首重扫——观感就是「一下全渲染、一下又一顿一顿」。

**关键在「格内插值」**"""
assert old in s
s = s.replace(old, new, 1)
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')
