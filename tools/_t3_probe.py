# -*- coding: utf-8 -*-
"""Task#3 UI E2E 前数据基线核对(只读探测)。"""
import json

import pymysql

conn = pymysql.connect(host='localhost', port=3306, user='root', password='bsoft',
                       database='yb_interface', charset='utf8mb4', autocommit=True)
cur = conn.cursor(pymysql.cursors.DictCursor)


def dump(title, sql):
    print('== %s ==' % title)
    cur.execute(sql)
    for r in cur.fetchall():
        print(json.dumps(r, ensure_ascii=True, default=str))


dump('plans', "SELECT id, plan_no, item_name, category, total_sessions, completed_sessions, status"
              " FROM his_treatment_plan WHERE deleted = 0 ORDER BY id")
dump('execs', "SELECT id, exec_no, plan_id, session_index, exec_status,"
              " DATE_FORMAT(checkin_time, '%Y-%m-%d %H:%i:%s') AS ck"
              " FROM his_treatment_exec WHERE deleted = 0 ORDER BY id")
dump('exec-log by category (status IN 1,2,3)', "SELECT p.category, COUNT(*) AS c"
     " FROM his_treatment_exec e JOIN his_treatment_plan p ON p.id = e.plan_id AND p.deleted = 0"
     " WHERE e.deleted = 0 AND e.exec_status IN (1,2,3) GROUP BY p.category")
dump('equips EQ-T3*', "SELECT id, equip_code, equip_name, equip_type, dept_id, status"
     " FROM his_treatment_equipment WHERE deleted = 0 AND equip_code LIKE 'EQ-T3%'")
dump('staff 曹静燕/技师', "SELECT id, staff_name, staff_type FROM his_staff"
     " WHERE deleted = 0 AND staff_type = '技师' LIMIT 10")

conn.close()
print('probe done')
