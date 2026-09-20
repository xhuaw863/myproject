# -*- coding: utf-8 -*-
"""
为「孝昌县人民医院」(tenant_id=1) 下属三个乡镇卫生院批量模拟生成: 科室 + 职工 + 用户。
三个卫生院(sys_org level=2, parent_id=1, org_type=C2, 行政区划码):
  - 花园镇卫生院(org 8)  前缀 XCHY
  - 丰山镇卫生院(org 12) 前缀 XCFS
  - 周巷镇卫生院(org 16) 前缀 XCZX

规范:
- 科室沿用五大类模型(门诊科室/住院科室/医技科室/行政后勤 为 level1 大类, 业务科室 level2), org_id 归属卫生院。
- 职工严格字典回填三件套(code+name+src): gender/title/staff_type/prac_cate/antibiotic/surgery。
- 全科/内科等临床医师 prac_cate=1; 中医科=4; 公共卫生科=3(公共卫生); 外科/妇产科医师带手术级别。
- 身份证按孝昌县 420921 生成含合法校验位; 出生年份由职称推导。
- 用户: 账号=工号, 默认密码 123456, 角色按职工类别映射(医师/技师->DOCTOR, 护士->NURSE, 药师->PHARMACIST, 管理->ADMIN)。
- 幂等: 按工号前缀判定, 已生成则整体跳过该院。
"""
import pymysql
import bcrypt
import random
from datetime import date
from collections import Counter

random.seed(20260920)

TENANT_ID = 1
ADMIN_CODE = "420921"      # 孝昌县
DEFAULT_PWD = "123456"

# 角色映射(staff_type -> role_code, role_id)
ROLE_MAP = {
    '医师': ('DOCTOR', 3),
    '技师': ('DOCTOR', 3),
    '护士': ('NURSE', 6),
    '药师': ('PHARMACIST', 4),
    '管理': ('ADMIN', 1),
}

# ---------------- 字典(与库内一致, 回填 _name/_src) ----------------
GENDER = {'1': '男', '2': '女'}
TITLE = {'1': '正高', '2': '副高', '3': '中级', '4': '师级/助理', '5': '士级'}
PRAC = {'1': '临床', '2': '口腔', '3': '公共卫生', '4': '中医'}
ABX = {'11': '一级', '12': '二级', '13': '三级'}
OPRN = {'1': '一级手术', '2': '二级手术', '3': '三级手术', '4': '四级手术'}

TITLE_BIRTH = {'1': (1962, 1970), '2': (1968, 1976), '3': (1976, 1988),
               '4': (1986, 1996), '5': (1994, 2002)}
REG_FEE = {'1': 30.00, '2': 20.00, '3': 10.00, '4': 6.00, '5': 5.00}
ABX_BY_TITLE = {'1': '13', '2': '13', '3': '12', '4': '11', '5': '11'}
OPRN_BY_TITLE = {'1': '3', '2': '3', '3': '2', '4': '1', '5': '1'}

# ---------------- 姓名池 ----------------
SURNAMES = list("王李张刘陈杨黄赵吴周徐孙马朱胡郭何高林罗郑梁谢宋唐许韩冯邓曹彭曾肖田董袁潘蒋蔡余杜叶程苏魏吕丁任沈姚卢姜崔钟谭陆汪范金石廖贾夏韦付方白邹孟熊秦邱江尹薛闫段雷侯龙史陶黎贺顾毛郝龚邵万钱严覃武戴莫孔向汤")
MALE_CHARS = list("建国志强志明国华文华建军建平建华国强志勇志鹏海涛海峰晓明晓东志刚伟东伟民俊杰俊豪浩然子轩宇航明辉德福永林永康嘉豪泽宇博文昊天鹏飞立新")
FEMALE_CHARS = list("秀英桂兰玉兰春梅晓燕丽华丽娟丹丹婷婷静静雅静梦琪欣怡语嫣可心怡然佳怡思颖慧敏慧兰雪梅红梅春霞晓霞娜娜丽丽美玲雅琴婉婷紫萱若曦")
used_names = set()


def gen_name(gender):
    pool = MALE_CHARS if gender == '1' else FEMALE_CHARS
    for _ in range(800):
        nm = random.choice(SURNAMES) + "".join(random.sample(pool, random.choice([1, 2])))
        if nm not in used_names:
            used_names.add(nm)
            return nm
    raise RuntimeError("name exhausted")


def id_card(seq, gender, birth):
    body = ADMIN_CODE + birth.strftime("%Y%m%d") + "%02d" % (seq % 99 + 1) + gender
    weights = [7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2]
    checks = "10X98765432"
    s = sum(int(body[i]) * weights[i] for i in range(17))
    return body + checks[s % 11]


def rand_birth(title):
    lo, hi = TITLE_BIRTH.get(title, (1980, 1995))
    return date(random.randint(lo, hi), random.randint(1, 12), random.randint(1, 28))


# ---------------- 卫生院科室 + 编制模板 ----------------
# 每科室: (dept_name, dept_category, dept_type, prac_hint, can_register, [(staff_type,title,gender), ...])
#   prac_hint: None=按 staff_type 默认(临床); '4'=中医; '3'=公共卫生
#   surgical: 科室名在 SURGICAL 集合内则医师带手术级别
SURGICAL = {'外科', '妇产科'}
DEPT_PLAN = [
    # 门诊科室
    ("全科医疗科", "门诊科室", "临床", None, True,
     [('医师', '3', '1'), ('医师', '4', '2'), ('医师', '4', '1'), ('护士', '4', '2'), ('护士', '5', '2')]),
    ("内科", "门诊科室", "临床", None, True,
     [('医师', '3', '2'), ('医师', '4', '1'), ('护士', '4', '2')]),
    ("外科", "门诊科室", "临床", None, True,
     [('医师', '3', '1'), ('医师', '4', '1'), ('护士', '4', '2')]),
    ("妇产科", "门诊科室", "临床", None, True,
     [('医师', '3', '2'), ('护士', '4', '2')]),
    ("儿科", "门诊科室", "临床", None, True,
     [('医师', '4', '2'), ('护士', '4', '2')]),
    ("中医科", "门诊科室", "临床", '4', True,
     [('医师', '3', '1'), ('医师', '4', '2')]),
    ("预防接种门诊", "门诊科室", "临床", None, False,
     [('护士', '4', '2'), ('护士', '5', '2')]),
    # 住院科室
    ("住院部", "住院科室", "临床", None, False,
     [('医师', '3', '1'), ('医师', '4', '2'), ('护士', '3', '2'), ('护士', '4', '2'), ('护士', '4', '1'), ('护士', '5', '2')]),
    # 医技科室
    ("检验科", "医技科室", "医技", None, False,
     [('技师', '3', '2'), ('技师', '4', '1')]),
    ("医学影像科", "医技科室", "医技", None, False,
     [('医师', '3', '1'), ('技师', '4', '2')]),
    ("超声诊断科", "医技科室", "医技", None, False,
     [('技师', '3', '2')]),
    # 行政后勤
    ("药房", "行政后勤", "行政", None, False,
     [('药师', '3', '2'), ('药师', '4', '1')]),
    ("公共卫生科", "行政后勤", "行政", '3', False,
     [('医师', '3', '2'), ('医师', '4', '1'), ('管理', '3', '1')]),
    ("院办公室", "行政后勤", "行政", None, False,
     [('管理', '2', '1'), ('管理', '4', '2')]),
]

# 五大类中的四大类(按出现顺序建 level1 大类节点)
CATEGORIES = ["门诊科室", "住院科室", "医技科室", "行政后勤"]
CAT_CODE = {"门诊科室": "MZ", "住院科室": "ZY", "医技科室": "YJ", "行政后勤": "XZ"}

conn = pymysql.connect(host='127.0.0.1', port=3306, user='root', password='bsoft',
                       database='yb_interface', charset='utf8mb4', autocommit=False)
cur = conn.cursor(pymysql.cursors.DictCursor)

# 定位三个卫生院(level2, parent=1, C2, 纯数字行政区划码)
cur.execute("""SELECT id, org_code, org_name FROM sys_org
               WHERE tenant_id=%s AND parent_id=1 AND org_level=2 AND org_type='C2'
                 AND org_code REGEXP '^[0-9]+$'
               ORDER BY id""", (TENANT_ID,))
CENTERS = cur.fetchall()
PREFIXES = ['XCHY', 'XCFS', 'XCZX']
if len(CENTERS) != 3:
    print("警告: 期望 3 个卫生院, 实际 %d 个: %s" % (len(CENTERS), [c['org_name'] for c in CENTERS]))

# 已有姓名纳入去重
cur.execute("SELECT staff_name FROM his_staff WHERE tenant_id=%s", (TENANT_ID,))
for r in cur.fetchall():
    if r['staff_name']:
        used_names.add(r['staff_name'])

# 预置一个密码散列(所有账号同密码 123456)
PWD_HASH = bcrypt.hashpw(DEFAULT_PWD.encode('utf-8'), bcrypt.gensalt(rounds=10, prefix=b"2a")).decode('utf-8')

DEPT_INSERT = """INSERT INTO his_dept
(tenant_id, org_id, parent_id, dept_code, dept_name, dept_type, dept_category, dept_level,
 sort_no, status, memo, create_by, create_time)
VALUES (%(tenant_id)s,%(org_id)s,%(parent_id)s,%(dept_code)s,%(dept_name)s,%(dept_type)s,
 %(dept_category)s,%(dept_level)s,%(sort_no)s,1,%(memo)s,'system-seed',NOW())"""

STAFF_INSERT = """INSERT INTO his_staff
(tenant_id, org_id, staff_no, staff_name, staff_type, staff_type_name, staff_type_src,
 gender, gender_name, gender_src, title_code, title_name, title_src,
 dept_id, id_card, birth_date, phone, med_insur_code, prac_cate, prac_cate_name, prac_cate_src,
 dr_qual_cert_no, prac_cert_no, can_register, reg_fee, sort_no, status,
 rx_right, narcotic_right, psych1_right, psych2_right,
 antibiotic_level, antibiotic_level_name, antibiotic_level_src,
 surgery_level, surgery_level_name, surgery_level_src,
 rx_auth_org, rx_auth_no, rx_auth_date, rx_valid_until, memo, create_by, create_time)
VALUES
(%(tenant_id)s,%(org_id)s,%(staff_no)s,%(staff_name)s,%(staff_type)s,%(staff_type_name)s,%(staff_type_src)s,
 %(gender)s,%(gender_name)s,%(gender_src)s,%(title_code)s,%(title_name)s,%(title_src)s,
 %(dept_id)s,%(id_card)s,%(birth_date)s,%(phone)s,%(med_insur_code)s,%(prac_cate)s,%(prac_cate_name)s,%(prac_cate_src)s,
 %(dr_qual_cert_no)s,%(prac_cert_no)s,%(can_register)s,%(reg_fee)s,%(sort_no)s,%(status)s,
 %(rx_right)s,%(narcotic_right)s,%(psych1_right)s,%(psych2_right)s,
 %(antibiotic_level)s,%(antibiotic_level_name)s,%(antibiotic_level_src)s,
 %(surgery_level)s,%(surgery_level_name)s,%(surgery_level_src)s,
 %(rx_auth_org)s,%(rx_auth_no)s,%(rx_auth_date)s,%(rx_valid_until)s,%(memo)s,'system-seed',NOW())"""

USER_INSERT = """INSERT INTO sys_user
(tenant_id, username, password, real_name, role, staff_id, dept_id, org_id, role_id, phone,
 status, create_by, create_time)
VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,1,'system-seed',NOW())"""

grand_staff = grand_user = grand_dept = 0
for idx, center in enumerate(CENTERS):
    org_id = center['id']
    org_name = center['org_name']
    prefix = PREFIXES[idx] if idx < len(PREFIXES) else ("XC%02d" % org_id)

    # 幂等: 该院工号前缀已存在则跳过
    cur.execute("SELECT COUNT(*) c FROM his_staff WHERE tenant_id=%s AND staff_no LIKE %s",
                (TENANT_ID, prefix + "%"))
    if cur.fetchone()['c'] > 0:
        print("[%s] 已存在 %s 前缀职工, 跳过。" % (org_name, prefix))
        continue

    # ---- 1. 科室: 先建四大类(level1), 再建业务科室(level2) ----
    cat_ids = {}
    sort = 0
    for cat in CATEGORIES:
        sort += 1
        cur.execute(DEPT_INSERT, {
            'tenant_id': TENANT_ID, 'org_id': org_id, 'parent_id': 0,
            'dept_code': "%s-CAT-%s" % (prefix, CAT_CODE[cat]), 'dept_name': cat,
            'dept_type': '行政', 'dept_category': cat, 'dept_level': 1, 'sort_no': sort,
            'memo': '%s-大类' % org_name})
        cat_ids[cat] = cur.lastrowid
        grand_dept += 1

    dept_ids = {}
    dseq = 0
    for (dname, cat, dtype, prac_hint, can_reg, tmpl) in DEPT_PLAN:
        dseq += 1
        sort += 1
        cur.execute(DEPT_INSERT, {
            'tenant_id': TENANT_ID, 'org_id': org_id, 'parent_id': cat_ids[cat],
            'dept_code': "%s-%02d" % (prefix, dseq), 'dept_name': dname,
            'dept_type': dtype, 'dept_category': cat, 'dept_level': 2, 'sort_no': sort,
            'memo': '%s-模拟生成' % org_name})
        dept_ids[dname] = cur.lastrowid
        grand_dept += 1

    # ---- 2. 职工 ----
    sseq = 0
    center_staff_n = 0
    for (dname, cat, dtype, prac_hint, can_reg, tmpl) in DEPT_PLAN:
        dept_id = dept_ids[dname]
        is_surgical = dname in SURGICAL
        for (stype, title, gender) in tmpl:
            sseq += 1
            staff_no = "%s%04d" % (prefix, sseq)
            name = gen_name(gender)
            birth = rand_birth(title)
            is_doctor = (stype == '医师')
            prac = prac_name = prac_src = None
            dq = pc = None
            if is_doctor:
                pcode = prac_hint if prac_hint else '1'
                prac, prac_name, prac_src = pcode, PRAC[pcode], "whvalue:CT98.00.024"
                dq = "110%d%s" % (random.randint(10**7, 10**8 - 1), title)
                pc = "110%d%s" % (random.randint(10**7, 10**8 - 1), title)
            rx = nar = p1 = p2 = 0
            abx = abx_name = abx_src = None
            if is_doctor:
                rx = 1
                abx = ABX_BY_TITLE.get(title, '11')
                abx_name, abx_src = ABX[abx], "hbvalue:HBCV08.50.029"
                if title in ('1', '2'):
                    nar, p1, p2 = 1, 1, 1
                elif title == '3':
                    p2 = 1
            sl = sl_name = sl_src = None
            if is_doctor and is_surgical:
                sl = OPRN_BY_TITLE.get(title, '1')
                sl_name, sl_src = OPRN[sl], "cv_code:oprn_lv_code"
            reg = 1 if (can_reg and is_doctor) else 0
            cur.execute(STAFF_INSERT, {
                'tenant_id': TENANT_ID, 'org_id': org_id, 'staff_no': staff_no,
                'staff_name': name, 'staff_type': stype, 'staff_type_name': stype,
                'staff_type_src': 'local:staff_type',
                'gender': gender, 'gender_name': GENDER[gender], 'gender_src': 'cv_code:gend',
                'title_code': title, 'title_name': TITLE[title], 'title_src': 'wst364:CV08.30.005',
                'dept_id': dept_id, 'id_card': id_card(sseq, gender, birth), 'birth_date': birth,
                'phone': "1%d%09d" % (random.choice([3, 5, 7, 8, 9]), random.randint(0, 999999999)),
                'med_insur_code': ("H42%013d" % random.randint(0, 10**13 - 1)) if (is_doctor or stype in ('护士', '药师')) else None,
                'prac_cate': prac, 'prac_cate_name': prac_name, 'prac_cate_src': prac_src,
                'dr_qual_cert_no': dq, 'prac_cert_no': pc,
                'can_register': reg, 'reg_fee': REG_FEE.get(title, 0) if reg else 0.00,
                'sort_no': sseq, 'status': 1,
                'rx_right': rx, 'narcotic_right': nar, 'psych1_right': p1, 'psych2_right': p2,
                'antibiotic_level': abx, 'antibiotic_level_name': abx_name, 'antibiotic_level_src': abx_src,
                'surgery_level': sl, 'surgery_level_name': sl_name, 'surgery_level_src': sl_src,
                'rx_auth_org': '医务科' if is_doctor else None,
                'rx_auth_no': ('%s-RX-2026-%04d' % (prefix, sseq)) if is_doctor else None,
                'rx_auth_date': date(2026, 1, 1) if is_doctor else None,
                'rx_valid_until': date(2028, 12, 31) if is_doctor else None,
                'memo': '批量模拟生成(%s·%s)' % (org_name, dname),
            })
            center_staff_n += 1
    grand_staff += center_staff_n

    # ---- 3. 用户(账号=工号, 密码 123456) ----
    cur.execute("""SELECT id, staff_no, staff_name, staff_type, dept_id, phone FROM his_staff
                   WHERE tenant_id=%s AND org_id=%s AND staff_no LIKE %s ORDER BY id""",
                (TENANT_ID, org_id, prefix + "%"))
    ucount = 0
    for s in cur.fetchall():
        role_code, role_id = ROLE_MAP.get(s['staff_type'], ('DOCTOR', 3))
        cur.execute(USER_INSERT, (TENANT_ID, s['staff_no'], PWD_HASH, s['staff_name'],
                                  role_code, s['id'], s['dept_id'], org_id, role_id, s['phone']))
        ucount += 1
    grand_user += ucount
    print("[%s org=%d 前缀=%s] 科室 %d, 职工 %d, 用户 %d" %
          (org_name, org_id, prefix, len(cat_ids) + len(dept_ids), center_staff_n, ucount))

conn.commit()
print("==== 合计: 科室 %d, 职工 %d, 用户 %d ====" % (grand_dept, grand_staff, grand_user))
print("默认登录密码: %s (账号=工号, 如 XCHY0001)" % DEFAULT_PWD)
conn.close()
