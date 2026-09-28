# -*- coding: utf-8 -*-
"""
县医院编制补充: 当前 ~1146 人, 补到 ~1500 人。
在所有门诊/住院/医技/行政科室间轮转分配。幂等: 按 RB2 前缀查重。
"""
import pymysql, bcrypt, random, sys, io
from datetime import date

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
random.seed(20260927)

TENANT_ID = 1
ADMIN_CODE = "420921"
DEFAULT_PWD = "123456"
TARGET = 1500

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
ROLE_MAP = {'医师': ('DOCTOR', 3), '技师': ('DOCTOR', 3), '护士': ('NURSE', 6),
            '药师': ('PHARMACIST', 4), '管理': ('ADMIN', 1)}

SURNAMES = list("王李张刘陈杨黄赵吴周徐孙马朱胡郭何高林罗郑梁谢宋唐许韩冯邓曹彭曾肖田董袁潘蒋蔡余杜叶程苏魏吕丁任沈姚卢姜崔钟谭陆汪范金石廖贾夏韦付方白邹孟熊秦邱江尹薛闫段雷侯龙史陶黎贺顾毛郝龚邵万钱严覃武戴莫孔向汤")
MALE_CHARS = list("建国志强志明国华文华建军建平建华国强志勇志鹏海涛海峰晓明晓东志刚伟东伟民俊杰俊豪浩然子轩宇航明辉德福永林永康嘉豪泽宇博文昊天鹏飞立新春生")
FEMALE_CHARS = list("秀英桂兰玉兰春梅晓燕丽华丽娟丹丹婷婷静静雅静梦琪欣怡语嫣可心怡然佳怡思颖慧敏慧兰雪梅红梅春霞晓霞娜娜丽丽美玲雅琴婉婷紫萱若曦")
used_names = set()


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


conn = pymysql.connect(host='127.0.0.1', port=3306, user='root', password='bsoft',
                       database='yb_interface', charset='utf8mb4', autocommit=False)
cur = conn.cursor(pymysql.cursors.DictCursor)

# 预加载姓名
cur.execute("SELECT staff_name FROM his_staff WHERE tenant_id=%s AND deleted=0", (TENANT_ID,))
for r in cur.fetchall():
    used_names.add(r['staff_name'])

# 医保编码池
med_pool = set()
cur.execute("SELECT med_insur_code c FROM his_staff WHERE med_insur_code IS NOT NULL")
for r in cur.fetchall():
    med_pool.add(r['c'])

# 幂等检查
cur.execute("SELECT COUNT(*) c FROM his_staff WHERE tenant_id=%s AND staff_no LIKE 'RB2%%' AND deleted=0", (TENANT_ID,))
rb2_cnt = cur.fetchone()['c']
if rb2_cnt > 0:
    print("已有 RB2 前缀(%d), 跳过。" % rb2_cnt)
    conn.close()
    sys.exit(0)

# 当前总人数
cur.execute("SELECT COUNT(*) c FROM his_staff WHERE tenant_id=1 AND org_id=1 AND deleted=0")
cur_staff = cur.fetchone()['c']
to_add = TARGET - cur_staff
if to_add <= 0:
    print("县医院已有 %d 人 >= 目标 %d, 无需补充。" % (cur_staff, TARGET))
    conn.close()
    sys.exit(0)
print("县医院当前 %d 人, 需补充 %d 人。" % (cur_staff, to_add))

# 获取所有 level>=2 的科室
cur.execute("""SELECT id, dept_name, dept_category, dept_type FROM his_dept
               WHERE tenant_id=1 AND org_id=1 AND deleted=0 AND dept_level>=2
               ORDER BY dept_category, id""")
depts = cur.fetchall()
print("可分配科室: %d 个" % len(depts))

PWD_HASH = bcrypt.hashpw(DEFAULT_PWD.encode('utf-8'), bcrypt.gensalt(rounds=10, prefix=b"2a")).decode('utf-8')

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
 %(rx_auth_org)s,%(rx_auth_no)s,%(rx_auth_date)s,%(rx_valid_until)s,%(memo)s,'rb2-seed',NOW())"""

USER_INSERT = """INSERT INTO sys_user
(tenant_id, username, password, real_name, role, staff_id, dept_id, org_id, role_id, phone,
 status, create_by, create_time)
VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,1,'rb2-seed',NOW())"""


def gen_med_code():
    while True:
        code = "H42%013d" % random.randint(0, 10 ** 13 - 1)
        if code not in med_pool:
            med_pool.add(code)
            return code


def pick_staff_type(dept):
    cat = dept.get('dept_category') or ''
    dtype = dept.get('dept_type') or ''
    if '行政' in cat or '后勤' in cat:
        return random.choice(['管理', '管理', '药师'])
    elif '医技' in cat or dtype == '医技':
        return random.choice(['技师', '技师', '医师'])
    elif '病区' in cat or '住院' in cat:
        return random.choice(['医师', '护士', '护士', '护士'])
    else:  # 门诊
        return random.choice(['医师', '医师', '护士', '护士'])


def is_surgical(dept):
    name = dept.get('dept_name') or ''
    return any(k in name for k in ['外科', '骨科', '妇', '产', '手术', '麻醉', 'ICU', '重症', '胸外', '神外', '泌尿', '肛肠'])


def is_reg_dept(dept):
    return dept.get('dept_category') == '门诊科室' and dept.get('dept_level', 0) >= 2


inserted = 0
sseq = 2000  # 起始序号区分 RB2
for i in range(to_add):
    sseq += 1
    dept = depts[i % len(depts)]
    stype = pick_staff_type(dept)
    title = random.choice(['3', '4', '4', '4', '5']) if stype in ('护士', '技师', '管理', '药师') else random.choice(['2', '3', '3', '4', '4'])
    gender = '2' if stype == '护士' else str(random.choice([1, 2]))
    birth = rand_birth(title)

    is_doc = (stype == '医师')
    prac = prac_name = prac_src = dq = pc = None
    if is_doc:
        pcode = '4' if '中医' in (dept.get('dept_name') or '') else '1'
        prac, prac_name, prac_src = pcode, PRAC[pcode], "whvalue:CT98.00.024"
        dq = "110%d%s" % (random.randint(10 ** 7, 10 ** 8 - 1), title)
        pc = "110%d%s" % (random.randint(10 ** 7, 10 ** 8 - 1), title)
    rx = nar = p1 = p2 = 0
    abx = abx_name = abx_src = None
    if is_doc:
        rx = 1
        abx = ABX_BY_TITLE.get(title, '11')
        abx_name, abx_src = ABX[abx], "hbvalue:HBCV08.50.029"
        if title in ('1', '2'):
            nar = p1 = p2 = 1
        elif title == '3':
            p2 = 1
    sl = sl_name = sl_src = None
    if is_doc and is_surgical(dept):
        sl = OPRN_BY_TITLE.get(title, '1')
        sl_name, sl_src = OPRN[sl], "cv_code:oprn_lv_code"
    reg = 1 if (is_reg_dept(dept) and is_doc) else 0
    med = gen_med_code() if (is_doc or stype in ('护士', '药师', '技师')) else None

    staff_no = "RB2%04d" % sseq
    name = gen_name(gender)
    cur.execute(STAFF_INSERT, {
        'tenant_id': TENANT_ID, 'org_id': 1, 'staff_no': staff_no,
        'staff_name': name, 'staff_type': stype, 'staff_type_name': stype,
        'staff_type_src': 'local:staff_type',
        'gender': gender, 'gender_name': GENDER[gender], 'gender_src': 'cv_code:gend',
        'title_code': title, 'title_name': TITLE[title], 'title_src': 'wst364:CV08.30.005',
        'dept_id': dept['id'], 'id_card': id_card(sseq, gender, birth), 'birth_date': birth,
        'phone': gen_phone(), 'med_insur_code': med,
        'prac_cate': prac, 'prac_cate_name': prac_name, 'prac_cate_src': prac_src,
        'dr_qual_cert_no': dq, 'prac_cert_no': pc,
        'can_register': reg, 'reg_fee': REG_FEE.get(title, 0) if reg else 0.00,
        'sort_no': sseq, 'status': 1,
        'rx_right': rx, 'narcotic_right': nar, 'psych1_right': p1, 'psych2_right': p2,
        'antibiotic_level': abx, 'antibiotic_level_name': abx_name, 'antibiotic_level_src': abx_src,
        'surgery_level': sl, 'surgery_level_name': sl_name, 'surgery_level_src': sl_src,
        'rx_auth_org': '医务科' if is_doc else None,
        'rx_auth_no': ('RB2-RX-2026-%04d' % sseq) if is_doc else None,
        'rx_auth_date': date(2026, 1, 1) if is_doc else None,
        'rx_valid_until': date(2028, 12, 31) if is_doc else None,
        'memo': '县医院编制补充(rb2)',
    })
    inserted += 1

conn.commit()

# 补建用户
print("已插入职工 %d, 开始补建用户..." % inserted)
cur.execute("""SELECT id, staff_no, staff_name, staff_type, dept_id, phone FROM his_staff
               WHERE tenant_id=1 AND org_id=1 AND staff_no LIKE 'RB2%%' AND deleted=0""")
staff_list = cur.fetchall()
user_cnt = 0
for s in staff_list:
    cur.execute("SELECT id FROM sys_user WHERE username=%s", (s['staff_no'],))
    if cur.fetchone():
        continue
    role_code, role_id = ROLE_MAP.get(s['staff_type'], ('DOCTOR', 3))
    cur.execute(USER_INSERT, (TENANT_ID, s['staff_no'], PWD_HASH, s['staff_name'],
                              role_code, s['id'], s['dept_id'], 1, role_id, s['phone']))
    user_cnt += 1
conn.commit()

# 终检
cur.execute("SELECT COUNT(*) c FROM his_staff WHERE tenant_id=1 AND org_id=1 AND deleted=0")
final = cur.fetchone()['c']
cur.execute("SELECT COUNT(*) t, COUNT(DISTINCT med_insur_code) d FROM his_staff WHERE med_insur_code IS NOT NULL AND deleted=0")
r = cur.fetchone()
print("县医院最终职工: %d" % final)
print("新增用户: %d" % user_cnt)
print("med_insur_code: 非空 %d, 去重 %d %s" % (r['t'], r['d'], "OK" if r['t'] == r['d'] else "!!重复!!"))
conn.close()
