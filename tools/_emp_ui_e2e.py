# -*- coding: utf-8 -*-
"""职工任职与授权 UI 取证(Playwright + 系统 Edge headless, 本会话专属端口 18082)。

覆盖: 登录 -> 固定菜单 -> 医共体管理/职工管理 -> 编辑首行职工(D001) -> 切换"任职与授权"Tab
      -> 断言主任职行回显(孝昌县人民医院/内科门诊)与临调授权区渲染 -> 新增任职行交互 -> 取消不污染数据。
截图输出: 工作区根目录 verify_emp_*.png (纯取证, 只读浏览+本地加行后取消, 零写库)。
"""
import os
import traceback

from playwright.sync_api import sync_playwright

BASE = 'http://localhost:18082/'
FAILS = []


def chk(name, cond, extra=''):
    print('[%s] %s %s' % ('PASS' if cond else 'FAIL', name, extra))
    if not cond:
        FAILS.append(name)


def main():
    errs = []
    with sync_playwright() as p:
        browser = p.chromium.launch(channel='msedge', headless=True)
        page = browser.new_page(viewport={'width': 1680, 'height': 950})
        page.set_default_timeout(15000)
        page.on('pageerror', lambda e: errs.append(str(e)))

        def shot(name):
            page.screenshot(path=os.path.join(r'd:\study\ybtest', name + '.png'))

        # ---------- 登录 ----------
        page.goto(BASE, wait_until='domcontentloaded')
        page.wait_for_selector('input[placeholder="账号"]')
        page.fill('input[placeholder="医院登录码"]', 'H42010000000')
        page.fill('input[placeholder="账号"]', 'admin')
        page.fill('input[placeholder="密码"]', 'admin123')
        page.locator('button:has-text("登 录")').click()
        page.wait_for_selector('text=欢迎回来', timeout=20000)
        chk('登录成功', True)

        # ---------- 固定菜单 -> 医共体管理 -> 职工管理 ----------
        # 图钉为两态开关(存 localStorage): 菜单不可见时才点击固定, 保脚本重跑幂等
        lead = page.get_by_text('医共体管理', exact=True).first
        if not lead.is_visible():
            page.locator('.aside-pin').click()
        lead.wait_for(state='visible', timeout=8000)
        lead.click()
        page.get_by_text('职工管理', exact=True).first.click()
        page.locator('.el-table button:has-text("编辑")').first.wait_for(timeout=20000)
        n_rows = page.locator('.el-table button:has-text("编辑")').count()
        chk('职工列表加载(含编辑行)', n_rows > 0, 'rows=%d' % n_rows)
        shot('verify_emp_01_staff_list')

        # ---------- 编辑首行 -> 任职与授权 Tab ----------
        page.locator('.el-table button:has-text("编辑")').first.click()
        page.wait_for_selector('.el-dialog:visible')
        page.locator('.el-dialog:visible .el-tabs__item:has-text("任职与授权")').click()
        page.wait_for_selector('.el-dialog:visible >> text=临调科室授权', timeout=10000)
        # 主任职行懒加载回显: 机构/科室下拉选中值 + 主任职单选选中
        sel_inputs = page.locator('.el-dialog:visible .el-table:has(th:has-text("主任职")) input')
        vals = [sel_inputs.nth(i).input_value() for i in range(min(sel_inputs.count(), 4))]
        chk('任职Tab懒加载回显主任职', any('孝昌县人民医院' in v for v in vals) and any('内科门诊' in v for v in vals), str(vals))
        prim = page.locator('.el-dialog:visible .el-radio.is-checked, .el-dialog:visible .el-radio__input.is-checked')
        chk('主任职单选已勾选', prim.count() >= 1, 'checked=%d' % prim.count())
        alert = page.locator('.el-dialog:visible .el-alert:has-text("任职为事实")')
        chk('分层说明提示渲染', alert.count() >= 1)
        btns = page.locator('.el-dialog:visible button').all_inner_texts()
        chk('任职/授权操作按钮齐备', any('新增任职' in b for b in btns) and any('保存任职' in b for b in btns) and any('新增授权' in b for b in btns))
        shot('verify_emp_02_employ_tab')

        # ---------- 交互: 新增任职行(仅客户端) ----------
        emp_tbl = page.locator('.el-dialog:visible .el-table').first
        before = emp_tbl.locator('tbody tr').count()
        page.locator('.el-dialog:visible button:has-text("新增任职")').click()
        page.wait_for_timeout(300)
        after = emp_tbl.locator('tbody tr').count()
        chk('新增任职行交互生效', after == before + 1, '%d->%d' % (before, after))
        shot('verify_emp_03_add_row')

        # ---------- 取消关闭(零写库) ----------
        page.locator('.el-dialog:visible .el-dialog__footer button:has-text("取消")').click()
        page.wait_for_timeout(500)
        chk('取消关闭弹窗', page.locator('.el-dialog:visible').count() == 0)

        chk('无JS页面异常', not errs, ('; '.join(errs[:3])) if errs else '')
        browser.close()

    print('\n== UI取证结果: %d FAIL ==' % len(FAILS))
    return 1 if FAILS else 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except Exception:
        traceback.print_exc()
        raise SystemExit(2)
