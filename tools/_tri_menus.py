# -*- coding: utf-8 -*-
"""UI走查前盘点: 三模块角色可见菜单(nl001/tp001/tc001)"""
import pymysql, io, sys
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
conn = pymysql.connect(host='localhost', port=3306, user='root', password='bsoft',
                       database='yb_interface', charset='utf8mb4')
cur = conn.cursor()
cur.execute("""SELECT u.username, u.role_id, u.role FROM sys_user u
WHERE u.username IN ('nl001','tp001','tc001','admin')""")
print("== users ==")
for r in cur.fetchall():
    print(r)
cur.execute("SHOW COLUMNS FROM sys_role")
print("== sys_role cols ==")
for r in cur.fetchall():
    print(r[0])
cur.execute("SHOW COLUMNS FROM sys_menu")
print("== sys_menu cols ==")
for r in cur.fetchall():
    print(r[0])
cur.execute("""SELECT id,parent_id,menu_name,menu_key,comp FROM sys_menu
WHERE deleted=0 AND (menu_name LIKE '%护士%' OR menu_name LIKE '%治疗%' OR menu_name LIKE '%医技%'
OR menu_name LIKE '%皮试%' OR menu_name LIKE '%标本%' OR menu_name LIKE '%危急%' OR menu_name LIKE '%过敏%'
OR menu_name LIKE '%报告%' OR menu_name LIKE '%设备%' OR menu_name LIKE '%计划%') ORDER BY parent_id,id""")
print("== menus ==")
for r in cur.fetchall():
    print(r)
conn.close()
