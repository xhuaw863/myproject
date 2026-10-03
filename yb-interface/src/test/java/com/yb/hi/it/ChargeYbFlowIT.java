package com.yb.hi.it;

import com.yb.hi.YbInterfaceApplication;
import com.yb.hi.dto.cashier.ChargeReq;
import com.yb.hi.dto.cashier.PartialRefundReq;
import com.yb.hi.dto.cashier.RefundReq;
import com.yb.hi.entity.cashier.HisChargeBill;
import com.yb.hi.entity.yb.HisYbTxnLog;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.CompTaskSweeper;
import com.yb.hi.service.cashier.CashierService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 门诊收费医保闭环端到端集成测试(批次4 M1/M2/M5, 隔离库 ybtest_it)。
 * 前置: ybtest_it 须先有全量基础表结构(DictSchemaMigration 启动只补列不建表, 故先克隆 dev 库 schema)。
 * 全链路走本地模拟医保平台(mock-enabled)。
 * 覆盖门诊两阶段化: T1 建单中间态(status=0/yb_status=1) → T2 医保链 2204→2206→2207 → T3 终态落账;
 * 用例A 结算成功(yb_status=2/setl_id 回写/金额守恒 total=fund+acct+self/就诊 charge_status=1);
 * 用例B 2207 UNKNOWN 注入(-Dyb.mock.unknown.2207)→补偿任务 BIZ_CHARGE 挂起→CompTaskSweeper 核对平台未受理复位
 *       (yb_status 1->0)→重新收费收敛终态(幽灵结算防护: 本地绝不置已收费)。
 * 资金写只打隔离库, 验后由人工 DROP DATABASE 无痕。
 */
@SpringBootTest(classes = YbInterfaceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("it")
@DisplayName("门诊收费医保闭环端到端(隔离库)")
class ChargeYbFlowIT {

    private static final long IT_TENANT = 880002L;
    private static final long IT_ORG = 1L;

    @Autowired
    private CashierService cashierService;
    @Autowired
    private CompTaskSweeper compTaskSweeper;
    @Autowired
    private JdbcTemplate jdbc;

    /** 主键区按时间戳派生(static, 跨用例递增): 保证隔离库残留数据不会与本次运行撞主键, IT 可重复执行 */
    private static final AtomicLong seq = new AtomicLong(System.currentTimeMillis());

    @BeforeEach
    void setUpCtx() {
        // 清理本 IT 租户的补偿任务与医保交易日志: 防止上次运行残留的 PENDING 任务被本次 sweep 扫到而串扰
        jdbc.update("DELETE FROM his_comp_task WHERE tenant_id = ?", IT_TENANT);
        jdbc.update("DELETE FROM his_yb_txn_log WHERE tenant_id = ?", IT_TENANT);
        TenantContext.set(IT_TENANT);
        UserContext.set(loginUser());
    }

    @AfterEach
    void clearCtx() {
        System.clearProperty("yb.mock.unknown.2207");
        System.clearProperty("yb.mock.unknown.2208");
        System.clearProperty("yb.mock.unknown.2204");
        System.clearProperty("yb.mock.unknown.2206");
        UserContext.clear();
        TenantContext.clear();
    }

    /* ==================== 用例A: 2204→2206→2207 医保结算成功闭环 ==================== */

    @Test
    @DisplayName("A: 接诊未收费 → 2204费用明细/2206预结算/2207结算成功 → 终态落账(金额守恒+setl_id)")
    void chargeSettleSuccess() {
        long visitId = seedChargeableVisit(new BigDecimal("100.00"), BigDecimal.ONE);

        ChargeReq req = new ChargeReq();
        req.setVisitId(visitId);
        req.setOrgId(IT_ORG);
        req.setPayType("yb");
        Map<String, Object> res = cashierService.charge(req);
        HisChargeBill bill = (HisChargeBill) res.get("bill");
        assertNotNull(bill, "结算成功应返回收费单");
        Long billId = bill.getId();

        Map<String, Object> b = jdbc.queryForMap(
                "SELECT status, yb_status, setl_id, total_amount, fund_pay, acct_pay, self_pay"
                        + " FROM his_charge_bill WHERE id=? AND tenant_id=? AND deleted=0", billId, IT_TENANT);
        assertEquals(1, ((Number) b.get("status")).intValue(), "结算成功收费单应置已收费 status=1");
        assertEquals(2, ((Number) b.get("yb_status")).intValue(), "结算成功应置 yb_status=2");
        assertNotNull(b.get("setl_id"), "2207 结算ID应回写至收费单");

        BigDecimal total = bd(b.get("total_amount"));
        BigDecimal fund = bd(b.get("fund_pay"));
        BigDecimal acct = bd(b.get("acct_pay"));
        BigDecimal self = bd(b.get("self_pay"));
        assertEquals(0, new BigDecimal("100.00").compareTo(total), "总金额应等于费用明细汇总");
        assertEquals(0, total.compareTo(fund.add(acct).add(self)), "金额守恒: total=fund+acct+self");

        Integer chargeStatus = jdbc.queryForObject(
                "SELECT charge_status FROM his_visit WHERE id=? AND tenant_id=?", Integer.class, visitId, IT_TENANT);
        assertEquals(1, chargeStatus.intValue(), "结算成功就诊应置已收费 charge_status=1");

        // 2207 成功交易应落 his_yb_txn_log SUCCESS(setl_id 补偿定位事实来源)
        Integer ok = jdbc.queryForObject(
                "SELECT COUNT(*) FROM his_yb_txn_log WHERE tenant_id=? AND infno='2207' AND status=? AND deleted=0",
                Integer.class, IT_TENANT, HisYbTxnLog.ST_SUCCESS);
        assertTrue(ok != null && ok >= 1, "2207 成功应落医保交易日志");
    }

    /* ==================== 用例B: 2207 UNKNOWN → 补偿复位 → 重结收敛(幽灵结算防护) ==================== */

    @Test
    @DisplayName("B: 2207 UNKNOWN 留中间态→BIZ_CHARGE 补偿→CompTaskSweeper 平台未受理复位→重收收敛终态")
    void unknownSettleCompensate() {
        long visitId = seedChargeableVisit(new BigDecimal("200.00"), BigDecimal.ONE);

        // 注入 2207 UNKNOWN(模拟网络超时): 结算应以补偿提示抛出, 单据留中间态挂起, 本地绝不置已收费
        System.setProperty("yb.mock.unknown.2207", "true");
        ChargeReq req = new ChargeReq();
        req.setVisitId(visitId);
        req.setOrgId(IT_ORG);
        req.setPayType("yb");
        assertThrows(RuntimeException.class, () -> cashierService.charge(req),
                "2207 UNKNOWN 应以补偿提示抛出");
        System.clearProperty("yb.mock.unknown.2207");

        Long billId = jdbc.queryForObject(
                "SELECT id FROM his_charge_bill WHERE visit_id=? AND tenant_id=? AND bill_type=1 AND deleted=0"
                        + " ORDER BY id DESC LIMIT 1", Long.class, visitId, IT_TENANT);
        Map<String, Object> mid = jdbc.queryForMap(
                "SELECT status, yb_status FROM his_charge_bill WHERE id=? AND tenant_id=?", billId, IT_TENANT);
        assertEquals(0, ((Number) mid.get("status")).intValue(), "UNKNOWN 后收费单应保持未收费中间态 status=0");
        assertEquals(1, ((Number) mid.get("yb_status")).intValue(), "UNKNOWN 后收费单应停在 yb_status=1结算中");
        assertEquals(0, jdbc.queryForObject(
                "SELECT charge_status FROM his_visit WHERE id=? AND tenant_id=?", Integer.class, visitId, IT_TENANT).intValue(),
                "UNKNOWN 后就诊应保持未收费(幽灵结算防护: 平台侧结果未知不得置已收费)");
        assertTrue(jdbc.queryForObject(
                "SELECT COUNT(*) FROM his_comp_task WHERE tenant_id=? AND biz_type='CHARGE' AND ref_id=? AND deleted=0",
                Integer.class, IT_TENANT, billId) >= 1, "UNKNOWN 应生成门诊结算补偿任务 BIZ_CHARGE");
        assertTrue(jdbc.queryForObject(
                "SELECT COUNT(*) FROM his_yb_txn_log WHERE tenant_id=? AND infno='2207' AND status=? AND deleted=0",
                Integer.class, IT_TENANT, HisYbTxnLog.ST_UNKNOWN) >= 1, "2207 UNKNOWN 应落医保交易日志");

        // 驱动补偿扫描: mock 本地核对(UNKNOWN 未落结算流水=平台未受理)→复位 yb_status 1->0 可重收。
        // 强制 next_run 落过去: 建任务时 next_run=now(截断到秒) 与手动 sweep 存在时钟秒边界竞态, 消除之
        jdbc.update("UPDATE his_comp_task SET next_run = DATE_SUB(NOW(), INTERVAL 1 MINUTE)"
                + " WHERE tenant_id=? AND biz_type='CHARGE' AND ref_id=? AND deleted=0", IT_TENANT, billId);
        compTaskSweeper.sweep();
        // sweep 同步跑在主线程, 收敛链路可能 TenantContext.clear(), 此处恢复测试上下文供重收使用
        TenantContext.set(IT_TENANT);
        UserContext.set(loginUser());

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM his_comp_task WHERE tenant_id=? AND biz_type='CHARGE' AND ref_id=? AND status='DONE' AND deleted=0",
                Integer.class, IT_TENANT, billId).intValue(), "补偿任务应收敛为 DONE");
        assertEquals(0, jdbc.queryForObject(
                "SELECT yb_status FROM his_charge_bill WHERE id=? AND tenant_id=?", Integer.class, billId, IT_TENANT).intValue(),
                "平台未受理: 结算中间态应复位 yb_status=0(可重收)");

        // 网络恢复后重新收费 → 新单落终态 yb_status=2
        ChargeReq req2 = new ChargeReq();
        req2.setVisitId(visitId);
        req2.setOrgId(IT_ORG);
        req2.setPayType("yb");
        Map<String, Object> res2 = cashierService.charge(req2);
        HisChargeBill bill2 = (HisChargeBill) res2.get("bill");
        assertEquals(2, jdbc.queryForObject(
                "SELECT yb_status FROM his_charge_bill WHERE id=? AND tenant_id=?", Integer.class, bill2.getId(), IT_TENANT).intValue(),
                "重收应落终态 yb_status=2");
        assertEquals(1, jdbc.queryForObject(
                "SELECT charge_status FROM his_visit WHERE id=? AND tenant_id=?", Integer.class, visitId, IT_TENANT).intValue(),
                "重收成功后就诊应置已收费 charge_status=1");
    }

    /* ==================== 夹具辅助(隔离租户显式 tenant_id AND deleted=0) ==================== */

    private HisChargeBill chargeOk(long visitId) {
        ChargeReq r = new ChargeReq();
        r.setVisitId(visitId);
        r.setOrgId(IT_ORG);
        r.setPayType("yb");
        return (HisChargeBill) cashierService.charge(r).get("bill");
    }

    /* ==================== 用例C: 2208 全额退费成功闭环 ==================== */

    @Test
    @DisplayName("C: 结算成功 → 2208 全额退费 → 原单已退费/yb撤销、退费单已收费、就诊转已退费")
    void refundCancelSettleSuccess() {
        long visitId = seedChargeableVisit(new BigDecimal("100.00"), BigDecimal.ONE);
        HisChargeBill origin = chargeOk(visitId);
        Long originId = origin.getId();

        RefundReq rr = new RefundReq();
        rr.setBillId(originId);
        rr.setReason("IT全额退费");
        HisChargeBill refundBill = cashierService.refund(rr);
        assertNotNull(refundBill, "退费应返回退费单");

        Map<String, Object> ob = jdbc.queryForMap(
                "SELECT status, yb_status FROM his_charge_bill WHERE id=? AND tenant_id=? AND deleted=0", originId, IT_TENANT);
        assertEquals(2, ((Number) ob.get("status")).intValue(), "原单退费成功应置已退费 status=2");
        assertEquals(4, ((Number) ob.get("yb_status")).intValue(), "原单 2208 撤销成功应置 yb_status=4已撤销");

        Map<String, Object> rb = jdbc.queryForMap(
                "SELECT bill_type, status, origin_bill_id FROM his_charge_bill WHERE id=? AND tenant_id=? AND deleted=0",
                refundBill.getId(), IT_TENANT);
        assertEquals(2, ((Number) rb.get("bill_type")).intValue(), "退费单 bill_type=2");
        assertEquals(1, ((Number) rb.get("status")).intValue(), "退费单 2208 成功应置已收费 status=1");
        assertEquals(originId, rb.get("origin_bill_id"), "退费单应回指原单");

        assertEquals(2, jdbc.queryForObject(
                "SELECT charge_status FROM his_visit WHERE id=? AND tenant_id=?", Integer.class, visitId, IT_TENANT).intValue(),
                "全额退费后就诊应置已退费 charge_status=2");
    }

    /* ==================== 用例D: 部分退费 A8 全撤重结 金额守恒 ==================== */

    @Test
    @DisplayName("D: 结算成功(qty=2) → 部分退 1 → 全撤重结 → 原单实收 = 部分退费 + 重结收费(金额守恒)")
    void partialRefundRebuildAmountConservation() {
        long visitId = seedChargeableVisit(new BigDecimal("100.00"), new BigDecimal("2"));
        HisChargeBill origin = chargeOk(visitId);
        Long originId = origin.getId();
        Long billItemId = jdbc.queryForObject(
                "SELECT id FROM his_charge_bill_item WHERE bill_id=? AND tenant_id=? AND deleted=0 ORDER BY id LIMIT 1",
                Long.class, originId, IT_TENANT);

        PartialRefundReq pr = new PartialRefundReq();
        pr.setBillId(originId);
        pr.setReason("IT部分退费");
        PartialRefundReq.RefundItem it = new PartialRefundReq.RefundItem();
        it.setBillItemId(billItemId);
        it.setRefundQty(BigDecimal.ONE);
        pr.setItems(new ArrayList<>(Collections.singletonList(it)));
        HisChargeBill refundBill = cashierService.partialRefund(pr);
        assertNotNull(refundBill, "部分退费应返回部分退费单");

        Map<String, Object> ob = jdbc.queryForMap(
                "SELECT status, total_amount FROM his_charge_bill WHERE id=? AND tenant_id=? AND deleted=0", originId, IT_TENANT);
        assertEquals(2, ((Number) ob.get("status")).intValue(), "全撤重结后原单应置已退费 status=2");

        Map<String, Object> rb = jdbc.queryForMap(
                "SELECT bill_type, status, total_amount FROM his_charge_bill WHERE id=? AND tenant_id=? AND deleted=0",
                refundBill.getId(), IT_TENANT);
        assertEquals(2, ((Number) rb.get("bill_type")).intValue(), "部分退费单 bill_type=2");
        assertEquals(1, ((Number) rb.get("status")).intValue(), "部分退费单应置已收费 status=1");
        BigDecimal refundAmt = bd(rb.get("total_amount"));
        assertEquals(0, new BigDecimal("100.00").compareTo(refundAmt), "退 1/2 数量应退费一半=100");

        // 剩余 1 单位应生成重结收费单(bill_type=1, 新 setl_id)
        Map<String, Object> re = jdbc.queryForMap(
                "SELECT id, yb_status, setl_id, total_amount FROM his_charge_bill"
                        + " WHERE visit_id=? AND tenant_id=? AND bill_type=1 AND status=1 AND id<>? AND deleted=0 ORDER BY id DESC LIMIT 1",
                visitId, IT_TENANT, originId);
        assertEquals(2, ((Number) re.get("yb_status")).intValue(), "重结收费单应置 yb_status=2");
        assertNotNull(re.get("setl_id"), "重结收费单应带新结算ID");
        BigDecimal reAmt = bd(re.get("total_amount"));
        assertEquals(0, new BigDecimal("100.00").compareTo(reAmt), "剩余 1 单位重结金额=100");

        // 金额守恒: 原单实收 = 部分退费 + 重结收费
        BigDecimal originTotal = bd(ob.get("total_amount"));
        assertEquals(0, originTotal.compareTo(refundAmt.add(reAmt)), "金额守恒: 原单=部分退费+重结");
    }

    private LoginUser loginUser() {
        LoginUser u = new LoginUser();
        u.setUserId(990_001L);
        u.setTenantId(IT_TENANT);
        u.setUsername("it_cashier");
        u.setRealName("IT收费员");
        u.setOrgId(IT_ORG);
        u.setRole("ADMIN");
        u.setRoles(new ArrayList<>(Collections.singletonList("ADMIN")));
        u.setLeadOrg(false);
        return u;
    }

    /* ==================== 用例E: 医保原生精度(单价16,6/数量16,4/金额16,2 全链路不截断) ==================== */

    @Test
    @DisplayName("E: 精度规范-单价6位/数量4位/金额2位 从开单到2204上报全链路不截断")
    void feePrecisionSixFourTwoEndToEnd() {
        // 0.123456 × 1.2345 = 0.152406... → 金额 HALF_UP 2位 = 0.15
        long visitId = seedChargeableVisit(new BigDecimal("0.123456"), new BigDecimal("1.2345"));

        // 1) 处方明细落库: 6/4/2 原精度保留(列宽医保规范后不得被截断)
        Map<String, Object> pi = jdbc.queryForMap(
                "SELECT price, quantity, amount FROM his_prescription_item WHERE tenant_id=? AND deleted=0 ORDER BY id DESC LIMIT 1",
                IT_TENANT);
        assertEquals(0, new BigDecimal("0.123456").compareTo(bd(pi.get("price"))), "处方单价6位不得截断");
        assertEquals(0, new BigDecimal("1.2345").compareTo(bd(pi.get("quantity"))), "处方数量4位不得截断");
        assertEquals(0, new BigDecimal("0.15").compareTo(bd(pi.get("amount"))), "金额 HALF_UP 2位=0.15");

        // 2) 医保结算成功: 收费单总额=0.15, 明细快照保留原精度, 金额守恒
        HisChargeBill bill = chargeOk(visitId);
        assertEquals(0, new BigDecimal("0.15").compareTo(bd(bill.getTotalAmount())), "结算总额=0.15");
        Map<String, Object> bi = jdbc.queryForMap(
                "SELECT price, qty, amount FROM his_charge_bill_item WHERE bill_id=? AND tenant_id=? AND deleted=0 LIMIT 1",
                bill.getId(), IT_TENANT);
        assertEquals(0, new BigDecimal("0.123456").compareTo(bd(bi.get("price"))), "收费明细单价6位不得截断");
        assertEquals(0, new BigDecimal("1.2345").compareTo(bd(bi.get("qty"))), "收费明细数量4位不得截断");
        Map<String, Object> fb = jdbc.queryForMap(
                "SELECT fund_pay, acct_pay, self_pay FROM his_charge_bill WHERE id=? AND tenant_id=?", bill.getId(), IT_TENANT);
        assertEquals(0, bill.getTotalAmount().compareTo(bd(fb.get("fund_pay")).add(bd(fb.get("acct_pay"))).add(bd(fb.get("self_pay")))),
                "金额守恒: total=fund+acct+self");

        // 3) 2204 上报报文钉死: txn_log.input_json 按原精度携带 pric/cnt, 金额 2 位
        String inputJson = jdbc.queryForObject(
                "SELECT input_json FROM his_yb_txn_log WHERE tenant_id=? AND infno='2204' AND deleted=0 ORDER BY id DESC LIMIT 1",
                String.class, IT_TENANT);
        assertNotNull(inputJson, "2204 交易应落医保交易日志");
        assertTrue(inputJson.contains("0.123456"), "2204 上报 pric 保留 6 位单价: " + inputJson);
        assertTrue(inputJson.contains("1.2345"), "2204 上报 cnt 保留 4 位数量");
        assertTrue(inputJson.contains("0.15"), "2204 上报金额 2 位");
    }

    private long nid() {
        return seq.incrementAndGet();
    }

    /**
     * 造一个"已接诊未收费、可医保结算"的门诊就诊:
     * 患者+参保+科室+医生+挂号凭证+就诊(visit_status=3, charge_status=0, mdtrt_id/psn_no)+处方+一条医保药品明细。
     */
    private long seedChargeableVisit(BigDecimal unitPrice, BigDecimal qty) {
        // 金额口径与生产划价一致: 单价×数量 HALF_UP 2位(医保规范 det_item_fee_sumamt 16,2)
        BigDecimal amount = unitPrice.multiply(qty).setScale(2, java.math.RoundingMode.HALF_UP);
        long patientId = nid();
        jdbc.update("INSERT INTO his_patient (id, tenant_id, patient_no, name, gender, age, id_card, deleted)"
                        + " VALUES (?,?,?,?,?,?,?,0)",
                patientId, IT_TENANT, "P" + patientId, "门诊测试患者", "男", 45, "IDC" + patientId);
        jdbc.update("INSERT INTO his_patient_insu (id, tenant_id, patient_id, psn_no, insuplc_admdvs, deleted)"
                        + " VALUES (?,?,?,?,?,0)", nid(), IT_TENANT, patientId, "PSN" + patientId, "420100");

        long deptId = nid();
        jdbc.update("INSERT INTO his_dept (id, tenant_id, dept_code, dept_name, yb_dept_code, deleted)"
                        + " VALUES (?,?,?,?,?,0)", deptId, IT_TENANT, "D" + deptId, "IT门诊科", "D0001");
        long staffId = nid();
        jdbc.update("INSERT INTO his_staff (id, tenant_id, staff_no, staff_name, atddr_no, deleted)"
                        + " VALUES (?,?,?,?,?,0)", staffId, IT_TENANT, "S" + staffId, "IT医生", "DOC" + staffId);

        long regId = nid();
        jdbc.update("INSERT INTO his_registration (id, tenant_id, reg_no, patient_id, mdtrt_cert_type, mdtrt_cert_no, deleted)"
                        + " VALUES (?,?,?,?,?,?,0)", regId, IT_TENANT, "R" + regId, patientId, "02", "MCN" + regId);

        long visitId = nid();
        jdbc.update("INSERT INTO his_visit (id, tenant_id, registration_id, patient_id, patient_name, mdtrt_id, psn_no,"
                        + " insutype, med_type, dept_code, dept_name, atddr_no, dr_name, visit_status, charge_status, deleted)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,3,0,0)",
                visitId, IT_TENANT, regId, patientId, "门诊测试患者", "OIT" + visitId, "PSN" + patientId,
                "310", "11", "D" + deptId, "IT门诊科", "DOC" + staffId, "IT医生");

        long rxId = nid();
        jdbc.update("INSERT INTO his_prescription (id, tenant_id, visit_id, rx_no, status, deleted)"
                        + " VALUES (?,?,?,?,1,0)", rxId, IT_TENANT, visitId, "RX" + rxId);
        jdbc.update("INSERT INTO his_prescription_item (id, tenant_id, prescription_id, item_code, item_name,"
                        + " unit, quantity, price, amount, med_list_codg, deleted)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,0)",
                nid(), IT_TENANT, rxId, "DC" + rxId, "IT医保药品", "盒", qty, unitPrice, amount, "MLC0001");
        return visitId;
    }

    private static BigDecimal bd(Object v) {
        return v == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(v));
    }
}
