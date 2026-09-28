# -*- coding: utf-8 -*-
"""提取 vendor 中 useExpandable(SY) 完整源码: watch 部分 + updateExpandRows + Oa。"""
import re

P = r'D:\study\ybtest\yb-interface\src\main\resources\static\vendor\element-plus-index.full.min.js'
d = open(P, 'rb').read()

i = d.find(b'if(o.value)r.value=c.slice();else if(d)')
print('found at', i)
seg = d[max(0, i - 4500):i + 900].decode('utf-8', 'replace')
print(seg)
print('=' * 60)
# 找 Oa 定义
for m in re.finditer(rb'[,;]Oa=\(', d):
    j = m.start()
    print('Oa @%d: %s' % (j, d[j - 50:j + 500].decode('utf-8', 'replace')))
    print('-' * 40)
