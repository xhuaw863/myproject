# -*- coding: utf-8 -*-
"""
孝昌县人民医院 + 卫生院 + 村卫生室 批量扩展(编制/科室/用户)。

目标规模:
  - 县医院(org_id=1): 约 1500 人, 科室按二级综合医院标准扩展;
  - 每个乡镇卫生院: 约 100 人, 增设老年病科/全科等;
  - 全部行政村建卫生室, 每室 5 人。

幂等策略:
  - 县医院: 工号前缀 'RB', 科室 dept_code 前缀 'RB-';
  - 卫生院: 检测现有职工数 < TARGET_STAFF_TOWNSHIP 才补编制;
  - 卫生室: org_code 存在跳过机构; 职工不足 5 人补到 5。

默认密码 123456。
"""
import pymysql, bcrypt, random, sys, io
from datetime import date

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
random.seed(20260927)

TENANT_ID = 1
ADMIN_CODE = "420921"
DEFAULT_PWD = "123456"

# =================== 字典 ===================
GENDER = {'1': '男', '2': '女'}
TITLE = {'1': '正高', '2': '副高', '3': '中级', '4': '师级/助理', '5': '士级'}
PRAC = {'1': '临床', '2': '口腔', '3': '公共卫生', '4': '中医'}
ABX = {'11': '一级', '12': '二级', '13': '三级'}
OPRN = {'1': '一级手术', '2': '二级手术', '3': '三级手术', '4': '四级手术'}
TITLE_BIRTH = {'1': (1962, 1972), '2': (1968, 1978), '3': (1975, 1990),
               '4': (1985, 1997), '5': (1992, 2003)}
REG_FEE = {'1': 30.00, '2': 20.00, '3': 10.00, '4': 6.00, '5': 5.00}
ABX_BY_TITLE = {'1': '13', '2': '13', '3': '12', '4': '11', '5': '11'}
OPRN_BY_TITLE = {'1': '3', '2': '3', '3': '2', '4': '1', '5': '1'}
ROLE_MAP = {
    '医师': ('DOCTOR', 3), '技师': ('DOCTOR', 3), '护士': ('NURSE', 6),
    '药师': ('PHARMACIST', 4), '管理': ('ADMIN', 1),
}

# =================== 姓名池 ===================
SURNAMES = list("王李张刘陈杨黄赵吴周徐孙马朱胡郭何高林罗郑梁谢宋唐许韩冯邓曹彭曾肖田董袁潘蒋蔡余杜叶程苏魏吕丁任沈姚卢姜崔钟谭陆汪范金石廖贾夏韦付方白邹孟熊秦邱江尹薛闫段雷侯龙史陶黎贺顾毛郝龚邵万钱严覃武戴莫孔向汤")
MALE_CHARS = list("建国志强志明国华文华建军建平建华国强志勇志鹏海涛海峰晓明晓东志刚伟东伟民俊杰俊豪浩然子轩宇航明辉德福永林永康嘉豪泽宇博文昊天鹏飞立新春生伟民")
FEMALE_CHARS = list("秀英桂兰玉兰春梅晓燕丽华丽娟丹丹婷婷静静雅静梦琪欣怡语嫣可心怡然佳怡思颖慧敏慧兰雪梅红梅春霞晓霞娜娜丽丽美玲雅琴婉婷紫萱若曦玉兰")
used_names = set()

# =================== 工具函数 ===================
def gen_name(gender):
    pool = MALE_CHARS if gender == '1' else FEMALE_CHARS
    for _ in range(5000):
        nm = random.choice(SURNAMES) + "".join(random.sample(pool, random.choice([1, 2])))
        if nm not in used_names:
            used_names.add(nm)
            return nm
    raise RuntimeError("name pool exhausted")


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


# =================== 数据库连接 ===================
conn = pymysql.connect(host='127.0.0.1', port=3306, user='root', password='bsoft',
                       database='yb_interface', charset='utf8mb4', autocommit=False)
cur = conn.cursor(pymysql.cursors.DictCursor)

# 预加载已有姓名
cur.execute("SELECT staff_name FROM his_staff WHERE tenant_id=%s AND deleted=0", (TENANT_ID,))
for r in cur.fetchall():
    if r['staff_name']:
        used_names.add(r['staff_name'])

# 医保编码池
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
global_sseq = 1000  # 全局工号序号起点(避免与既有冲突)

# =================== SQL 模板 ===================
DEPT_INSERT = """INSERT INTO his_dept
(tenant_id, org_id, parent_id, dept_code, dept_name, dept_type, dept_category, dept_level,
 sort_no, status, open_clinic, memo, create_by, create_time)
VALUES (%(tenant_id)s,%(org_id)s,%(parent_id)s,%(dept_code)s,%(dept_name)s,%(dept_type)s,
 %(dept_category)s,%(dept_level)s,%(sort_no)s,1,%(open_clinic)s,%(memo)s,'rb-seed',NOW())"""

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
 %(rx_auth_org)s,%(rx_auth_no)s,%(rx_auth_date)s,%(rx_valid_until)s,%(memo)s,'rb-seed',NOW())"""

USER_INSERT = """INSERT INTO sys_user
(tenant_id, username, password, real_name, role, staff_id, dept_id, org_id, role_id, phone,
 status, create_by, create_time)
VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,1,'rb-seed',NOW())"""

ORG_INSERT = """INSERT INTO sys_org
(tenant_id, org_code, org_name, org_level, is_lead, parent_id, org_type, org_type_name, org_type_src,
 admvs_code, leader, phone, address, sort_no, status, bed_cnt, price_lv, create_by, create_time)
VALUES (%(tenant_id)s,%(org_code)s,%(org_name)s,%(org_level)s,0,%(parent_id)s,%(org_type)s,%(org_type_name)s,
 'cv_code:MEDINS_TYPE',%(admvs_code)s,%(leader)s,%(phone)s,%(address)s,%(sort_no)s,1,%(bed_cnt)s,1,'rb-seed',NOW())"""


def make_staff_row(org_id, staff_no, dept_id, stype, title, gender, dseq, can_reg, is_surgical, prac_hint=None):
    """生成一条职工 INSERT 参数字典。"""
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
    med = gen_med_code() if (is_doctor or stype in ('护士', '药师', '技师')) else None
    return {
        'tenant_id': TENANT_ID, 'org_id': org_id, 'staff_no': staff_no,
        'staff_name': gen_name(gender), 'staff_type': stype, 'staff_type_name': stype,
        'staff_type_src': 'local:staff_type',
        'gender': gender, 'gender_name': GENDER[gender], 'gender_src': 'cv_code:gend',
        'title_code': title, 'title_name': TITLE[title], 'title_src': 'wst364:CV08.30.005',
        'dept_id': dept_id, 'id_card': id_card(dseq, gender, birth), 'birth_date': birth,
        'phone': gen_phone(), 'med_insur_code': med,
        'prac_cate': prac, 'prac_cate_name': prac_name, 'prac_cate_src': prac_src,
        'dr_qual_cert_no': dq, 'prac_cert_no': pc,
        'can_register': reg, 'reg_fee': REG_FEE.get(title, 0) if reg else 0.00,
        'sort_no': dseq, 'status': 1,
        'rx_right': rx, 'narcotic_right': nar, 'psych1_right': p1, 'psych2_right': p2,
        'antibiotic_level': abx, 'antibiotic_level_name': abx_name, 'antibiotic_level_src': abx_src,
        'surgery_level': sl, 'surgery_level_name': sl_name, 'surgery_level_src': sl_src,
        'rx_auth_org': '医务科' if is_doctor else None,
        'rx_auth_no': ('%s-RX-2026-%04d' % (staff_no[:4], dseq)) if is_doctor else None,
        'rx_auth_date': date(2026, 1, 1) if is_doctor else None,
        'rx_valid_until': date(2028, 12, 31) if is_doctor else None,
        'memo': '编制扩展批量生成',
    }


def create_users_for_staff(org_id, prefix):
    """为指定机构+工号前缀的职工补建登录用户。"""
    cur.execute("""SELECT id, staff_no, staff_name, staff_type, dept_id, phone FROM his_staff
                   WHERE tenant_id=%s AND org_id=%s AND staff_no LIKE %s AND deleted=0 ORDER BY id""",
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


# ======================================================================
# PHASE 1: 孝昌县人民医院 (org_id=1) 科室+编制扩展到 ~1500 人
# ======================================================================
print("=" * 70)
print("PHASE 1: 孝昌县人民医院 扩展")
print("=" * 70)

# 检查幂等: 若前缀 RB 的工号已存在则跳过
cur.execute("SELECT COUNT(*) c FROM his_staff WHERE tenant_id=%s AND staff_no LIKE 'RB%%' AND deleted=0", (TENANT_ID,))
rb_cnt = cur.fetchone()['c']
if rb_cnt > 0:
    print("县医院已有 RB 前缀职工(%d人), 跳过 Phase 1。" % rb_cnt)
    SKIP_PH1 = True
else:
    SKIP_PH1 = False

if not SKIP_PH1:
    sseq = global_sseq
    # 定位大类 parent_id (已在库中)
    cur.execute("SELECT id, dept_name FROM his_dept WHERE tenant_id=%s AND org_id=1 AND deleted=0 AND dept_level=1 AND parent_id=0",
                (TENANT_ID,))
    cats = {r['dept_name']: r['id'] for r in cur.fetchall()}
    CAT_MZ = cats.get('门诊科室', 15)
    CAT_ZY = cats.get('住院科室', 16)
    CAT_BQ = cats.get('病区护理', 17)
    CAT_YJ = cats.get('医技科室', 18)
    CAT_XZ = cats.get('行政后勤', 19)

    # 县医院扩展科室模板: (名称, 大类, 类型, open_clinic, 编制列表)
    # 编制列表: [(staff_type, title_code, count, gender)]  gender 1=男 2=女 0=随机
    HOSP_DEPTS = [
        # ---- 门诊科室 ----
        ("呼吸内科", "MZ", "临床", 1, [('医师','1',2,0),('医师','2',3,0),('医师','3',5,0),('医师','4',3,0),('护士','4',8,2)]),
        ("消化内科", "MZ", "临床", 1, [('医师','1',2,0),('医师','2',3,0),('医师','3',4,0),('医师','4',3,0),('护士','4',7,2)]),
        ("神经内科", "MZ", "临床", 1, [('医师','1',2,0),('医师','2',3,0),('医师','3',4,0),('医师','4',3,0),('护士','4',7,2)]),
        ("心血管内科", "MZ", "临床", 1, [('医师','1',2,0),('医师','2',3,0),('医师','3',5,0),('医师','4',3,0),('护士','4',8,2)]),
        ("血液内科", "MZ", "临床", 1, [('医师','2',2,0),('医师','3',3,0),('医师','4',2,0),('护士','4',5,2)]),
        ("肾内科", "MZ", "临床", 1, [('医师','2',2,0),('医师','3',3,0),('医师','4',2,0),('护士','4',5,2)]),
        ("内分泌科", "MZ", "临床", 1, [('医师','2',2,0),('医师','3',3,0),('医师','4',2,0),('护士','4',4,2)]),
        ("风湿免疫科", "MZ", "临床", 1, [('医师','2',1,0),('医师','3',2,0),('医师','4',2,0),('护士','4',3,2)]),
        ("感染性疾病科", "MZ", "临床", 1, [('医师','2',1,0),('医师','3',2,0),('医师','4',2,0),('护士','4',4,2)]),
        ("普通外科", "MZ", "临床", 1, [('医师','1',2,0),('医师','2',3,0),('医师','3',5,0),('医师','4',3,0),('护士','4',6,2)]),
        ("骨科", "MZ", "临床", 1, [('医师','1',2,0),('医师','2',3,0),('医师','3',5,0),('医师','4',4,0),('护士','4',8,2)]),
        ("神经外科", "MZ", "临床", 1, [('医师','2',2,0),('医师','3',3,0),('医师','4',3,0),('护士','4',5,2)]),
        ("泌尿外科", "MZ", "临床", 1, [('医师','2',2,0),('医师','3',3,0),('医师','4',2,0),('护士','4',5,2)]),
        ("胸外科", "MZ", "临床", 1, [('医师','2',1,0),('医师','3',2,0),('医师','4',2,0),('护士','4',4,2)]),
        ("肛肠外科", "MZ", "临床", 1, [('医师','2',1,0),('医师','3',3,0),('医师','4',2,0),('护士','4',4,2)]),
        ("妇科", "MZ", "临床", 1, [('医师','2',2,0),('医师','3',4,0),('医师','4',3,0),('护士','4',6,2)]),
        ("产科", "MZ", "临床", 1, [('医师','2',2,0),('医师','3',4,0),('医师','4',3,0),('护士','4',8,2)]),
        ("新生儿科", "MZ", "临床", 1, [('医师','2',1,0),('医师','3',3,0),('医师','4',3,0),('护士','4',6,2)]),
        ("小儿外科", "MZ", "临床", 1, [('医师','3',2,0),('医师','4',2,0),('护士','4',3,2)]),
        ("眼科", "MZ", "临床", 1, [('医师','2',1,0),('医师','3',3,0),('医师','4',3,0),('护士','4',4,2)]),
        ("耳鼻咽喉科", "MZ", "临床", 1, [('医师','2',1,0),('医师','3',3,0),('医师','4',3,0),('护士','4',4,2)]),
        ("口腔科", "MZ", "临床", 1, [('医师','2',1,0),('医师','3',3,0),('医师','4',3,0),('护士','4',4,0)]),
        ("皮肤科", "MZ", "临床", 1, [('医师','2',1,0),('医师','3',3,0),('医师','4',2,0),('护士','4',3,2)]),
        ("疼痛科", "MZ", "临床", 1, [('医师','3',2,0),('医师','4',2,0),('护士','4',3,2)]),
        ("康复医学科", "MZ", "临床", 1, [('医师','2',1,0),('医师','3',3,0),('医师','4',3,0),('护士','4',5,2)]),
        ("营养科", "MZ", "临床", 1, [('医师','3',1,0),('医师','4',2,0),('管理','4',2,0)]),
        ("全科医学科", "MZ", "临床", 1, [('医师','2',1,0),('医师','3',3,0),('医师','4',4,0),('护士','4',5,2)]),
        ("老年病科", "MZ", "临床", 1, [('医师','2',1,0),('医师','3',3,0),('医师','4',3,0),('护士','4',5,2)]),
        ("中医科(扩展)", "MZ", "临床", 1, [('医师','3',2,0),('医师','4',3,0),('护士','4',3,2)]),
        # ---- 住院科室 ----
        ("呼吸内科病区", "ZY", "临床", 1, [('医师','1',1,0),('医师','2',2,0),('医师','3',4,0),('医师','4',3,0),('护士','3',2,2),('护士','4',8,2),('护士','5',4,2)]),
        ("消化内科病区", "ZY", "临床", 1, [('医师','2',2,0),('医师','3',3,0),('医师','4',3,0),('护士','3',2,2),('护士','4',7,2),('护士','5',3,2)]),
        ("神经内科病区", "ZY", "临床", 1, [('医师','2',2,0),('医师','3',3,0),('医师','4',3,0),('护士','3',1,2),('护士','4',7,2),('护士','5',3,2)]),
        ("心血管内科病区", "ZY", "临床", 1, [('医师','1',1,0),('医师','2',2,0),('医师','3',4,0),('医师','4',3,0),('护士','3',2,2),('护士','4',8,2),('护士','5',4,2)]),
        ("血液肾内科病区", "ZY", "临床", 1, [('医师','2',2,0),('医师','3',3,0),('医师','4',3,0),('护士','3',1,2),('护士','4',6,2),('护士','5',3,2)]),
        ("内分泌风湿病区", "ZY", "临床", 1, [('医师','2',1,0),('医师','3',3,0),('医师','4',2,0),('护士','3',1,2),('护士','4',5,2),('护士','5',2,2)]),
        ("普外病区", "ZY", "临床", 1, [('医师','1',1,0),('医师','2',2,0),('医师','3',4,0),('医师','4',4,0),('护士','3',2,2),('护士','4',8,2),('护士','5',3,2)]),
        ("骨科病区", "ZY", "临床", 1, [('医师','1',1,0),('医师','2',2,0),('医师','3',4,0),('医师','4',4,0),('护士','3',2,2),('护士','4',8,2),('护士','5',4,2)]),
        ("神外胸外泌尿病区", "ZY", "临床", 1, [('医师','2',2,0),('医师','3',3,0),('医师','4',4,0),('护士','3',1,2),('护士','4',7,2),('护士','5',3,2)]),
        ("妇科病区", "ZY", "临床", 1, [('医师','2',2,0),('医师','3',3,0),('医师','4',3,0),('护士','3',1,2),('护士','4',6,2),('护士','5',3,2)]),
        ("产科病区", "ZY", "临床", 1, [('医师','2',1,0),('医师','3',3,0),('医师','4',3,0),('护士','3',2,2),('护士','4',8,2),('护士','5',4,2)]),
        ("儿科病区", "ZY", "临床", 1, [('医师','2',1,0),('医师','3',3,0),('医师','4',4,0),('护士','3',2,2),('护士','4',8,2),('护士','5',4,2)]),
        ("眼科耳鼻喉口腔病区", "ZY", "临床", 1, [('医师','2',1,0),('医师','3',3,0),('医师','4',3,0),('护士','3',1,2),('护士','4',5,2),('护士','5',2,2)]),
        ("皮肤科病区", "ZY", "临床", 1, [('医师','3',2,0),('医师','4',2,0),('护士','4',4,2),('护士','5',2,2)]),
        ("重症医学科(ICU)", "ZY", "临床", 1, [('医师','2',2,0),('医师','3',4,0),('医师','4',4,0),('护士','3',3,2),('护士','4',12,2),('护士','5',6,2)]),
        ("肿瘤科病区", "ZY", "临床", 1, [('医师','2',2,0),('医师','3',3,0),('医师','4',3,0),('护士','3',1,2),('护士','4',6,2),('护士','5',3,2)]),
        ("传染科病区", "ZY", "临床", 1, [('医师','2',1,0),('医师','3',2,0),('医师','4',2,0),('护士','3',1,2),('护士','4',5,2),('护士','5',2,2)]),
        ("康复医学科病区", "ZY", "临床", 1, [('医师','2',1,0),('医师','3',3,0),('医师','4',3,0),('护士','3',1,2),('护士','4',6,2),('护士','5',3,2)]),
        ("老年病科病区", "ZY", "临床", 1, [('医师','2',1,0),('医师','3',3,0),('医师','4',3,0),('护士','3',2,2),('护士','4',7,2),('护士','5',3,2)]),
        # ---- 医技科室 ----
        ("检验科", "YJ", "医技", 0, [('技师','2',2,0),('技师','3',5,0),('技师','4',8,0),('技师','5',3,0)]),
        ("医学影像科(MRI)", "YJ", "医技", 0, [('医师','3',2,0),('技师','3',3,0),('技师','4',4,0)]),
        ("超声医学科", "YJ", "医技", 0, [('医师','2',2,0),('医师','3',3,0),('技师','4',5,0)]),
        ("心电图室", "YJ", "医技", 0, [('技师','3',2,0),('技师','4',3,0),('医师','4',1,0)]),
        ("脑电图室", "YJ", "医技", 0, [('技师','3',1,0),('技师','4',2,0)]),
        ("内镜中心", "YJ", "医技", 0, [('医师','2',2,0),('医师','3',3,0),('技师','4',4,0),('护士','4',5,2)]),
        ("病理科", "YJ", "医技", 0, [('医师','2',1,0),('医师','3',3,0),('技师','4',3,0)]),
        ("输血科(血库)", "YJ", "医技", 0, [('技师','3',2,0),('技师','4',3,0)]),
        ("核医学科", "YJ", "医技", 0, [('医师','3',1,0),('技师','3',1,0),('技师','4',2,0)]),
        ("放射治疗科", "YJ", "医技", 0, [('医师','2',1,0),('医师','3',2,0),('技师','3',2,0),('技师','4',3,0)]),
        ("麻醉科", "YJ", "医技", 0, [('医师','2',2,0),('医师','3',5,0),('医师','4',5,0),('护士','4',6,2)]),
        ("手术室", "YJ", "医技", 0, [('护士','3',3,2),('护士','4',12,2),('护士','5',5,2)]),
        ("消毒供应中心", "YJ", "医技", 0, [('护士','4',3,2),('管理','5',2,0)]),
        ("体检中心", "YJ", "医技", 0, [('医师','3',2,0),('医师','4',3,0),('护士','4',5,2),('管理','4',2,0)]),
        # ---- 行政后勤 ----
        ("院办公室", "XZ", "行政", 0, [('管理','2',1,0),('管理','3',2,0),('管理','4',3,0)]),
        ("人事科", "XZ", "行政", 0, [('管理','3',1,0),('管理','4',3,0)]),
        ("财务科", "XZ", "行政", 0, [('管理','3',2,0),('管理','4',5,0),('药师','3',1,0)]),
        ("医务科", "XZ", "行政", 0, [('管理','2',1,0),('管理','3',2,0),('管理','4',3,0)]),
        ("护理部", "XZ", "行政", 0, [('护士','2',1,2),('护士','3',3,2),('护士','4',3,2)]),
        ("院感管理科", "XZ", "行政", 0, [('管理','3',1,0),('医师','3',1,0),('护士','4',2,2)]),
        ("设备科", "XZ", "行政", 0, [('管理','3',1,0),('管理','4',3,0)]),
        ("总务科(后勤保障)", "XZ", "行政", 0, [('管理','3',2,0),('管理','4',5,0),('管理','5',3,0)]),
        ("病案统计科", "XZ", "行政", 0, [('管理','3',1,0),('管理','4',3,0)]),
        ("医保管理科", "XZ", "行政", 0, [('管理','3',1,0),('管理','4',3,0)]),
        ("信息科(HIS)", "XZ", "行政", 0, [('管理','3',1,0),('管理','4',4,0)]),
        ("药剂科(扩展)", "XZ", "行政", 0, [('药师','2',2,0),('药师','3',5,0),('药师','4',8,0),('药师','5',3,0)]),
        ("保卫科", "XZ", "行政", 0, [('管理','4',2,0),('管理','5',4,0)]),
        ("投诉管理办公室", "XZ", "行政", 0, [('管理','3',1,0),('管理','4',2,0)]),
    ]

    CAT_PREFIX = {"MZ": CAT_MZ, "ZY": CAT_ZY, "BQ": CAT_BQ, "YJ": CAT_YJ, "XZ": CAT_XZ}
    CAT_NAME = {"MZ": "门诊科室", "ZY": "住院科室", "BQ": "病区护理", "YJ": "医技科室", "XZ": "行政后勤"}
    CAT_DTYPE = {"MZ": "临床", "ZY": "临床", "BQ": "临床", "YJ": "医技", "XZ": "行政"}

    dept_count = staff_count = 0
    sseq = 0
    for didx, (dname, cat_key, dtype, open_cl, staff_tmpl) in enumerate(HOSP_DEPTS, 1):
        parent = CAT_PREFIX[cat_key]
        dept_code = "RB-%03d" % didx
        # 查重
        cur.execute("SELECT id FROM his_dept WHERE tenant_id=%s AND org_id=1 AND dept_code=%s AND deleted=0",
                    (TENANT_ID, dept_code))
        if cur.fetchone():
            continue
        cur.execute(DEPT_INSERT, {
            'tenant_id': TENANT_ID, 'org_id': 1, 'parent_id': parent,
            'dept_code': dept_code, 'dept_name': dname,
            'dept_type': dtype, 'dept_category': CAT_NAME[cat_key],
            'dept_level': 2, 'sort_no': 100 + didx, 'open_clinic': open_cl,
            'memo': '县医院编制扩展'
        })
        dept_id = cur.lastrowid
        dept_count += 1

        is_surgical = any(k in dname for k in ['外科', '骨科', '妇', '产', '手术', '麻醉', ' ICU', '重症'])
        prac_hint = '4' if '中医' in dname else ('3' if '预防' in dname or '感染' in dname else None)

        for (stype, title, cnt, gender) in staff_tmpl:
            for _ in range(cnt):
                sseq += 1
                g = str(random.choice([1, 2])) if gender == 0 else str(gender)
                if stype == '护士':
                    g = '2'  # 护士以女性为主(模拟真实)
                row = make_staff_row(1, "RB%04d" % sseq, dept_id, stype, title, g, sseq,
                                     can_reg=(cat_key == 'MZ'), is_surgical=is_surgical, prac_hint=prac_hint)
                cur.execute(STAFF_INSERT, row)
                staff_count += 1

    # 补齐已有科室的职工: 内科门诊/外科门诊/儿科门诊/妇产科门诊/急诊科/中医科
    cur.execute("""SELECT id, dept_name FROM his_dept WHERE tenant_id=%s AND org_id=1 AND deleted=0
                   AND dept_level=2 AND dept_category='门诊科室' AND dept_code NOT LIKE 'RB%%'""", (TENANT_ID,))
    existing_depts = cur.fetchall()
    for ed in existing_depts:
        cur.execute("SELECT COUNT(*) c FROM his_staff WHERE tenant_id=%s AND org_id=1 AND dept_id=%s AND deleted=0",
                    (TENANT_ID, ed['id']))
        have = cur.fetchone()['c']
        need = max(0, 20 - have)  # 每个既有门诊科室补到 20 人
        for _ in range(need):
            sseq += 1
            stype = random.choice(['医师','医师','护士','护士','护士'])
            title = random.choice(['2','3','3','4','4'])
            g = '1' if stype == '医师' else '2'
            row = make_staff_row(1, "RB%04d" % sseq, ed['id'], stype, title, g, sseq,
                                 can_reg=True, is_surgical=('外科' in ed['dept_name']))
            cur.execute(STAFF_INSERT, row)
            staff_count += 1

    users_created = create_users_for_staff(1, "RB")
    conn.commit()
    print("  新增科室: %d, 新增职工: %d, 新增用户: %d" % (dept_count, staff_count, users_created))
    # 统计全院
    cur.execute("SELECT COUNT(*) c FROM his_staff WHERE tenant_id=1 AND org_id=1 AND deleted=0")
    print("  县医院现有职工总数: %d" % cur.fetchone()['c'])

# ======================================================================
# PHASE 2: 卫生院扩展 (每院目标 ~100 人)
# ======================================================================
print("\n" + "=" * 70)
print("PHASE 2: 卫生院扩展到每院 ~100 人")
print("=" * 70)

TOWN_TARGET = 100
TOWNS = [
    (8, "花园镇", "XCHY"), (12, "丰山镇", "XCFS"), (16, "周巷镇", "XCZX"),
    (23, "小河镇", "XC103"), (24, "王店镇", "XC104"), (25, "卫店镇", "XC105"),
    (26, "白沙镇", "XC106"), (27, "邹岗镇", "XC107"), (28, "小悟乡", "XC200"),
    (29, "季店乡", "XC201"), (30, "花西乡", "XC202"), (31, "陡山乡", "XC203"),
]

# 需为卫生院新增的科室模板
NEW_TOWN_DEPTS = [
    ("老年病科", "门诊科室", "临床", 1,
     [('医师','2',1,0),('医师','3',2,0),('医师','4',2,0),('护士','4',3,2)]),
    ("全科医学科", "门诊科室", "临床", 1,
     [('医师','2',1,0),('医师','3',2,0),('医师','4',3,0),('护士','4',4,2)]),
    ("感染科", "门诊科室", "临床", 1,
     [('医师','3',1,0),('医师','4',2,0),('护士','4',2,2)]),
    ("眼科", "门诊科室", "临床", 1,
     [('医师','3',1,0),('医师','4',2,0),('护士','4',2,2)]),
    ("耳鼻咽喉科", "门诊科室", "临床", 1,
     [('医师','3',1,0),('医师','4',1,0),('护士','4',2,2)]),
    ("口腔科", "门诊科室", "临床", 1,
     [('医师','3',1,0),('医师','4',2,0),('护士','4',2,0)]),
    ("皮肤科", "门诊科室", "临床", 1,
     [('医师','3',1,0),('医师','4',2,0),('护士','4',2,2)]),
    ("心电图室", "医技科室", "医技", 0,
     [('技师','3',1,0),('技师','4',2,0)]),
    ("胃镜室", "医技科室", "医技", 0,
     [('医师','3',1,0),('技师','4',2,0),('护士','4',2,2)]),
    ("收费室", "行政后勤", "行政", 0,
     [('管理','4',3,0),('管理','5',2,0)]),
    ("院感科", "行政后勤", "行政", 0,
     [('医师','3',1,0),('护士','4',1,2)]),
    ("医务科", "行政后勤", "行政", 0,
     [('管理','3',1,0),('管理','4',2,0)]),
    ("护理部", "行政后勤", "行政", 0,
     [('护士','3',1,2),('护士','4',2,2)]),
    ("病案室", "行政后勤", "行政", 0,
     [('管理','4',2,0)]),
    ("总务科", "行政后勤", "行政", 0,
     [('管理','4',2,0),('管理','5',3,0)]),
]

grand_dept_new = grand_staff_new = 0
town_sseq = 0
for org_id, town_name, prefix in TOWNS:
    # 当前职工数
    cur.execute("SELECT COUNT(*) c FROM his_staff WHERE tenant_id=%s AND org_id=%s AND deleted=0", (TENANT_ID, org_id))
    cur_staff = cur.fetchone()['c']
    to_add = TOWN_TARGET - cur_staff
    if to_add <= 0:
        print("  [%s] 已有 %d 人, 跳过。" % (town_name, cur_staff))
        continue

    # 新增科室 (幂等: 按 dept_code 查重)
    cat_map = {}
    cur.execute("SELECT id, dept_name FROM his_dept WHERE tenant_id=%s AND org_id=%s AND deleted=0 AND dept_level=1",
                (TENANT_ID, org_id))
    for r in cur.fetchall():
        cat_map[r['dept_name']] = r['id']

    dept_new_here = 0
    dept_ids = {}  # name -> id for this town
    for dname, cat, dtype, open_cl, tmpl in NEW_TOWN_DEPTS:
        dcode = "%s-RB-%s" % (prefix, dname[:2])
        cur.execute("SELECT id FROM his_dept WHERE tenant_id=%s AND org_id=%s AND dept_code=%s AND deleted=0",
                    (TENANT_ID, org_id, dcode))
        row = cur.fetchone()
        if row:
            dept_ids[dname] = row['id']
            continue
        parent = cat_map.get(cat, 0)
        cur.execute(DEPT_INSERT, {
            'tenant_id': TENANT_ID, 'org_id': org_id, 'parent_id': parent,
            'dept_code': dcode, 'dept_name': dname,
            'dept_type': dtype, 'dept_category': cat,
            'dept_level': 2, 'sort_no': 50 + len(dept_ids), 'open_clinic': open_cl,
            'memo': '%s卫生院-编制扩展' % town_name
        })
        dept_ids[dname] = cur.lastrowid
        dept_new_here += 1

    # 补职工: 将编制分配到全院各科室(含新建科室)
    # 获取全院所有 level2 科室
    cur.execute("""SELECT id, dept_name, dept_category FROM his_dept
                   WHERE tenant_id=%s AND org_id=%s AND deleted=0 AND dept_level=2""", (TENANT_ID, org_id))
    all_depts = cur.fetchall()
    if not all_depts:
        continue

    # 按模板先给新科室加人, 剩余名额按已有科室轮流填充
    staff_this = 0
    # 先填新科室
    for dname, cat, dtype, open_cl, tmpl in NEW_TOWN_DEPTS:
        did = dept_ids.get(dname)
        if not did:
            continue
        for (stype, title, cnt, gender) in tmpl:
            if staff_this >= to_add:
                break
            for _ in range(cnt):
                if staff_this >= to_add:
                    break
                town_sseq += 1
                g = str(random.choice([1,2])) if gender == 0 else str(gender)
                if stype == '护士':
                    g = '2'
                row = make_staff_row(org_id, "%sRB%03d" % (prefix, town_sseq), did, stype, title, g, town_sseq,
                                     can_reg=(cat == '门诊科室'), is_surgical=False)
                cur.execute(STAFF_INSERT, row)
                staff_this += 1

    # 剩余名额分配给全院各科室轮转
    remaining = to_add - staff_this
    if remaining > 0 and all_depts:
        # 获取已有所有科室的职工数(含刚插入的新科室)
        cur.execute("""SELECT d.id, d.dept_name, d.dept_category, COUNT(s.id) sn
                       FROM his_dept d LEFT JOIN his_staff s ON s.dept_id=d.id AND s.deleted=0
                       WHERE d.tenant_id=%s AND d.org_id=%s AND d.deleted=0 AND d.dept_level=2
                       GROUP BY d.id, d.dept_name, d.dept_category""", (TENANT_ID, org_id))
        dept_staff_info = cur.fetchall()
        idx = 0
        for _ in range(remaining):
            d = dept_staff_info[idx % len(dept_staff_info)]
            idx += 1
            town_sseq += 1
            # 根据科室大类决定人员类型
            if d['dept_category'] in ('门诊科室', '住院科室', '病区护理'):
                stype = random.choice(['医师','护士','护士'])
            elif d['dept_category'] == '医技科室':
                stype = random.choice(['技师','医师'])
            else:
                stype = random.choice(['管理','药师'])
            title = random.choice(['3','4','4','5'])
            g = '2' if stype == '护士' else str(random.choice([1,2]))
            row = make_staff_row(org_id, "%sRB%03d" % (prefix, town_sseq), d['id'], stype, title, g, town_sseq,
                                 can_reg=(d['dept_category'] == '门诊科室'), is_surgical=False)
            cur.execute(STAFF_INSERT, row)
            staff_this += 1

    grand_dept_new += dept_new_here
    grand_staff_new += staff_this
    print("  [%s org=%d] 新增科室 %d, 新增职工 %d (总 %d)" % (town_name, org_id, dept_new_here, staff_this, cur_staff + staff_this))

    # 创建用户
    create_users_for_staff(org_id, prefix + "RB")

conn.commit()
print("  合计: 新增科室 %d, 新增职工 %d" % (grand_dept_new, grand_staff_new))

# ======================================================================
# PHASE 3: 村卫生室全覆盖 (每个行政村设室, 每室 5 人)
# ======================================================================
print("\n" + "=" * 70)
print("PHASE 3: 村卫生室全覆盖 (每室 5 人)")
print("=" * 70)

WS_TARGET_STAFF = 5
TOWN_CODES = [
    (420921100000, 8, "花园镇"), (420921101000, 12, "丰山镇"), (420921102000, 16, "周巷镇"),
    (420921103000, 23, "小河镇"), (420921104000, 24, "王店镇"), (420921105000, 25, "卫店镇"),
    (420921106000, 26, "白沙镇"), (420921107000, 27, "邹岗镇"), (420921200000, 28, "小悟乡"),
    (420921201000, 29, "季店乡"), (420921202000, 30, "花西乡"), (420921203000, 31, "陡山乡"),
]

ws_new = ws_supplement = ws_staff_added = 0
village_sseq = 0
for town_code, town_org_id, town_name in TOWN_CODES:
    cur.execute("SELECT code, name FROM area_code_2021 WHERE level=5 AND pcode=%s ORDER BY code", (town_code,))
    villages = cur.fetchall()
    for v in villages:
        vcode = str(v['code'])
        vname = v['name']
        # 名称处理
        if vname.endswith("居委会") or vname.endswith("村委会"):
            base = vname[:-3]
        else:
            base = vname
        ws_name = base + "卫生室"

        # 查机构是否已存在
        cur.execute("SELECT id FROM sys_org WHERE tenant_id=%s AND org_code=%s AND deleted=0", (TENANT_ID, vcode))
        row = cur.fetchone()
        if row:
            ws_id = row['id']
        else:
            # 新建机构
            cur.execute(ORG_INSERT, {
                'tenant_id': TENANT_ID, 'org_code': vcode, 'org_name': ws_name, 'org_level': 3,
                'parent_id': town_org_id, 'org_type': 'D600', 'org_type_name': '村卫生室',
                'admvs_code': ADMIN_CODE, 'leader': gen_name(random.choice(['1','2'])),
                'phone': "027" + "%08d" % random.randint(0, 99999999),
                'address': "孝昌县" + town_name + vname, 'sort_no': 0, 'bed_cnt': None,
            })
            ws_id = cur.lastrowid
            # 定点编号
            cur.execute("UPDATE sys_org SET fixmedins_code=%s, fixmedins_name=%s, fixmedins_type='1', "
                        "fixmedins_type_name='定点医疗机构', fixmedins_type_src='cv_code:fixmedins_type' WHERE id=%s",
                        ("H42092100%03d" % ws_id, "孝昌县" + ws_name, ws_id))
            ws_new += 1

        # 检查职工数
        cur.execute("SELECT COUNT(*) c FROM his_staff WHERE tenant_id=%s AND org_id=%s AND deleted=0", (TENANT_ID, ws_id))
        cur_staff = cur.fetchone()['c']
        if cur_staff >= WS_TARGET_STAFF:
            continue

        # 确保有科室 (全科)
        cur.execute("SELECT id FROM his_dept WHERE tenant_id=%s AND org_id=%s AND deleted=0 LIMIT 1", (TENANT_ID, ws_id))
        drow = cur.fetchone()
        if drow:
            dept_id = drow['id']
        else:
            cur.execute(DEPT_INSERT, {
                'tenant_id': TENANT_ID, 'org_id': ws_id, 'parent_id': 0,
                'dept_code': "%s-WS" % vcode, 'dept_name': '全科医疗科',
                'dept_type': '临床', 'dept_category': '门诊科室', 'dept_level': 1, 'sort_no': 1,
                'open_clinic': 1, 'memo': '%s-村卫生室全科' % ws_name
            })
            dept_id = cur.lastrowid

        # 补到 5 人
        to_add = WS_TARGET_STAFF - cur_staff
        for i in range(to_add):
            village_sseq += 1
            gender = random.choice(['1','2'])
            title = random.choice(['4','4','5','5','3'])
            pcode = random.choice(['1','1','3','4'])
            sprefix = vcode
            staff_no = "%s%02d" % (sprefix, cur_staff + i + 1)
            # 查重工号
            cur.execute("SELECT id FROM his_staff WHERE staff_no=%s", (staff_no,))
            if cur.fetchone():
                staff_no = "%sRB%02d" % (sprefix, i + 1)
                cur.execute("SELECT id FROM his_staff WHERE staff_no=%s", (staff_no,))
                if cur.fetchone():
                    continue
            birth = rand_birth(title)
            abx = ABX_BY_TITLE.get(title, '11')
            cur.execute(STAFF_INSERT, {
                'tenant_id': TENANT_ID, 'org_id': ws_id, 'staff_no': staff_no,
                'staff_name': gen_name(gender), 'staff_type': '医师', 'staff_type_name': '乡村医生',
                'staff_type_src': 'local:staff_type',
                'gender': gender, 'gender_name': GENDER[gender], 'gender_src': 'cv_code:gend',
                'title_code': title, 'title_name': TITLE[title], 'title_src': 'wst364:CV08.30.005',
                'dept_id': dept_id, 'id_card': id_card(village_sseq, gender, birth), 'birth_date': birth,
                'phone': gen_phone(), 'med_insur_code': gen_med_code(),
                'prac_cate': pcode, 'prac_cate_name': PRAC[pcode], 'prac_cate_src': "whvalue:CT98.00.024",
                'dr_qual_cert_no': "110%d%s" % (random.randint(10**7, 10**8-1), title),
                'prac_cert_no': "110%d%s" % (random.randint(10**7, 10**8-1), title),
                'can_register': 1, 'reg_fee': REG_FEE.get(title, 5.00), 'sort_no': cur_staff+i+1, 'status': 1,
                'rx_right': 1, 'narcotic_right': 0, 'psych1_right': 0, 'psych2_right': 1 if title == '3' else 0,
                'antibiotic_level': abx, 'antibiotic_level_name': ABX[abx], 'antibiotic_level_src': "hbvalue:HBCV08.50.029",
                'surgery_level': None, 'surgery_level_name': None, 'surgery_level_src': None,
                'rx_auth_org': '公共卫生服务科',
                'rx_auth_no': 'XCXN-%s-%02d' % (vcode[-6:], cur_staff+i+1),
                'rx_auth_date': date(2026, 1, 1), 'rx_valid_until': date(2028, 12, 31),
                'memo': '村卫生室乡村医生(%s)' % ws_name,
            })
            ws_staff_added += 1
        if cur_staff > 0:
            ws_supplement += 1
        # 创建用户
        create_users_for_staff(ws_id, vcode)

    if (TOWN_CODES.index((town_code, town_org_id, town_name)) + 1) % 3 == 0:
        conn.commit()
        print("  已处理 %d/%d 个乡镇..." % (TOWN_CODES.index((town_code, town_org_id, town_name))+1, len(TOWN_CODES)))

conn.commit()
print("  新建卫生室: %d, 补充职工的已有室: %d, 新增职工: %d" % (ws_new, ws_supplement, ws_staff_added))

# ======================================================================
# 终检
# ======================================================================
print("\n" + "=" * 70)
print("终检统计")
print("=" * 70)
cur.execute("SELECT COUNT(*) c FROM his_staff WHERE tenant_id=1 AND org_id=1 AND deleted=0")
print("县医院职工: %d" % cur.fetchone()['c'])
cur.execute("SELECT COUNT(*) c FROM his_dept WHERE tenant_id=1 AND org_id=1 AND deleted=0")
print("县医院科室: %d" % cur.fetchone()['c'])
cur.execute("SELECT COUNT(*) c FROM his_staff WHERE tenant_id=1 AND org_id IN (8,12,16,23,24,25,26,27,28,29,30,31) AND deleted=0")
town_total = cur.fetchone()['c']
print("卫生院职工总计: %d (均 %.0f/院)" % (town_total, town_total/12))
cur.execute("SELECT COUNT(*) c FROM sys_org WHERE tenant_id=1 AND org_level=3 AND deleted=0")
ws_total = cur.fetchone()['c']
print("村卫生室总数: %d" % ws_total)
cur.execute("SELECT COUNT(*) c FROM his_staff WHERE tenant_id=1 AND deleted=0 AND org_id IN (SELECT id FROM sys_org WHERE tenant_id=1 AND org_level=3 AND deleted=0)")
ws_staff_total = cur.fetchone()['c']
print("村卫生室职工总计: %d (均 %.1f/室)" % (ws_staff_total, ws_staff_total/max(ws_total,1)))
cur.execute("SELECT COUNT(*) t, COUNT(DISTINCT med_insur_code) d FROM his_staff WHERE med_insur_code IS NOT NULL AND deleted=0")
r = cur.fetchone()
print("med_insur_code: 非空 %d, 去重 %d %s" % (r['t'], r['d'], "OK" if r['t']==r['d'] else "!!重复!!"))
cur.execute("SELECT COUNT(*) c FROM sys_user WHERE tenant_id=1 AND deleted=0")
print("全库用户总数: %d" % cur.fetchone()['c'])
print("\n默认登录密码: %s (账号=工号)" % DEFAULT_PWD)
conn.close()
