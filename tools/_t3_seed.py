# -*- coding: utf-8 -*-
"""Task#3 治疗管理 E2E 演示数据准备(幂等, 可重复执行):
1. 打印治疗类医嘱单详情与明细(供接口冒烟引用);
2. 将已开立(status=1)的治疗类医嘱单置为已收费(paid_flag=1), 模拟收费回写,
   使待执行工作台可见(收费模块属其他任务, 此处仅造 E2E 前置数据)。"""
import pymysql

conn = pymysql.connect(host='localhost', port=3306, user='root', password='bsoft',
                       database='yb_interface', charset='utf8mb4')
cur = conn.cursor(pymysql.cursors.DictCursor)

def q(sql, args=None):
    cur.execute(sql, args or ())
    return cur.fetchall()

print('== 治疗类医嘱单 ==')
orders = q("""SELECT id, order_no, visit_id, patient_id, patient_name, dr_id, dr_name,
              dept_id, dept_name, exec_dept_id, status, exec_status, paid_flag
              FROM his_order WHERE order_type='治疗' AND deleted=0 ORDER BY id DESC LIMIT 5""")
for o in orders:
    items = q("""SELECT id, item_code, item_name, quantity, unit FROM his_order_item
                 WHERE order_id=%s AND deleted=0 ORDER BY id""", (o['id'],))
    print(o)
    for it in items:
        print('   item:', it)

print()
print('== 置已收费(paid_flag=1): 已开立且未退的治疗单 ==')
cur.execute("UPDATE his_order SET paid_flag=1, update_time=NOW() WHERE order_type='治疗' AND status=1 AND paid_flag=0 AND deleted=0")
print('updated rows:', cur.rowcount)

print()
print('== 现存治疗设备 ==')
for r in q("SELECT id, equip_code, equip_name, equip_type, dept_id, status FROM his_treatment_equipment WHERE deleted=0"):
    print(r)

conn.commit()
conn.close()
print('done')
