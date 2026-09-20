# -*- coding: utf-8 -*-
"""
把「孝昌县人民医院」(tenant_id=1, org_id=1) 全部在职职工批量加入用户管理(sys_user)。
- 账号 = 职工工号(staff_no); 默认密码 123456 (BCrypt $2a$10$, 与 Hutool BCrypt 兼容)。
- 角色按职工类别映射: 医师/技师->DOCTOR, 护士->NURSE, 药师->PHARMACIST, 管理->ADMIN。
- 关联 staff_id/dept_id/org_id/phone; 幂等: 已建账号(按 staff_id 或 username)自动跳过。
"""
import pymysql
import bcrypt

TENANT_ID = 1
ORG_ID = 1
DEFAULT_PWD = "123456"

# staff_type -> (role_code, role_id)
ROLE_MAP = {
    '医师': ('DOCTOR', 3),
    '技师': ('DOCTOR', 3),
    '护士': ('NURSE', 6),
    '药师': ('PHARMACIST', 4),
    '管理': ('ADMIN', 1),
}

conn = pymysql.connect(host='127.0.0.1', port=3306, user='root', password='bsoft',
                       database='yb_interface', charset='utf8mb4', autocommit=False)
cur = conn.cursor(pymysql.cursors.DictCursor)

# 目标职工
cur.execute("""SELECT id, staff_no, staff_name, staff_type, dept_id, phone
               FROM his_staff
               WHERE tenant_id=%s AND org_id=%s AND deleted=0 AND status=1
               ORDER BY id""", (TENANT_ID, ORG_ID))
staff = cur.fetchall()

# 已建账号: staff_id 集合 + username 集合(租户内)
cur.execute("SELECT staff_id, username FROM sys_user WHERE tenant_id=%s", (TENANT_ID,))
exist = cur.fetchall()
used_staff_ids = {r['staff_id'] for r in exist if r['staff_id'] is not None}
used_names = {r['username'] for r in exist}

# 统一密码散列(同一明文, 每用户独立加盐)
INSERT_SQL = """INSERT INTO sys_user
(tenant_id, username, password, real_name, role, staff_id, dept_id, dept_scope,
 org_id, role_id, phone, status, create_by, create_time)
VALUES
(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,1,'system-seed',NOW())"""

rows = []
skipped = 0
for s in staff:
    if s['id'] in used_staff_ids:
        skipped += 1
        continue
    username = s['staff_no']
    if not username:
        username = "staff%d" % s['id']
    # 保证租户内 username 唯一
    base = username
    n = 1
    while username in used_names:
        n += 1
        username = "%s_%d" % (base, n)
    used_names.add(username)

    role_code, role_id = ROLE_MAP.get(s['staff_type'], ('DOCTOR', 3))
    pwd_hash = bcrypt.hashpw(DEFAULT_PWD.encode('utf-8'),
                             bcrypt.gensalt(rounds=10, prefix=b"2a")).decode('utf-8')
    rows.append((TENANT_ID, username, pwd_hash, s['staff_name'], role_code,
                 s['id'], s['dept_id'], None, ORG_ID, role_id, s['phone']))

cur.executemany(INSERT_SQL, rows)
conn.commit()

print("目标职工 %d 人; 已建账号跳过 %d 人; 本次新增用户 %d 个。" % (len(staff), skipped, len(rows)))
from collections import Counter
byrole = Counter(r[4] for r in rows)
print("按角色:", dict(byrole))
print("默认密码:", DEFAULT_PWD)
conn.close()
