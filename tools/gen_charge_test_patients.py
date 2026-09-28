# -*- coding: utf-8 -*-
"""
生成 5 个门诊"已接诊待收费"病人(孝昌县人民医院 org_id=1, tenant_id=1), 供门诊收费流程手工测试。

数据链条(每人一条): his_patient -> his_registration(status=1) -> his_visit(visit_status=3, charge_status=0)
  -> his_prescription(+item, dispense_status=0) 和/或 his_order(+item)

5 个场景覆盖收费台不同路径:
  1. 内科门诊·职工310·医保·西药2种            -> 医保收费(2206+2207)
  2. 外科门诊·居民390·医保·西药1种+检查1项    -> 医保·药品+检查混合明细
  3. 儿科门诊·居民390·医保·检查2项            -> 医保·纯检查(order 路径)
  4. 内科门诊·自费(无 mdtrt_id/psn_no)·西药1种 -> 自费收费(selfPayCharge)
  5. 急诊科·职工310·医保·西药2种+治疗1项       -> 急诊 med_type=14·大额混单

规范:
  - 新建 5 个测试患者, 姓名带"测试"前缀, create_by='test-seed-charge' 幂等标识(已存在则整体跳过);
  - 药品一律取目录内已对照医保编码(yb_drug_code)且门诊药房(org1 warehouse 库存 qty>0)有货的品种,
    价格取 retail_price, 保证收费后可继续走发药;
  - 检查/治疗项目取 his_charge_item 已对照(med_list_codg 非空)项目, 价格取 price;
  - reg_no/ipt_otp_no 沿用 R/OT+时间戳+序号 格式; 号源 left_num 不扣减(避免破坏号源守恒统计);
  - 挂号费: 医保单 pay_method='cash', 自费单 pay_method='free'(与存量口径一致)。
"""
import pymysql
import io
import sys
from datetime import datetime, date
from decimal import Decimal

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

TENANT_ID = 1
ORG_ID = 1
MARK = 'test-seed-charge'

conn = pymysql.connect(host='127.0.0.1', port=3306, user='root', password='bsoft',
                       database='yb_interface', charset='utf8mb4', autocommit=False)
cur = conn.cursor(pymysql.cursors.DictCursor)

# ---- 幂等: 已生成过则跳过 ----
cur.execute("SELECT COUNT(*) c FROM his_patient WHERE tenant_id=%s AND create_by=%s", (TENANT_ID, MARK))
if cur.fetchone()['c'] > 0:
    print("已存在 %s 生成的测试患者, 跳过(如需重生成请先删除其 patient/registration/visit/prescription/order 数据)" % MARK)
    conn.close()
    sys.exit(0)

NOW = datetime.now()
TODAY = NOW.date()
TS = NOW.strftime('%Y%m%d%H%M%S')

# ---- 基础数据定位 ----
cur.execute("SELECT id, dept_name, dept_code FROM his_dept WHERE tenant_id=%s AND id=%s AND deleted=0", (TENANT_ID, 1))
DEPT_NK = cur.fetchone()
cur.execute("SELECT id, dept_name, dept_code FROM his_dept WHERE tenant_id=%s AND id=%s AND deleted=0", (TENANT_ID, 2))
DEPT_WK = cur.fetchone()
cur.execute("SELECT id, dept_name, dept_code FROM his_dept WHERE tenant_id=%s AND id=%s AND deleted=0", (TENANT_ID, 3))
DEPT_EK = cur.fetchone()
cur.execute("SELECT id, dept_name, dept_code FROM his_dept WHERE tenant_id=%s AND id=%s AND deleted=0", (TENANT_ID, 5))
DEPT_JZ = cur.fetchone()
cur.execute("SELECT id, staff_name, atddr_no FROM his_staff WHERE tenant_id=%s AND id=%s AND deleted=0", (TENANT_ID, 1))
DR1 = cur.fetchone()
cur.execute("SELECT id, staff_name, atddr_no FROM his_staff WHERE tenant_id=%s AND id=%s AND deleted=0", (TENANT_ID, 2))
DR2 = cur.fetchone()
cur.execute("SELECT id, staff_name, atddr_no FROM his_staff WHERE tenant_id=%s AND id=%s AND deleted=0", (TENANT_ID, 3))
DR3 = cur.fetchone()
cur.execute("SELECT id, staff_name, atddr_no FROM his_staff WHERE tenant_id=%s AND id=%s AND deleted=0", (TENANT_ID, 5))
DR5 = cur.fetchone()

# 药品: 目录已对照医保编码 + org1 门诊药房有库存, 按库存批次的目录为准
cur.execute(
    "SELECT c.id, c.drug_code, c.generic_name, c.yb_drug_code, c.retail_price, c.spec, c.pack_unit "
    "FROM his_drug_catalog c JOIN his_drug_stock s ON s.drug_catalog_id = c.id AND s.deleted = 0 AND s.qty > 0"
    " WHERE c.tenant_id=%s AND c.deleted=0 AND c.status=1 AND s.org_id=%s"
    " AND c.yb_drug_code IS NOT NULL AND c.yb_drug_code <> '' "
    "GROUP BY c.id ORDER BY c.id LIMIT 6", (TENANT_ID, ORG_ID))
DRUGS = cur.fetchall()
assert len(DRUGS) >= 3, "库存+医保对照的药品不足, 无法生成"

# 检查/治疗项目: 已对照医保编码
cur.execute(
    "SELECT id, item_code, item_name, med_list_codg, price, unit, spec FROM his_charge_item "
    "WHERE tenant_id=%s AND deleted=0 AND status=1 AND med_list_codg IS NOT NULL AND med_list_codg <> '' "
    "ORDER BY id LIMIT 20", (TENANT_ID,))
ITEMS = cur.fetchall()
assert len(ITEMS) >= 2, "已对照收费项目不足"
# 挑两个价格适中的项目(避免异常高价)
ITEMS.sort(key=lambda r: r['price'])
EXAM1 = ITEMS[len(ITEMS) // 3]        # 检查项目
TREAT1 = ITEMS[len(ITEMS) * 2 // 3]   # 治疗项目


def drug_amount(drug, qty):
    return (drug['retail_price'] * qty).quantize(Decimal('0.01'))


# ---- 5 个测试场景 ----
# patients: (姓名, 性别, 年龄, 险种, 是否医保, 科室, 医生, med_type, 药品明细[(索引,数量)], 项目明细[(dict,数量,order_type)])
SCENES = [
    ('测试-职工门诊', '1', 45, '310', True,  DEPT_NK, DR1, '11', [(0, 1), (1, 2)], []),
    ('测试-居民混合', '2', 32, '390', True,  DEPT_WK, DR2, '11', [(2, 1)], [(EXAM1, 1, '检查')]),
    ('测试-儿科检查', '1', 7,  '390', True,  DEPT_EK, DR3, '11', [], [(EXAM1, 1, '检查'), (TREAT1, 1, '检查')]),
    ('测试-自费购药', '2', 55, '510', False, DEPT_NK, DR1, '11', [(1, 1)], []),
    ('测试-急诊大额', '1', 68, '310', True,  DEPT_JZ, DR5, '14', [(0, 2), (2, 3)], [(TREAT1, 2, '治疗')]),
]

PSN_POOL = ['420900000000000701', '420900000000000702', '420900000000000703',
            '420900000000000704', '420900000000000705']
MDTRT_POOL = ['M201', 'M202', 'M203', 'M204', 'M205']
IDCARD_POOL = ['420921198001%02d1234' % (i + 10) for i in range(5)]

print("药品池: %s" % [(d['generic_name'], str(d['retail_price'])) for d in DRUGS[:4]])
print("项目池: 检查=%s(%s) 治疗=%s(%s)" % (EXAM1['item_name'], EXAM1['price'], TREAT1['item_name'], TREAT1['price']))

summary = []
for i, (name, gender, age, insutype, is_yb, dept, doctor, med_type, drug_idx, item_rows) in enumerate(SCENES):
    seq = i + 1
    psn_no = PSN_POOL[i] if is_yb else None
    mdtrt_id = MDTRT_POOL[i] if is_yb else None
    reg_no = 'R%s%03d' % (TS, 600 + seq)
    ipt_otp_no = 'OT%s%03d' % (TS, 600 + seq)
    reg_time = datetime.now()
    visit_time = datetime.now()
    finish_time = datetime.now()
    pay_method = 'cash' if is_yb else 'free'

    # 1) 患者档案
    patient = {
        'tenant_id': TENANT_ID, 'org_id': ORG_ID,
        'patient_no': 'P%sT%02d' % (TS, seq),
        'name': name, 'gender': gender, 'gender_src': 'cv_code:gend',
        'age': age, 'id_card': IDCARD_POOL[i], 'phone': '138%08d' % (10000000 + seq),
        'insutype': insutype,
        'psn_no': psn_no,
        'mdtrt_cert_type': '02' if is_yb else None,
        'mdtrt_cert_no': IDCARD_POOL[i] if is_yb else None,
        'status': 1, 'create_by': MARK, 'create_time': reg_time,
    }
    cols = list(patient.keys())
    cur.execute("INSERT INTO his_patient (%s) VALUES (%s)" % (
        ",".join(cols), ",".join("%(" + c + ")s" for c in cols)), patient)
    patient_id = cur.lastrowid

    # 2) 挂号(已就诊 status=1)
    reg_fee = {'01': Decimal('10.00')}.get('01')
    reg = {
        'tenant_id': TENANT_ID, 'reg_no': reg_no,
        'patient_id': patient_id, 'patient_no': patient['patient_no'], 'patient_name': name,
        'psn_no': psn_no, 'insutype': insutype,
        'mdtrt_cert_type': '02' if is_yb else None, 'mdtrt_cert_no': IDCARD_POOL[i] if is_yb else None,
        'mdtrt_id': mdtrt_id,
        'dept_id': dept['id'], 'dept_code': dept['dept_code'], 'dept_name': dept['dept_name'],
        'caty': '内科' if dept is DEPT_NK else None,
        'staff_id': doctor['id'], 'atddr_no': doctor['atddr_no'], 'dr_name': doctor['staff_name'],
        'schedule_id': None, 'work_date': TODAY, 'time_type': 'am',
        'reg_level_code': '01', 'reg_level_name': '普通号', 'reg_fee': reg_fee,
        'med_type': med_type, 'ipt_otp_no': ipt_otp_no,
        'reg_time': reg_time, 'status': 1, 'operator': MARK,
        'fee_type': 'normal', 'discount_type': 'none', 'discount_amount': Decimal('0.00'),
        'actual_fee': reg_fee, 'pay_method': pay_method, 'queue_no': 'TS-%04d' % seq,
        'create_by': MARK, 'create_time': reg_time,
    }
    cols = list(reg.keys())
    cur.execute("INSERT INTO his_registration (%s) VALUES (%s)" % (
        ",".join(cols), ",".join("%(" + c + ")s" for c in cols)), reg)
    reg_id = cur.lastrowid

    # 3) 就诊(已接诊未完成收费: visit_status=3, charge_status=0)
    total = Decimal('0.00')
    visit = {
        'tenant_id': TENANT_ID, 'registration_id': reg_id, 'reg_no': reg_no,
        'mdtrt_id': mdtrt_id, 'ipt_otp_no': ipt_otp_no,
        'patient_id': patient_id, 'patient_no': patient['patient_no'], 'patient_name': name,
        'gender': gender, 'age': age, 'psn_no': psn_no, 'insutype': insutype,
        'dept_id': dept['id'], 'dept_code': dept['dept_code'], 'dept_name': dept['dept_name'],
        'staff_id': doctor['id'], 'atddr_no': doctor['atddr_no'], 'dr_name': doctor['staff_name'],
        'work_date': TODAY, 'visit_status': 3, 'charge_status': 0,
        'chief_complaint': '门诊收费流程测试就诊', 'med_type': med_type,
        'visit_time': visit_time, 'finish_time': finish_time, 'queue_no': 'TS-%04d' % seq,
        'create_by': MARK, 'create_time': visit_time,
    }
    cols = list(visit.keys())
    cur.execute("INSERT INTO his_visit (%s) VALUES (%s)" % (
        ",".join(cols), ",".join("%(" + c + ")s" for c in cols)), visit)
    visit_id = cur.lastrowid

    # 4) 处方 + 明细(药品)
    rx_total = Decimal('0.00')
    if drug_idx:
        rx_no = 'RX%s%03d' % (TS, 600 + seq)
        rx = {
            'tenant_id': TENANT_ID, 'rx_no': rx_no, 'visit_id': visit_id,
            'patient_id': patient_id, 'patient_name': name,
            'dept_id': dept['id'], 'dept_name': dept['dept_name'],
            'dr_id': doctor['id'], 'dr_name': doctor['staff_name'],
            'rx_type': '西药', 'diag_name': '测试用诊断(急性上呼吸道感染)',
            'status': 1, 'dispense_status': 0, 'create_by': MARK, 'create_time': visit_time,
        }
        cols = list(rx.keys())
        cur.execute("INSERT INTO his_prescription (%s) VALUES (%s)" % (
            ",".join(cols), ",".join("%(" + c + ")s" for c in cols)), rx)
        rx_id = cur.lastrowid
        for gidx, qty in drug_idx:
            d = DRUGS[gidx]
            amt = drug_amount(d, qty)
            rx_total += amt
            cur.execute(
                "INSERT INTO his_prescription_item (tenant_id, prescription_id, drug_id, item_code, item_name,"
                " spec, unit, price, quantity, amount, usage_method, frequency, administration, days,"
                " med_list_codg, create_by, create_time) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                (TENANT_ID, rx_id, d['id'], d['drug_code'], d['generic_name'], d['spec'],
                 d['pack_unit'] or '盒', d['retail_price'], qty, amt,
                 '口服', 'tid', 'po', 3, d['yb_drug_code'], MARK, visit_time))
        cur.execute("UPDATE his_prescription SET total_amount=%s WHERE id=%s", (rx_total, rx_id))

    # 5) 医嘱单 + 明细(检查/治疗)
    od_total = Decimal('0.00')
    for it, qty, otype in item_rows:
        od_no = 'OD%s%03d%s' % (TS, 600 + seq, otype[0])
        od = {
            'tenant_id': TENANT_ID, 'order_no': od_no, 'visit_id': visit_id,
            'patient_id': patient_id, 'patient_name': name,
            'dept_id': dept['id'], 'dept_name': dept['dept_name'],
            'dr_id': doctor['id'], 'dr_name': doctor['staff_name'],
            'order_type': otype, 'diag_name': '测试用诊断',
            'status': 1, 'create_by': MARK, 'create_time': visit_time,
        }
        cols = list(od.keys())
        cur.execute("INSERT INTO his_order (%s) VALUES (%s)" % (
            ",".join(cols), ",".join("%(" + c + ")s" for c in cols)), od)
        od_id = cur.lastrowid
        amt = (it['price'] * qty).quantize(Decimal('0.01'))
        od_total += amt
        cur.execute(
            "INSERT INTO his_order_item (tenant_id, order_id, item_id, item_code, item_name, spec, unit,"
            " price, quantity, amount, med_list_codg, exec_dept, create_by, create_time)"
            " VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
            (TENANT_ID, od_id, it['id'], it['item_code'], it['item_name'], it['spec'], it['unit'] or '次',
             it['price'], qty, amt, it['med_list_codg'], dept['dept_name'], MARK, visit_time))
        cur.execute("UPDATE his_order SET total_amount=%s WHERE id=%s", (amt, od_id))

    total = rx_total + od_total
    summary.append((seq, name, dept['dept_name'], doctor['staff_name'], reg_no,
                    '医保' if is_yb else '自费', insutype, str(rx_total), str(od_total), str(total)))

conn.commit()

print("\n==== 已生成 5 个已接诊待收费病人(org=孝昌县人民医院) ====")
print("%-3s %-14s %-8s %-6s %-22s %-4s %-4s %8s %8s %9s" %
      ('#', '姓名', '科室', '医生', '挂号单号', '支付', '险种', '药品额', '项目额', '合计'))
for s in summary:
    print("%-3d %-14s %-8s %-6s %-22s %-4s %-4s %8s %8s %9s" % s)

print("\n验证待收费列表(SQL 与收费台 todoPage 同口径):")
cur.execute(
    "SELECT v.id visit_id, v.patient_name, v.dept_name, DATE_FORMAT(v.finish_time,'%%H:%%i') t,"
    " IFNULL((SELECT SUM(pi.amount) FROM his_prescription_item pi"
    "   JOIN his_prescription pr ON pr.id=pi.prescription_id AND pr.deleted=0 AND pr.status>0"
    "   WHERE pr.visit_id=v.id AND pi.deleted=0),0)"
    " + IFNULL((SELECT SUM(oi.amount) FROM his_order_item oi"
    "   JOIN his_order o ON o.id=oi.order_id AND o.deleted=0 AND o.status>0"
    "   WHERE o.visit_id=v.id AND oi.deleted=0),0) total"
    " FROM his_visit v JOIN his_dept d ON d.id=v.dept_id AND d.deleted=0"
    " WHERE v.visit_status=3 AND v.charge_status=0 AND v.deleted=0 AND v.tenant_id=%s AND d.org_id=%s"
    " ORDER BY v.id", (TENANT_ID, ORG_ID))
for r in cur.fetchall():
    print("  visit_id=%s %s %s 合计=%s" % (r['visit_id'], r['patient_name'], r['dept_name'], r['total']))
conn.close()
