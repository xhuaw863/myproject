import pymysql

c = pymysql.connect(host='127.0.0.1', user='root', password='bsoft', database='yb_interface', charset='utf8mb4')
cur = c.cursor()
cur.execute("SELECT id, username, tenant_id, org_id, role, role_id, deleted FROM sys_user ORDER BY tenant_id, id")
print('user_id | username | tenant | org | role | role_id | deleted')
for r in cur.fetchall():
    print(' | '.join(str(x) for x in r))
cur.execute("SELECT id, tenant_id, role_code, role_name, all_menus, role_type, deleted FROM sys_role ORDER BY tenant_id, id")
print('\nrole_id | tenant | code | name | all_menus | type | deleted')
for r in cur.fetchall():
    print(' | '.join(str(x) for x in r))
cur.execute("SHOW TABLES LIKE 'sys_user_role'")
print('\nsys_user_role exists:', cur.fetchall())
c.close()
