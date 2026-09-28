# -*- coding: utf-8 -*-
"""输出最近若干就诊与其医嘱类型/收费标志, 供下游专项脚本定位夹具."""
import pymysql, sys, json
conn = pymysql.connect(host='127.0.0.1', user='root', password='bsoft', database='yb_interface',
                       charset='utf8mb4', cursorclass=pymysql.cursors.DictCursor)
with conn.cursor() as c:
    c.execute("""SELECT v.id visit_id, v.patient_id, v.patient_name, v.visit_status, v.charge_status,
                        GROUP_CONCAT(CONCAT(o.order_type, ':', o.id, '/paid', IFNULL(o.paid_flag,0))) orders
                 FROM his_visit v LEFT JOIN his_order o ON o.visit_id = v.id AND o.deleted = 0 AND o.status > 0
                 WHERE v.deleted = 0 GROUP BY v.id ORDER BY v.id DESC LIMIT 8""")
    for r in c.fetchall():
        print(json.dumps(r, ensure_ascii=False, default=str))
conn.close()
