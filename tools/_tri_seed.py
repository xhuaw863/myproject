# -*- coding: utf-8 -*-
"""三模块全流程测试前置: 建/复用 本机构(org_id=1, tenant=1) 的 护士/治疗师/医技 职工与账号, 以及可开单的职工。
幂等: 按 code 查找已存在则仅建账号(或复用账号)。运行: python _tri_seed.py
"""
import pymysql

TENANT = 1
ORG = 1
PWD = 'nurse123'
import hashlib
# BCrypt 无法在此生成, 复用已有 admin 的散列(同密码需一致) -> 改为直接查 admin 的 hash 并复用
conn = pymysql.connect(host='127.0.0.1', user='root', password='bsoft', database='yb_interface',
                       charset='utf8mb4', autocommit=True)
cur = conn.cursor()
cur.execute("SELECT password FROM sys_user WHERE username='admin' AND tenant_id=1 AND deleted=0 LIMIT 1")
admin_hash = cur.fetchone()[0]
cur.execute("SELECT id, role_code FROM sys_role WHERE deleted=0")
roles = {r[1]: r[0] for r in cur.fetchall()}

STAFF = [
    ('NL001', '牛护士', '护士', 6),
    ('TP001', '谭治疗师', '技师', 23),
    ('TC001', '佟技师', '技师', 24),
]
USER = [
    ('nl001', '牛护士', roles['NURSE'], 27),
    ('tp001', '谭治疗师', roles['THERAPIST'], None),
    ('tc001', '佟技师', roles['TECHNICIAN'], None),
]

def ensure_staff(code, name, staff_type, dept_id):
    cur.execute("SELECT id FROM his_staff WHERE tenant_id=%s AND staff_no=%s AND deleted=0", (TENANT, code))
    r = cur.fetchone()
    if r:
        print('staff exists', code, r[0])
        return r[0]
    cur.execute("""INSERT INTO his_staff(tenant_id,org_id,staff_no,staff_name,staff_type,dept_id,status,deleted,create_by,create_time,update_by,update_time)
                   VALUES (%s,%s,%s,%s,%s,%s,1,0,'seed',NOW(),'seed',NOW())""", (TENANT, ORG, code, name, staff_type, dept_id))
    print('staff created', code, cur.lastrowid)
    return cur.lastrowid

def ensure_user(username, realname, role_id, staff_id):
    cur.execute("SELECT id FROM sys_user WHERE tenant_id=%s AND username=%s AND deleted=0", (TENANT, username))
    r = cur.fetchone()
    if r:
        print('user exists', username, r[0])
        return r[0]
    cur.execute("""INSERT INTO sys_user(tenant_id,username,password,real_name,role,role_id,staff_id,org_id,status,deleted,create_time,update_time)
                   VALUES (%s,%s,%s,%s,'',%s,%s,%s,1,0,NOW(),NOW())""", (TENANT, username, admin_hash, realname, role_id, staff_id, ORG))
    print('user created', username, cur.lastrowid)
    return cur.lastrowid

ids = {}
for code, name, st, dept in STAFF:
    ids[code] = ensure_staff(code, name, st, dept)
mapping = {'nl001': 'NL001', 'tp001': 'TP001', 'tc001': 'TC001'}
for username, realname, role_id, _ in USER:
    ensure_user(username, realname, role_id, ids[mapping[username]])
print('DONE org=%s tenant=%s staff=%s' % (ORG, TENANT, ids))
conn.close()
