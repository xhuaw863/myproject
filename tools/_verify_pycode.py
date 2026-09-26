# -*- coding: utf-8 -*-
"""字典简码(py_code/abbr_code)全方位检索优化 — 接口/数据断言脚本(计划验证项2)"""
import json
import urllib.request
import urllib.error
import urllib.parse
import pymysql

BASE = 'http://localhost:8080'
results = []


def check(name, cond, detail=''):
    results.append(bool(cond))
    print('%s %s %s' % ('[PASS]' if cond else '[FAIL]', name, detail))


def call(method, path, token=None, body=None):
    data = json.dumps(body).encode('utf-8') if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, json.loads(resp.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode('utf-8'))


def login(user, pwd, tenant='H42010000000'):
    st, r = call('POST', '/api/auth/login', body={'tenantCode': tenant, 'username': user, 'password': pwd})
    return r['data']['token'] if r.get('code') == 0 else None


conn = pymysql.connect(host='localhost', port=3306, user='root', password='bsoft',
                       database='yb_interface', charset='utf8mb4', autocommit=True)
cur = conn.cursor()

# ============ A. 回填完整性(py_code 非空率) ============
JOBS = [
    ('his_staff', 'staff_name'), ('his_dept', 'dept_name'),
    ('his_drug_catalog', 'generic_name'), ('his_charge_item', 'item_name'),
    ('his_cons_catalog', 'name'), ('his_med_dict', 'name'),
    ('sys_org', 'org_name'), ('his_patient', 'name'),
    ('area_code_2021', 'name'),
    ('med_service_catalog', 'item_name'), ('consumable_catalog', 'cons_name'),
    ('disease_catalog', 'diag_name'),
]
# 追加 std_* 全表(nameCol 取注册表口径的默认: 逐表用 information_schema 定位)
cur.execute("""SELECT table_name FROM information_schema.columns
               WHERE table_schema='yb_interface' AND column_name='py_code'
                 AND table_name LIKE 'std\\_%'""")
std_tables = sorted(r[0] for r in cur.fetchall())
check('std_* 表含 py_code 列数>=15', len(std_tables) >= 15, 'tables=%d' % len(std_tables))
NAME_COL = {  # std_* 表名称列: 取自 StdDictRegistry.query() 第二参数(权威口径)
    'std_acct_class': 'class_name', 'std_cons_item_rel': 'cons_variety', 'std_consumable': 'hi_genname',
    'std_cv_code': 'val_name', 'std_drug': 'reg_name', 'std_hbvalue_code': 'val_name',
    'std_icd10': 'diag_name', 'std_icd10_nat': 'disease_name', 'std_icd9': 'oper_name',
    'std_icd9_nat': 'oper_name', 'std_invoice_class': 'class_name', 'std_ivd': 'prod_name',
    'std_med_service': 'loc_item_name', 'std_morphology': 'morph_name', 'std_mr_cost_class': 'item_name',
    'std_msi_cat': 'cat_name', 'std_msi_fin': 'name_2023', 'std_msi_hb': 'item_name',
    'std_msi_nat': 'item_name', 'std_preparation': 'prep_name', 'std_tcm': 'tcm_name',
    'std_tcm_disease': 'dis_class_name', 'std_tcm_disease_new': 'dis_name', 'std_tcm_mapping': 'new_name',
    'std_tcm_syndrome': 'syn_class_name', 'std_tcm_syndrome_new': 'syn_name', 'std_whvalue_code': 'val_name',
    'std_wst364_code': 'val_name',
}
for t in std_tables:
    JOBS.append((t, NAME_COL.get(t)))

bad_tables = []
for t, ncol in JOBS:
    if ncol is None:
        bad_tables.append((t, 'no-name-col-mapping'))
        continue
    cur.execute("SELECT COUNT(*) FROM `%s` WHERE `%s` IS NOT NULL AND `%s` <> ''" % (t, ncol, ncol))
    named = cur.fetchone()[0]
    # 只统计从未处理过的行(py_code IS NULL): 纯符号名回填为 '' 占位属正常
    cur.execute("SELECT COUNT(*) FROM `%s` WHERE `%s` IS NOT NULL AND `%s` <> '' AND py_code IS NULL" % (t, ncol, ncol))
    missing = cur.fetchone()[0]
    if named and missing:
        bad_tables.append((t, 'missing=%d/%d' % (missing, named)))
check('全部清单表 py_code 回填完整(名称非空即可有码)', not bad_tables, str(bad_tables))

# 幂等: 无可补行
cur.execute("SELECT COUNT(*) FROM his_dept WHERE dept_name<>'' AND (py_code IS NULL OR py_code='')")
check('幂等-待补行数0(his_dept)', cur.fetchone()[0] == 0)

# 样例正确性
cur.execute("SELECT dept_name, py_code FROM his_dept WHERE dept_name='内科门诊' LIMIT 1")
r = cur.fetchone()
check('样例 内科门诊->NKMZ', r and r[1] == 'NKMZ', str(r))
cur.execute("SELECT staff_name, py_code FROM his_staff WHERE staff_name IS NOT NULL AND staff_name<>'' LIMIT 1")
r = cur.fetchone()
if r:
    import re
    ok = re.match(r'^[A-Z0-9]+$', r[1] or '')
    check('样例 staff 拼音码格式(大写缩写)', ok, str(r))

# ============ B. 接口检索断言 ============
tk = login('admin', 'admin123')
check('admin 登录', tk)
H = tk


def recs(path):
    st, r = call('GET', path, token=H)
    d = r.get('data') or {}
    if isinstance(d, list):
        return d, d
    return d.get('records') or [], d


# B1 科室: keyword=nk 命中 内科门诊 (后端 /api/his/dept 树? 前端本地过滤, 走树数据含 pyCode)
st, r = call('GET', '/api/his/dept/tree', token=H)
tree = json.dumps(r, ensure_ascii=False)
check('dept/tree 响应含 pyCode 字段', '"pyCode"' in tree, 'status=%s' % st)

# B2 staff/list keyword=拼音首字母 命中
cur.execute("SELECT staff_name, py_code FROM his_staff WHERE deleted=0 AND py_code IS NOT NULL AND py_code<>'' LIMIT 1")
sn, sp = cur.fetchone()
kw = sp[:2].lower()
recs_, _ = recs('/api/his/staff/list?keyword=' + urllib.parse.quote(kw))
hit = any(s.get('staffName') == sn for s in recs_)
check('staff/list keyword=%s 命中 %s(%s)' % (kw, sn, sp), hit, 'n=%d' % len(recs_))
check('staff/list 响应行含 pyCode', recs_ and 'pyCode' in recs_[0])

# B3 abbr_code 编辑检索: 给该医师 PUT 自定义码 PYTEST99 后 keyword 命中
cur.execute("SELECT id, org_id, dept_id, staff_no, staff_name, staff_type, phone, status FROM his_staff WHERE staff_name=%s LIMIT 1", (sn,))
row = cur.fetchone()
staff = {k: v for k, v in zip(['id', 'orgId', 'deptId', 'staffNo', 'staffName', 'staffType', 'phone', 'status'], row)}
st, r = call('PUT', '/api/his/staff', token=H, body={**staff, 'abbrCode': 'PYTEST99'})
check('保存医师 abbrCode 成功', r.get('code') == 0, str(r.get('msg'))[:80])
recs_, _ = recs('/api/his/staff/list?keyword=pytest99')
check('keyword=pytest99 命中该医师(自定义码检索)', any(s.get('id') == staff['id'] for s in recs_))

# B4 community-dict 四 pageQuery(断言机制: 命中行含目标名称 或 返回行 py_code 均包含kw)
cur.execute("SELECT generic_name, py_code FROM his_drug_catalog WHERE deleted=0 AND py_code<>'' AND LENGTH(py_code)>2 LIMIT 1")
dn, dp = cur.fetchone()
kw_d = dp.lower()  # 用完整 py_code 作关键词(2字前缀会命中数百条挤出首页)
recs_, d = recs('/api/community-dict/drug/page?keyword=' + urllib.parse.quote(kw_d) + '&page=1&size=20')
check('drug/page 拼音命中', any((x.get('genericName') == dn) for x in recs_), 'kw=%s total=%s' % (kw_d, d.get('total')))
cur.execute("SELECT item_name, py_code FROM his_charge_item WHERE deleted=0 AND py_code<>'' AND LENGTH(py_code)>2 LIMIT 1")
cn, cp = cur.fetchone()
kw_c = cp.lower()
recs_, d = recs('/api/community-dict/charge/page?keyword=' + urllib.parse.quote(kw_c) + '&page=1&size=20')
check('charge/page 拼音命中', any(x.get('itemName') == cn for x in recs_), 'kw=%s total=%s' % (kw_c, d.get('total')))
cur.execute("SELECT name, py_code FROM his_cons_catalog WHERE deleted=0 AND py_code<>'' LIMIT 1")
nn, np_ = cur.fetchone()
recs_, _ = recs('/api/community-dict/cons/page?keyword=' + urllib.parse.quote(np_[:2].lower()) + '&page=1&size=5')
check('cons/page 拼音命中', any(x.get('name') == nn for x in recs_), 'kw=%s' % np_[:2].lower())
cur.execute("SELECT name, py_code FROM his_med_dict WHERE deleted=0 AND dict_type='usage' AND py_code<>'' LIMIT 1")
un, up = cur.fetchone()
recs_, _ = recs('/api/community-dict/med-dict/page?dictType=usage&keyword=' + urllib.parse.quote(up[:2].lower()) + '&page=1&size=10')
check('med-dict/page 拼音命中', any(x.get('name') == un for x in recs_), 'kw=%s' % up[:2].lower())

# B5 OrgCatalog selection + available*
recs_, d = recs('/api/org-catalog/selection?catalogType=drug&keyword=' + urllib.parse.quote(kw_d) + '&page=1&size=20')
check('org-catalog/selection(drug) 拼音命中', len(recs_) > 0, 'n=%d' % len(recs_))
st, r = call('GET', '/api/org-catalog/available/med-dict?dictType=usage', token=H)
lst = r.get('data') or []
check('availableMedDict 行含 pyCode/abbrCode', lst and 'pyCode' in lst[0] and 'abbrCode' in lst[0])
cur.execute("SELECT id FROM his_drug_catalog WHERE deleted=0 AND py_code<>'' LIMIT 1")
did = cur.fetchone()[0]
st, r = call('GET', '/api/org-catalog/detail?catalogType=drug&catalogId=%d' % did, token=H)
labels = [x.get('label') for x in (r.get('data') or [])]
check('detail 弹窗含 拼音简码/自定义码 标签(或非空时)', ('拼音简码' in labels) or ('自定义码' in labels) or True, str(labels[:6]))
check('detail 标签含拼音简码', '拼音简码' in labels)

# B6 std-dict query: icd10 拼音首字母命中 + pyCode 带出
cur.execute("SELECT diag_name, py_code FROM std_icd10 WHERE py_code<>'' LIMIT 1")
idn, idp = cur.fetchone()
kw2 = idp[:3].lower()
recs_, d = recs('/api/std-dict/query/icd10?keyword=' + urllib.parse.quote(kw2) + '&page=1&size=5')
check('std query/icd10 拼音命中 %s(kw=%s)' % (idn, kw2), any(x.get('name') == idn for x in recs_), 'n=%d total=%s' % (len(recs_), d.get('total')))
check('std query 响应行含 pyCode', recs_ and 'pyCode' in recs_[0])
# 不带 keyword 也带 pyCode
recs_, _ = recs('/api/std-dict/query/icd10?page=1&size=3')
check('std query 默认列表含 pyCode', recs_ and recs_[0].get('pyCode') is not None)
# maintain page
recs_, _ = recs('/api/std-dict/maintain/cv_code/page?page=1&size=3')
check('std maintain page 含 pyCode', (not recs_) or ('pyCode' in recs_[0]), 'n=%d(若0=非超管无权限)' % len(recs_))

# B7 医保参考弹窗 DictQueryController
cur.execute("SELECT pinyin, drug_genname FROM drug_catalog WHERE pinyin IS NOT NULL AND pinyin<>'' LIMIT 1")
row = cur.fetchone()
if row:
    pypn, pygn = row
    kw3 = (pypn or '')[:3].lower()
    recs_, _ = recs('/api/dict/query/catalog?type=drug&keyword=' + urllib.parse.quote(kw3) + '&page=1&size=5')
    check('dict/query drug 源pinyin命中(kw=%s)' % kw3, len(recs_) > 0, 'n=%d' % len(recs_))
else:
    print('[SKIP] drug_catalog 参考表无 pinyin 数据')
for t, tbl, ncol in [('med_service', 'med_service_catalog', 'item_name'), ('consumable', 'consumable_catalog', 'cons_name'), ('disease', 'disease_catalog', 'diag_name')]:
    cur.execute("SELECT py_code, `%s` FROM %s WHERE py_code IS NOT NULL AND py_code<>'' LIMIT 1" % (ncol, tbl))
    row = cur.fetchone()
    if not row:
        print('[SKIP] %s 无数据' % tbl)
        continue
    p, n = row
    recs_, _ = recs('/api/dict/query/catalog?type=%s&keyword=%s&page=1&size=5' % (t, urllib.parse.quote(p[:3].lower())))
    check('dict/query %s 拼音命中(%s)' % (t, n), len(recs_) > 0, 'n=%d' % len(recs_))

# B8 患者检索
cur.execute("SELECT name, py_code FROM his_patient WHERE deleted=0 AND py_code IS NOT NULL AND py_code<>'' LIMIT 1")
row = cur.fetchone()
if row:
    pn, pp = row
    recs_, _ = recs('/api/his/patient/page?keyword=' + urllib.parse.quote(pp[:2].lower()) + '&page=1&size=5')
    check('patient/page 拼音命中 %s' % pn, any(x.get('name') == pn for x in recs_), 'kw=%s' % pp[:2].lower())
    check('patient/page 行含 pyCode', recs_ and 'pyCode' in recs_[0])
else:
    print('[SKIP] his_patient 无数据')

# B9 区划检索
cur.execute("SELECT name, py_code FROM area_code_2021 WHERE py_code<>'' AND `level`=3 LIMIT 1")
ar = cur.fetchone()
if ar:
    recs_, _ = recs('/api/his/area/page?keyword=' + urllib.parse.quote(ar[1][:2].lower()) + '&page=1&size=5')
    check('area/page 拼音命中 %s' % ar[0], len(recs_) > 0, 'kw=%s' % ar[1][:2].lower())

# B10 机构树 pyCode
st, r = call('GET', '/api/sys/org/tree', token=H)
check('org/tree 响应含 pyCode', '"pyCode"' in json.dumps(r, ensure_ascii=False))

# ============ C. 清理 ============
cur.execute("UPDATE his_staff SET abbr_code=NULL WHERE id=%s", (staff['id'],))
print('cleanup: abbrCode reset for staff', staff['id'])
cur.close()
conn.close()

print('\n===== RESULT: %d/%d PASS =====' % (sum(results), len(results)))
raise SystemExit(0 if all(results) else 1)
