# -*- coding: utf-8 -*-
"""三模块子菜单全清单(写文件避免控制台乱码)"""
import pymysql, io
conn = pymysql.connect(host='localhost', port=3306, user='root', password='bsoft',
                       database='yb_interface', charset='utf8mb4')
cur = conn.cursor()
cur.execute("""SELECT id,parent_id,menu_name,menu_key,comp FROM sys_menu
WHERE deleted=0 AND parent_id IN (69,75,80) ORDER BY parent_id,sort_no""")
with io.open(r'd:\study\ybtest\tools\_tri_menus_out.txt', 'w', encoding='utf-8') as f:
    for r in cur.fetchall():
        f.write(str(r) + '\n')
# 角色可见性
cur.execute("""SELECT r.role_code, r.role_name, r.all_menus, GROUP_CONCAT(m.menu_key) 
FROM sys_role r LEFT JOIN sys_role_menu rm ON rm.role_id=r.id 
LEFT JOIN sys_menu m ON m.id=rm.menu_id 
WHERE r.id IN (6,23,24) GROUP BY r.id""")
with io.open(r'd:\study\ybtest\tools\_tri_menus_out.txt', 'a', encoding='utf-8') as f:
    for r in cur.fetchall():
        f.write(str(r) + '\n')
conn.close()
print('ok')
