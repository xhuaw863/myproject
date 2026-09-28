# -*- coding: utf-8 -*-
"""三模块(护士站/治疗/医技)全流程测试前的数据盘点(只读)."""
import pymysql, json

conn = pymysql.connect(host='127.0.0.1', port=3306, user='root', password='bsoft',
                       database='yb_interface', charset='utf8mb4', cursorclass=pymysql.cursors.DictCursor)

def q(sql, args=None):
    with conn.cursor() as c:
        c.execute(sql, args or ())
        return c.fetchall()

out = {}
out['role_tables'] = q("SELECT table_name,column_name FROM information_schema.columns WHERE table_schema='yb_interface' AND (table_name LIKE '%%role%%' OR table_name LIKE 'user%%') ORDER BY table_name")
out['sys_user_cols'] = [r['Field'] for r in q("SHOW COLUMNS FROM sys_user")]
out['order_types'] = q("SELECT order_type, paid_flag, exec_status, COUNT(*) n FROM his_order WHERE deleted=0 AND status>0 GROUP BY order_type,paid_flag,exec_status")
out['nurse_tables'] = {}
for t in ['his_nurse_exec','his_skin_test','his_infusion_record','his_patient_allergy',
          'his_treatment_plan','his_treatment_exec','his_treatment_equipment',
          'his_specimen','his_exam_report','his_exam_result_item','his_critical_value','his_critical_rule']:
    try:
        out['nurse_tables'][t] = q("SELECT COUNT(*) n FROM " + t)[0]['n']
    except Exception as e:
        out['nurse_tables'][t] = 'ERR ' + str(e)
        conn.rollback()
out['demo_paid_orders'] = q("SELECT id,visit_id,order_no,order_type,paid_flag,exec_status,tenant_id FROM his_order WHERE deleted=0 AND status>0 AND paid_flag=1 ORDER BY id DESC LIMIT 10")
out['demo_unpaid_orders'] = q("SELECT id,visit_id,order_no,order_type,paid_flag,exec_status FROM his_order WHERE deleted=0 AND status>0 AND paid_flag=0 ORDER BY id DESC LIMIT 10")
out['visits_charged'] = q("SELECT id,patient_id,patient_name,dept_id,visit_status,charge_status,tenant_id FROM his_visit WHERE deleted=0 AND visit_status=3 AND charge_status=0 ORDER BY id DESC LIMIT 10")
out['critical_rules'] = q("SELECT * FROM his_critical_rule LIMIT 10") if True else []
print(json.dumps(out, ensure_ascii=False, default=str, indent=1))
conn.close()
