# -*- coding: utf-8 -*-
"""验证 /api/his/dept/outpatient 现返回 level3 诊室(仅按启用), 且 level2 未开诊科室仍被排除。"""
import json, urllib.request, urllib.error
BASE = 'http://localhost:8080'

def call(method, path, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token: req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode())

st, r = call('POST', '/api/auth/login', body={'tenantCode': 'H42010000000', 'username': 'admin', 'password': 'admin123'})
tk = r['data']['token']
st, r = call('GET', '/api/his/dept/outpatient', token=tk)
depts = r.get('data') or []
l3 = [d for d in depts if d.get('deptLevel') == 3]
l2 = [d for d in depts if d.get('deptLevel') == 2]
print('level3 rooms count:', len(l3))
for d in l3:
    print('  room', d.get('id'), d.get('deptName'), 'parent=', d.get('parentId'), 'openClinic=', d.get('openClinic'), 'status=', d.get('status'))
# 内科门诊(id=1)下诊室应出现
under1 = [d for d in l3 if d.get('parentId') == 1]
print('rooms under 内科门诊(parent=1):', [d.get('deptName') for d in under1])
# level2 未开诊科室应被排除
st2, r2 = call('GET', '/api/his/dept/outpatient', token=tk)
l2_open0 = [d for d in (r2.get('data') or []) if d.get('deptLevel') == 2 and d.get('openClinic') == 0]
print('level2 with openClinic=0 in result (should be empty):', [d.get('deptName') for d in l2_open0])
print('OK' if len(under1) >= 1 and not l2_open0 else 'CHECK')
