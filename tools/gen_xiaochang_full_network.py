# -*- coding: utf-8 -*-
"""
按孝昌县(420921)行政区划, 补齐全县医疗机构网络: 乡镇卫生院 + 村卫生室, 并为各院设定业务科室、模拟职工与登录用户。

数据源: 本地 area_code_2021 (全国行政区划表, 已由租户管理导入), 不依赖外部查询。

范围(与用户确认):
1) 新建 9 个乡镇卫生院(仅镇/乡, 不含开发区/渡假区):
   小河镇/王店镇/卫店镇/白沙镇/邹岗镇/小悟乡/季店乡/花西乡/陡山乡
   - 沿用既有 花园镇(XCHY)/丰山镇(XCFS)/周巷镇(XCZX) 的 DEPT_PLAN 业务科室模型 + 约50人编制 + 用户。
   - 工号前缀取 "XC" + 镇级代码中段(如 XC103), 唯一且可追溯。
2) 村卫生室: 每个卫生院(含既有3院)按 area_code_2021 真实行政村抽样前 5 个村生成;
   - org_code 已存在则跳过(幂等); 每室 1 个全科科室 + 1~3 名乡村医生 + 用户。
3) 医保编码 med_insur_code: 'H42'+13位共16位; 先取全库已有码池做查重, 事后校验唯一性。
4) 定点编号 fixmedins_code: 'H42092100'+LPAD(org_id,3,'0') 共12位; 名称='孝昌县'+org_name。

默认登录密码 123456 (账号=工号)。幂等: 卫生院按工号前缀, 卫生室按 org_code/该室无职工判定。
"""
import pymysql
import bcrypt
import random
from datetime import date

random.seed(20260926)

TENANT_ID = 1
ADMIN_CODE = "420921"          # 孝昌县行政区划码(6位)
DEFAULT_PWD = "123456"
VILLAGE_SAMPLE = 5             # 每卫生院抽样村数

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
ROLE_MAP = {
    '医师': ('DOCTOR', 3), '技师': ('DOCTOR', 3), '护士': ('NURSE', 6),
    '药师': ('PHARMACIST', 4), '管理': ('ADMIN', 1),
}

# ---------------- 姓名池 ----------------
SURNAMES = list("王李张刘陈杨黄赵吴周徐孙马朱胡郭何高林罗郑梁谢宋唐许韩冯邓曹彭曾肖田董袁潘蒋蔡余杜叶程苏魏吕丁任沈姚卢姜崔钟谭陆汪范金石廖贾夏韦付方白邹孟熊秦邱江尹薛闫段雷侯龙史陶黎贺顾毛郝龚邵万钱严覃武戴莫孔向汤")
MALE_CHARS = list("建国志强志明国华文华建军建平建华国强志勇志鹏海涛海峰晓明晓东志刚伟东伟民俊杰俊豪浩然子轩宇航明辉德福永林永康嘉豪泽宇博文昊天鹏飞立新")
FEMALE_CHARS = list("秀英桂兰玉兰春梅晓燕丽华丽娟丹丹婷婷静静雅静梦琪欣怡语嫣可心怡然佳怡思颖慧敏慧兰雪梅红梅春霞晓霞娜娜丽丽美玲雅琴婉婷紫萱若曦")
used_names = set()

# ---------------- 乡镇卫生院清单 (code, 镇乡名, 前缀, 是否已存在) ----------------
TOWNS = [
    (420921100000, "花园镇", "XCHY", True),
    (420921101000, "丰山镇", "XCFS", True),
    (420921102000, "周巷镇", "XCZX", True),
    (420921103000, "小河镇", "XC103", False),
    (420921104000, "王店镇", "XC104", False),
    (420921105000, "卫店镇", "XC105", False),
    (420921106000, "白沙镇", "XC106", False),
    (420921107000, "邹岗镇", "XC107", False),
    (420921200000, "小悟乡", "XC200", False),
    (420921201000, "季店乡", "XC201", False),
    (420921202000, "花西乡", "XC202", False),
    (420921203000, "陡山乡", "XC203", False),
]

# ---------------- 卫生院业务科室 + 编制模板 (沿用 gen_xcrh_town_health.py) ----------------
SURGICAL = {'外科', '妇产科'}
DEPT_PLAN = [
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
    ("住院部", "住院科室", "临床", None, False,
     [('医师', '3', '1'), ('医师', '4', '2'), ('护士', '3', '2'), ('护士', '4', '2'), ('护士', '4', '1'), ('护士', '5', '2')]),
    ("检验科", "医技科室", "医技", None, False,
     [('技师', '3', '2'), ('技师', '4', '1')]),
    ("医学影像科", "医技科室", "医技", None, False,
     [('医师', '3', '1'), ('技师', '4', '2')]),
    ("超声诊断科", "医技科室", "医技", None, False,
     [('技师', '3', '2')]),
    ("药房", "行政后勤", "行政", None, False,
     [('药师', '3', '2'), ('药师', '4', '1')]),
    ("公共卫生科", "行政后勤", "行政", '3', False,
     [('医师', '3', '2'), ('医师', '4', '1'), ('管理', '3', '1')]),
    ("院办公室", "行政后勤", "行政", None, False,
     [('管理', '2', '1'), ('管理', '4', '2')]),
]
CATEGORIES = ["门诊科室", "住院科室", "医技科室", "行政后勤"]
CAT_CODE = {"门诊科室": "MZ", "住院科室": "ZY", "医技科室": "YJ", "行政后勤": "XZ"}


def gen_name(gender):
    pool = MALE_CHARS if gender == '1' else FEMALE_CHARS
    for _ in range(2000):
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


def gen_phone():
    return "1%d%09d" % (random.choice([3, 5, 7, 8, 9]), random.randint(0, 999999999))


# ---------------- 连接与查重池 ----------------
conn = pymysql.connect(host='127.0.0.1', port=3306, user='root', password='bsoft',
                       database='yb_interface', charset='utf8mb4', autocommit=False)
cur = conn.cursor(pymysql.cursors.DictCursor)

cur.execute("SELECT staff_name FROM his_staff WHERE tenant_id=%s", (TENANT_ID,))
for r in cur.fetchall():
    if r['staff_name']:
        used_names.add(r['staff_name'])

med_pool = set()
cur.execute("SELECT med_insur_code c FROM his_staff WHERE med_insur_code IS NOT NULL")
for r in cur.fetchall():
    med_pool.add(r['c'])
cur.execute("SELECT fixmedins_code c FROM sys_org WHERE fixmedins_code IS NOT NULL AND fixmedins_code<>''")
for r in cur.fetchall():
    med_pool.add(r['c'])


def gen_med_code():
    while True:
        code = "H42%013d" % random.randint(0, 10 ** 13 - 1)
        if code not in med_pool:
            med_pool.add(code)
            return code


PWD_HASH = bcrypt.hashpw(DEFAULT_PWD.encode('utf-8'), bcrypt.gensalt(rounds=10, prefix=b"2a")).decode('utf-8')

ORG_INSERT = """INSERT INTO sys_org
(tenant_id, org_code, org_name, org_level, is_lead, parent_id, org_type, org_type_name, org_type_src,
 admvs_code, leader, phone, address, sort_no, status, bed_cnt, price_lv, create_by, create_time)
VALUES (%(tenant_id)s,%(org_code)s,%(org_name)s,%(org_level)s,0,%(parent_id)s,%(org_type)s,%(org_type_name)s,
 'cv_code:MEDINS_TYPE',%(admvs_code)s,%(leader)s,%(phone)s,%(address)s,%(sort_no)s,1,%(bed_cnt)s,1,'system-seed',NOW())"""

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


def set_fixmedins(org_id, org_name):
    """按既有规则补种 12 位定点编号与孝昌县前缀定点名称。"""
    cur.execute("UPDATE sys_org SET fixmedins_code=%s, fixmedins_name=%s WHERE id=%s",
                ("H42092100%03d" % org_id, "孝昌县" + org_name, org_id))


def create_users(org_id, prefix):
    cur.execute("""SELECT id, staff_no, staff_name, staff_type, dept_id, phone FROM his_staff
                   WHERE tenant_id=%s AND org_id=%s AND staff_no LIKE %s ORDER BY id""",
                (TENANT_ID, org_id, prefix + "%"))
    n = 0
    for s in cur.fetchall():
        cur.execute("SELECT id FROM sys_user WHERE username=%s", (s['staff_no'],))
        if cur.fetchone():
            continue
        role_code, role_id = ROLE_MAP.get(s['staff_type'], ('DOCTOR', 3))
        cur.execute(USER_INSERT, (TENANT_ID, s['staff_no'], PWD_HASH, s['staff_name'],
                                  role_code, s['id'], s['dept_id'], org_id, role_id, s['phone']))
        n += 1
    return n


def ensure_center_org(code, town_name, sort):
    """定位或创建卫生院(level2/C2), 返回 org_id。"""
    cur.execute("SELECT id FROM sys_org WHERE tenant_id=%s AND org_code=%s", (TENANT_ID, str(code)))
    row = cur.fetchone()
    if row:
        return row['id'], False
    org_name = town_name + "卫生院"
    cur.execute(ORG_INSERT, {
        'tenant_id': TENANT_ID, 'org_code': str(code), 'org_name': org_name, 'org_level': 2,
        'parent_id': 1, 'org_type': 'C2', 'org_type_name': '乡镇卫生院',
        'admvs_code': ADMIN_CODE, 'leader': gen_name('1'), 'phone': "027" + "%08d" % random.randint(0, 99999999),
        'address': "孝昌县" + town_name, 'sort_no': sort, 'bed_cnt': random.randint(30, 99),
    })
    org_id = cur.lastrowid
    set_fixmedins(org_id, org_name)
    return org_id, True


# ============================================================
# 阶段 1: 卫生院 (新建9个 + 补齐既有3个的业务科室/职工)
# ============================================================
town_org = {}
grand_dept = grand_staff = grand_user = new_centers = 0
for sort, (code, town_name, prefix, exists) in enumerate(TOWNS, start=1):
    org_id, created = ensure_center_org(code, town_name, sort)
    town_org[code] = (org_id, town_name)
    if created:
        new_centers += 1

    # 幂等: 该院前缀工号已存在则跳过科室/职工生成
    cur.execute("SELECT COUNT(*) c FROM his_staff WHERE tenant_id=%s AND staff_no LIKE %s",
                (TENANT_ID, prefix + "%"))
    if cur.fetchone()['c'] > 0:
        print("[%s org=%d] 已有 %s 前缀职工, 跳过编制生成。" % (town_name, org_id, prefix))
        continue

    # 业务科室树: 4大类(level1) + 业务科室(level2)
    cat_ids, srt = {}, 0
    for cat in CATEGORIES:
        srt += 1
        cur.execute(DEPT_INSERT, {
            'tenant_id': TENANT_ID, 'org_id': org_id, 'parent_id': 0,
            'dept_code': "%s-CAT-%s" % (prefix, CAT_CODE[cat]), 'dept_name': cat,
            'dept_type': '行政', 'dept_category': cat, 'dept_level': 1, 'sort_no': srt,
            'memo': '%s卫生院-大类' % town_name})
        cat_ids[cat] = cur.lastrowid
        grand_dept += 1
    dept_ids, dseq = {}, 0
    for (dname, cat, dtype, prac_hint, can_reg, tmpl) in DEPT_PLAN:
        dseq += 1
        srt += 1
        cur.execute(DEPT_INSERT, {
            'tenant_id': TENANT_ID, 'org_id': org_id, 'parent_id': cat_ids[cat],
            'dept_code': "%s-%02d" % (prefix, dseq), 'dept_name': dname,
            'dept_type': dtype, 'dept_category': cat, 'dept_level': 2, 'sort_no': srt,
            'memo': '%s卫生院-模拟生成' % town_name})
        dept_ids[dname] = cur.lastrowid
        grand_dept += 1

    # 职工
    sseq = cn = 0
    for (dname, cat, dtype, prac_hint, can_reg, tmpl) in DEPT_PLAN:
        dept_id = dept_ids[dname]
        is_surgical = dname in SURGICAL
        for (stype, title, gender) in tmpl:
            sseq += 1
            birth = rand_birth(title)
            is_doctor = (stype == '医师')
            prac = prac_name = prac_src = dq = pc = None
            if is_doctor:
                pcode = prac_hint or '1'
                prac, prac_name, prac_src = pcode, PRAC[pcode], "whvalue:CT98.00.024"
                dq = "110%d%s" % (random.randint(10 ** 7, 10 ** 8 - 1), title)
                pc = "110%d%s" % (random.randint(10 ** 7, 10 ** 8 - 1), title)
            rx = nar = p1 = p2 = 0
            abx = abx_name = abx_src = None
            if is_doctor:
                rx = 1
                abx = ABX_BY_TITLE.get(title, '11')
                abx_name, abx_src = ABX[abx], "hbvalue:HBCV08.50.029"
                if title in ('1', '2'):
                    nar = p1 = p2 = 1
                elif title == '3':
                    p2 = 1
            sl = sl_name = sl_src = None
            if is_doctor and is_surgical:
                sl = OPRN_BY_TITLE.get(title, '1')
                sl_name, sl_src = OPRN[sl], "cv_code:oprn_lv_code"
            reg = 1 if (can_reg and is_doctor) else 0
            med = gen_med_code() if (is_doctor or stype in ('护士', '药师')) else None
            cur.execute(STAFF_INSERT, {
                'tenant_id': TENANT_ID, 'org_id': org_id, 'staff_no': "%s%04d" % (prefix, sseq),
                'staff_name': gen_name(gender), 'staff_type': stype, 'staff_type_name': stype,
                'staff_type_src': 'local:staff_type',
                'gender': gender, 'gender_name': GENDER[gender], 'gender_src': 'cv_code:gend',
                'title_code': title, 'title_name': TITLE[title], 'title_src': 'wst364:CV08.30.005',
                'dept_id': dept_id, 'id_card': id_card(sseq, gender, birth), 'birth_date': birth,
                'phone': gen_phone(), 'med_insur_code': med,
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
                'memo': '批量模拟生成(%s卫生院·%s)' % (town_name, dname),
            })
            cn += 1
    grand_staff += cn
    u = create_users(org_id, prefix)
    grand_user += u
    print("[%s org=%d 前缀=%s]%s 科室 %d, 职工 %d, 用户 %d" %
          (town_name, org_id, prefix, "(新建院)" if created else "(补编制)",
           len(cat_ids) + len(dept_ids), cn, u))

conn.commit()

# ============================================================
# 阶段 2: 村卫生室 (每卫生院抽样前 5 个真实行政村)
# ============================================================
ws_created = ws_staff = ws_user = 0
for code, town_name, prefix, exists in TOWNS:
    org_id = town_org[code][0]
    cur.execute("SELECT code, name FROM area_code_2021 WHERE level=5 AND pcode=%s ORDER BY code LIMIT %s",
                (code, VILLAGE_SAMPLE))
    villages = cur.fetchall()
    for v in villages:
        vcode = str(v['code'])
        vname = v['name']
        # 卫生室名称: 去 村委会/居委会 后缀 + 卫生室
        if vname.endswith("居委会"):
            base = vname[:-3]
        elif vname.endswith("村委会"):
            base = vname[:-3]
        else:
            base = vname
        ws_name = base + "卫生室"

        cur.execute("SELECT id FROM sys_org WHERE tenant_id=%s AND org_code=%s", (TENANT_ID, vcode))
        row = cur.fetchone()
        if row:
            ws_id = row['id']
        else:
            cur.execute(ORG_INSERT, {
                'tenant_id': TENANT_ID, 'org_code': vcode, 'org_name': ws_name, 'org_level': 3,
                'parent_id': org_id, 'org_type': 'D600', 'org_type_name': '村卫生室',
                'admvs_code': ADMIN_CODE, 'leader': gen_name(random.choice(['1', '2'])),
                'phone': "027" + "%08d" % random.randint(0, 99999999),
                'address': "孝昌县" + town_name + vname, 'sort_no': 0, 'bed_cnt': None,
            })
            ws_id = cur.lastrowid
            set_fixmedins(ws_id, ws_name)
            # 补 fixmedins_type=1 定点医疗机构
            cur.execute("UPDATE sys_org SET fixmedins_type='1', fixmedins_type_name='定点医疗机构', "
                        "fixmedins_type_src='cv_code:fixmedins_type' WHERE id=%s", (ws_id,))
            ws_created += 1

        # 幂等: 该室已有职工则跳过
        cur.execute("SELECT COUNT(*) c FROM his_staff WHERE tenant_id=%s AND org_id=%s", (TENANT_ID, ws_id))
        if cur.fetchone()['c'] > 0:
            continue

        # 全科科室 (若无)
        cur.execute("SELECT id FROM his_dept WHERE tenant_id=%s AND org_id=%s ORDER BY id LIMIT 1", (TENANT_ID, ws_id))
        drow = cur.fetchone()
        if drow:
            dept_id = drow['id']
        else:
            cur.execute(DEPT_INSERT, {
                'tenant_id': TENANT_ID, 'org_id': ws_id, 'parent_id': 0,
                'dept_code': "%s-WS" % vcode, 'dept_name': '全科医疗科',
                'dept_type': '临床', 'dept_category': '门诊科室', 'dept_level': 1, 'sort_no': 1,
                'memo': '%s-村卫生室全科' % ws_name})
            dept_id = cur.lastrowid

        # 1~3 名乡村医生
        n = random.randint(1, 3)
        sprefix = vcode  # 工号前缀用村级区划码, 唯一可追溯
        for i in range(1, n + 1):
            gender = random.choice(['1', '2'])
            title = random.choice(['4', '4', '5', '3'])
            birth = rand_birth(title)
            pcode = random.choice(['1', '1', '3'])
            abx = ABX_BY_TITLE.get(title, '11')
            cur.execute(STAFF_INSERT, {
                'tenant_id': TENANT_ID, 'org_id': ws_id, 'staff_no': "%s%02d" % (sprefix, i),
                'staff_name': gen_name(gender), 'staff_type': '医师', 'staff_type_name': '乡村医生',
                'staff_type_src': 'local:staff_type',
                'gender': gender, 'gender_name': GENDER[gender], 'gender_src': 'cv_code:gend',
                'title_code': title, 'title_name': TITLE[title], 'title_src': 'wst364:CV08.30.005',
                'dept_id': dept_id, 'id_card': id_card(i, gender, birth), 'birth_date': birth,
                'phone': gen_phone(), 'med_insur_code': gen_med_code(),
                'prac_cate': pcode, 'prac_cate_name': PRAC[pcode], 'prac_cate_src': "whvalue:CT98.00.024",
                'dr_qual_cert_no': "110%d%s" % (random.randint(10 ** 7, 10 ** 8 - 1), title),
                'prac_cert_no': "110%d%s" % (random.randint(10 ** 7, 10 ** 8 - 1), title),
                'can_register': 1, 'reg_fee': REG_FEE.get(title, 5.00), 'sort_no': i, 'status': 1,
                'rx_right': 1, 'narcotic_right': 0, 'psych1_right': 0, 'psych2_right': 1 if title == '3' else 0,
                'antibiotic_level': abx, 'antibiotic_level_name': ABX[abx], 'antibiotic_level_src': "hbvalue:HBCV08.50.029",
                'surgery_level': '1', 'surgery_level_name': OPRN['1'], 'surgery_level_src': "cv_code:oprn_lv_code",
                'rx_auth_org': '公共卫生服务科',
                'rx_auth_no': 'XCXN-%s-%02d' % (vcode[-6:], i),
                'rx_auth_date': date(2026, 1, 1), 'rx_valid_until': date(2028, 12, 31),
                'memo': '村卫生室乡村医生(%s)' % ws_name,
            })
            ws_staff += 1
        ws_user += create_users(ws_id, sprefix)

conn.commit()
print("==== 卫生院: 新建 %d 个 | 科室 %d, 职工 %d, 用户 %d ====" % (new_centers, grand_dept, grand_staff, grand_user))
print("==== 村卫生室: 新建 %d 个 | 乡村医生 %d, 用户 %d ====" % (ws_created, ws_staff, ws_user))
print("默认登录密码: %s (账号=工号)" % DEFAULT_PWD)

# 唯一性终检
cur.execute("SELECT COUNT(*) t, COUNT(DISTINCT med_insur_code) d FROM his_staff WHERE med_insur_code IS NOT NULL")
r = cur.fetchone()
print("med_insur_code 非空 %d, 去重后 %d %s" % (r['t'], r['d'], "OK" if r['t'] == r['d'] else "!!重复!!"))
conn.close()
