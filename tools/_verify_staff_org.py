# -*- coding: utf-8 -*-
"""医师机构校验用例数据探查 + 接口断言"""
import json
import urllib.request
import urllib.error
import pymysql

BASE = 'http://localhost:8080'
results = []


def check(name, cond, detail=''):
    results.append(cond)
    print('%s %s %s' % ('[PASS]' if cond else '[FAIL]', name, detail))


def call(method, path, token=None, body=None):
    data = json.dumps(body).encode('utf-8') if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, json.loads(resp.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode('utf-8'))


conn = pymysql.connect(host='localhost', port=3306, user='root', password='bsoft',
                       database='yb_interface', charset='utf8mb4')
cur = conn.cursor()
cur.execute("""SELECT id,staff_no,staff_name,org_id,staff_type FROM his_staff
               WHERE deleted=0 AND org_id IN (1,8) ORDER BY org_id, id LIMIT 10""")
for r in cur.fetchall():
    print('staff:', r)

# 牵头医师(org1) 与 卫生院医师(org8)
cur.execute("SELECT id,staff_no,staff_name FROM his_staff WHERE deleted=0 AND org_id=1 AND staff_type LIKE '%%医%%' LIMIT 1")
lead_staff = cur.fetchone()
cur.execute("SELECT id,staff_no,staff_name FROM his_staff WHERE deleted=0 AND org_id=8 AND staff_type LIKE '%%医%%' LIMIT 1")
hy_staff = cur.fetchone()
print('lead_staff=', lead_staff, 'hy_staff=', hy_staff)

cur.execute("SELECT id FROM his_dept WHERE deleted=0 AND org_id=1 AND dept_category='门诊科室' AND dept_level=2 AND status=1 AND open_clinic=1 LIMIT 1")
lead_dept = cur.fetchone()[0]
cur.execute("SELECT id FROM his_dept WHERE deleted=0 AND org_id=8 AND dept_category='门诊科室' AND dept_level=2 AND status=1 AND open_clinic=1 LIMIT 1")
hy_dept = cur.fetchone()[0]


def login(user, pwd):
    st, r = call('POST', '/api/auth/login', body={'tenantCode': 'H42010000000', 'username': user, 'password': pwd})
    return r['data']['token'] if r.get('code') == 0 else None


tk1 = login('admin', 'admin123')
tk8 = login('XCHY0035', '123456')
check('两账号登录', tk1 and tk8)

# 1) staff/list 牵头带 withSubOrgs=false 只见本院医师
st, r = call('GET', '/api/his/staff/list?staffType=%E5%8C%BB%E5%B8%88&withSubOrgs=false&orgId=1', tk1)
lst = r.get('data') or []
check('牵头 staff/list withSubOrgs=false 仅本院', r.get('code') == 0 and lst and all(s['orgId'] == 1 for s in lst),
      'count=%d' % len(lst))
st, r = call('GET', '/api/his/staff/list?staffType=%E5%8C%BB%E5%B8%88&orgId=1', tk1)
lst2 = r.get('data') or []
check('默认(不带参数)仍级联=职工管理页保留原能力', any(s['orgId'] != 1 for s in lst2), 'count=%d' % len(lst2))

# 2) 牵头 admin 用卫生院医师排班 → 拒
if hy_staff:
    body = {'deptId': lead_dept, 'staffId': hy_staff[0], 'workDate': '2026-10-08',
            'timeType': 'am', 'regLevelCode': '01', 'regLevelName': '普通号', 'totalNum': 10}
    st, r = call('POST', '/api/his/schedule', tk1, body)
    check('牵头用卫生院医师排班被拒', r.get('code') != 0, str(r.get('msg'))[:70])

# 3) 卫生院用牵头医师排班 → 拒
if lead_staff:
    body = {'deptId': hy_dept, 'staffId': lead_staff[0], 'workDate': '2026-10-08',
            'timeType': 'am', 'regLevelCode': '01', 'regLevelName': '普通号', 'totalNum': 10}
    st, r = call('POST', '/api/his/schedule', tk8, body)
    check('卫生院用牵头医师排班被拒', r.get('code') != 0, str(r.get('msg'))[:70])

# 4) 合法: 卫生院医师 + 卫生院科室 → 成功并清理
if hy_staff:
    body = {'deptId': hy_dept, 'staffId': hy_staff[0], 'workDate': '2026-10-08',
            'timeType': 'am', 'regLevelCode': '01', 'regLevelName': '普通号', 'totalNum': 10}
    st, r = call('POST', '/api/his/schedule', tk8, body)
    okc = r.get('code') == 0
    check('本院医师排班成功', okc, str(r.get('msg'))[:60] if not okc else 'id=%s' % (r.get('data') or {}).get('id'))
    if okc:
        call('DELETE', '/api/his/schedule/%s' % r['data']['id'], tk8)

# 5) 模板: 牵头 admin 给卫生院医师建模板 → 拒
if hy_staff:
    tpl = {'staffId': hy_staff[0], 'deptId': lead_dept, 'weekday': 1, 'timeType': 'am',
           'regLevelCode': '01', 'regLevelName': '普通号', 'regFee': 10, 'totalNum': 30}
    st, r = call('POST', '/api/his/schedule/template', tk1, tpl)
    if r.get('code') == 0:
        call('DELETE', '/api/his/schedule/template/%s' % r['data']['id'], tk1)
    check('牵头给卫生院医师建模板被拒', r.get('code') != 0, 'code=%s msg=%s' % (r.get('code'), str(r.get('msg'))[:50]))

print('-' * 60)
print('TOTAL %d checks, %d passed' % (len(results), sum(results)))
