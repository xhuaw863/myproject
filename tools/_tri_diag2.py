# -*- coding: utf-8 -*-
"""排查: 危急值规则/治疗执行单visit_id/场景C收费日结状态"""
import pymysql, io, sys
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
conn = pymysql.connect(host='localhost', port=3306, user='root', password='bsoft',
                       database='yb_interface', charset='utf8mb4')
cur = conn.cursor()

cur.execute("SELECT id,item_code,item_name,patient_type,low_threshold,high_threshold,is_active,tenant_id FROM his_critical_rule WHERE deleted=0")
print("== his_critical_rule ==")
for r in cur.fetchall():
    print(r)
cur.execute("SHOW TABLES LIKE 'his_charge%'")
print("== charge tables ==")
for r in cur.fetchall():
    print(r)

cur.execute("SELECT id,plan_id,exec_no,session_index,exec_status,checkin_time,tenant_id,deleted FROM his_treatment_exec ORDER BY id DESC LIMIT 8")
print("== his_treatment_exec latest ==")
for r in cur.fetchall():
    print(r)

cur.execute("SELECT id,plan_no,item_name,total_sessions,completed_sessions,status FROM his_treatment_plan ORDER BY id DESC LIMIT 5")
print("== his_treatment_plan latest ==")
for r in cur.fetchall():
    print(r)

cur.execute("SELECT id,exec_no,exec_type,exec_status,visit_id FROM his_nurse_exec ORDER BY id DESC LIMIT 10")
print("== his_nurse_exec latest ==")
for r in cur.fetchall():
    print(r)

cur.execute("SELECT id,order_id,status,critical_flag,report_no FROM his_exam_report ORDER BY id DESC LIMIT 5")
print("== his_exam_report latest ==")
for r in cur.fetchall():
    print(r)



conn.close()
