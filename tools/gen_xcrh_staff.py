# -*- coding: utf-8 -*-
"""
为「孝昌县人民医院」(tenant_id=1, org_id=1) 各业务科室批量模拟生成职工。
- 数据严格遵循系统字典回填规范(gender/title/prac_cate/antibiotic/surgery 均带 _name+_src)。
- 医师带处方权(rx_right)、抗菌分级、精麻专项权; 外科系带手术级别。
- 身份证按孝昌县行政区码 420921 生成, 含合法校验位; 出生日期由职称档次推导。
- 幂等: 工号前缀 XCRH, 已存在则整体跳过, 可重复执行。
"""
import pymysql
import random
from datetime import date

random.seed(20260920)

TENANT_ID = 1
ORG_ID = 1
ADMIN_CODE = "420921"   # 孝昌县
STAFF_NO_PREFIX = "XCRH"

conn = pymysql.connect(host='127.0.0.1', port=3306, user='root',
                       password='bsoft', database='yb_interface', charset='utf8mb4',
                       autocommit=False)
cur = conn.cursor(pymysql.cursors.DictCursor)

# ---------------- 字典(与库内一致, 用于回填 _name/_src) ----------------
GENDER = {'1': '男', '2': '女'}
TITLE = {'1': '正高', '2': '副高', '3': '中级', '4': '师级/助理', '5': '士级'}
PRAC = {'1': '临床', '2': '口腔', '3': '公共卫生', '4': '中医'}
ABX = {'11': '一级', '12': '二级', '13': '三级'}
OPRN = {'1': '一级手术', '2': '二级手术', '3': '三级手术', '4': '四级手术'}

# ---------------- 姓名池 ----------------
SURNAMES = list("王李张刘陈杨黄赵吴周徐孙马朱胡郭何高林罗郑梁谢宋唐许韩冯邓曹彭曾肖田董袁潘蒋蔡余杜叶程苏魏吕丁任沈姚卢姜崔钟谭陆汪范金石廖贾夏韦付方白邹孟熊秦邱江尹薛闫段雷侯龙史陶黎贺顾毛郝龚邵万钱严覃武戴莫孔向汤")
MALE_CHARS = list("建国志强志明国华文华文华建军建平建华国强志勇志鹏海涛海峰晓明晓东志刚志刚伟东伟民俊杰俊豪浩然子轩宇航明辉德福永林永康嘉豪泽宇博文昊天鹏飞立新")
FEMALE_CHARS = list("秀英桂兰玉兰春梅晓燕丽华丽娟丹丹婷婷静静雅静梦琪欣怡语嫣可心怡然佳怡思颖慧敏慧兰雪梅红梅春霞晓霞娜娜丽丽美玲雅琴婉婷紫萱若曦")

used_names = set()


def gen_name(gender):
    pool = MALE_CHARS if gender == '1' else FEMALE_CHARS
    for _ in range(500):
        sn = random.choice(SURNAMES)
        gn = "".join(random.sample(pool, random.choice([1, 2])))
        nm = sn + gn
        if nm not in used_names:
            used_names.add(nm)
            return nm
    raise RuntimeError("name exhausted")


# 已有姓名纳入去重
cur.execute("SELECT staff_name FROM his_staff WHERE tenant_id=%s", (TENANT_ID,))
for r in cur.fetchall():
    if r['staff_name']:
        used_names.add(r['staff_name'])

# ---------------- 身份证(带校验位) ----------------
def id_card(seq, gender, birth):
    body = ADMIN_CODE + birth.strftime("%Y%m%d") + "%02d" % (seq % 99 + 1) + gender
    weights = [7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2]
    checks = "10X98765432"
    s = sum(int(body[i]) * weights[i] for i in range(17))
    return body + checks[s % 11]


# 职称档次 -> 出生年份区间
TITLE_BIRTH = {'1': (1962, 1970), '2': (1970, 1978), '3': (1978, 1988),
               '4': (1986, 1995), '5': (1994, 2001)}


def rand_birth(title):
    lo, hi = TITLE_BIRTH.get(title, (1980, 1995))
    y = random.randint(lo, hi)
    return date(y, random.randint(1, 12), random.randint(1, 28))


# 职称 -> 挂号费 / 抗菌级别 / 手术级别建议
REG_FEE = {'1': 50.00, '2': 30.00, '3': 20.00, '4': 10.00, '5': 8.00}
ABX_BY_TITLE = {'1': '13', '2': '13', '3': '12', '4': '11', '5': '11'}
OPRN_BY_TITLE = {'1': '4', '2': '3', '3': '2', '4': '1', '5': '1'}

# ---------------- 科室编制模板 ----------------
# 角色: (职工类别, 职称码, 性别)
CLINIC_OP = [   # 门诊临床科室
    ('医师', '1', '1'), ('医师', '2', '2'), ('医师', '3', '1'),
    ('医师', '3', '2'), ('医师', '4', '1'), ('护士', '4', '2'), ('护士', '5', '2'),
]
CLINIC_IN = [   # 住院临床科室
    ('医师', '1', '1'), ('医师', '2', '1'), ('医师', '3', '2'),
    ('医师', '4', '1'), ('护士', '3', '2'), ('护士', '4', '2'), ('护士', '5', '2'),
]
WARD = [        # 病区护理
    ('护士', '3', '2'), ('护士', '4', '2'), ('护士', '4', '2'),
    ('护士', '5', '2'), ('护士', '5', '1'),
]
TECH = [        # 医技科室(CT/B超/放射)
    ('医师', '3', '1'), ('技师', '3', '2'), ('技师', '4', '1'), ('技师', '5', '2'),
]
PHARM = [       # 药剂/药房
    ('药师', '3', '2'), ('药师', '4', '1'), ('药师', '5', '2'),
]
ADMIN = [       # 行政后勤
    ('管理', '3', '1'), ('管理', '4', '2'),
]

# 手术科室(医师赋手术级别)
SURGICAL_DEPTS = {2, 4, 5, 39, 40}

# 科室编制清单: (dept_id, dept_name, 模板, 是否门诊可挂号)
DEPT_PLAN = [
    (1,  "内科",     CLINIC_OP, True),
    (2,  "外科",     CLINIC_OP, True),
    (3,  "儿科",     CLINIC_OP, True),
    (4,  "妇产科",   CLINIC_OP, True),
    (5,  "急诊科",   CLINIC_OP, True),
    (6,  "中医科",   CLINIC_OP, True),
    (39, "外一科",   CLINIC_IN, False),
    (40, "外二科",   CLINIC_IN, False),
    (41, "内一科",   CLINIC_IN, False),
    (42, "外科病区", WARD,      False),
    (43, "内科病区", WARD,      False),
    (44, "CT室",     TECH,      False),
    (45, "B超室",    TECH,      False),
    (46, "放射科",   TECH,      False),
    (29, "药剂科",   PHARM,     False),
    (35, "门诊药房", PHARM,     False),
    (37, "住院药房", PHARM,     False),
    (30, "信息科",   ADMIN,     False),
    (32, "医保办",   ADMIN,     False),
]

# ---------------- 幂等检查 ----------------
cur.execute("SELECT COUNT(*) c FROM his_staff WHERE tenant_id=%s AND staff_no LIKE %s",
            (TENANT_ID, STAFF_NO_PREFIX + "%"))
if cur.fetchone()['c'] > 0:
    print("已存在 %s 前缀职工, 判定为已生成, 跳过。如需重跑请先清理。" % STAFF_NO_PREFIX)
    conn.close()
    raise SystemExit(0)

INSERT_SQL = """INSERT INTO his_staff
(tenant_id, org_id, staff_no, staff_name, staff_type, staff_type_name, staff_type_src,
 gender, gender_name, gender_src, title_code, title_name, title_src,
 dept_id, id_card, birth_date, phone, med_insur_code, prac_cate, prac_cate_name, prac_cate_src,
 dr_qual_cert_no, prac_cert_no, can_register, reg_fee, sort_no, status,
 rx_right, narcotic_right, psych1_right, psych2_right,
 antibiotic_level, antibiotic_level_name, antibiotic_level_src,
 surgery_level, surgery_level_name, surgery_level_src,
 rx_auth_org, rx_auth_no, rx_auth_date, rx_valid_until, memo,
 create_by, create_time)
VALUES
(%(tenant_id)s,%(org_id)s,%(staff_no)s,%(staff_name)s,%(staff_type)s,%(staff_type_name)s,%(staff_type_src)s,
 %(gender)s,%(gender_name)s,%(gender_src)s,%(title_code)s,%(title_name)s,%(title_src)s,
 %(dept_id)s,%(id_card)s,%(birth_date)s,%(phone)s,%(med_insur_code)s,%(prac_cate)s,%(prac_cate_name)s,%(prac_cate_src)s,
 %(dr_qual_cert_no)s,%(prac_cert_no)s,%(can_register)s,%(reg_fee)s,%(sort_no)s,%(status)s,
 %(rx_right)s,%(narcotic_right)s,%(psych1_right)s,%(psych2_right)s,
 %(antibiotic_level)s,%(antibiotic_level_name)s,%(antibiotic_level_src)s,
 %(surgery_level)s,%(surgery_level_name)s,%(surgery_level_src)s,
 %(rx_auth_org)s,%(rx_auth_no)s,%(rx_auth_date)s,%(rx_valid_until)s,%(memo)s,
 %(create_by)s,NOW())"""

seq = 0
rows = []
for dept_id, dept_name, template, can_reg in DEPT_PLAN:
    is_tcm = (dept_id == 6)
    for (stype, title, gender) in template:
        seq += 1
        staff_no = "%s%04d" % (STAFF_NO_PREFIX, seq)
        name = gen_name(gender)
        birth = rand_birth(title)
        idc = id_card(seq, gender, birth)
        phone = "1%d%09d" % (random.choice([3, 5, 7, 8, 9]), random.randint(0, 999999999))
        med_code = "H42%013d" % random.randint(0, 10**13 - 1)

        is_doctor = (stype == '医师')
        prac = prac_name = prac_src = None
        dq = pc = None
        if is_doctor:
            pcode = '4' if is_tcm else '1'
            prac, prac_name, prac_src = pcode, PRAC[pcode], "whvalue:CT98.00.024"
            dq = "110%d%s" % (random.randint(10**7, 10**8 - 1), title)
            pc = "110%d%s" % (random.randint(10**7, 10**8 - 1), title)

        # 处方权 / 抗菌 / 精麻
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

        # 手术级别(仅手术科室医师)
        sl = sl_name = sl_src = None
        if is_doctor and dept_id in SURGICAL_DEPTS:
            sl = OPRN_BY_TITLE.get(title, '1')
            sl_name, sl_src = OPRN[sl], "cv_code:oprn_lv_code"

        reg = 1 if (can_reg and is_doctor) else 0
        rows.append({
            'tenant_id': TENANT_ID, 'org_id': ORG_ID, 'staff_no': staff_no,
            'staff_name': name, 'staff_type': stype, 'staff_type_name': stype,
            'staff_type_src': 'local:staff_type',
            'gender': gender, 'gender_name': GENDER[gender], 'gender_src': 'cv_code:gend',
            'title_code': title, 'title_name': TITLE[title], 'title_src': 'wst364:CV08.30.005',
            'dept_id': dept_id, 'id_card': idc, 'birth_date': birth, 'phone': phone,
            'med_insur_code': med_code if (is_doctor or stype in ('护士', '药师')) else None,
            'prac_cate': prac, 'prac_cate_name': prac_name, 'prac_cate_src': prac_src,
            'dr_qual_cert_no': dq, 'prac_cert_no': pc,
            'can_register': reg, 'reg_fee': REG_FEE.get(title, 0) if reg else 0.00,
            'sort_no': seq, 'status': 1,
            'rx_right': rx, 'narcotic_right': nar, 'psych1_right': p1, 'psych2_right': p2,
            'antibiotic_level': abx, 'antibiotic_level_name': abx_name, 'antibiotic_level_src': abx_src,
            'surgery_level': sl, 'surgery_level_name': sl_name, 'surgery_level_src': sl_src,
            'rx_auth_org': '医务科' if is_doctor else None,
            'rx_auth_no': ('YWK-RX-2026-%04d' % seq) if is_doctor else None,
            'rx_auth_date': date(2026, 1, 1) if is_doctor else None,
            'rx_valid_until': date(2028, 12, 31) if is_doctor else None,
            'memo': '批量模拟生成(%s)' % dept_name,
            'create_by': 'system-seed',
        })

cur.executemany(INSERT_SQL, rows)
conn.commit()

print("成功生成职工 %d 名, 覆盖科室 %d 个。" % (len(rows), len(DEPT_PLAN)))
# 汇总
from collections import Counter
by_dept = Counter((r['dept_id']) for r in rows)
by_type = Counter(r['staff_type'] for r in rows)
print("按类别:", dict(by_type))
for dept_id, dept_name, _, _ in DEPT_PLAN:
    print("  %-8s(dept %2d): %d 人" % (dept_name, dept_id, by_dept[dept_id]))
conn.close()
