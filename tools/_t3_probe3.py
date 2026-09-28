# -*- coding: utf-8 -*-
"""静态提取 vendor 中 expand-change 发射源码与 toggleRowExpansion 实现。"""
import re

P = r'D:\study\ybtest\yb-interface\src\main\resources\static\vendor\element-plus-index.full.min.js'
d = open(P, 'rb').read()
print('file bytes:', len(d))

def show(pat, before=150, after=150, limit=6):
    print('##### PATTERN', pat)
    n = 0
    for m in re.finditer(re.escape(pat), d):
        i = m.start()
        seg = d[max(0, i - before):i + after].decode('utf-8', 'replace')
        print('@%d: ...%s...' % (i, seg.replace('\n', ' ')))
        print('-' * 40)
        n += 1
        if n >= limit:
            break
    if not n:
        print('NOT FOUND')

show(b'expand-change')
show(b'toggleRowExpansion')
