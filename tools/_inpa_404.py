# -*- coding: utf-8 -*-
from playwright.sync_api import sync_playwright
with sync_playwright() as p:
    b = p.chromium.launch(channel='msedge', headless=True)
    pg = b.new_context(viewport={'width': 1680, 'height': 950}).new_page()
    hits = []
    pg.on('response', lambda r: hits.append(r.url) if r.status == 404 else None)
    pg.goto('http://localhost:8080/', wait_until='domcontentloaded')
    pg.wait_for_selector('input[placeholder="账号"]')
    pg.locator('input[placeholder="医院登录码"]').fill('H42010000000')
    pg.locator('input[placeholder="账号"]').fill('admin')
    pg.locator('input[placeholder="密码"]').fill('admin123')
    pg.locator('button:has-text("登 录")').click(); pg.wait_for_timeout(2500)
    pg.evaluate("HIS.go('inp-doctor-ws')")
    pg.wait_for_selector('.iw-patient', timeout=12000)
    pg.locator('.iw-patient').first.click(); pg.wait_for_timeout(3000)
    print('404s:', hits)
    b.close()
