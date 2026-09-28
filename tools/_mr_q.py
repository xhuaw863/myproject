# -*- coding: utf-8 -*-
import pymysql
c = pymysql.connect(host='127.0.0.1', user='root', password='bsoft', database='yb_interface', charset='utf8mb4')
cur = c.cursor()
cur.execute("SELECT id,username,real_name,role,role_id,org_id,tenant_id FROM sys_user WHERE deleted=0 AND tenant_id=1 AND role='DOCTOR' ORDER BY id LIMIT 10")
for r in cur.fetchall():
    print(r)
cur.execute("SELECT COUNT(*) FROM sys_user_role")
print('sys_user_role rows:', cur.fetchone()[0])
cur.execute("SELECT user_id, GROUP_CONCAT(role_id ORDER BY id) FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE username IN ('admin','dr_zhang')) GROUP BY user_id")
for r in cur.fetchall():
    print(r)
