# -*- coding: utf-8 -*-
"""诊断11: 验证 row-key 修复后行展开是否稳定保持并加载明细数据。"""
from playwright.sync_api import sync_playwright

BASE = 'http://localhost:8080/'

with sync_playwright() as p:
    browser = p.chromium.launch(channel='msedge', headless=True)
    page = browser.new_page(viewport={'width': 1680, 'height': 950})
    page.set_default_timeout(15000)
    errors = []
    page.on('pageerror', lambda e: errors.append(str(e)[:160]))

    page.goto(BASE, wait_until='domcontentloaded')
    page.wait_for_selector('input[placeholder="账号"]')
    page.locator('button:has-text("登 录")').click()
    page.wait_for_selector('text=治疗管理', timeout=15000)
    page.get_by_text('治疗管理', exact=True).first.click()
    page.get_by_text('治疗计划', exact=True).first.wait_for()
    page.get_by_text('治疗计划', exact=True).first.click()
    page.wait_for_selector('text=共 4 条', timeout=10000)
    page.wait_for_timeout(600)

    icon = page.locator('.page-card .el-table__expand-icon').first
    icon.click()

    # 1. 展开行在 2 秒后仍保持
    page.wait_for_selector('.page-card .el-table__expanded-cell', timeout=5000)
    page.wait_for_timeout(2000)
    cells = page.locator('.page-card .el-table__expanded-cell').count()
    print('step1 after 2s, expandedCells =', cells, '(expect 1)')

    # 2. 明细加载: 内层表格出现 '执行单号' 表头与第1次数据行
    page.wait_for_selector('.page-card .el-table__expanded-cell >> text=执行单号', timeout=8000)
    print('step2 inner table header 执行单号 = OK')
    page.wait_for_selector('.page-card .el-table__expanded-cell >> text=第1次', timeout=8000)
    print('step3 inner data row 第1次 = OK')

    # 3. 收起再展开同一行
    icon.click()
    page.wait_for_timeout(600)
    cells = page.locator('.page-card .el-table__expanded-cell').count()
    print('step4 after collapse, expandedCells =', cells, '(expect 0)')
    icon.click()
    page.wait_for_timeout(1200)
    cells = page.locator('.page-card .el-table__expanded-cell').count()
    print('step5 re-expand, expandedCells =', cells, '(expect 1)')

    # 4. 第二行也能展开(并存)
    page.locator('.page-card .el-table__expand-icon').nth(1).click()
    page.wait_for_timeout(1200)
    cells = page.locator('.page-card .el-table__expanded-cell').count()
    print('step6 second row, expandedCells =', cells, '(expect 2)')

    print('pageerrors:', errors if errors else 'none')
    browser.close()
print('diag11 done')
