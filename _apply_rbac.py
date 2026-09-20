# -*- coding: utf-8 -*-
"""应用 sys_rbac.sql: 建 4 张新表 + 幂等为 sys_user/his_patient/his_staff 增列。"""
import re
import pymysql

SQL = r"yb-interface/src/main/resources/sql/sys_rbac.sql"

conn = pymysql.connect(host="localhost", user="root", password="bsoft",
                       database="yb_interface", charset="utf8mb4")
cur = conn.cursor()

sql = open(SQL, encoding="utf-8").read()

# 逐张建表(DROP + CREATE)
for tbl in ["sys_org", "sys_menu", "sys_role", "sys_role_menu"]:
    cur.execute("DROP TABLE IF EXISTS " + tbl)
    m = re.search(r"CREATE TABLE " + tbl + r" \([\s\S]*?ENGINE=InnoDB[^;]*;", sql)
    if not m:
        raise SystemExit("未找到建表语句: " + tbl)
    cur.execute(m.group(0))
    print("created", tbl)

# 幂等增列
ALTERS = [
    ("sys_user", "org_id", "BIGINT DEFAULT NULL COMMENT '归属机构ID'"),
    ("sys_user", "role_id", "BIGINT DEFAULT NULL COMMENT '角色ID(sys_role)'"),
    ("his_patient", "org_id", "BIGINT DEFAULT NULL COMMENT '建档/首诊机构ID'"),
    ("his_staff", "org_id", "BIGINT DEFAULT NULL COMMENT '职工归属机构ID'"),
]
for tbl, col, ddl in ALTERS:
    cur.execute(
        "SELECT COUNT(*) FROM information_schema.columns "
        "WHERE table_schema='yb_interface' AND table_name=%s AND column_name=%s",
        (tbl, col))
    if cur.fetchone()[0] == 0:
        cur.execute("ALTER TABLE %s ADD COLUMN %s %s" % (tbl, col, ddl))
        print("altered", tbl, "add", col)
    else:
        print("skip(exists)", tbl, col)

conn.commit()

# 校验
for tbl in ["sys_org", "sys_menu", "sys_role", "sys_role_menu"]:
    cur.execute("SHOW COLUMNS FROM " + tbl)
    print(tbl, "cols=", len(cur.fetchall()))
conn.close()
print("DONE")
