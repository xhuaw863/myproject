# -*- coding: utf-8 -*-
"""Task#3 治疗管理 页面 E2E(Playwright + 系统 Edge headless)。

覆盖: 登录 -> 待执行工作台(签到/开始[治疗师+设备+参数]/完成) -> 治疗计划(列表/行展开/调整)
      -> 治疗设备(列表/新增/编辑/删除/查询) -> 治疗记录(筛选/详情) -> JS异常检查。

前提: 应用已在 8080 运行; 冒烟脚本已准备演示数据(订单13下 2 条待执行, 计划编号/设备 EQ-T3-*)。
"""
import os
import sys
import traceback

from playwright.sync_api import sync_playwright

BASE = 'http://localhost:8080/'
SHOT_DIR = r'd:\study\ybtest\tools\_t3_shots'
FAILS = []
PAGE_ERRS = []
CONSOLE_ERRS = []


def chk(name, cond, extra=''):
    tag = 'PASS' if cond else 'FAIL'
    if not cond:
        FAILS.append(name)
    print('[%s] %s %s' % (tag, name, extra))


def main():
    os.makedirs(SHOT_DIR, exist_ok=True)
    with sync_playwright() as p:
        browser = p.chromium.launch(channel='msedge', headless=True)
        page = browser.new_page(viewport={'width': 1680, 'height': 950})
        page.set_default_timeout(10000)
        page.on('pageerror', lambda e: PAGE_ERRS.append(str(e)))
        page.on('console', lambda m: CONSOLE_ERRS.append(m.text) if m.type == 'error' else None)

        def shot(name):
            try:
                page.screenshot(path=os.path.join(SHOT_DIR, name + '.png'))
            except Exception:
                pass

        # ---------- 登录 ----------
        page.goto(BASE, wait_until='domcontentloaded')
        page.wait_for_selector('input[placeholder="账号"]')
        chk('SPA登录页渲染', page.locator('input[placeholder="医院登录码"]').input_value() == 'H42010000000')
        page.locator('button:has-text("登 录")').click()
        page.wait_for_selector('text=治疗管理', timeout=15000)
        chk('登录成功(主界面含治疗管理菜单)', True)
        page.get_by_text('治疗管理', exact=True).first.click()
        page.get_by_text('待执行治疗', exact=True).first.wait_for()
        shot('00_login_ok')

        # ---------- 1. 待执行治疗工作台 ----------
        try:
            page.get_by_text('待执行治疗', exact=True).first.click()
            page.wait_for_selector('text=签到排队')
            page.wait_for_selector('text=排队 2 人', timeout=10000)
            chk('工作台队列2人', True)
            chk('队列患者卡片', page.get_by_text('测试-急诊大额').count() >= 1)
            chk('初始待签到标签', page.get_by_text('待签到').count() >= 1)
            page.wait_for_selector('text=治疗项目')
            chk('右侧详情(针灸治疗)', page.get_by_text('针灸治疗').count() >= 1)
            shot('01_pending_initial')

            # 签到
            page.locator('button:has-text("患者签到")').click()
            page.wait_for_selector('text=已加入排队', timeout=8000)
            page.wait_for_selector('text=已签到', timeout=8000)
            chk('患者签到成功', True)

            # 开始治疗: 治疗师 + 设备 + 参数
            page.locator('button:has-text("开始治疗")').click()
            dlg = page.locator('.el-dialog:visible')
            dlg.locator('input[placeholder="选择治疗师"]').wait_for()
            dlg.locator('input[placeholder="选择治疗师"]').click()
            page.locator('.el-select-dropdown__item:visible').filter(has_text='曹静燕').first.click()
            dlg.locator('input[placeholder*="不指定设备"]').click()
            page.locator('.el-select-dropdown__item:visible').filter(has_text='EQ-T3-01').first.click()
            vals = dlg.locator('input[placeholder="参数值(如 2.0)"]')
            vals.nth(0).fill('腰部')
            vals.nth(1).fill('2.0')
            shot('02_start_dialog')
            page.locator('button:has-text("确认开始")').click()
            page.wait_for_selector('text=已开始治疗', timeout=8000)
            page.wait_for_selector('text=治疗中', timeout=8000)
            chk('开始治疗成功(状态治疗中)', True)

            # 完成治疗: 时长 + 患者反应
            page.locator('button:has-text("完成治疗")').click()
            dlg2 = page.locator('.el-dialog:visible')
            dlg2.locator('.el-input-number input').wait_for()
            dlg2.locator('.el-input-number input').fill('25')
            dlg2.locator('textarea').nth(0).click()  # 失焦提交时长
            dlg2.locator('textarea').nth(1).fill('UI E2E: 无明显不适')
            shot('03_finish_dialog')
            page.locator('button:has-text("确认完成")').click()
            page.wait_for_selector('text=本疗程已全部完成', timeout=8000)
            page.wait_for_selector('text=排队 1 人', timeout=10000)
            chk('完成治疗成功(单次疗程完成, 队列剩1人)', True)
            shot('04_pending_after_finish')
        except Exception as e:
            chk('工作台流程中断', False, repr(e))
            traceback.print_exc()

        # ---------- 2. 治疗计划 ----------
        try:
            page.get_by_text('治疗计划', exact=True).first.click()
            page.wait_for_selector('text=疗程管理')
            page.wait_for_selector('text=共 4 条', timeout=10000)
            chk('计划列表4条', True)
            chk('状态标签(已终止/执行中)', page.get_by_text('已终止').count() >= 1
                and page.get_by_text('执行中').count() >= 1)
            # 行展开
            page.locator('.el-table__expand-icon').first.click()
            page.wait_for_selector('.el-table__expanded-cell')
            page.wait_for_selector('.el-table__expanded-cell >> text=执行单号', timeout=8000)
            chk('行展开加载执行明细', True)
            shot('05_plan_list')

            # 调整低频脉冲治疗: 1 -> 2
            row = page.locator('tr', has_text='低频脉冲治疗').first
            row.get_by_role('button', name='调整').click()
            dlg3 = page.locator('.el-dialog:visible')
            dlg3.locator('.el-input-number input').wait_for()
            dlg3.locator('.el-input-number input').fill('2')
            dlg3.locator('textarea').first.click()
            dlg3.locator('textarea').first.fill('UI E2E 增加一次')
            page.locator('button:has-text("确认调整")').click()
            page.wait_for_selector('text=总次数已调整为 2', timeout=8000)
            page.wait_for_selector('text=0/2', timeout=8000)
            chk('计划调整成功(1->2, 进度0/2)', True)
            shot('06_plan_adjusted')
        except Exception as e:
            chk('计划页面流程中断', False, repr(e))
            traceback.print_exc()

        # ---------- 3. 治疗设备 ----------
        try:
            page.get_by_text('治疗设备', exact=True).first.click()
            page.wait_for_selector('text=治疗设备台账')
            page.wait_for_selector('text=EQ-T3-01', timeout=10000)
            chk('设备列表可见', page.get_by_text('低频脉冲治疗仪(II型)').count() >= 1
                and page.get_by_text('针灸治疗仪').count() >= 1)
            chk('维修状态标签', page.get_by_text('维修').count() >= 1)

            # 新增
            page.locator('button:has-text("新增设备")').click()
            dlg4 = page.locator('.el-dialog:visible')
            dlg4.locator('input[placeholder*="机构内唯一"]').wait_for()
            dlg4.locator('input[placeholder*="机构内唯一"]').fill('EQ-T3-UI')
            dlg4.locator('input[placeholder*="中频治疗仪"]').fill('UI验证设备')
            dlg4.locator('input[placeholder="选择类别"]').click()
            page.locator('.el-select-dropdown__item:visible').filter(has_text='理疗').first.click()
            dlg4.locator('input[placeholder="选择科室"]').click()
            page.locator('.el-select-dropdown__item:visible').filter(has_text='急诊科').first.click()
            shot('07_equip_dialog')
            page.locator('button:has-text("保 存")').click()
            page.wait_for_selector('text=设备已新增', timeout=8000)
            page.wait_for_selector('text=EQ-T3-UI', timeout=8000)
            chk('新增设备成功', True)

            # 编辑
            rowui = page.locator('tr', has_text='EQ-T3-UI').first
            rowui.get_by_role('button', name='编辑').click()
            dlg5 = page.locator('.el-dialog:visible')
            dlg5.locator('input[placeholder*="中频治疗仪"]').wait_for()
            dlg5.locator('input[placeholder*="中频治疗仪"]').fill('UI验证设备(改)')
            page.locator('button:has-text("保 存")').click()
            page.wait_for_selector('text=设备已更新', timeout=8000)
            page.wait_for_selector('text=UI验证设备(改)', timeout=8000)
            chk('编辑设备成功', True)

            # 删除
            rowui2 = page.locator('tr', has_text='UI验证设备(改)').first
            rowui2.get_by_role('button', name='删除').click()
            page.locator('.el-message-box:visible').get_by_role('button', name='删除').click()
            page.wait_for_selector('text=设备已删除', timeout=8000)
            page.wait_for_function("() => !document.body.innerText.includes('EQ-T3-UI')", timeout=8000)
            chk('删除设备成功', True)

            # 关键字查询
            page.locator('input[placeholder="设备编码/名称"]').fill('EQ-T3-02')
            page.locator('button:has-text("查询")').click()
            page.wait_for_selector('text=针灸治疗仪', timeout=8000)
            chk('设备关键字查询可用', True)
            shot('08_equip_list')
        except Exception as e:
            chk('设备页面流程中断', False, repr(e))
            traceback.print_exc()

        # ---------- 4. 治疗记录 ----------
        try:
            page.get_by_text('治疗记录', exact=True).first.click()
            page.wait_for_selector('text=执行明细查询')
            page.wait_for_selector('text=共 5 条', timeout=10000)
            chk('治疗记录5条', True)
            # 类别筛选
            page.locator('input[placeholder="全部类别"]').click()
            page.locator('.el-select-dropdown__item:visible').filter(has_text='理疗').first.click()
            page.locator('button:has-text("查询")').click()
            page.wait_for_selector('text=共 3 条', timeout=10000)
            chk('类别筛选(理疗)=3条', True)
            # 详情
            page.locator('button:has-text("详情")').first.click()
            page.wait_for_selector('text=治疗执行详情')
            dlg6 = page.locator('.el-dialog:visible')
            chk('详情弹窗字段完整', dlg6.get_by_text('执行单号').count() >= 1
                and dlg6.get_by_text('患者反应').count() >= 1
                and dlg6.get_by_text('治疗参数').count() >= 1)
            shot('09_log_detail')
            page.locator('button:has-text("关 闭")').click()
            shot('10_log_list')
        except Exception as e:
            chk('记录页面流程中断', False, repr(e))
            traceback.print_exc()

        # ---------- 汇总 ----------
        page.wait_for_timeout(800)
        chk('无未捕获JS异常', len(PAGE_ERRS) == 0, str(PAGE_ERRS[:3]))
        browser.close()

    if CONSOLE_ERRS:
        print('--- 控制台error消息(%d条, 供排查) ---' % len(CONSOLE_ERRS))
        for m in CONSOLE_ERRS[:10]:
            print('   *', m[:200])
    if FAILS:
        print('!!!!!! 页面E2E失败 %d 项:' % len(FAILS))
        for f in FAILS:
            print('   - ' + f)
        sys.exit(1)
    print('页面E2E全部通过 OK (截图目录: %s)' % SHOT_DIR)


if __name__ == '__main__':
    main()
