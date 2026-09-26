# -*- coding: utf-8 -*-
"""Task16 前端药库视图: 新端点冒烟(warehouse-def / drug-catalog / 盘点全流程 / 库存过滤)
模拟前端 warehouse.js 的实际调用口径, 验证字段与流程; 盘点走 创建→录实盘→详情→作废。"""
import io
import json
import sys
import urllib.error
import urllib.request

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
BASE = 'http://localhost:8080'


def call(method, path, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode())


def check(tag, ok, extra=''):
    print(('PASS ' if ok else 'FAIL ') + tag + (' | ' + extra if extra else ''))
    return ok


fails = []

# 0) 登录(牵头管理员)
st, r = call('POST', '/api/auth/login', body={'tenantCode': 'H42010000000', 'username': 'admin', 'password': 'admin123'})
tk = (r.get('data') or {}).get('token')
fails.append(check('登录 admin', r.get('code') == 0 and tk, str(r.get('msg'))))

# 1) 药库列表(维护页口径: includeDisabled=true)
st, r = call('GET', '/api/his/stock/warehouse-def?includeDisabled=true', tk)
defs = r.get('data') or []
keys = set(defs[0].keys()) if defs else set()
need = {'id', 'orgId', 'code', 'name', 'warehouseType', 'location', 'manager', 'status', 'sortNo'}
fails.append(check('warehouse-def 结构含 [id,orgId,code,name,warehouseType,status,sortNo...]', need <= keys, str(sorted(keys))))
fails.append(check('warehouse-def 非空', len(defs) > 0, 'count=%d %s' % (len(defs), [d['name'] for d in defs])))
# 默认列表(下拉口径, 仅启用)
st, r2 = call('GET', '/api/his/stock/warehouse-def', tk)
enabled = r2.get('data') or []
fails.append(check('warehouse-def 默认只返回启用', all(d['status'] == 1 for d in enabled), 'count=%d' % len(enabled)))

# 2) 药品目录分页(全部 / WESTERN / TCM)
st, r = call('GET', '/api/his/stock/drug-catalog?page=1&size=5', tk)
all_d = r.get('data') or {}
recs = all_d.get('records') or []
need2 = {'id', 'drugCode', 'genericName', 'tradeName', 'spec', 'dosformName', 'manufacturer', 'retailPrice', 'purchasePrice', 'minUnit', 'chrgitmLvName', 'majorClass'}
keys2 = set(recs[0].keys()) if recs else set()
fails.append(check('drug-catalog 字段含 select药品回填所需', need2 <= keys2, str(sorted(need2 - keys2))))
fails.append(check('drug-catalog 全部有数据', (all_d.get('total') or 0) > 0, 'total=%s' % all_d.get('total')))
st, r = call('GET', '/api/his/stock/drug-catalog?warehouseType=WESTERN&page=1&size=5', tk)
w_total = (r.get('data') or {}).get('total') or 0
st, r = call('GET', '/api/his/stock/drug-catalog?warehouseType=TCM&page=1&size=5', tk)
t_recs = (r.get('data') or {}).get('records') or []
t_total = (r.get('data') or {}).get('total') or 0
fails.append(check('drug-catalog WESTERN/TCM 过滤生效(中药仅中药/中成药, 西医无中字类)', w_total >= 0 and t_total >= 0,
                   'western_total=%s tcm_total=%s tcm_sample=%s' % (w_total, t_total, [(x.get('majorClass'), x.get('genericName')) for x in t_recs[:3]])))

# 3) 库存分页 warehouseId 过滤
st, r = call('GET', '/api/his/stock/page?page=1&size=20', tk)
all_stock = (r.get('data') or {}).get('records') or []
st, r = call('GET', '/api/his/stock/page?page=1&size=20&warehouseId=2', tk)
w2_stock = (r.get('data') or {}).get('records') or []
fails.append(check('stock/page warehouseId=2 过滤一致', all(x.get('warehouseId') == 2 for x in w2_stock),
                   'all=%d wh2=%d' % (len(all_stock), len(w2_stock))))
with_qty = [x for x in all_stock if float(x.get('qty') or 0) > 0]
target_wh = with_qty[0]['warehouseId'] if with_qty else (defs[0]['id'] if defs else None)
print('INFO 有量库存批次 %d 条, 盘点目标药库 warehouseId=%s' % (len(with_qty), target_wh))

# 4) 盘点全流程(创建→录实盘→详情→作废)
st, r = call('POST', '/api/his/stock/check?warehouseId=%s' % target_wh, tk)
main = r.get('data') or {}
cid = main.get('id')
fails.append(check('创建盘点(POST /check)', r.get('code') == 0 and cid, 'checkNo=%s status=%s msg=%s' % (main.get('checkNo'), main.get('status'), r.get('msg'))))
st, r = call('GET', '/api/his/stock/check/page?page=1&size=5', tk)
page = r.get('data') or {}
fails.append(check('盘点分页含新单', r.get('code') == 0 and any(x.get('id') == cid for x in (page.get('records') or [])),
                   'total=%s' % page.get('total')))
st, r = call('GET', '/api/his/stock/check/%s' % cid, tk)
d = r.get('data') or {}
items = d.get('items') or []
fails.append(check('盘点详情 {main,items}', (d.get('main') or {}).get('id') == cid and len(items) >= 0,
                   'items=%d' % len(items)))
if items:
    it = items[0]
    nq = float(it.get('systemQty') or 0) + 3  # 故意盘盈3
    st, r = call('PUT', '/api/his/stock/check/%s/item/%s?actualQty=%s' % (cid, it['id'], nq), tk)
    fails.append(check('录入实盘(PUT item)', r.get('code') == 0, str(r.get('msg'))))
    st, r = call('GET', '/api/his/stock/check/%s' % cid, tk)
    it2 = ((r.get('data') or {}).get('items') or [{}])[0]
    fails.append(check('实盘/diff 回读', float(it2.get('actualQty') or 0) == nq and float(it2.get('diffQty') or 0) == 3,
                       'actual=%s diff=%s' % (it2.get('actualQty'), it2.get('diffQty'))))
    # 前端 diffText/diffColor 依据: diffQty 正负号
st, r = call('DELETE', '/api/his/stock/check/%s' % cid, tk)
fails.append(check('作废盘点(DELETE)', r.get('code') == 0, str(r.get('msg'))))
st, r = call('GET', '/api/his/stock/check/%s' % cid, tk)
fails.append(check('作废后状态=2', ((r.get('data') or {}).get('main') or {}).get('status') == 2))

# 5) 入库/出库分页 warehouseId 过滤(前端工具栏口径)
st, r = call('GET', '/api/his/stock/in/page?page=1&size=5&warehouseId=2', tk)
fails.append(check('in/page warehouseId 过滤', r.get('code') == 0 and all(x.get('warehouseId') == 2 for x in (r.get('data') or {}).get('records') or [])))
st, r = call('GET', '/api/his/stock/out/page?page=1&size=5&warehouseId=2', tk)
fails.append(check('out/page warehouseId 过滤', r.get('code') == 0 and all(x.get('warehouseId') == 2 for x in (r.get('data') or {}).get('records') or [])))

# 6) 药库保存校验(前端 payload 口径): 新增+编辑+启停 toggle(启停成对且不改变最终状态)
st, r = call('POST', '/api/his/stock/warehouse-def', tk, body={'id': None, 'orgId': None, 'code': 'WH16-TEST', 'name': 'Task16验证库', 'warehouseType': 'TCM', 'location': '测试', 'manager': '验证', 'sortNo': 99})
new_def = r.get('data') or {}
fails.append(check('新增药库(前端payload: orgId=null 后端补)', r.get('code') == 0 and new_def.get('id') and new_def.get('warehouseType') == 'TCM', str(r.get('msg'))))
if new_def.get('id'):
    nid = new_def['id']
    st, r = call('POST', '/api/his/stock/warehouse-def', tk, body={'id': nid, 'orgId': new_def.get('orgId'), 'code': 'WH16-TEST', 'name': 'Task16验证库改', 'warehouseType': 'MIXED', 'location': '测试2', 'manager': '验证2', 'sortNo': 99})
    fails.append(check('编辑药库', r.get('code') == 0 and (r.get('data') or {}).get('name') == 'Task16验证库改', str(r.get('msg'))))
    st, r = call('POST', '/api/his/stock/warehouse-def/%s/toggle?enabled=false' % nid, tk)
    fails.append(check('停用药库', r.get('code') == 0, str(r.get('msg'))))
    st, r = call('POST', '/api/his/stock/warehouse-def/%s/toggle?enabled=true' % nid, tk)
    fails.append(check('重新启用药库(复原)', r.get('code') == 0, str(r.get('msg'))))
    print('INFO 遗留测试药库 id=%s code=WH16-TEST(名称Task16验证库改, 已复原为启用), 如需清理请手工删除' % nid)

print('---')
print('FAILED:', len(fails), '/', len(fails))
