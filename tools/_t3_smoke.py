# -*- coding: utf-8 -*-
"""Task#3 治疗管理模块 接口冒烟(E2E)。

覆盖: 登录 -> 设备CRUD -> 医嘱转计划 -> 待执行工作台 -> 签到/开始/完成/取消
      -> 调整(调大/调小) -> 终止 -> 治疗记录查询 -> 关键断言(疗程完成/医嘱回写/收费过滤)。

执行前提: 应用已在 8080 端口运行, MySQL 可连。
幂等: 每次运行先重置本脚本产生的治疗数据(订单 9 / ODT3TERM01 / ODT3RUN01 的计划与执行单、EQ-T3* 设备)。
"""
import json
import sys
import urllib.error
import urllib.request

import pymysql

BASE = 'http://127.0.0.1:8080'
FAILS = []


# ---------- 基础工具 ----------
def chk(name, cond, extra=''):
    tag = 'PASS' if cond else 'FAIL'
    if not cond:
        FAILS.append(name)
    print('[%s] %s %s' % (tag, name, extra))


def call(method, path, body=None, token=None, raw=False):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header('Content-Type', 'application/json;charset=UTF-8')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    data = json.dumps(body, ensure_ascii=False).encode('utf-8') if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=60) as resp:
            text = resp.read().decode('utf-8')
    except urllib.error.HTTPError as e:
        text = e.read().decode('utf-8')
    try:
        obj = json.loads(text)
    except Exception:
        obj = {'code': -1, 'msg': text[:200]}
    if raw:
        return obj
    return obj


def ok(resp):
    return resp.get('code') == 0


def records(resp):
    d = resp.get('data')
    if d is None:
        return []
    if isinstance(d, list):
        return d
    return d.get('records', [])


conn = pymysql.connect(host='localhost', port=3306, user='root', password='bsoft',
                       database='yb_interface', charset='utf8mb4', autocommit=True)
cur = conn.cursor(pymysql.cursors.DictCursor)


def q1(sql, args=None):
    cur.execute(sql, args or ())
    return cur.fetchall()


def qv(sql, args=None):
    rows = q1(sql, args)
    if not rows:
        return None
    return list(rows[0].values())[0]


def dbsql(sql, args=None):
    cur.execute(sql, args or ())
    return cur.rowcount


print('===== Phase 0: 登录 =====')
lg = call('POST', '/api/auth/login', {'tenantCode': 'H42010000000', 'username': 'admin', 'password': 'admin123'})
chk('登录成功', ok(lg), lg.get('msg', ''))
TOKEN = (lg.get('data') or {}).get('token')
chk('获取token', bool(TOKEN))
if not TOKEN:
    print('无法登录, 终止')
    sys.exit(1)
me = call('GET', '/api/auth/me', token=TOKEN)
print('  当前用户: %s orgId=%s staffId=%s' % ((me.get('data') or {}).get('realName'),
      (me.get('data') or {}).get('orgId'), (me.get('data') or {}).get('staffId')))

# 治疗师: 优先 id=96, 否则任取一个职工
th = q1("SELECT id, staff_name FROM his_staff WHERE id=96 AND deleted=0")
if not th:
    th = q1("SELECT id, staff_name FROM his_staff WHERE tenant_id=1 AND deleted=0 ORDER BY id LIMIT 1")
THERAPIST_ID = th[0]['id']
THERAPIST_NAME = th[0]['staff_name']
print('  治疗师: %s(id=%s)' % (THERAPIST_NAME, THERAPIST_ID))

# 订单9 关键信息(用于克隆构造新治疗单)
o9 = q1("SELECT * FROM his_order WHERE id=9 AND deleted=0")[0]
print('  订单9: %s patient=%s(%s) dept=%s(%s)' % (o9['order_no'], o9['patient_name'], o9['patient_id'],
      o9['dept_name'], o9['dept_id']))

print()
print('===== Phase 1: 数据重置(幂等) =====')
# 清掉本脚本产出的计划/执行单/设备, 让每次运行从头开始
xids = "SELECT id FROM his_order WHERE order_no IN ('ODT3TERM01','ODT3RUN01') AND deleted=0"
oid_list = [r['id'] for r in q1(xids)] + [9]
placeholders = ','.join(['%s'] * len(oid_list))
print('  重置涉及订单: %s' % oid_list)
n1 = dbsql("DELETE FROM his_treatment_exec WHERE order_id IN (%s)" % placeholders, oid_list)
n2 = dbsql("DELETE FROM his_treatment_plan WHERE order_id IN (%s)" % placeholders, oid_list)
dbsql("UPDATE his_order SET exec_status=0 WHERE id IN (%s)" % placeholders, oid_list)
n3 = dbsql("DELETE FROM his_treatment_equipment WHERE equip_code LIKE 'EQ-T3%%' AND tenant_id=1")
dbsql("UPDATE his_order SET paid_flag=1, status=1 WHERE id=9 AND deleted=0")
print('  清除 exec=%s plan=%s equip=%s' % (n1, n2, n3))


# 构造新治疗单(幂等: 已存在则复用并补明细)
def seed_order(order_no, diag, items):
    rows = q1("SELECT id FROM his_order WHERE order_no=%s AND deleted=0", (order_no,))
    if rows:
        return rows[0]['id']
    cur.execute("""INSERT INTO his_order (tenant_id, visit_id, order_no, patient_id, patient_name, dept_id, dept_name,
                     dr_id, dr_name, order_type, diag_name, total_amount, status, create_by, create_time, update_time,
                     deleted, exec_status, exec_dept_id, paid_flag)
                     VALUES (1, %s, %s, %s, %s, %s, %s, %s, %s, '治疗', %s, 0, 1, 't3-smoke', NOW(), NOW(), 0, 0, %s, 1)""",
                (o9['visit_id'], order_no, o9['patient_id'], o9['patient_name'], o9['dept_id'], o9['dept_name'],
                 o9['dr_id'], o9['dr_name'], diag, o9['dept_id']))
    oid = cur.lastrowid
    for code, name, qty in items:
        cur.execute("""INSERT INTO his_order_item (tenant_id, order_id, item_code, item_name, unit, quantity,
                         create_time, update_time, deleted) VALUES (1, %s, %s, %s, '次', %s, NOW(), NOW(), 0)""",
                    (oid, code, name, qty))
    return oid


TERM_ORDER = seed_order('ODT3TERM01', '腰痛(终止测试)', [('ZL0002', '推拿治疗', 1)])
RUN_ORDER = seed_order('ODT3RUN01', '颈椎病', [('ZL0001', '针灸治疗', 2), ('ZL0009', '低频脉冲治疗', 1)])
print('  终止测试订单 id=%s; 工作台演示订单 id=%s' % (TERM_ORDER, RUN_ORDER))

print()
print('===== Phase 2: 设备台账 CRUD =====')
eq1 = call('POST', '/api/treatment/equipment',
           {'equipCode': 'EQ-T3-01', 'equipName': '低频脉冲治疗仪', 'equipType': '理疗', 'deptId': o9['dept_id'], 'status': 1}, TOKEN)
chk('新增设备EQ-T3-01', ok(eq1), eq1.get('msg', ''))
EQ1_ID = (eq1.get('data') or {}).get('id')

eq2 = call('POST', '/api/treatment/equipment',
           {'equipCode': 'EQ-T3-02', 'equipName': '针灸治疗仪', 'equipType': '中医传统', 'deptId': o9['dept_id'], 'status': 1}, TOKEN)
chk('新增设备EQ-T3-02', ok(eq2), eq2.get('msg', ''))
EQ2_ID = (eq2.get('data') or {}).get('id')

dup = call('POST', '/api/treatment/equipment',
           {'equipCode': 'EQ-T3-01', 'equipName': '重复编码测试', 'equipType': '理疗', 'deptId': o9['dept_id']}, TOKEN)
chk('编码重复创建被拒', not ok(dup), 'msg=' + str(dup.get('msg')))

up = call('PUT', '/api/treatment/equipment/%s' % EQ1_ID, {'equipName': '低频脉冲治疗仪(II型)', 'status': 1}, TOKEN)
chk('编辑设备名称', ok(up), up.get('msg', ''))

up2 = call('PUT', '/api/treatment/equipment/%s' % EQ2_ID, {'equipName': '针灸治疗仪', 'equipType': '中医传统', 'deptId': o9['dept_id'], 'status': 2}, TOKEN)
chk('设备EQ-T3-02置为维修', ok(up2), up2.get('msg', ''))

eqd = call('POST', '/api/treatment/equipment',
           {'equipCode': 'EQ-T3-DEL', 'equipName': '待删除设备', 'equipType': '康复', 'deptId': o9['dept_id']}, TOKEN)
EQR_ID = (eqd.get('data') or {}).get('id')
dele = call('DELETE', '/api/treatment/equipment/%s' % EQR_ID, token=TOKEN)
chk('删除临时设备', ok(dele), dele.get('msg', ''))

lst = call('GET', '/api/treatment/equipment?deptId=%s&keyword=T3' % o9['dept_id'], token=TOKEN)
rows = records(lst)
codes = [r.get('equip_code') for r in rows]
chk('设备列表筛选(dept+keyword)', ok(lst) and 'EQ-T3-01' in codes and 'EQ-T3-02' in codes and 'EQ-T3-DEL' not in codes, str(codes))
st2 = [r for r in rows if r.get('equip_code') == 'EQ-T3-02']
chk('EQ-T3-02状态=维修(2)', st2 and st2[0].get('status') == 2, str(st2[0].get('status') if st2 else None))
nm1 = [r for r in rows if r.get('equip_code') == 'EQ-T3-01']
chk('EQ-T3-01名称已更新', nm1 and nm1[0].get('equip_name') == '低频脉冲治疗仪(II型)', str(nm1[0].get('equip_name') if nm1 else None))

print()
print('===== Phase 3: 订单9 全流程(转计划->签到->开始->完成->取消->调整) =====')
fo = call('POST', '/api/treatment/plan/from-order/9', token=TOKEN)
chk('订单9生成治疗计划', ok(fo), str(fo.get('data')))
d = fo.get('data') or {}
PLAN1 = (d.get('planIds') or [None])[0]
chk('planIds非空', PLAN1 is not None)
chk('创建数=1 跳过数=0', d.get('created') == 1 and d.get('skipped') == 0, str(d))

fo2 = call('POST', '/api/treatment/plan/from-order/9', token=TOKEN)
d2 = fo2.get('data') or {}
chk('重复转计划幂等(跳过)', ok(fo2) and d2.get('created') == 0 and d2.get('skipped') == 1, str(d2))

p1 = q1("SELECT * FROM his_treatment_plan WHERE id=%s", (PLAN1,))[0]
chk('计划单号ZL前缀+单次疗程', p1['plan_no'].startswith('ZL') and p1['total_sessions'] == 1 and p1['status'] == 0,
    '%s total=%s cat=%s' % (p1['plan_no'], p1['total_sessions'], p1['category']))

pend = call('GET', '/api/treatment/pending', token=TOKEN)
prows = records(pend)
myexec = [r for r in prows if r.get('plan_id') == PLAN1]
chk('待执行工作台可见(已收费)', ok(pend) and len(myexec) == 1, 'count=%s' % len(myexec))
EXEC1 = myexec[0]['id'] if myexec else None
chk('待执行含患者/项目/未签到', myexec and myexec[0].get('patient_name') == o9['patient_name']
    and myexec[0].get('checked_in') in (0, '0') and myexec[0].get('session_index') == 1, str(myexec[:1]))

ci = call('POST', '/api/treatment/exec/%s/checkin' % EXEC1, token=TOKEN)
chk('患者签到', ok(ci), str(ci.get('data')))
ci2 = call('POST', '/api/treatment/exec/%s/checkin' % EXEC1, token=TOKEN)
chk('重复签到幂等', ok(ci2) and (ci2.get('data') or {}).get('already') is True, str(ci2.get('data')))

# 调整总次数 1 -> 3 (调大; 会话1仍在途, 不重复补建)
adj = call('POST', '/api/treatment/plan/%s/adjust' % PLAN1, {'newTotal': 3, 'reason': '病情需要增加疗程'}, TOKEN)
ad = adj.get('data') or {}
chk('调整总次数1->3', ok(adj) and ad.get('totalSessions') == 3 and ad.get('completedSessions') == 0, str(ad))
p1 = q1("SELECT * FROM his_treatment_plan WHERE id=%s", (PLAN1,))[0]
chk('调整留痕remark', ad.get('totalSessions') == 3 and '1→3' in (p1.get('remark') or ''), str(p1.get('remark')))
inf = q1("SELECT id, session_index, exec_status FROM his_treatment_exec WHERE plan_id=%s AND exec_status IN (0,1) ORDER BY id", (PLAN1,))
chk('调整后在途仍1条(不重复补建)', len(inf) == 1 and inf[0]['session_index'] == 1, str(inf))

st_bad = call('POST', '/api/treatment/exec/%s/start' % EXEC1, {'therapistId': THERAPIST_ID, 'equipCode': 'EQ-T3-02'}, TOKEN)
chk('维修中设备不可开始', not ok(st_bad), 'msg=' + str(st_bad.get('msg')))

st = call('POST', '/api/treatment/exec/%s/start' % EXEC1, {'therapistId': THERAPIST_ID, 'equipCode': 'EQ-T3-01'}, TOKEN)
chk('开始治疗(设备EQ-T3-01)', ok(st) and (st.get('data') or {}).get('execStatus') == 1, str(st.get('data')))
row = q1("SELECT exec_status, exec_therapist_id, equipment_code, exec_date FROM his_treatment_exec WHERE id=%s", (EXEC1,))[0]
chk('执行单落库(治疗师/设备/日期)', row['exec_status'] == 1 and row['exec_therapist_id'] == THERAPIST_ID
    and row['equipment_code'] == 'EQ-T3-01' and str(row['exec_date']), str(row))

fin = call('POST', '/api/treatment/exec/%s/finish' % EXEC1,
           {'durationMin': 30, 'params': '频率=50Hz; 时间=20min', 'response': '无明显不适'}, TOKEN)
fd = fin.get('data') or {}
chk('完成治疗', ok(fin) and fd.get('execStatus') == 2, str(fd))
chk('完成1/3: 疗程未满+自动补建下一会话', fd.get('completedSessions') == 1 and fd.get('totalSessions') == 3
    and fd.get('planFinished') is False and fd.get('nextCreated') is True, str(fd))
rowf = q1("SELECT duration_min, parameters, patient_response FROM his_treatment_exec WHERE id=%s", (EXEC1,))[0]
chk('完成信息落库(时长/参数/反应)', rowf['duration_min'] == 30 and '50Hz' in (rowf['parameters'] or '')
    and rowf['patient_response'] == '无明显不适', str(rowf))

exec2 = q1("SELECT id, session_index FROM his_treatment_exec WHERE plan_id=%s AND exec_status = 0 ORDER BY session_index", (PLAN1,))
chk('自动补建会话2待执行', len(exec2) == 1 and exec2[0]['session_index'] == 2, str(exec2))
EXEC2 = exec2[0]['id'] if exec2 else None

can = call('POST', '/api/treatment/exec/%s/cancel' % EXEC2, {'reason': '患者临时不适, 暂停一次'}, TOKEN)
cd = can.get('data') or {}
chk('取消执行单(会话2)', ok(can) and cd.get('execStatus') == 3, str(cd))
r2 = q1("SELECT exec_status, cancel_reason, session_index FROM his_treatment_exec WHERE id=%s", (EXEC2,))[0]
chk('取消原因落库', r2['exec_status'] == 3 and r2['cancel_reason'] == '患者临时不适, 暂停一次', str(r2))
inf3 = q1("SELECT id, session_index, exec_status FROM his_treatment_exec WHERE plan_id=%s AND exec_status IN (0,1)", (PLAN1,))
chk('取消后自动补建会话3', len(inf3) == 1 and inf3[0]['session_index'] == 3, str(inf3))
EXEC3 = inf3[0]['id'] if inf3 else None

# 调整调小 3 -> 1: completed=1>=1 -> 计划完成 + 取消在途会话3 + 医嘱回写
adj2 = call('POST', '/api/treatment/plan/%s/adjust' % PLAN1, {'newTotal': 1, 'reason': '评估后疗程已足'}, TOKEN)
a2 = adj2.get('data') or {}
chk('调整3->1触发计划完成', ok(adj2) and a2.get('planFinished') is True and a2.get('orderWritten') is True
    and a2.get('cancelledExecs') == 1, str(a2))
p1f = q1("SELECT status, total_sessions, completed_sessions, remark FROM his_treatment_plan WHERE id=%s", (PLAN1,))[0]
chk('计划状态=1已完成且进度1/1', p1f['status'] == 1 and p1f['total_sessions'] == 1 and p1f['completed_sessions'] == 1, str(p1f))
e3 = q1("SELECT exec_status, cancel_reason FROM his_treatment_exec WHERE id=%s", (EXEC3,))[0]
chk('在途会话3被自动取消', e3['exec_status'] == 3 and '疗程已满' in (e3['cancel_reason'] or ''), str(e3))
ord9 = q1("SELECT exec_status FROM his_order WHERE id=9")[0]
chk('医嘱9 exec_status回写=2', ord9['exec_status'] == 2, str(ord9['exec_status']))

pend2 = call('GET', '/api/treatment/pending', token=TOKEN)
chk('计划完成后工作台不再显示', PLAN1 not in [r.get('plan_id') for r in records(pend2)])

pl1 = call('GET', '/api/treatment/plans?status=1&size=100', token=TOKEN)
chk('计划列表status=1含该计划', PLAN1 in [r.get('id') for r in records(pl1)])
pl0 = call('GET', '/api/treatment/plans?status=0&size=100', token=TOKEN)
chk('计划列表status=0不含该计划', PLAN1 not in [r.get('id') for r in records(pl0)])

dt = call('GET', '/api/treatment/plan/%s' % PLAN1, token=TOKEN)
dd = dt.get('data') or {}
exlist = dd.get('execs') or []
chk('计划详情含3条执行明细', len(exlist) == 3, 'n=%s' % len(exlist))
chk('详情含患者信息', (dd.get('plan') or {}).get('patient_name') == o9['patient_name'],
    str((dd.get('plan') or {}).get('patient_name')))
chk('详情明细含治疗师/设备名', any((e.get('therapist_name') == THERAPIST_NAME and e.get('equip_name') == '低频脉冲治疗仪(II型)') for e in exlist),
    str([(e.get('session_index'), e.get('exec_status')) for e in exlist]))

print()
print('===== Phase 4: 终止计划(新订单 ODT3TERM01) =====')
foT = call('POST', '/api/treatment/plan/from-order/%s' % TERM_ORDER, token=TOKEN)
dtT = foT.get('data') or {}
PLAN2 = (dtT.get('planIds') or [None])[0]
chk('终止测试: 转计划成功', ok(foT) and PLAN2 is not None, str(dtT))

ter0 = call('POST', '/api/treatment/plan/%s/terminate' % PLAN2, {'reason': ''}, TOKEN)
chk('空原因终止被拒', not ok(ter0), 'msg=' + str(ter0.get('msg')))

ter = call('POST', '/api/treatment/plan/%s/terminate' % PLAN2, {'reason': '患者转院, 停止治疗'}, TOKEN)
td = ter.get('data') or {}
chk('终止计划成功', ok(ter) and td.get('status') == 2 and td.get('cancelledExecs') == 1, str(td))
p2f = q1("SELECT status, terminate_reason FROM his_treatment_plan WHERE id=%s", (PLAN2,))[0]
chk('终止状态/原因落库', p2f['status'] == 2 and p2f['terminate_reason'] == '患者转院, 停止治疗', str(p2f))
eT = q1("SELECT exec_status, cancel_reason FROM his_treatment_exec WHERE plan_id=%s", (PLAN2,))
chk('在途执行单被取消(计划已终止)', eT and eT[0]['exec_status'] == 3 and eT[0]['cancel_reason'] == '治疗计划已终止', str(eT))
oTC = q1("SELECT exec_status FROM his_order WHERE id=%s", (TERM_ORDER,))[0]
chk('终止测试订单回写exec_status=2', oTC['exec_status'] == 2, str(oTC['exec_status']))

print()
print('===== Phase 5: 工作台演示数据 + 收费过滤(ODT3RUN01) =====')
foR = call('POST', '/api/treatment/plan/from-order/%s' % RUN_ORDER, token=TOKEN)
dR = foR.get('data') or {}
chk('演示订单转2条计划', ok(foR) and dR.get('created') == 2 and len(dR.get('planIds') or []) == 2, str(dR))
RUN_PLANS = dR.get('planIds') or []

pend3 = call('GET', '/api/treatment/pending', token=TOKEN)
inR = [r for r in records(pend3) if r.get('plan_id') in RUN_PLANS]
chk('演示计划2条待执行可见', len(inR) == 2, 'n=%s' % len(inR))

dbsql("UPDATE his_order SET paid_flag=0 WHERE id=%s", (RUN_ORDER,))
pend4 = call('GET', '/api/treatment/pending', token=TOKEN)
inR2 = [r for r in records(pend4) if r.get('plan_id') in RUN_PLANS]
chk('未收费单不出现在工作台', len(inR2) == 0, 'n=%s' % len(inR2))
dbsql("UPDATE his_order SET paid_flag=1 WHERE id=%s", (RUN_ORDER,))
pend5 = call('GET', '/api/treatment/pending', token=TOKEN)
inR3 = [r for r in records(pend5) if r.get('plan_id') in RUN_PLANS]
chk('恢复收费后重新可见', len(inR3) == 2, 'n=%s' % len(inR3))

print()
print('===== Phase 6: 治疗记录查询 exec-log =====')
lg1 = call('GET', '/api/treatment/exec-log?size=100', token=TOKEN)
lrecs = records(lg1)
chk('治疗记录分页可见', ok(lg1) and len(lrecs) >= 4, 'n=%s' % len(lrecs))
chk('记录含治疗师姓名', any(r.get('therapist_name') == THERAPIST_NAME for r in lrecs))
chk('记录含设备名称', any(r.get('equip_name') == '低频脉冲治疗仪(II型)' for r in lrecs))
chk('记录不含纯待执行(status=0)', all(r.get('exec_status') in (1, 2, 3) for r in lrecs))

lg2 = call('GET', '/api/treatment/exec-log?category=physiotherapy&size=100', token=TOKEN)
chk('按类别筛选(理疗)', ok(lg2) and len(records(lg2)) >= 3
    and all(r.get('category') == 'physiotherapy' for r in records(lg2)), 'n=%s' % len(records(lg2)))

from datetime import date
today = date.today().strftime('%Y-%m-%d')
lg3 = call('GET', '/api/treatment/exec-log?startDate=%s&endDate=%s&size=100' % (today, today), token=TOKEN)
chk('按日期区间筛选(今天)', ok(lg3) and len(records(lg3)) >= 4, 'n=%s' % len(records(lg3)))

lg4 = call('GET', '/api/treatment/exec-log?therapistId=%s&size=100' % THERAPIST_ID, token=TOKEN)
chk('按治疗师筛选', ok(lg4) and len(records(lg4)) >= 1, 'n=%s' % len(records(lg4)))

print()
print('===== Phase 7: 计划列表关键字检索 =====')
from urllib.parse import quote
plk = call('GET', '/api/treatment/plans?keyword=%s&size=100' % quote(o9['patient_name']), token=TOKEN)
chk('按患者姓名检索计划', ok(plk) and len(records(plk)) >= 2, 'n=%s' % len(records(plk)))

print()
conn.close()
if FAILS:
    print('!!!!!! 冒烟失败 %d 项:' % len(FAILS))
    for f in FAILS:
        print('   - ' + f)
    sys.exit(1)
print('冒烟全部通过 OK')
