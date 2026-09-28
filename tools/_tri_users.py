# -*- coding: utf-8 -*-
import pymysql
conn = pymysql.connect(host='127.0.0.1', user='root', password='bsoft', database='yb_interface', charset='utf8mb4')
cur = conn.cursor()
cur.execute("SELECT u.id,u.username,u.real_name,u.role,u.role_id,u.org_id,u.staff_id,r.role_code FROM sys_user u LEFT JOIN sys_role r ON r.id=u.role_id WHERE u.deleted=0 AND u.status=1 ORDER BY u.id")
for r in cur.fetchall():
    print(*r)
cur.execute("SELECT id,role_code,role_name,tenant_id FROM sys_role WHERE deleted=0")
print('---roles---')
for r in cur.fetchall():
    print(*r)
conn.close()
