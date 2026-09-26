# -*- coding: utf-8 -*-
"""排班机构隔离改造 - 数据面探查: 管理员账号 / 门诊科室分布 / open_clinic 回填情况"""
import pymysql

c = pymysql.connect(host='localhost', port=3306, user='root', password='bsoft',
                    database='yb_interface', charset='utf8mb4')
cur = c.cursor()

print('== sys_user (ADMIN/ORG_ADMIN/SUPER_ADMIN) ==')
cur.execute("""SELECT u.id,u.username,u.role,u.org_id,u.tenant_id,o.org_name,o.is_lead
               FROM sys_user u LEFT JOIN sys_org o ON o.id=u.org_id
               WHERE u.deleted=0 AND u.role IN ('ADMIN','ORG_ADMIN','SUPER_ADMIN')""")
for r in cur.fetchall():
    print(r)

print('== sys_org ==')
cur.execute("SELECT id,org_name,org_code,is_lead,tenant_id FROM sys_org WHERE deleted=0")
for r in cur.fetchall():
    print(r)

print('== his_dept 门诊科室 按机构统计 ==')
cur.execute("""SELECT org_id, COUNT(*), SUM(open_clinic=1), SUM(open_clinic=0), SUM(status!=1)
               FROM his_dept WHERE deleted=0 AND dept_category='门诊科室' GROUP BY org_id""")
for r in cur.fetchall():
    print(r)

print('== 样例: 每机构前3条门诊科室(dept_level 2/3) ==')
cur.execute("""SELECT id,org_id,dept_name,dept_level,status,open_clinic FROM his_dept
               WHERE deleted=0 AND dept_category='门诊科室' AND dept_level IN (2,3)
               ORDER BY org_id,id LIMIT 15""")
for r in cur.fetchall():
    print(r)
