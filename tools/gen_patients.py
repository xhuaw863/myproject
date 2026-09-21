# -*- coding: utf-8 -*-
"""
为「孝昌县」医共体(tenant_id=1)6 家主力医疗机构批量模拟建档患者档案(his_patient)。

目标机构(每机构 100 条, 合计 600 条):
  - org 1  孝昌县人民医院   (level1 A100)
  - org 6  孝昌县中医院     (level1 A2)
  - org 7  孝昌县妇幼保健院 (level1 G100)
  - org 8  花园镇卫生院     (level2 C2)
  - org 12 丰山镇卫生院     (level2 C2)
  - org 16 周巷镇卫生院     (level2 C2)

规范(严格对齐 HisPatientService.enrichDict 的三件套 code/name/src):
  - cv_code(std_cv_code): gend / insutype / mdtrt_cert_type / psn_cert_type / naty
  - hbvalue(std_hbvalue_code): GB/T 2659.1-2022(国籍) / GB/T 2261.2-2003(婚姻) /
        GB/T 4658-2006(文化程度) / CV02.01.202(职业) / GB/T 4761-2008(与患者关系)
  - area_code_2021: 现住址/出生地/户籍/通讯/单位/联系人 四级级联(省/市/县/镇) + 参保地区划
  - 身份证以 420921(孝昌县)为主, 约 15% 用孝感市内其他区县(外地), 含合法校验位;
        出生日期精确到秒; 第 17 位奇男偶女; 年龄按 2026-09-20 计算。
  - 字典编码全部运行时从库加载, 只挑选真实存在的 val_code, 保证 name 回填不为空。
  - org_id 作建档机构归属标注(医共体统一患者主索引, 跨机构按身份证去重)。
  - 幂等: 按 create_by='system-seed' + org_id 判定, 该院已生成则整体跳过。
"""
import pymysql
import random
import io
import sys
from datetime import date, datetime

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
random.seed(20260920)

DRY_RUN = False            # 先试运行校验, 确认后改 False 正式写库
PER_ORG = 100             # 每机构生成条数
TENANT_ID = 1
AS_OF = date(2026, 9, 20)  # 年龄计算基准日
BATCH_STAMP = "20260920"
OUT_COUNTY_RATIO = 0.15    # 外地(孝感市内其他区县)占比

# 6 家主力机构(按生成顺序)
TARGET_ORGS = [1, 6, 7, 8, 12, 16]

# 孝昌县 6 位行政区划码(身份证主体前缀)
HOME_COUNTY6 = "420921"
PROV_CODE = "420000000000"   # 湖北省
CITY_CODE = "420900000000"   # 孝感市
COUNTY_CODE = "420921000000"  # 孝昌县(12 位)

# ---------------- 姓名池 ----------------
SURNAMES = list("王李张刘陈杨黄赵吴周徐孙马朱胡郭何高林罗郑梁谢宋唐许韩冯邓曹彭曾肖田董袁潘蒋蔡余杜叶程苏魏吕丁任沈姚卢姜崔钟谭陆汪范金石廖贾夏韦付方白邹孟熊秦邱江尹薛闫段雷侯龙史陶黎贺顾毛郝龚邵万钱严覃武戴莫孔向汤")
MALE_CHARS = "建国志强志明国华文华建军建平建华国强志勇志鹏海涛海峰晓明晓东志刚伟东伟民俊杰俊豪浩然子轩宇航明辉德福永林永康嘉豪泽宇博文昊天鹏飞立新军平安康祥瑞"
FEMALE_CHARS = "秀英桂兰玉兰春梅晓燕丽华丽娟丹丹婷婷静静雅静梦琪欣怡语嫣可心怡然佳怡思颖慧敏慧兰雪梅红梅春霞晓霞娜娜丽丽美玲雅琴婉婷紫萱若曦桂芳淑珍秀珍"
MALE_CHARS = list(MALE_CHARS)
FEMALE_CHARS = list(FEMALE_CHARS)
used_names = set()
used_idcards = set()

conn = pymysql.connect(host='127.0.0.1', port=3306, user='root', password='bsoft',
                       database='yb_interface', charset='utf8mb4', autocommit=False)
cur = conn.cursor(pymysql.cursors.DictCursor)


# ==================== 字典/区划加载 ====================
def load_cv(dict_code):
    cur.execute("SELECT val_code, val_name FROM std_cv_code WHERE dict_code=%s AND vali_flag='1'", (dict_code,))
    return {r['val_code']: r['val_name'] for r in cur.fetchall()}


def load_hb(std_code):
    cur.execute("SELECT val_code, val_name FROM std_hbvalue_code WHERE dict_code=%s AND vali_flag='1'", (std_code,))
    m = {}
    for r in cur.fetchall():
        m.setdefault(r['val_code'], r['val_name'])  # 去重(库内有重复行)
    return m


CV = {
    'gend': load_cv('gend'),
    'insutype': load_cv('insutype'),
    'mdtrt_cert_type': load_cv('mdtrt_cert_type'),
    'psn_cert_type': load_cv('psn_cert_type'),
    'naty': load_cv('naty'),
}
HB = {
    'nationality': load_hb('GB/T 2659.1-2022'),
    'marital': load_hb('GB/T 2261.2-2003'),
    'edu': load_hb('GB/T 4658-2006'),
    'occupation': load_hb('CV02.01.202'),
    'relation': load_hb('GB/T 4761-2008'),
}


def find_code(m, *keywords):
    """按名称关键词在字典 map 中找第一个匹配的 val_code(找不到返回 None)。"""
    for kw in keywords:
        for c, n in m.items():
            if n and kw in n:
                return c
    return None


# 关键字典编码定位(全部取库内真实存在的编码)
CHINA_CODE = find_code(HB['nationality'], '中国') or '156'
CERT_ID_CODE = find_code(CV['psn_cert_type'], '居民身份证') or '01'
INS_310 = '310' if '310' in CV['insutype'] else (list(CV['insutype'])[0] if CV['insutype'] else '310')
INS_390 = find_code(CV['insutype'], '居民') or '390'
MCT_ID = find_code(CV['mdtrt_cert_type'], '身份证') or '02'
MCT_ELEC = find_code(CV['mdtrt_cert_type'], '电子凭证') or '01'
MCT_CARD = find_code(CV['mdtrt_cert_type'], '社会保障卡', '社保卡') or '03'
NATION_HAN = find_code(CV['naty'], '汉族') or '1'

MAR_UNMARRIED = find_code(HB['marital'], '未婚')
MAR_MARRIED = find_code(HB['marital'], '已婚')
MAR_WIDOW = find_code(HB['marital'], '丧偶')
MAR_DIVORCE = find_code(HB['marital'], '离婚')

EDU_POOL = [c for c in [
    find_code(HB['edu'], '研究生'),
    find_code(HB['edu'], '大学本科'),
    find_code(HB['edu'], '大学专科'),
    find_code(HB['edu'], '普通高中毕业'),
    find_code(HB['edu'], '初中毕业'),
    find_code(HB['edu'], '小学毕业'),
    find_code(HB['edu'], '其他'),
] if c]

OCC_FARMER = find_code(HB['occupation'], '农民')
OCC_WORKER = find_code(HB['occupation'], '工人')
OCC_CADRE = find_code(HB['occupation'], '干部职员')
OCC_TEACHER = find_code(HB['occupation'], '教师')
OCC_MEDIC = find_code(HB['occupation'], '医务人员')
OCC_STUDENT = find_code(HB['occupation'], '学生')
OCC_HOUSEWIFE = find_code(HB['occupation'], '家务及待业')
OCC_RETIRED = find_code(HB['occupation'], '离退人员')
OCC_CHILD = find_code(HB['occupation'], '幼托儿童')
OCC_BIZ = find_code(HB['occupation'], '商业服务')

REL_FATHER = find_code(HB['relation'], '父亲')
REL_MOTHER = find_code(HB['relation'], '母亲')
REL_SPOUSE = find_code(HB['relation'], '夫') or find_code(HB['relation'], '配偶')
REL_SON = find_code(HB['relation'], '长子') or find_code(HB['relation'], '子')
REL_DAU = find_code(HB['relation'], '长女') or find_code(HB['relation'], '女')


# ==================== 区划加载 ====================
def load_county(county12):
    """加载某区县(level3, 12位)下的乡镇(level4)及其村(level5)。返回 (name, [(town_code, town_name, [(vcode,vname)...])...])"""
    cur.execute("SELECT name FROM area_code_2021 WHERE code=%s", (county12,))
    r = cur.fetchone()
    cname = r['name'] if r else ''
    cur.execute("SELECT code, name FROM area_code_2021 WHERE pcode=%s AND level=4 ORDER BY code", (county12,))
    towns = []
    for t in cur.fetchall():
        cur2 = conn.cursor(pymysql.cursors.DictCursor)
        cur2.execute("SELECT code, name FROM area_code_2021 WHERE pcode=%s AND level=5 ORDER BY code", (t['code'],))
        villages = [(v['code'], v['name']) for v in cur2.fetchall()]
        cur2.close()
        towns.append((t['code'], t['name'], villages))
    return cname, towns


HOME_COUNTY_NAME, HOME_TOWNS = load_county(COUNTY_CODE)

# 孝感市内其他区县(外地患者): level3 under 420900000000, 排除孝昌县
cur.execute("SELECT code, name FROM area_code_2021 WHERE pcode=%s AND level=3 AND code<>%s ORDER BY code",
            (CITY_CODE, COUNTY_CODE))
OTHER_COUNTIES = []
for c in cur.fetchall():
    nm, towns = load_county(c['code'])
    if towns:
        OTHER_COUNTIES.append((c['code'], c['name'], towns))

PROV_NAME = '湖北省'
CITY_NAME = '孝感市'


def gen_name(gender):
    pool = MALE_CHARS if gender == '1' else FEMALE_CHARS
    for _ in range(1000):
        nm = random.choice(SURNAMES) + "".join(random.sample(pool, random.choice([1, 2])))
        if nm not in used_names:
            used_names.add(nm)
            return nm
    # 姓名池耗尽时追加随机数字后缀保证唯一
    nm = random.choice(SURNAMES) + random.choice(pool) + str(random.randint(0, 9))
    used_names.add(nm)
    return nm


def id_checksum(body17):
    weights = [7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2]
    checks = "10X98765432"
    s = sum(int(body17[i]) * weights[i] for i in range(17))
    return checks[s % 11]


def make_id_card(county6, birth, seq, gender):
    g = '1' if gender == '1' else '2'  # 第17位: 奇男偶女
    seq2 = "%02d" % (seq % 99 + 1)
    body = county6 + birth.strftime("%Y%m%d") + seq2 + g
    return body + id_checksum(body)


def rand_datetime(birth_date):
    return datetime(birth_date.year, birth_date.month, birth_date.day,
                    random.randint(0, 23), random.randint(0, 59), random.randint(0, 59))


def pick_birth():
    """按人群分布抽出生日期: 儿童/青壮年/中老年/老年。"""
    r = random.random()
    if r < 0.12:      # 0-14 儿童
        y = random.randint(2011, 2025)
    elif r < 0.55:    # 18-45 青壮年
        y = random.randint(1981, 2007)
    elif r < 0.85:    # 46-65 中老年
        y = random.randint(1961, 1980)
    else:             # 66+ 老年
        y = random.randint(1940, 1960)
    return date(y, random.randint(1, 12), random.randint(1, 28))


def calc_age(birth):
    return AS_OF.year - birth.year - ((AS_OF.month, AS_OF.day) < (birth.month, birth.day))


def pick_town(towns):
    t = random.choice(towns)
    return t  # (code, name, villages)


def detail_addr(villages, town_name):
    if villages:
        vc, vn = random.choice(villages)
        return vn + "%d组%d号" % (random.randint(1, 12), random.randint(1, 200)), vn
    return town_name + "街道%d号" % random.randint(1, 300), town_name


def phone():
    return "1%d%09d" % (random.choice([3, 5, 7, 8, 9]), random.randint(0, 999999999))



# ==================== 单位名称池 ====================
EMP_SUFFIX = ["有限公司", "工贸有限公司", "食品有限公司", "建材厂", "机械厂", "纺织厂", "养殖专业合作社",
              "农业科技有限公司", "商贸有限公司", "电子科技有限公司", "服饰有限公司"]
EMP_ORG = ["孝昌县第一人民医院", "孝昌县中医院", "孝昌县妇幼保健院", "花园镇人民政府", "丰山镇中心小学",
           "周巷镇卫生院", "孝昌县农村商业银行", "孝昌县供电公司", "孝感市孝昌工业园"]
EDU_POOL_NAMES = ["希望小学", "实验小学", "第一中学", "职业技术学校"]


def rand_employer(county_name, town_name):
    r = random.random()
    if r < 0.35:
        return random.choice(EMP_ORG)
    if r < 0.7:
        return county_name + random.choice(["鑫源", "宏达", "昌盛", "富民", "金桥", "华丰"]) + random.choice(EMP_SUFFIX)
    return town_name + random.choice(EDU_POOL_NAMES)


def area_names(county_name, town_code, town_name):
    """返回四级级联 dict 片段(prov/city/county/town 的 code+name)。"""
    return {
        'prov': PROV_CODE, 'prov_name': PROV_NAME,
        'city': CITY_CODE, 'city_name': CITY_NAME,
        'county': None, 'county_name': county_name,
        'town': str(town_code), 'town_name': town_name,
    }


def build_patient(org_id, org_name, org_index, seq, gseq):
    gender = '1' if random.random() < 0.51 else '2'
    name = gen_name(gender)
    birth = pick_birth()
    age = calc_age(birth)

    # ---- 居住地: 本地(孝昌县)为主, 约 15% 外地(孝感市内其他区县) ----
    if OTHER_COUNTIES and random.random() < OUT_COUNTY_RATIO:
        oc_code, oc_name, oc_towns = random.choice(OTHER_COUNTIES)
        county12, county_name, towns = str(oc_code), oc_name, oc_towns
    else:
        county12, county_name, towns = COUNTY_CODE, HOME_COUNTY_NAME, HOME_TOWNS
    county6 = county12[:6]
    town_code, town_name, villages = pick_town(towns)
    detail, village_name = detail_addr(villages, town_name)
    full_addr = PROV_NAME + CITY_NAME + county_name + town_name + detail

    idc = make_id_card(county6, birth, seq, gender)
    k = seq
    while idc in used_idcards:
        k += random.randint(1, 98)
        idc = make_id_card(county6, birth, k, gender)
    used_idcards.add(idc)
    ph = phone()

    p = {
        'tenant_id': TENANT_ID,
        'org_id': org_id,
        'patient_no': "P%s%02d%04d" % (BATCH_STAMP, org_index, seq),
        'name': name,
        'gender': gender, 'gender_name': CV['gend'].get(gender), 'gender_src': 'cv_code:gend',
        'birth_date': rand_datetime(birth),
        'age': age,
        'id_card': idc,
        'phone': ph,
        'address': full_addr,
        # A 身份人口学
        'cert_type': CERT_ID_CODE, 'cert_type_name': CV['psn_cert_type'].get(CERT_ID_CODE),
        'cert_type_src': 'cv_code:psn_cert_type',
        'nationality': CHINA_CODE, 'nationality_name': HB['nationality'].get(CHINA_CODE),
        'nationality_src': 'hbvalue:GB/T 2659.1-2022',
        # B 现住址四级级联
        'present_prov': PROV_CODE, 'present_prov_name': PROV_NAME,
        'present_city': CITY_CODE, 'present_city_name': CITY_NAME,
        'present_county': county12, 'present_county_name': county_name,
        'present_town': str(town_code), 'present_town_name': town_name,
        'present_src': 'area_code_2021', 'present_detail': detail,
        'status': 1,
        'create_by': 'system-seed',
        'create_time': datetime(2026, 9, 20, random.randint(8, 18), random.randint(0, 59), random.randint(0, 59)),
    }

    # ---- 民族: 汉族为主, 少量少数民族 ----
    if random.random() < 0.92 or not CV['naty']:
        nation = NATION_HAN
    else:
        nation = random.choice(list(CV['naty'].keys()))
    p['nation'] = nation
    p['nation_name'] = CV['naty'].get(nation)
    p['nation_src'] = 'cv_code:naty'

    # ---- 婚姻状况(按年龄) ----
    if age < 22:
        mar = MAR_UNMARRIED
    elif age >= 70 and MAR_WIDOW and random.random() < 0.35:
        mar = MAR_WIDOW
    elif MAR_DIVORCE and random.random() < 0.06:
        mar = MAR_DIVORCE
    else:
        mar = MAR_MARRIED or MAR_UNMARRIED
    if mar:
        p['marital_status'] = mar
        p['marital_status_name'] = HB['marital'].get(mar)
        p['marital_status_src'] = 'hbvalue:GB/T 2261.2-2003'

    # ---- 文化程度(按年龄) ----
    edu = None
    if age >= 16 and EDU_POOL:
        if age >= 65:
            edu = random.choice([c for c in EDU_POOL if c] or [None])
            edu = find_code(HB['edu'], '小学毕业') or find_code(HB['edu'], '初中毕业') or EDU_POOL[-1]
        elif age >= 45:
            edu = random.choice([find_code(HB['edu'], '初中毕业'), find_code(HB['edu'], '小学毕业'),
                                 find_code(HB['edu'], '普通高中毕业')] or EDU_POOL)
        elif age >= 23:
            edu = random.choice([find_code(HB['edu'], '普通高中毕业'), find_code(HB['edu'], '大学专科'),
                                 find_code(HB['edu'], '大学本科'), find_code(HB['edu'], '初中毕业')] or EDU_POOL)
        else:
            edu = find_code(HB['edu'], '普通高中毕业') or find_code(HB['edu'], '初中毕业')
    if edu:
        p['edu_level'] = edu
        p['edu_level_name'] = HB['edu'].get(edu)
        p['edu_level_src'] = 'hbvalue:GB/T 4658-2006'

    # ---- 职业(按年龄 + 机构类型) ----
    is_rural_org = org_id in (8, 12, 16)
    occ = None
    if age < 6:
        occ = OCC_CHILD
    elif age < 18:
        occ = OCC_STUDENT
    elif age < 60:
        if is_rural_org:
            occ = random.choices(
                [OCC_FARMER, OCC_WORKER, OCC_BIZ, OCC_CADRE, OCC_TEACHER, OCC_MEDIC, OCC_HOUSEWIFE],
                weights=[55, 12, 8, 5, 4, 3, 13])[0]
        else:
            occ = random.choices(
                [OCC_FARMER, OCC_WORKER, OCC_BIZ, OCC_CADRE, OCC_TEACHER, OCC_MEDIC, OCC_HOUSEWIFE],
                weights=[22, 18, 14, 14, 8, 10, 14])[0]
    else:
        occ = random.choices([OCC_FARMER, OCC_RETIRED, OCC_HOUSEWIFE], weights=[45, 35, 20])[0]
    if occ:
        p['occupation'] = occ
        p['occupation_name'] = HB['occupation'].get(occ)
        p['occupation_src'] = 'hbvalue:CV02.01.202'

    # ---- 工作单位(青壮年且有职业者约 45%) ----
    has_emp = (18 <= age < 60) and occ in (OCC_WORKER, OCC_CADRE, OCC_TEACHER, OCC_MEDIC, OCC_BIZ) \
        and random.random() < 0.7
    if has_emp or (18 <= age < 60 and random.random() < 0.2):
        emp = rand_employer(county_name, town_name)
        p['employer'] = emp
        p['employer_phone'] = "0712-%07d" % random.randint(1000000, 9999999)
        p['employer_addr'] = PROV_NAME + CITY_NAME + county_name + town_name + "单位路%d号" % random.randint(1, 99)
        p['emp_prov'] = PROV_CODE; p['emp_prov_name'] = PROV_NAME
        p['emp_city'] = CITY_CODE; p['emp_city_name'] = CITY_NAME
        p['emp_county'] = county12; p['emp_county_name'] = county_name
        p['emp_town'] = str(town_code); p['emp_town_name'] = town_name
        p['emp_src'] = 'area_code_2021'
    else:
        has_emp = False

    # ---- 出生地: 80% 与现住址同县, 否则孝昌县 ----
    if random.random() < 0.8:
        b_county12, b_county_name, b_towns = county12, county_name, towns
    else:
        b_county12, b_county_name, b_towns = COUNTY_CODE, HOME_COUNTY_NAME, HOME_TOWNS
    b_town_code, b_town_name, b_villages = pick_town(b_towns)
    b_detail, _ = detail_addr(b_villages, b_town_name)
    p.update({
        'birth_prov': PROV_CODE, 'birth_prov_name': PROV_NAME,
        'birth_city': CITY_CODE, 'birth_city_name': CITY_NAME,
        'birth_county': b_county12, 'birth_county_name': b_county_name,
        'birth_town': str(b_town_code), 'birth_town_name': b_town_name,
        'birth_src': 'area_code_2021', 'birth_detail': b_detail,
    })

    # ---- 户籍/通讯地址: 与现住址一致(本地居民) ----
    p.update({
        'household_prov': PROV_CODE, 'household_prov_name': PROV_NAME,
        'household_city': CITY_CODE, 'household_city_name': CITY_NAME,
        'household_county': county12, 'household_county_name': county_name,
        'household_town': str(town_code), 'household_town_name': town_name,
        'household_src': 'area_code_2021', 'household_addr': full_addr,
        'mail_prov': PROV_CODE, 'mail_prov_name': PROV_NAME,
        'mail_city': CITY_CODE, 'mail_city_name': CITY_NAME,
        'mail_county': county12, 'mail_county_name': county_name,
        'mail_town': str(town_code), 'mail_town_name': town_name,
        'mail_src': 'area_code_2021',
    })

    # ---- 险种 / 参保地 ----
    if has_emp or (not is_rural_org and random.random() < 0.4):
        ins = INS_310
    else:
        ins = INS_390
    p['insutype'] = ins
    p['insutype_name'] = CV['insutype'].get(ins)
    p['insutype_src'] = 'cv_code:insutype'
    p['insuplc_admdvs'] = county6
    p['insuplc_admdvs_name'] = county_name
    p['insuplc_admdvs_src'] = 'area_code_2021'

    # ---- 医保人员编号: 参保者约 90% 有 ----
    if random.random() < 0.9:
        p['psn_no'] = "4209%014d" % gseq

    # ---- 就诊凭证类型 ----
    r = random.random()
    if r < 0.6:
        mct, mct_no = MCT_ID, idc
    elif r < 0.8:
        mct, mct_no = MCT_ELEC, "42%016d" % random.randint(0, 10**16 - 1)
    else:
        mct, mct_no = MCT_CARD, p.get('psn_no') or ("4209%014d" % gseq)
    p['mdtrt_cert_type'] = mct
    p['mdtrt_cert_type_name'] = CV['mdtrt_cert_type'].get(mct)
    p['mdtrt_cert_type_src'] = 'cv_code:mdtrt_cert_type'
    p['mdtrt_cert_no'] = mct_no

    # ---- 联系人: 未成年人=父母, 老年人=子女, 其余约 30% 配偶 ----
    rel = c_name = None
    if age < 18:
        rel = random.choice([c for c in [REL_FATHER, REL_MOTHER] if c]) if (REL_FATHER or REL_MOTHER) else None
        csur = name[0] if rel == REL_FATHER else random.choice(SURNAMES)
        c_name = csur + "".join(random.sample(MALE_CHARS if rel == REL_FATHER else FEMALE_CHARS, 2))
    elif age >= 70:
        rel = random.choice([c for c in [REL_SON, REL_DAU] if c]) if (REL_SON or REL_DAU) else None
        c_name = name[0] + "".join(random.sample(MALE_CHARS if rel == REL_SON else FEMALE_CHARS, 2))
    elif (MAR_MARRIED and p.get('marital_status') == MAR_MARRIED) and random.random() < 0.3:
        rel = REL_SPOUSE
        og = '2' if gender == '1' else '1'
        c_name = gen_name(og)
    if rel and c_name:
        c_birth = date(max(1935, birth.year - random.randint(-5, 30)), random.randint(1, 12), random.randint(1, 28))
        c_gender = '1' if rel in (REL_FATHER, REL_SON, REL_SPOUSE) and gender == '1' else ('2' if rel in (REL_MOTHER, REL_DAU) else random.choice(['1', '2']))
        if rel == REL_SPOUSE:
            c_gender = '2' if gender == '1' else '1'
        elif rel == REL_FATHER or rel == REL_SON:
            c_gender = '1'
        elif rel == REL_MOTHER or rel == REL_DAU:
            c_gender = '2'
        p['contact_relation'] = rel
        p['contact_relation_name'] = HB['relation'].get(rel)
        p['contact_relation_src'] = 'hbvalue:GB/T 4761-2008'
        p['contact_name'] = c_name
        p['contact_phone'] = phone()
        p['contact_id_card'] = make_id_card(county6, c_birth, seq + 50, c_gender)
        p['contact_addr'] = full_addr
        p['contact_prov'] = PROV_CODE; p['contact_prov_name'] = PROV_NAME
        p['contact_city'] = CITY_CODE; p['contact_city_name'] = CITY_NAME
        p['contact_county'] = county12; p['contact_county_name'] = county_name
        p['contact_town'] = str(town_code); p['contact_town_name'] = town_name
        p['contact_src'] = 'area_code_2021'

    p['memo'] = '批量模拟建档(%s)' % org_name
    return p


# ==================== 主流程 ====================
# 机构信息
fmt = ",".join(["%s"] * len(TARGET_ORGS))
cur.execute("SELECT id, org_name FROM sys_org WHERE tenant_id=%%s AND id IN (%s)" % fmt,
            tuple([TENANT_ID] + TARGET_ORGS))
ORG_MAP = {r['id']: r['org_name'] for r in cur.fetchall()}

# 已有姓名/身份证纳入去重
cur.execute("SELECT name, id_card FROM his_patient WHERE tenant_id=%s", (TENANT_ID,))
for r in cur.fetchall():
    if r['name']:
        used_names.add(r['name'])
    if r['id_card']:
        used_idcards.add(r['id_card'])

print("加载字典: gend=%d insutype=%d mdtrt_cert_type=%d psn_cert_type=%d naty=%d" %
      (len(CV['gend']), len(CV['insutype']), len(CV['mdtrt_cert_type']), len(CV['psn_cert_type']), len(CV['naty'])))
print("加载hbvalue: 国籍=%d 婚姻=%d 文化=%d 职业=%d 关系=%d" %
      (len(HB['nationality']), len(HB['marital']), len(HB['edu']), len(HB['occupation']), len(HB['relation'])))
print("本地: %s(%s) 乡镇%d个; 外地候选区县%d个: %s" %
      (HOME_COUNTY_NAME, HOME_COUNTY6, len(HOME_TOWNS), len(OTHER_COUNTIES),
       [c[1] for c in OTHER_COUNTIES]))
print("关键编码: 国籍中国=%s 居民身份证=%s 职工=%s 居民=%s 身份证凭证=%s 汉族=%s" %
      (CHINA_CODE, CERT_ID_CODE, INS_310, INS_390, MCT_ID, NATION_HAN))
print("目标机构:", ORG_MAP)

if DRY_RUN:
    sample = build_patient(8, ORG_MAP.get(8, '花园镇卫生院'), 4, 1, 1)
    print("\n==== DRY_RUN 样本(花园镇卫生院第1条) ====")
    for k in sorted(sample.keys()):
        print("  %-24s = %s" % (k, sample[k]))
    print("\nDRY_RUN=True, 未写库。确认无误后改为 False 重跑。")
    conn.close()
    sys.exit(0)

grand = 0
gseq = 0
for org_index, org_id in enumerate(TARGET_ORGS, start=1):
    org_name = ORG_MAP.get(org_id)
    if not org_name:
        print("跳过: org %d 不存在" % org_id)
        continue
    cur.execute("SELECT COUNT(*) c FROM his_patient WHERE tenant_id=%s AND org_id=%s AND create_by='system-seed'",
                (TENANT_ID, org_id))
    if cur.fetchone()['c'] > 0:
        print("[%s org=%d] 已存在模拟建档, 跳过。" % (org_name, org_id))
        continue
    n = 0
    for seq in range(1, PER_ORG + 1):
        gseq += 1
        rec = build_patient(org_id, org_name, org_index, seq, gseq)
        cols = list(rec.keys())
        sql = "INSERT INTO his_patient (%s) VALUES (%s)" % (
            ",".join(cols), ",".join("%%(%s)s" % c for c in cols))
        cur.execute(sql, rec)
        n += 1
    grand += n
    print("[%s org=%d] 建档 %d 条" % (org_name, org_id, n))

conn.commit()
print("==== 合计建档 %d 条 ====" % grand)
conn.close()
