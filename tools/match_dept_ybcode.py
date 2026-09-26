# -*- coding: utf-8 -*-
"""
按名称自动匹配院内科室 -> 医保科室编码(his_dept.yb_dept_code, 医保标准字典 caty 值域)。
背景: 医保结算/挂号(2201)报送必须用医保科别编码, 不允许回退院内科室编码。
匹配规则(按优先级, 只认高置信, 其余输出待手工清单):
  R1 已维护医保科别 dept_caty 且该码在 caty 值域内 -> 直接采用同码
  R2 科室名称与 caty 名称唯一精确同名
  R3 科室名称去掉尾缀(门诊/住院/病区/科门诊->科)后与 caty 名称唯一精确同名
- 仅处理 deleted=0 且 dept_level>=2 且 yb_dept_code 为空的行; 大类(1)与诊室/窗口不参与。
- 幂等: 已有 yb_dept_code 的行不动, 可重复执行。
"""
import pymysql
import re

conn = pymysql.connect(host='127.0.0.1', port=3306, user='root',
                       password='bsoft', database='yb_interface', charset='utf8mb4',
                       autocommit=False)
cur = conn.cursor(pymysql.cursors.DictCursor)

# ---------------- caty 字典: code 集合 + 名称唯一映射 ----------------
cur.execute("SELECT val_code, val_name FROM std_cv_code "
            "WHERE dict_code='caty' AND vali_flag='1'")
caty = cur.fetchall()
code_set = set(r['val_code'] for r in caty)
name_cnt, name_code = {}, {}
for r in caty:
    nm = (r['val_name'] or '').strip()
    name_cnt[nm] = name_cnt.get(nm, 0) + 1
    name_code[nm] = r['val_code']


def uniq_by_name(nm):
    """名称在字典中唯一命中才返回码(多个'其他'等重名视为歧义)"""
    return name_code.get(nm) if name_cnt.get(nm) == 1 else None


def strip_suffix(nm):
    """R3 规范化: 科门诊->科(内科门诊->内科); 其余剥尾缀 门诊/住院/病区"""
    s = re.sub(r'科门诊$', '科', nm)
    s = re.sub(r'(门诊|住院|病区)$', '', s)
    return s or None


# ---------------- 待匹配科室 ----------------
cur.execute("SELECT id, tenant_id, org_id, dept_level, dept_name, dept_caty FROM his_dept "
            "WHERE deleted = 0 AND dept_level >= 2 "
            "AND (yb_dept_code IS NULL OR yb_dept_code = '') ORDER BY tenant_id, org_id, id")
rows = cur.fetchall()

matched, manual = [], []
for r in rows:
    nm = (r['dept_name'] or '').strip()
    hit, rule = None, None
    if r['dept_caty'] and r['dept_caty'] in code_set:
        hit, rule = r['dept_caty'], 'R1科别同码'
    if not hit:
        hit = uniq_by_name(nm)
        rule = 'R2名称精确'
    if not hit:
        norm = strip_suffix(nm)
        if norm and norm != nm:
            hit = uniq_by_name(norm)
            rule = 'R3去尾缀精确(%s)' % norm
    if hit:
        cur.execute("UPDATE his_dept SET yb_dept_code=%s, update_by='name-match', update_time=NOW() WHERE id=%s",
                    (hit, r['id']))
        matched.append((r['id'], r['tenant_id'], r['org_id'], nm, hit, rule))
    else:
        manual.append((r['id'], r['tenant_id'], r['org_id'], r['dept_level'], nm))

conn.commit()

print("=== 自动匹配成功 %d 条 ===" % len(matched))
for m in matched:
    print("id=%s tenant=%s org=%s [%s] -> %s  (%s)" % (m[0], m[1], m[2], m[3], m[4], m[5]))
print("=== 未匹配, 请手工维护 %d 条 ===" % len(manual))
for m in manual:
    print("id=%s tenant=%s org=%s level=%s [%s]" % m)

# ---------------- 终检 ----------------
cur.execute("SELECT COUNT(*) c FROM his_dept WHERE deleted=0 AND dept_level>=2 "
            "AND (yb_dept_code IS NULL OR yb_dept_code='')")
print("剩余空码科室(level>=2): %d" % cur.fetchone()['c'])
conn.close()
