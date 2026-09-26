# -*- coding: utf-8 -*-
"""重置 XCHY0035 密码为 123456 (BCrypt $2a$, 与 Hutool 兼容) 并打印当前 hash 供对比"""
import bcrypt
import pymysql

conn = pymysql.connect(host='127.0.0.1', port=3306, user='root', password='bsoft',
                       database='yb_interface', charset='utf8mb4')
cur = conn.cursor()
cur.execute("SELECT username, LEFT(password,20) FROM sys_user WHERE username IN ('XCHY0035','admin') AND tenant_id=1")
for r in cur.fetchall():
    print('current:', r)

new_hash = bcrypt.hashpw(b'123456', bcrypt.gensalt(rounds=10, prefix=b'2a')).decode()
cur.execute("UPDATE sys_user SET password=%s WHERE username='XCHY0035' AND tenant_id=1", (new_hash,))
conn.commit()
print('rows updated:', cur.rowcount, 'new hash prefix:', new_hash[:10])
