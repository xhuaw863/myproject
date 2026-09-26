# -*- coding: utf-8 -*-
"""Task16 冒烟遗留清理: 删除本次验证新增的药库 WH16-TEST(id=3), 仅当无任何业务引用。"""
import io
import sys
import pymysql

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

conn = pymysql.connect(host='127.0.0.1', port=3306, user='root', password='bsoft',
                       database='yb_interface', charset='utf8mb4', autocommit=True)
cur = conn.cursor(pymysql.cursors.DictCursor)

cur.execute("SELECT id, code, name, status FROM his_warehouse_def WHERE code='WH16-TEST'")
rows = cur.fetchall()
print('待清理:', rows)
for r in rows:
    wid = r['id']
    refs = 0
    for tbl in ('his_drug_stock', 'his_stock_in', 'his_stock_out', 'his_stock_check'):
        cur.execute("SELECT COUNT(*) c FROM %s WHERE warehouse_id=%%s" % tbl, (wid,))
        c = cur.fetchone()['c']
        refs += c
        if c:
            print('  引用', tbl, c)
    if refs == 0:
        cur.execute('DELETE FROM his_warehouse_def WHERE id=%s', (wid,))
        print('已删除药库 id=%s (无业务引用)' % wid)
    else:
        print('存在业务引用, 跳过删除 id=%s' % wid)

cur.execute("SELECT id, code, name, status FROM his_warehouse_def")
print('剩余药库:', cur.fetchall())
conn.close()
