# -*- coding: utf-8 -*-
"""诊断12: 精确定位登录页加载期间 404 资源 URL。"""
from playwright.sync_api import sync_playwright

with sync_playwright() as p:
    b = p.chromium.launch(channel='msedge', headless=True)
    pg = b.new_page(viewport={'width': 1680, 'height': 950})
    bad = []
    pg.on('response', lambda r: bad.append((r.status, r.url)) if r.status >= 400 else None)
    pg.goto('http://localhost:8080/', wait_until='load')
    pg.wait_for_timeout(2500)
    print('status>=400 resources:')
    for s, u in bad:
        print('   ', s, u)
    b.close()
print('diag12 done')
