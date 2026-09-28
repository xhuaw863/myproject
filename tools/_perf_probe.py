# -*- coding: utf-8 -*-
"""性能定位探针: 登录 18082 后对 用户/科室/职工 管理相关端点逐个计时, 找出慢点。"""
import json
import time

import requests

BASE = 'http://localhost:18082'


def login():
    r = requests.post(BASE + '/api/auth/login', json={
        'tenantCode': 'H42010000000', 'username': 'admin', 'password': 'admin123'})
    d = r.json()
    assert d.get('code') == 0 or d.get('success'), d
    return d['data']['token']


def timed(tok, name, path, params=None):
    h = {'Authorization': 'Bearer ' + tok}
    t0 = time.time()
    r = requests.get(BASE + path, headers=h, params=params, timeout=60)
    ms = (time.time() - t0) * 1000
    body = r.json()
    data = body.get('data')
    n = len(data) if isinstance(data, list) else '-'
    print('%8.0fms  rows=%-5s  %s %s' % (ms, n, path, json.dumps(params or {}, ensure_ascii=False)))
    return ms


def main():
    tok = login()
    print('--- 单轮计时 ---')
    timed(tok, 'user', '/api/sys/user/list')
    timed(tok, 'dept-tree', '/api/sys/org/tree')
    timed(tok, 'dept', '/api/his/dept/list')
    timed(tok, 'dept-tree2', '/api/his/dept/tree')
    timed(tok, 'staff', '/api/his/staff/list')
    timed(tok, 'menus', '/api/sys/menu/user-menus')
    print('--- 二轮(排除冷缓存) ---')
    timed(tok, 'user', '/api/sys/user/list')
    timed(tok, 'dept', '/api/his/dept/list')
    timed(tok, 'staff', '/api/his/staff/list')


if __name__ == '__main__':
    main()
