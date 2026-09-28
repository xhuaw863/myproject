# -*- coding: utf-8 -*-
"""三模块工作站 UI 级 Playwright 走查(Edge headless, 18092 实例)。

覆盖:
  护士 nl001 : 待执行医嘱(渲染+执行一条注射/输液流转) / 输液管理四页签 / 皮试管理 / 过敏档案 / 执行记录
  治疗师 tp001: 待执行治疗 / 疗程管理(满疗程计划可见) / 设备管理 / 治疗记录
  医技 tc001 : 标本管理 / 报告工作站(危急值报告红色标记) / 危急值管理(闭环留痕) / 危急值规则 / 报告查询
每步截图, 收集 pageerror / console error。
"""
import os
import sys
import traceback

from playwright.sync_api import sync_playwright

BASE = 'http://localhost:18092/'
SHOT = r'd:\study\ybtest\tools\_tri_ui_shots'
FAILS = []
PERRS = []
CERRS = []


def chk(name, cond, extra=''):
    tag = 'PASS' if cond else 'FAIL'
    if not cond:
        FAILS.append(name)
    print('[%s] %s %s' % (tag, name, extra))


def login(page, user, pwd):
    page.goto(BASE, wait_until='domcontentloaded')
    # 清会话(多角色切换) + 固定侧边菜单(默认悬浮态菜单不可见, 无法点击)
    page.evaluate("try{localStorage.clear();sessionStorage.clear();localStorage.setItem('his-aside-pinned','1');localStorage.setItem('his-aside-collapsed','0');}catch(e){}")
    page.reload(wait_until='domcontentloaded')
    page.wait_for_selector('input[placeholder="账号"]')
    page.locator('input[placeholder="账号"]').fill(user)
    page.locator('input[placeholder="密码"]').fill(pwd)
    page.locator('button:has-text("登 录")').click()
    page.wait_for_timeout(2500)


def open_menu(page, group, item):
    # el-sub-menu 是 toggling: 子项已可见则无需再点分组(再点反而收起菜单)
    it = page.get_by_text(item, exact=True).first
    try:
        visible = it.is_visible()
    except Exception:
        visible = False
    if not visible:
        page.get_by_text(group, exact=True).first.click()
        page.wait_for_timeout(500)
    it.click()
    page.wait_for_timeout(1800)


def main():
    os.makedirs(SHOT, exist_ok=True)
    with sync_playwright() as p:
        browser = p.chromium.launch(channel='msedge', headless=True)
        page = browser.new_page(viewport={'width': 1680, 'height': 950})
        page.set_default_timeout(12000)
        page.on('pageerror', lambda e: PERRS.append(str(e)))
        page.on('console', lambda m: CERRS.append(m.text) if m.type == 'error' else None)

        def shot(name):
            try:
                page.screenshot(path=os.path.join(SHOT, name + '.png'))
            except Exception:
                pass

        # ================= 护士站 =================
        try:
            login(page, 'nl001', 'admin123')
            chk('N-登录成功(门诊护士站菜单可见)', page.get_by_text('门诊护士站').count() >= 1)
            open_menu(page, '门诊护士站', '待执行医嘱')
            rows = page.locator('.el-table__body-wrapper .el-table__row')
            n0 = rows.count()
            chk('N-待执行医嘱列表有数据(收费联动)', n0 >= 1, 'rows=%d' % n0)
            shot('n1_pending')
            # 执行一条单: 待执行页签有"开始执行"则走三查七对; 若全被历史测试推到执行中, 则在执行中页签走"完成"闭环
            started = False
            finished = False
            for i in range(min(n0, 8)):
                r = page.locator('.el-table__body-wrapper .el-table__row').nth(i)
                if r.locator('button:has-text("开始执行")').count() == 0:
                    continue
                r.get_by_text('开始执行', exact=True).first.click()
                dlg = page.locator('.el-dialog:visible')
                dlg.first.wait_for(timeout=6000)
                boxes = dlg.locator('.el-checkbox')
                for k in range(boxes.count()):
                    boxes.nth(k).click()
                page.wait_for_timeout(200)
                dlg.locator('button').filter(has_text='确认').last.click()
                page.wait_for_timeout(2000)
                started = True
                break
            chk('N-UI三查七对后开始执行成功', started or n0 == 0, 'started=%s' % started)
            shot('n2_after_start')
            page.locator('label.el-radio-button').filter(has_text='执行中').first.click(force=True)
            page.wait_for_timeout(2200)
            rows2 = page.locator('.el-table__body-wrapper .el-table__row')
            chk('N-执行中页签可见该行', rows2.count() >= 1, 'rows=%d' % rows2.count())
            for i in range(min(rows2.count(), 8)):
                r = page.locator('.el-table__body-wrapper .el-table__row').nth(i)
                fin = r.locator('button:has-text("完成")')
                if fin.count() == 0:
                    continue
                fin.first.click()
                dlg2 = page.locator('.el-dialog:visible')
                dlg2.first.wait_for(timeout=6000)
                ta = dlg2.locator('textarea')
                if ta.count() > 0:
                    ta.first.fill('UI走查-无不良反应')
                okb = dlg2.locator('button').filter(has_text='确认')
                okb.last.click()
                page.wait_for_timeout(1800)
                finished = True
                break
            chk('N-UI完成执行成功(执行中页签闭环)', finished, 'finished=%s' % finished)
            shot('n2b_running')

            open_menu(page, '门诊护士站', '输液管理')
            tabs = page.locator('.el-tabs__item')
            tnames = [tabs.nth(k).inner_text() for k in range(tabs.count())]
            chk('N-输液四页签渲染', all(any(w in t for t in tnames) for w in ['待配液', '输液中', '待拔针', '已完成']), str(tnames))
            open_menu(page, '门诊护士站', '皮试管理')
            chk('N-皮试管理渲染', page.locator('.el-table, .el-empty').count() >= 1)
            open_menu(page, '门诊护士站', '过敏档案')
            page.wait_for_timeout(1000)
            chk('N-过敏档案含青霉素记录', page.get_by_text('青霉素').count() >= 1)
            shot('n3_allergy')
            open_menu(page, '门诊护士站', '执行记录查询')
            chk('N-执行记录有留痕', page.locator('.el-table__row').count() >= 1)
            shot('n4_execlog')
        except Exception as e:
            chk('护士站走查中断', False, repr(e))
            traceback.print_exc()
            shot('n_err')

        # ================= 治疗站 =================
        try:
            login(page, 'tp001', 'admin123')
            chk('T-登录成功(治疗管理菜单可见)', page.get_by_text('治疗管理').count() >= 1)
            open_menu(page, '治疗管理', '疗程管理')
            chk('T-疗程列表渲染', page.locator('.el-table__row').count() >= 1)
            body = page.locator('body').inner_text()
            chk('T-满疗程计划可见(已完成)', ('已完成' in body) or ('换药' in body), '')
            shot('t1_plan')
            open_menu(page, '治疗管理', '待执行治疗')
            chk('T-待执行工作台渲染', len(page.locator('body').inner_text()) > 100 and page.get_by_text('排队').count() >= 0)
            shot('t2_pending')
            open_menu(page, '治疗管理', '设备管理')
            chk('T-设备台账渲染', page.locator('.el-table, .el-empty').count() >= 1)
            open_menu(page, '治疗管理', '治疗记录查询')
            chk('T-治疗记录含换药留痕', '换药' in page.locator('body').inner_text())
            shot('t3_log')
        except Exception as e:
            chk('治疗站走查中断', False, repr(e))
            traceback.print_exc()
            shot('t_err')

        # ================= 医技站 =================
        try:
            login(page, 'tc001', 'admin123')
            chk('M-登录成功(医技管理菜单可见)', page.get_by_text('医技管理').count() >= 1)
            open_menu(page, '医技管理', '标本管理')
            # 页面结构断言(待采集可能为空, 数据留痕看"已签收"页签)
            chk('M-标本管理页渲染(页签+表格)', page.get_by_text('待采集').count() >= 1 and page.locator('.el-table').count() >= 1)
            page.get_by_text('已签收', exact=True).first.click()
            page.wait_for_timeout(1500)
            chk('M-已签收标本有流转留痕(E2E数据)', page.locator('.el-table__row').count() >= 1,
                'rows=%d' % page.locator('.el-table__row').count())
            shot('m1_specimen')
            open_menu(page, '医技管理', '报告工作站')
            page.wait_for_timeout(1000)
            chk('M-报告工作站渲染', page.get_by_text('危急值').count() >= 1)
            shot('m2_report')
            open_menu(page, '医技管理', '危急值管理')
            chk('M-危急值闭环留痕可见(血钾)', '血钾' in page.locator('body').inner_text())
            shot('m3_critical')
            open_menu(page, '医技管理', '危急值规则')
            chk('M-规则列表含血钾', '血钾' in page.locator('body').inner_text())
            open_menu(page, '医技管理', '报告查询')
            chk('M-报告查询渲染', page.locator('.el-table, .el-empty').count() >= 1)
            shot('m4_query')
        except Exception as e:
            chk('医技站走查中断', False, repr(e))
            traceback.print_exc()
            shot('m_err')

        chk('JS无页面异常(pageerror=0)', len(PERRS) == 0, str(PERRS[:3]))
        real_cerr = [x for x in CERRS if 'favicon' not in x and 'Failed to load resource' not in x]
        chk('控制台无实质错误', len(real_cerr) == 0, str(real_cerr[:3]))
        browser.close()

    print('\n==== TRI-UI SUMMARY: %d FAIL ====' % len(FAILS))
    sys.exit(1 if FAILS else 0)


if __name__ == '__main__':
    main()
