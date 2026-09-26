# -*- coding: utf-8 -*-
"""多班次验证: 同医师同日同时段 跨科室可加排 / 同科室拒绝 / 周视图单元格为列表"""
import json
import urllib.request
import datetime

BASE = 'http://localhost:8080'


def req(method, path, body=None, token=None):
    data = json.dumps(body).encode('utf-8') if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header('Content-Type', 'application/json')
    if token:
        r.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(r, timeout=20) as resp:
            return json.loads(resp.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode('utf-8'))


ok = fail = 0


def check(name, cond, detail=''):
    global ok, fail
    if cond:
        ok += 1
        print('PASS |', name, '|', detail)
    else:
        fail += 1
        print('FAIL |', name, '|', detail)


login = req('POST', '/api/auth/login', {'tenantCode': 'H42010000000', 'username': 'admin', 'password': 'admin123'})
tk = login['data']['token']
d = (datetime.date.today() + datetime.timedelta(days=30)).isoformat()

# 取内科门诊/外科门诊(2201/2203)与一个本院医师
depts = req('GET', '/api/his/dept/outpatient', token=tk)['data']
nk = next(x for x in depts if x['deptName'] == '内科门诊')
wk = next(x for x in depts if x['deptName'] == '外科门诊')
staffs = req('GET', '/api/his/staff/list?staffType=%E5%8C%BB%E5%B8%88&withSubOrgs=false', token=tk)['data']
st = staffs[0]
sid = st['id']
base = {'staffId': sid, 'workDate': d, 'timeType': 'am', 'totalNum': 10, 'deptId': nk['id'], 'orgId': nk['orgId']}

r1 = req('POST', '/api/his/schedule', dict(base), tk)
check('班次1: 内科门诊 上午 创建', r1.get('code') == 0, str(r1.get('msg')))
id1 = (r1.get('data') or {}).get('id')

r2 = req('POST', '/api/his/schedule', dict(base, deptId=wk['id'], totalNum=20), tk)
check('班次2: 同医师同日同时段 换外科门诊 加排成功', r2.get('code') == 0, str(r2.get('msg')))
id2 = (r2.get('data') or {}).get('id')

r3 = req('POST', '/api/his/schedule', dict(base), tk)
check('班次3: 再回内科门诊 被拒(同科室重复)', r3.get('code') != 0, str(r3.get('msg')))

wv = req('GET', '/api/his/schedule/week?weekStart=' + (datetime.date.fromisoformat(d) - datetime.timedelta(days=datetime.date.fromisoformat(d).weekday())).isoformat(), token=tk)['data']
row = next((x for x in wv if x['staffId'] == sid), None)
cell = (row or {}).get('slots', {}).get('wed_am' if datetime.date.fromisoformat(d).weekday() == 2 else 'slot', None)
# 动态计算星期前缀
prefix = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'][datetime.date.fromisoformat(d).weekday()]
cell = (row or {}).get('slots', {}).get(prefix + '_am')
check('周视图单元格为列表且含2个班次', isinstance(cell, list) and len(cell) == 2,
      'cell=' + json.dumps([c.get('deptName') for c in cell], ensure_ascii=False) if isinstance(cell, list) else str(cell))
if isinstance(cell, list):
    check('slot 携带 deptName 供卡片显示', {c.get('deptName') for c in cell} == {nk['deptName'], wk['deptName']},
        str([c.get('deptName') for c in cell]))

# 编辑: 把班次2改成内科门诊 应被拒(与班次1同科室同时段)
r4 = req('PUT', '/api/his/schedule', {'id': id2, 'deptId': nk['id']}, tk)
check('编辑换到已排科室 被拒', r4.get('code') != 0, str(r4.get('msg')))

for i in (id1, id2):
    if i:
        req('DELETE', '/api/his/schedule/' + str(i), token=tk)
print('清理测试排班完成')
print('RESULT: %d PASS / %d FAIL' % (ok, fail))
