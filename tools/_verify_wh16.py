# -*- coding: utf-8 -*-
"""Task16 前端药库视图改造: 数据前置校验(机构开展药品目录/药库定义/盘点表/库存)"""
import io
import sys
import pymysql

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

conn = pymysql.connect(host='127.0.0.1', port=3306, user='root', password='bsoft',
                       database='yb_interface', charset='utf8mb4', autocommit=True)
cur = conn.cursor(pymysql.cursors.DictCursor)


def q(sql, tag):
    try:
        cur.execute(sql)
        print(tag, cur.fetchall())
    except Exception as e:
        print(tag, 'ERR', e)


q("SELECT COUNT(*) c FROM his_org_catalog WHERE catalog_type='drug' AND enabled=1", 'org_catalog_drug_enabled')
q("SELECT org_id, COUNT(*) c FROM his_org_catalog WHERE catalog_type='drug' AND enabled=1 GROUP BY org_id LIMIT 10", 'by_org')
q("SELECT COUNT(*) c FROM his_drug_catalog WHERE status=1", 'drug_catalog_total')
q("SELECT id, org_id, code, name, warehouse_type, status, sort_no FROM his_warehouse_def LIMIT 20", 'warehouse_defs')
q("SELECT COUNT(*) c FROM his_drug_stock WHERE qty>0", 'stock_positive')
q("SELECT COUNT(*) c FROM his_drug_stock WHERE warehouse_id IS NULL", 'stock_no_warehouse')
q("SHOW TABLES LIKE 'his_stock_check%'", 'check_tables')
conn.close()
