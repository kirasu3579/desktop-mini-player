import io

p = 'preview.sh'
s = io.open(p, encoding='utf-8').read()
bad = '"$JDK/bin/java.exe" -Dfile.encoding=UTF-8 \\n  -cp'
good = '"$JDK/bin/java.exe" -Dfile.encoding=UTF-8 ' + chr(92) + '\n  -cp'
assert bad in s, s[-400:]
s = s.replace(bad, good)
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')
