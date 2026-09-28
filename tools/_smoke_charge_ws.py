# -*- coding: utf-8 -*-
"""收费工作站后端冒烟(8081 专用端口, 避免与并行会话抢 8080):
1. login(admin) 2. daily-summary 3. todo 取一条 4. bill/{visitId} 验证票据新字段
5. bills 取一张历史收费单 -> receipt/invoice-print 验证 11 类归并+大写"""
import io, json, sys, urllib.request

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
BASE = 'http://127.0.0.1:8081'


def call(method, path, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read().decode())


tk = call('POST', '/api/auth/login', body={'tenantCode': 'H42010000000', 'username': 'admin', 'password': 'admin123'})['data']['token']
print('[1] login OK')

s = call('GET', '/api/his/cashier/daily-summary', tk)['data']
print('[2] daily-summary:', json.dumps(s, ensure_ascii=False))

todo = call('GET', '/api/his/cashier/todo?page=1&size=5', tk)['data']
recs = todo.get('records') or []
print('[3] todo total=%s' % todo.get('total'))
assert recs, '无待收费患者(可先跑 tools/gen_charge_test_patients.py)'
vid = recs[0]['visit_id']

d = call('GET', '/api/his/cashier/bill/%s' % vid, tk)['data']
it0 = d['items'][0] if d['items'] else {}
print('[4] bill %s items=%s 首行字段: rebateClass=%s selfCost=%s outOfScope=%s invoiceCat=%s' % (
    vid, len(d['items']), it0.get('rebateClass'), it0.get('selfCost'), it0.get('outOfScope'), it0.get('invoiceCat')))
assert 'rebateClass' in it0 and 'invoiceCat' in it0, 'billDetail 票据字段缺失'

bills = call('GET', '/api/his/cashier/bills?page=1&size=5&billType=1', tk)['data'].get('records') or []
if bills:
    bid = bills[0]['id']
    rc = call('GET', '/api/his/cashier/receipt/%s' % bid, tk)['data']
    rit = rc['items'][0] if rc['items'] else {}
    print('[5] receipt bill=%s items=%s 首行 rebateClass=%s invoiceCat=%s' % (bid, len(rc['items']), rit.get('rebateClass'), rit.get('invoiceCat')))
    tp = call('GET', '/api/his/cashier/invoice-print/%s' % bid, tk)['data']
    cats = [(c['name'], str(c['amount'])) for c in tp['cats'] if float(c['amount'])]
    print('[6] invoice-print header=%s pay.upper=%s 非零归并类=%s' % (
        tp['header']['title'], tp['pay']['upper'], cats))
    assert tp['pay']['upper'], '大写为空'
    assert len(tp['cats']) == 11, '归并类应为11栏'
else:
    print('[5] 无历史收费单, 跳过 receipt/invoice-print(端到端阶段收费后再验)')
print('SMOKE PASS')
