# -*- coding: utf-8 -*-
"""提取 vue.global.prod.js 中 yi (getNow 缓存) 的完整定义。"""
P = r'D:\study\ybtest\yb-interface\src\main\resources\static\vendor\vue.global.prod.js'
d = open(P, 'rb').read()
i = d.find(b'vi=Promise.resolve()')
print(d[i - 200:i + 300].decode('utf-8', 'replace'))
print('=====')
j = d.find(b'const yi=')
if j < 0:
    j = d.find(b',yi=')
print(d[j - 60:j + 260].decode('utf-8', 'replace'))
