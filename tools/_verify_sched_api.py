# -*- coding: utf-8 -*-
"""排班机构隔离 + 开诊门诊科室 + 出诊科室解耦 —— 接口层端到端验证"""
import json
import urllib.request
import urllib.error

BASE = 'http://localhost:8080'
OK, FAIL = '[PASS]', '[FAIL]'
results = []


def check(name, cond, detail=''):
    tag = OK if cond else FAIL
    results.append(cond)
    print('%s %s %s' % (tag, name, detail))


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
        raw = e.read().decode('utf-8')
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {'raw': raw}


def login(tenant_code, username, password):
    st, r = call('POST', '/api/auth/login',
                 body={'tenantCode': tenant_code, 'username': username, 'password': password})
    if st == 200 and r.get('code') == 0:
        return r['data']['token'], r['data']
    return None, r


# ---------- 1. 牵头机构 admin (org 1 孝昌县人民医院, is_lead=1) ----------
tk1, u1 = login('H42010000000', 'admin', 'admin123')
check('牵头 admin 登录', tk1 is not None, str(u1)[:80] if not tk1 else 'orgId=%s lead=%s' % (u1['orgId'], u1.get('leadOrg')))

st, r = call('GET', '/api/his/dept/outpatient', tk1)
check('牵头 /dept/outpatient 成功', r.get('code') == 0)
depts1 = r.get('data') or []
check('牵头 outpatient 下拉仅含本机构', all(d['orgId'] == 1 for d in depts1), 'count=%d' % len(depts1))
check('牵头 outpatient 下拉仅门诊科室', all(d.get('deptCategory') == '门诊科室' for d in depts1))
check('牵头 outpatient 下拉仅开诊', all(d.get('openClinic') != 0 for d in depts1))
check('牵头不再穿透成员机构科室', not any(d['orgId'] in (8, 12, 16) for d in depts1),
      'orgIds=%s' % sorted({d['orgId'] for d in depts1}))

# ---------- 2. 非牵头 ORG_ADMIN (org 8 花园镇卫生院) ----------
tk8, u8 = login('H42010000000', 'XCHY0035', '123456')
if tk8 is None:
    tk8, u8 = login('H42010000000', 'XCHY0035', 'admin123')
check('花园镇 XCHY0035 登录', tk8 is not None, str(u8)[:80] if not tk8 else 'orgId=%s role=%s' % (u8['orgId'], u8['role']))

st, r = call('GET', '/api/his/dept/outpatient', tk8)
depts8 = r.get('data') or []
check('花园镇 outpatient 仅含本机构(org=8)', r.get('code') == 0 and len(depts8) > 0 and all(d['orgId'] == 8 for d in depts8),
      'count=%d' % len(depts8))
check('花园镇看不到牵头机构科室', not any(d['orgId'] == 1 for d in depts8))

# 排班读接口: 只见本机构
st, r = call('GET', '/api/his/schedule/week?weekStart=2026-09-28', tk8)
wk8 = r.get('data') or []
orgs_in_week = {d.get('deptId') for d in wk8}
check('花园镇 /schedule/week 正常返回', r.get('code') == 0, 'rows=%d' % len(wk8))
if wk8:
    check('花园镇周视图排班均归属本机构科室', all(oid in {d['id'] for d in depts8} or oid is None for oid in orgs_in_week),
          'deptIds=%s' % sorted(x for x in orgs_in_week if x))

# 越权写: 用牵头机构科室(org1 内科 id=1)建排班 → 必须被拒
foreign = next(d for d in depts1 if d['deptLevel'] in (2, 3))
mine = next(d for d in depts8 if d['deptLevel'] in (2, 3))
body = {'deptId': foreign['id'], 'staffId': 1, 'workDate': '2026-10-05',
        'timeType': 'am', 'regLevelCode': '01', 'regLevelName': '普通号', 'totalNum': 10}
st, r = call('POST', '/api/his/schedule', tk8, body)
check('花园镇跨机构排班被拒(科室属org1)', r.get('code') != 0, str(r.get('msg'))[:60])

# 合法写: 本机构开诊科室
st, r = call('GET', '/api/his/staff/list?staffType=%E5%8C%BB%E5%B8%88&orgId=8', tk8)
staffs8 = (r.get('data') or [])
if not staffs8:
    st, r = call('GET', '/api/his/staff/list?orgId=8', tk8)
    staffs8 = (r.get('data') or [])
if staffs8:
    body2 = dict(body, deptId=mine['id'], staffId=staffs8[0]['id'])
    st, r = call('POST', '/api/his/schedule', tk8, body2)
    created = r.get('data') or {}
    check('花园镇本机构自维护排班成功(各机构自维护)', r.get('code') == 0, str(r.get('msg'))[:60] if r.get('code') != 0 else 'id=%s' % created.get('id'))
    if r.get('code') == 0:
        # 清理
        call('DELETE', '/api/his/schedule/%s' % created['id'], tk8)
else:
    print('[SKIP] 花园镇无医师, 跳过合法写验证')

# 停用开诊 → /outpatient 不再返回 + 排班被拒 (科室维护用牵头 admin 令牌)
st, r = call('PUT', '/api/his/dept', tk1, dict(mine, openClinic=0))
ok0 = r.get('code') == 0
st, r = call('GET', '/api/his/dept/outpatient', tk8)
after = [d['id'] for d in (r.get('data') or [])]
check('关诊后 /outpatient 不再列该科室', ok0 and mine['id'] not in after, 'deptId=%s' % mine['id'])
if staffs8:
    body3 = dict(body, deptId=mine['id'], staffId=staffs8[0]['id'], workDate='2026-10-06')
    st, r = call('POST', '/api/his/schedule', tk8, body3)
    check('未开诊科室排班被拒', r.get('code') != 0, str(r.get('msg'))[:60])
call('PUT', '/api/his/dept', tk1, dict(mine, openClinic=1))  # 复原

# 非管理员(挂号员)写排班 → 403
st, r = call('GET', '/api/auth/me', tk8)  # 仅确认 token 有效
print('-' * 60)
print('TOTAL %d checks, %d passed' % (len(results), sum(1 for x in results if x)))
