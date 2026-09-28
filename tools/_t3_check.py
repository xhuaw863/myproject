# -*- coding: utf-8 -*-
"""Task#3 治疗管理: 数据现状检查(表/演示数据/菜单)。只读, 可重复执行。"""
import pymysql

conn = pymysql.connect(host='localhost', port=3306, user='root', password='bsoft',
                       database='yb_interface', charset='utf8mb4')
cur = conn.cursor(pymysql.cursors.DictCursor)

def q(sql, args=None):
    cur.execute(sql, args or ())
    return cur.fetchall()

# 1. 治疗三表是否存在
for t in ('his_treatment_plan', 'his_treatment_exec', 'his_treatment_equipment'):
    cur.execute("SELECT COUNT(*) c FROM information_schema.tables WHERE table_schema='yb_interface' AND table_name=%s", (t,))
    print(t, 'exists=' , cur.fetchone()['c'] > 0)

# 2. his_treatment_exec 列
try:
    rows = q("SHOW COLUMNS FROM his_treatment_exec")
    print('exec cols:', [r['Field'] for r in rows])
except Exception as e:
    print('exec cols err:', e)

# 3. 治疗类医嘱演示数据
try:
    rows = q("""SELECT o.id, o.order_no, o.order_type, o.status, o.exec_status, o.paid_flag, o.exec_dept_id,
                o.patient_name, o.dept_name, COUNT(oi.id) items
                FROM his_order o LEFT JOIN his_order_item oi ON oi.order_id = o.id AND oi.deleted = 0
                WHERE o.order_type='治疗' AND o.deleted=0 GROUP BY o.id ORDER BY o.id DESC LIMIT 10""")
    print('treatment orders:', rows)
except Exception as e:
    print('orders err:', e)

# 4. 治疗菜单
try:
    rows = q("SELECT id, menu_key, menu_name, comp, parent_id FROM sys_menu WHERE menu_key LIKE 'treatment%'")
    print('menus:', rows)
except Exception as e:
    print('menu err:', e)

# 5. 技师职工样例
rows = q("SELECT id, staff_no, staff_name, staff_type, dept_id FROM his_staff WHERE staff_type='技师' AND deleted=0 LIMIT 5")
print('technicians:', rows)

# 6. 各机构
rows = q("SELECT id, org_name, org_level FROM sys_org WHERE deleted=0 ORDER BY id LIMIT 8")
print('orgs:', rows)

conn.close()
