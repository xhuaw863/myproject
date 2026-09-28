# -*- coding: utf-8 -*-
"""浏览器级网络计时 v2: 打开 用户/科室/职工 管理页(先切工作台再切回以触发重载), 记录 /api/ 耗时。"""
import time

from playwright.sync_api import sync_playwright

BASE = 'http://localhost:18082/'

with sync_playwright() as pw:
    browser = pw.chromium.launch(channel='msedge', headless=True)
    page = browser.new_page(viewport={'width': 1680, 'height': 950})
    page.set_default_timeout(15000)
    page.goto(BASE, wait_until='domcontentloaded')
    page.wait_for_selector('input[placeholder="账号"]')
    page.fill('input[placeholder="医院登录码"]', 'H42010000000')
    page.fill('input[placeholder="账号"]', 'admin')
    page.fill('input[placeholder="密码"]', 'admin123')
    page.locator('button:has-text("登 录")').click()
    page.wait_for_selector('text=欢迎回来', timeout=20000)
    lead = page.get_by_text('医共体管理', exact=True).first
    if not lead.is_visible():
        page.locator('.aside-pin').click()
    lead.wait_for(state='visible')
    lead.click()
    page.wait_for_timeout(300)

    pend, results = {}, []

    def on_req(req):
        if '/api/' in req.url:
            pend[req] = time.time()

    def on_fin(req):
        if req in pend:
            results.append((round((time.time() - pend.pop(req)) * 1000), req.url.replace(BASE, '')))

    page.on('request', on_req)
    page.on('requestfinished', on_fin)
    page.on('requestfailed', on_fin)

    for label in ['用户管理', '科室管理', '职工管理']:
        page.get_by_text('工作台', exact=True).first.click()
        page.wait_for_timeout(200)
        results.clear(); pend.clear()
        page.get_by_text(label, exact=True).first.click()
        page.wait_for_timeout(3500)
        print('== %s ==' % label)
        for ms, url in sorted(results, reverse=True)[:8]:
            print('%7dms  %s' % (ms, url[:120]))
        if not results:
            print('   (未捕获到请求)')
    browser.close()
