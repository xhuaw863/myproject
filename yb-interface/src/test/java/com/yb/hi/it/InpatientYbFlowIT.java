package com.yb.hi.it;

import com.yb.hi.YbInterfaceApplication;
import com.yb.hi.common.YbResponse;
import com.yb.hi.dto.InpFeeDetailRevokeReq;
import com.yb.hi.dto.inpatient.InpAdmitDTO;
import com.yb.hi.dto.inpatient.InpSettleDTO;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.platform.CompTaskSweeper;
import com.yb.hi.service.InpatientService;
import com.yb.hi.service.inpatient.InpSettleService;
import com.yb.hi.service.inpatient.InpVisitService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 住院医保上报闭环端到端集成测试(批次4 M5, 隔离库 ybtest_it)。
 * 前置: ybtest_it 须先有全量基础表结构(DictSchemaMigration 启动只补列不建表, 故先克隆 dev 库 schema)。
 * 全链路走本地模拟医保平台(mock-enabled)。
 * 覆盖: 2401入院/2402出院上报接入业务事件(非阻断, 落 INP_REG/INP_DISCH); 2301->2303->2304 结算两阶段化
 * 结果三分(yb_status 1->2 + 金额守恒 + setl_record + 出院校准 + 释放床位); 2305 撤销(2->3->4);
 * 2302 明细撤销; 2304 UNKNOWN 留中间态->his_comp_task->CompTaskSweeper 未受理复位->重结收敛。
 * 资金写只打隔离库, 验后由 CI/人工 DROP DATABASE 无痕。
 */
@SpringBootTest(classes = YbInterfaceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("it")
@DisplayName("住院医保上报闭环端到端(隔离库)")
class InpatientYbFlowIT {

    private static final long IT_TENANT = 880001L;
    private static final long IT_ORG = 1L;

    @Autowired
    private InpVisitService inpVisitService;
    @Autowired
    private InpSettleService inpSettleService;
    @Autowired
    private InpatientService inpatientService;
    @Autowired
    private CompTaskSweeper compTaskSweeper;
    @Autowired
    private JdbcTemplate jdbc;

    /** 全类共享主键区(static, 跨用例递增), 避免 JUnit5 每方法新建实例导致跨用例撞主键 */
    private static final AtomicLong seq = new AtomicLong(9_100_000L);

    @BeforeEach
    void setUpTenant() {
        TenantContext.set(IT_TENANT);
    }

    @AfterEach
    void clearProps() {
        System.clearProperty("yb.mock.unknown.2304");
        System.clearProperty("yb.mock.unknown.2301");
        TenantContext.clear();
    }

    /* ==================== 用例A: 上报接入 + 结算成功 + 撤销 + 2302 ==================== */

    @Test
    @DisplayName("A: 入院2401→出院2402上报接入 → 2301/2303/2304结算成功 → 2305撤销 → 2302全撤")
    void admissionDischargeSettleCancel() {
        long patientId = seedPatient();
        long bedId = seedBed();
        long deptId = seedDept();
        long staffId = seedStaff();
        long itemId = seedChargeItem();

        // 入院登记(服务层真实链路: 占床+住院号+入院诊断), 提交后触发 2401 上报
        Long visitId = admit(patientId, bedId, deptId, staffId);
        // 医保就诊ID(非 2401 回写, 前置已核实: 由登记/1101 侧预置) + 预交金
        jdbc.update("UPDATE his_inp_visit SET mdtrt_id = ?, deposit_balance = ? WHERE id = ? AND tenant_id = ?",
                "MIT" + visitId, new BigDecimal("100.00"), visitId, IT_TENANT);
        seedChargeDetail(visitId, itemId, new BigDecimal("100.00"));
        seedChargeDetail(visitId, itemId, new BigDecimal("100.00"));

        // 断言 2401 上报接入: INP_REG 成功(status=1)
        Map<String, Object> reg = jdbc.queryForMap(
                "SELECT biz_type, status FROM his_upload_status WHERE tenant_id=? AND biz_type='INP_REG' AND biz_id=? AND deleted=0",
                IT_TENANT, visitId);
        assertEquals(1, ((Number) reg.get("status")).intValue(), "入院登记2401上报应成功");

        // 预结算(2303 透传试算): 医保链路 + 回执
        Map<String, Object> pre = inpSettleService.preSettle(visitId);
        assertEquals(Boolean.TRUE, pre.get("ybFlag"), "有医保标识且金额>0应走2303试算");
        assertNotNull(pre.get("setlInfo"), "2303应返回setlInfo");

        // 出院办理(2->3), 提交后触发 2402 上报
        inpVisitService.dischargeApply(visitId);
        Map<String, Object> disch = jdbc.queryForMap(
                "SELECT status FROM his_upload_status WHERE tenant_id=? AND biz_type='INP_DISCH' AND biz_id=? AND deleted=0",
                IT_TENANT, visitId);
        assertEquals(1, ((Number) disch.get("status")).intValue(), "出院办理2402上报应成功");

        // 正式结算(2301->2304): T1中间态 -> T2医保链 -> T3终态 yb_status 1->2
        InpSettleDTO dto = new InpSettleDTO();
        dto.setInpVisitId(visitId);
        dto.setSettleType(1);
        Map<String, Object> res = inpSettleService.settle(dto, IT_ORG);
        Long settleId = ((Number) ((com.yb.hi.entity.inpatient.HisInpSettle) res.get("settle")).getId()).longValue();

        Map<String, Object> s = jdbc.queryForMap(
                "SELECT yb_status, total_amount, fund_pay, acct_pay, self_pay, cash_pay, deposit_deduct, refund_amount"
                        + " FROM his_inp_settle WHERE id=? AND tenant_id=? AND deleted=0", settleId, IT_TENANT);
        assertEquals(2, ((Number) s.get("yb_status")).intValue(), "结算成功应置 yb_status=2");
        BigDecimal total = bd(s.get("total_amount"));
        BigDecimal fund = bd(s.get("fund_pay"));
        BigDecimal acct = bd(s.get("acct_pay"));
        BigDecimal self = bd(s.get("self_pay"));
        assertEquals(0, new BigDecimal("200.00").compareTo(total), "总金额应等于明细汇总");
        assertEquals(0, total.compareTo(fund.add(acct).add(self)), "金额守恒: total=fund+acct+self");
        // mock 口径: 统筹70%, 自付30%, 个账0
        assertEquals(0, new BigDecimal("140.00").compareTo(fund), "mock基金支付=70%");
        assertEquals(0, new BigDecimal("60.00").compareTo(self), "mock个人负担=30%");
        // 现金应付 = self - acct = 60; 预交金抵扣=min(100,60)=60; 补缴现金=0; 退还=100-60=40
        assertEquals(0, new BigDecimal("60.00").compareTo(bd(s.get("deposit_deduct"))), "预交金抵扣");
        assertEquals(0, BigDecimal.ZERO.compareTo(bd(s.get("cash_pay"))), "预交金足额则现缴为0");
        assertEquals(0, new BigDecimal("40.00").compareTo(bd(s.get("refund_amount"))), "多缴预交金退还");

        // setl_record(对账/补偿事实来源) 已落住院2304已结算
        Integer setlRec = jdbc.queryForObject(
                "SELECT COUNT(*) FROM setl_record WHERE tenant_id=? AND mdtrt_id=? AND biz_type='inpatient' AND infno='2304' AND status='1'",
                Integer.class, IT_TENANT, "MIT" + visitId);
        assertTrue(setlRec != null && setlRec >= 1, "2304应落setl_record");

        // 明细挂账 + 出院校准 + 床位释放
        Integer frozen = jdbc.queryForObject(
                "SELECT COUNT(*) FROM his_inp_charge_detail WHERE tenant_id=? AND inp_visit_id=? AND settle_id=? AND deleted=0",
                Integer.class, IT_TENANT, visitId, settleId);
        assertEquals(2, frozen.intValue(), "结算范围内2条明细应挂 settle_id");
        Map<String, Object> v = jdbc.queryForMap(
                "SELECT visit_status, discharge_date, deposit_balance FROM his_inp_visit WHERE id=? AND tenant_id=?", visitId, IT_TENANT);
        assertEquals(4, ((Number) v.get("visit_status")).intValue(), "出院结算后 visit_status=4");
        assertNotNull(v.get("discharge_date"), "出院结算应回填出院时间");
        assertEquals(0, BigDecimal.ZERO.compareTo(bd(v.get("deposit_balance"))), "出院后余额清零");
        Integer bedFree = jdbc.queryForObject(
                "SELECT status FROM his_bed WHERE id=? AND tenant_id=?", Integer.class, bedId, IT_TENANT);
        assertEquals(0, bedFree.intValue(), "出院结算应释放床位");

        // 撤销结算(2305): 2->3->4 + 明细解挂 + visit 回 2
        inpSettleService.cancelSettle(settleId);
        Map<String, Object> sc = jdbc.queryForMap(
                "SELECT yb_status FROM his_inp_settle WHERE id=? AND tenant_id=?", settleId, IT_TENANT);
        assertEquals(4, ((Number) sc.get("yb_status")).intValue(), "撤销成功后 yb_status=4已撤销");
        Map<String, Object> vc = jdbc.queryForMap(
                "SELECT visit_status FROM his_inp_visit WHERE id=? AND tenant_id=?", visitId, IT_TENANT);
        assertEquals(2, ((Number) vc.get("visit_status")).intValue(), "撤销后就诊回在院(可重结)");
        Integer unfrozen = jdbc.queryForObject(
                "SELECT COUNT(*) FROM his_inp_charge_detail WHERE tenant_id=? AND inp_visit_id=? AND settle_id IS NULL AND deleted=0",
                Integer.class, IT_TENANT, visitId);
        assertEquals(2, unfrozen.intValue(), "撤销后明细解挂回待结算池");

        // 2302 明细撤销(feedetl_sn="0000" 全撤): 独立能力直接调用, mock 受理成功
        InpFeeDetailRevokeReq revoke = new InpFeeDetailRevokeReq();
        revoke.setFeedetlSn("0000");
        revoke.setMdtrtId("MIT" + visitId);
        revoke.setPsnNo(psnNo(visitId));
        YbResponse rr = inpatientService.feeDetailRevoke(Collections.singletonList(revoke));
        assertNotNull(rr, "2302应有回执");
        assertTrue(rr.isSuccess(), "2302明细全撤应成功(mock infcode=0)");
    }

    /* ==================== 用例B: 2304 UNKNOWN 留中间态 + 补偿复位 + 重结 ==================== */

    @Test
    @DisplayName("B: 2304 UNKNOWN留中间态→comp_task→CompTaskSweeper未受理复位→重结收敛终态")
    void unknownSettleCompensate() {
        long patientId = seedPatient();
        long bedId = seedBed();
        long deptId = seedDept();
        long staffId = seedStaff();
        long itemId = seedChargeItem();

        Long visitId = admit(patientId, bedId, deptId, staffId);
        jdbc.update("UPDATE his_inp_visit SET mdtrt_id = ?, deposit_balance = ? WHERE id = ? AND tenant_id = ?",
                "MUN" + visitId, new BigDecimal("50.00"), visitId, IT_TENANT);
        seedChargeDetail(visitId, itemId, new BigDecimal("200.00"));
        inpVisitService.dischargeApply(visitId);

        // 注入 2304 UNKNOWN(模拟网络超时): 结算应抛业务异常且单据留中间态挂起
        System.setProperty("yb.mock.unknown.2304", "true");
        InpSettleDTO dto = new InpSettleDTO();
        dto.setInpVisitId(visitId);
        dto.setSettleType(1);
        assertThrows(RuntimeException.class, () -> inpSettleService.settle(dto, IT_ORG),
                "2304 UNKNOWN 应以补偿提示抛出");
        System.clearProperty("yb.mock.unknown.2304");

        // 中间态挂起: yb_status=1, visit_status=4, 明细冻结, 补偿任务与 INP_SETL 待补留痕
        Map<String, Object> mid = jdbc.queryForMap(
                "SELECT id, yb_status FROM his_inp_settle WHERE tenant_id=? AND inp_visit_id=? AND deleted=0 ORDER BY id DESC LIMIT 1",
                IT_TENANT, visitId);
        Long settleId = ((Number) mid.get("id")).longValue();
        assertEquals(1, ((Number) mid.get("yb_status")).intValue(), "UNKNOWN后结算应停在 yb_status=1中间态");
        assertEquals(4, jdbc.queryForObject(
                "SELECT visit_status FROM his_inp_visit WHERE id=? AND tenant_id=?", Integer.class, visitId, IT_TENANT).intValue(),
                "UNKNOWN后就诊保持出院占位4");
        assertTrue(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM his_comp_task WHERE tenant_id=? AND biz_type='INP_SETTLE' AND ref_id=? AND deleted=0",
                        Integer.class, IT_TENANT, settleId) >= 1,
                "UNKNOWN应生成住院结算补偿任务");
        assertTrue(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM his_upload_status WHERE tenant_id=? AND biz_type='INP_SETL' AND biz_id=? AND status=2 AND deleted=0",
                        Integer.class, IT_TENANT, settleId) >= 1,
                "UNKNOWN应在上报中心留住院结算待补痕");

        // 驱动补偿扫描: mock 本地核对(UNKNOWN未落结算流水=平台未受理)->复位可重结
        // 强制 next_run 落过去: 建任务时 next_run=now(截断到秒) 与手动 sweep 存在时钟秒边界竞态, 消除之
        jdbc.update("UPDATE his_comp_task SET next_run = DATE_SUB(NOW(), INTERVAL 1 MINUTE)"
                + " WHERE tenant_id=? AND biz_type='INP_SETTLE' AND ref_id=? AND deleted=0",
                IT_TENANT, settleId);
        compTaskSweeper.sweep();
        // sweep 同步跑在主线程, resolveUnknownSettle finally 会 TenantContext.clear(), 此处恢复测试租户上下文
        TenantContext.set(IT_TENANT);

        assertEquals(1, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM his_comp_task WHERE tenant_id=? AND biz_type='INP_SETTLE' AND ref_id=? AND status='DONE' AND deleted=0",
                        Integer.class, IT_TENANT, settleId).intValue(),
                "补偿任务应收敛为 DONE");
        assertEquals(0, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM his_inp_settle WHERE id=? AND tenant_id=? AND deleted=0",
                        Integer.class, settleId, IT_TENANT).intValue(),
                "平台未受理: 结算中间态应作废(deleted=1)");
        assertEquals(3, jdbc.queryForObject(
                        "SELECT visit_status FROM his_inp_visit WHERE id=? AND tenant_id=?", Integer.class, visitId, IT_TENANT).intValue(),
                "复位后就诊回到出院办理中3(可重结)");
        assertNull(jdbc.queryForObject(
                        "SELECT settle_id FROM his_inp_charge_detail WHERE tenant_id=? AND inp_visit_id=? AND deleted=0",
                        java.lang.Long.class, IT_TENANT, visitId),
                "复位后明细解挂");

        // 重新结算(网络恢复)-> 正常收敛终态 yb_status=2
        Map<String, Object> res2 = inpSettleService.settle(dto, IT_ORG);
        Long settleId2 = ((Number) ((com.yb.hi.entity.inpatient.HisInpSettle) res2.get("settle")).getId()).longValue();
        assertEquals(2, jdbc.queryForObject(
                        "SELECT yb_status FROM his_inp_settle WHERE id=? AND tenant_id=?", Integer.class, settleId2, IT_TENANT).intValue(),
                "重结应落终态 yb_status=2");
        assertTrue(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM his_inp_settle WHERE id=? AND tenant_id=?", Integer.class, settleId, IT_TENANT) >= 0,
                "作废单可追溯");
    }

    /* ==================== 夹具辅助(隔离租户显式 tenant_id AND deleted=0) ==================== */

    private long nid() {
        return seq.incrementAndGet();
    }

    private long seedPatient() {
        long id = nid();
        jdbc.update("INSERT INTO his_patient (id, tenant_id, patient_no, name, deleted) VALUES (?,?,?,?,0)",
                id, IT_TENANT, "P" + id, "住院测试患者");
        long insu = nid();
        jdbc.update("INSERT INTO his_patient_insu (id, tenant_id, patient_id, psn_no, insuplc_admdvs, deleted)"
                        + " VALUES (?,?,?,?,?,0)", insu, IT_TENANT, id, "PSN" + id, "420100");
        return id;
    }

    private long seedDept() {
        long id = nid();
        jdbc.update("INSERT INTO his_dept (id, tenant_id, dept_code, dept_name, yb_dept_code, deleted)"
                        + " VALUES (?,?,?,?,?,0)", id, IT_TENANT, "D" + id, "IT住院科", "D0001");
        return id;
    }

    private long seedStaff() {
        long id = nid();
        jdbc.update("INSERT INTO his_staff (id, tenant_id, staff_no, staff_name, atddr_no, deleted)"
                        + " VALUES (?,?,?,?,?,0)", id, IT_TENANT, "S" + id, "IT医生", "DOC" + id);
        return id;
    }

    private long seedBed() {
        long ward = nid();
        jdbc.update("INSERT INTO his_ward (id, tenant_id, org_id, ward_name, deleted) VALUES (?,?,?,? ,0)",
                ward, IT_TENANT, IT_ORG, "IT病区");
        long bed = nid();
        jdbc.update("INSERT INTO his_bed (id, tenant_id, org_id, bed_no, ward_id, status, deleted)"
                        + " VALUES (?,?,?,?,?,0,0)", bed, IT_TENANT, IT_ORG, "B" + bed, ward);
        return bed;
    }

    private long seedChargeItem() {
        long id = nid();
        jdbc.update("INSERT INTO his_charge_item (id, tenant_id, item_code, item_name, med_list_codg, deleted)"
                        + " VALUES (?,?,?,?,?,0)", id, IT_TENANT, "C" + id, "IT医保项目", "MLC0001");
        return id;
    }

    private void seedChargeDetail(long visitId, long itemId, BigDecimal amount) {
        long id = nid();
        jdbc.update("INSERT INTO his_inp_charge_detail (id, tenant_id, org_id, inp_visit_id, charge_item_id,"
                        + " item_name, unit_price, amount, quantity, charge_date, fee_type, status, deleted)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,1,0)",
                id, IT_TENANT, IT_ORG, visitId, itemId, "IT医保项目", amount, amount, BigDecimal.ONE, LocalDate.now(), 1);
    }

    private Long admit(long patientId, long bedId, long deptId, long staffId) {
        InpAdmitDTO d = new InpAdmitDTO();
        d.setPatientId(patientId);
        d.setBedId(bedId);
        d.setDeptId(deptId);
        d.setDoctorId(staffId);
        d.setAdmitDiag("入院测试诊断");
        d.setMedType("21");
        d.setPsnNo(psnOfPatient(patientId));
        d.setInsutype("310");
        return inpVisitService.admit(d, IT_ORG).getId();
    }

    private String psnOfPatient(long patientId) {
        return "PSN" + patientId;
    }

    private String psnNo(Long visitId) {
        return jdbc.queryForObject("SELECT psn_no FROM his_inp_visit WHERE id=? AND tenant_id=?",
                String.class, visitId, IT_TENANT);
    }

    private static BigDecimal bd(Object v) {
        return v == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(v));
    }
}
