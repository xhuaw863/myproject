# -*- coding: utf-8 -*-
"""
为「医师/护士」批量模拟生成国家医保业务编码 (his_staff.med_insur_code)。
- 沿用库内既有格式: 'H42' + 13 位数字(共 16 位), 全库唯一(与存量码查重后重生成)。
- 幂等: 仅回填 med_insur_code 为 NULL/空串 且 staff_type IN ('医师','护士') 且未删除的记录, 可重复执行。
- 阶段二消重: 存量跨脚本随机碰撞产生的重复码, 每对保留一条(非医师/护士角色优先, 其次 id 小者),
  其余重新分配唯一码; 重复对中的药师行不在需求范围不动码。
- 护士存量已全部有码, 脚本覆盖到亦不产生变更; 药师/技师/管理不在本次需求范围。
"""
import pymysql
import random

random.seed(20260926)

conn = pymysql.connect(host='127.0.0.1', port=3306, user='root',
                       password='bsoft', database='yb_interface', charset='utf8mb4',
                       autocommit=False)
cur = conn.cursor(pymysql.cursors.DictCursor)

# ---------------- 存量编码池(保证唯一) ----------------
cur.execute("SELECT DISTINCT med_insur_code FROM his_staff "
            "WHERE med_insur_code IS NOT NULL AND med_insur_code <> ''")
pool = set(r['med_insur_code'] for r in cur.fetchall())
print("存量已用编码: %d 个" % len(pool))


def gen_code():
    while True:
        code = "H42%013d" % random.randint(0, 10 ** 13 - 1)
        if code not in pool:
            pool.add(code)
            return code


# ---------------- 待补人员 ----------------
cur.execute("SELECT id, tenant_id, org_id, staff_no, staff_name, staff_type FROM his_staff "
            "WHERE deleted = 0 AND staff_type IN ('医师', '护士') "
            "AND (med_insur_code IS NULL OR med_insur_code = '') ORDER BY tenant_id, id")
rows = cur.fetchall()
for r in rows:
    code = gen_code()
    cur.execute("UPDATE his_staff SET med_insur_code=%s, update_by='system-seed', update_time=NOW() WHERE id=%s",
                (code, r['id']))
    print("补码: tenant=%s org=%s %s %s(%s) -> %s" % (
        r['tenant_id'], r['org_id'], r['staff_no'], r['staff_name'], r['staff_type'], code))
if not rows:
    print("补码阶段: 医师/护士无空码记录, 跳过。")

# ---------------- 阶段二: 存量重复码消重 ----------------
cur.execute("SELECT id, tenant_id, org_id, staff_no, staff_name, staff_type, med_insur_code "
            "FROM his_staff WHERE deleted = 0 AND med_insur_code IS NOT NULL AND med_insur_code <> '' "
            "AND med_insur_code IN (SELECT med_insur_code FROM (SELECT med_insur_code c FROM his_staff "
            "WHERE deleted = 0 AND med_insur_code IS NOT NULL AND med_insur_code <> '' "
            "GROUP BY med_insur_code HAVING COUNT(*) > 1) t) ORDER BY med_insur_code, id")
dup_rows = cur.fetchall()
groups = {}
for r in dup_rows:
    groups.setdefault(r['med_insur_code'], []).append(r)
for code, grp in groups.items():
    # 保留规则: 非医师/护士角色(药师等, 不在本次范围)优先保留, 其次 id 最小者; 其余重分新码
    grp.sort(key=lambda r: (0 if r['staff_type'] not in ('医师', '护士') else 1, r['id']))
    keep = grp[0]
    for r in grp[1:]:
        new_code = gen_code()
        cur.execute("UPDATE his_staff SET med_insur_code=%s, update_by='system-seed', update_time=NOW() WHERE id=%s",
                    (new_code, r['id']))
        print("消重: tenant=%s org=%s %s %s(%s) 原码%s与%s重复 -> %s" % (
            r['tenant_id'], r['org_id'], r['staff_no'], r['staff_name'], r['staff_type'],
            code, keep['staff_no'], new_code))
if not groups:
    print("消重阶段: 无重复码, 跳过。")

conn.commit()

# ---------------- 落库校验 ----------------
cur.execute("SELECT COUNT(*) c FROM his_staff WHERE deleted=0 AND staff_type IN ('医师','护士') "
            "AND (med_insur_code IS NULL OR med_insur_code='')")
remain = cur.fetchone()['c']
cur.execute("SELECT COUNT(DISTINCT med_insur_code) d, COUNT(*) t FROM his_staff "
            "WHERE deleted=0 AND med_insur_code IS NOT NULL AND med_insur_code<>''")
u = cur.fetchone()
cur.execute("SELECT COUNT(*) c FROM his_staff WHERE deleted=0 AND staff_type IN ('医师','护士')")
total_dn = cur.fetchone()['c']
print("医师/护士总数: %d | 空码剩余: %d | 全库编码唯一性: %d/%d %s" % (
    total_dn, remain, u['d'], u['t'], "OK" if u['d'] == u['t'] else "存在重复!"))
conn.close()
