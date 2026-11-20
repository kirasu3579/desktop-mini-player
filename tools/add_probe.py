import io
p = 'preview.sh'
s = io.open(p, encoding='utf-8').read()
anchor = '  -cp "$CP;$PROJ/build/classes;$PROJ/build/preview" com.miniplayer.SimProbe\n'
add = anchor + ('"$JDK/bin/java.exe" -Dfile.encoding=UTF-8 ' + chr(92) + '\n'
                '  -cp "$CP;$PROJ/build/classes;$PROJ/build/preview" com.miniplayer.LyricSyncProbe\n')
assert anchor in s
s = s.replace(anchor, add, 1)
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')
