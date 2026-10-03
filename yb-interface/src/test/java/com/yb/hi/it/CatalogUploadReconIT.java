package com.yb.hi.it;

import com.yb.hi.YbInterfaceApplication;
import com.yb.hi.common.MockYbServer;
import com.yb.hi.config.YbConfig;
import com.yb.hi.controller.yb.ReconController;
import com.yb.hi.dto.cashier.ChargeReq;
import com.yb.hi.entity.cashier.HisChargeBill;
import com.yb.hi.entity.yb.HisReconTask;
import com.yb.hi.entity.yb.HisYbTxnLog;
import com.yb.hi.entity.yb.HisYbUploadQueue;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.service.cashier.CashierService;
import com.yb.hi.service.yb.CatalogUploadService;
import com.yb.hi.service.yb.ReconService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批次4 外围闭环端到端集成测试(M4 目录对照上传 3301/3302 + M3 对账 3201/3202/9101 + 2601 冲正闭环, 隔离库 ybtest_it)。
 * 前置: ybtest_it 克隆 dev 库全量表结构(DictSchemaMigration 只补列不建表); mock-enabled 走本地模拟医保平台。
 * 覆盖:
 *   E  list_type 未配置拒绝上报(防错误字典值上平台) → 配置后失败复位重传成功(3301 受理入 mock 对照表);
 *   F  CHANGE 先 3302 撤旧码后 3301 传新码(交易日志顺序断言) + CLEAR 撤销旧码后平台侧不并存;
 *   G  150 条 MAP 批边界(规范 3301 重点说明2: 每批 ≤100 条, 应拆 2 批发出);
 *   H  收费结算成功 → 3201 对总账平(setl_record 本地口径 vs mock 平台流水, TOTAL 任务 result=1);
 *   I  篡改本地 setl_record 制造"机构多" → 3201 不平(000103) → 9101 明细文件 + 3202 差异落 his_recon_diff
 *      (差异 msgid 必须可回溯 his_yb_txn_log 2207 msgid —— 2601 冲正 omsgid 凭据链) → 人工核对处置 →
 *      数据修复后 force 重对自愈收敛为平;
 *   J  场景6 冲正闭环: 差异守卫(非可冲正清单/无 msgid) → 2601 UNKNOWN 本地零变更(防双重冲正) →
 *      冲正受理 → 原交易日志 REVERSED + mock 平台流水消失 + 本地同流水反向冲销行 → force 重对复核平 →
 *      重复冲正拒绝(幂等)。
 * 资金写只打隔离库, 验后人工 DROP DATABASE 无痕。
 */
@SpringBootTest(classes = YbInterfaceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("it")
@DisplayName("目录对照上传与医保对账端到端(隔离库)")
class CatalogUploadReconIT {

    private static final long IT_TENANT = 880003L;
    private static final long IT_ORG = 1L;
    private static final String LT_DRUG = "101";

    @Autowired
    private CatalogUploadService catalogUploadService;
    @Autowired
    private ReconService reconService;
    @Autowired
    private ReconController reconController;
    @Autowired
    private CashierService cashierService;
    @Autowired
    private MockYbServer mockYbServer;
    @Autowired
    private YbConfig ybConfig;
    @Autowired
    private JdbcTemplate jdbc;

    /** 主键区按时间戳派生: 隔离库残留不与本次运行撞主键, IT 可重复执行 */
    private static final AtomicLong seq = new AtomicLong(System.currentTimeMillis());

    private String origListDrug, origListCons, origListCharge;

    @BeforeEach
    void setUpCtx() throws Exception {
        // 本租户全量清理: 队列/对账任务/差异/交易日志/补偿任务/结算留存, 保证断言只看到本次运行的数据
        jdbc.update("DELETE FROM his_yb_upload_queue WHERE tenant_id = ?", IT_TENANT);
        jdbc.update("DELETE FROM his_recon_task WHERE tenant_id = ?", IT_TENANT);
        jdbc.update("DELETE FROM his_recon_diff WHERE tenant_id = ?", IT_TENANT);
        jdbc.update("DELETE FROM his_yb_txn_log WHERE tenant_id = ?", IT_TENANT);
        jdbc.update("DELETE FROM his_comp_task WHERE tenant_id = ?", IT_TENANT);
        jdbc.update("DELETE FROM setl_record WHERE tenant_id = ?", IT_TENANT);
        // mock 平台侧结算流水为 JVM 全局: 同 fork 并跑其他 IT 的结算会污染 3201 平台口径汇总, 用例内先清空
        clearMockSetls();
        origListDrug = ybConfig.getListTypeDrug();
        origListCons = ybConfig.getListTypeCons();
        origListCharge = ybConfig.getListTypeCharge();
        TenantContext.set(IT_TENANT);
        UserContext.set(loginUser());
    }

    @AfterEach
    void clearCtx() {
        ybConfig.setListTypeDrug(origListDrug);
        ybConfig.setListTypeCons(origListCons);
        ybConfig.setListTypeCharge(origListCharge);
        UserContext.clear();
        TenantContext.clear();
    }

    /* ==================== 用例E: list_type 未配置拒绝 → 配置后复位重传成功 ==================== */

    @Test
    @DisplayName("E: list_type 未配置拒绝上报(不发出 3301) → 配置后 retry 复位重传成功入 mock 对照表")
    void listTypeUnconfiguredRejectThenRetry() {
        ybConfig.setListTypeDrug("");
        String itemCode = "ITC" + nid();
        catalogUploadService.enqueue("drug", nid(), itemCode, "IT对照药品", null, "YP99990001",
                HisYbUploadQueue.ACTION_MAP);

        Map<String, Object> res = catalogUploadService.runUpload(IT_TENANT, "drug");
        restoreCtx();
        assertEquals(1, num(res.get("failed")), "list_type 未配置应整批拒绝");
        assertEquals(0, num(res.get("uploaded")));
        Map<String, Object> row = queueRow(itemCode);
        assertEquals(HisYbUploadQueue.STATUS_FAILED, num(row.get("status")), "队列行应置失败(2)");
        assertTrue(String.valueOf(row.get("last_err")).contains("list_type 未配置"),
                "失败原因应说明 list_type 未配置");
        assertEquals(0, txnCount("3301"), "未配置时不得向平台发出 3301");

        // 配置目录类别后失败复位重传(设计: 配置后补可重传)
        ybConfig.setListTypeDrug(LT_DRUG);
        assertEquals(1, catalogUploadService.retryFailed(IT_TENANT), "失败行应复位 1 条");
        restoreCtx();
        res = catalogUploadService.runUpload(IT_TENANT, "drug");
        restoreCtx();
        assertEquals(1, num(res.get("uploaded")), "配置后重传应成功");
        row = queueRow(itemCode);
        assertEquals(HisYbUploadQueue.STATUS_UPLOADED, num(row.get("status")));
        assertNotNull(row.get("batch_no"), "成功批次应回传批次号");
        assertTrue(mockYbServer.mockCatalogContains(itemCode, LT_DRUG, "YP99990001"),
                "3301 受理后平台侧应存在该对照");
        assertTrue(txnCount("3301") >= 1, "3301 应落交易日志 SUCCESS");
    }

    /* ==================== 用例F: CHANGE 先撤后传 + CLEAR 撤销 ==================== */

    @Test
    @DisplayName("F: MAP→3301; CHANGE→先 3302 撤旧码后 3301 传新码(顺序断言, 不并存); CLEAR→3302")
    void changeRevokeBeforeUploadAndClear() {
        ybConfig.setListTypeDrug(LT_DRUG);
        String itemCode = "ITC" + nid();

        // 首次对照 MAP 旧码 A
        catalogUploadService.enqueue("drug", nid(), itemCode, "IT对照药品", null, "YPA00000001",
                HisYbUploadQueue.ACTION_MAP);
        catalogUploadService.runUpload(IT_TENANT, "drug");
        restoreCtx();
        assertTrue(mockYbServer.mockCatalogContains(itemCode, LT_DRUG, "YPA00000001"), "MAP 后旧码 A 应在平台侧");

        // 变更 CHANGE A->B: 必须先 3302 撤 A 再 3301 传 B, 平台侧不并存
        catalogUploadService.enqueue("drug", nid(), itemCode, "IT对照药品", "YPA00000001", "YPB00000002",
                HisYbUploadQueue.ACTION_CHANGE);
        Map<String, Object> res = catalogUploadService.runUpload(IT_TENANT, "drug");
        restoreCtx();
        assertEquals(1, num(res.get("revoked")));
        assertEquals(1, num(res.get("uploaded")));
        List<Long> order = jdbc.queryForList(
                "SELECT id FROM his_yb_txn_log WHERE tenant_id=? AND infno='3302' AND deleted=0 ORDER BY id DESC LIMIT 1",
                Long.class, IT_TENANT);
        List<Long> order2 = jdbc.queryForList(
                "SELECT id FROM his_yb_txn_log WHERE tenant_id=? AND infno='3301' AND deleted=0 ORDER BY id DESC LIMIT 1",
                Long.class, IT_TENANT);
        assertFalse(order.isEmpty() && order2.isEmpty(), "CHANGE 应同时产生 3302 与 3301 交易日志");
        assertTrue(order.get(0) < order2.get(0), "同轮 CHANGE: 3302 撤销批应先于 3301 上传批发出");
        assertFalse(mockYbServer.mockCatalogContains(itemCode, LT_DRUG, "YPA00000001"), "变更后旧码 A 应已撤销");
        assertTrue(mockYbServer.mockCatalogContains(itemCode, LT_DRUG, "YPB00000002"), "变更新码 B 应上传生效");

        // 清除对照 CLEAR: 3302 撤 B, 平台侧不残留
        catalogUploadService.enqueue("drug", nid(), itemCode, "IT对照药品", "YPB00000002", null,
                HisYbUploadQueue.ACTION_CLEAR);
        res = catalogUploadService.runUpload(IT_TENANT, "drug");
        restoreCtx();
        assertEquals(1, num(res.get("revoked")));
        assertFalse(mockYbServer.mockCatalogContains(itemCode, LT_DRUG, "YPB00000002"), "CLEAR 后平台侧不应残留对照");
    }

    /* ==================== 用例G: 每批 ≤100 条规范边界 ==================== */

    @Test
    @DisplayName("G: 150 条 MAP 应拆 2 批(≤100/批, 规范3301重点说明2), 全部上传成功")
    void batchBoundary150Rows() {
        ybConfig.setListTypeDrug(LT_DRUG);
        String firstItem = null, lastItem = null;
        for (int i = 1; i <= 150; i++) {
            String itemCode = "ITGB" + i + "_" + nid();
            if (i == 1) {
                firstItem = itemCode;
            }
            if (i == 150) {
                lastItem = itemCode;
            }
            catalogUploadService.enqueue("drug", nid(), itemCode, "IT批量药品" + i, null,
                    "YPG" + String.format("%07d", i), HisYbUploadQueue.ACTION_MAP);
        }
        Map<String, Object> res = catalogUploadService.runUpload(IT_TENANT, "drug");
        restoreCtx();
        assertEquals(150, num(res.get("uploaded")), "150 条应全部上传成功");
        assertEquals(2, txnCount("3301"), "150 条应拆 2 批发出 3301(100+50)");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM his_yb_upload_queue WHERE tenant_id=? AND status<>? AND deleted=0",
                Integer.class, IT_TENANT, HisYbUploadQueue.STATUS_UPLOADED).intValue(), "队列应无未成功残留行");
        assertTrue(mockYbServer.mockCatalogContains(firstItem, LT_DRUG, "YPG0000001"), "首批首条应在平台侧");
        assertTrue(mockYbServer.mockCatalogContains(lastItem, LT_DRUG, "YPG0000150"), "次批末条应在平台侧");
    }

    /* ==================== 用例H: 收费结算 → 3201 对总账平 ==================== */

    @Test
    @DisplayName("H: 2207 结算成功 → setl_record 留存 → 3201 本地口径=平台流水 → TOTAL 任务平(1)")
    void reconcileTotalMatch() {
        long visitId = seedChargeableVisit();
        chargeOk(visitId);
        // 结算留存是本地对账口径的事实来源: 租户/金额必须落对, 否则对账永远不平
        Map<String, Object> rec = jdbc.queryForMap(
                "SELECT insutype, medfee_sumamt, fund_pay_sumamt FROM setl_record"
                        + " WHERE tenant_id=? AND status='1' ORDER BY id DESC LIMIT 1", IT_TENANT);
        assertNotNull(rec.get("insutype"), "setl_record 应带险种(3201 分组维度)");

        reconService.reconcile(IT_TENANT, LocalDate.now());
        restoreCtx();

        Map<String, Object> t = jdbc.queryForMap(
                "SELECT result, stmt_rslt, cnt_local FROM his_recon_task"
                        + " WHERE tenant_id=? AND recon_type='TOTAL' AND deleted=0 ORDER BY id DESC LIMIT 1", IT_TENANT);
        assertEquals(HisReconTask.RESULT_MATCH, t.get("result"), "口径一致时 3201 应对平 result=1");
        assertEquals("000000", t.get("stmt_rslt"));
        assertTrue(txnCount("3201") >= 1, "3201 应落交易日志");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM his_recon_task WHERE tenant_id=? AND recon_type='DETAIL' AND deleted=0",
                Integer.class, IT_TENANT).intValue(), "平账不应触发 3202 明细对账");
    }

    /* ==================== 用例I: 不平 → 3202 差异留痕 → 人工处置 → 修复后 force 重对自愈 ==================== */

    @Test
    @DisplayName("I: 篡改 setl_record → 3201 不平 → 9101+3202 差异落 his_recon_diff(msgid 可回溯 2207) → 处置 → force 重对平")
    void reconcileDiffHandleAndSelfHeal() {
        long visitId = seedChargeableVisit();
        chargeOk(visitId);
        Map<String, Object> rec = jdbc.queryForMap(
                "SELECT setl_id, medfee_sumamt, fund_pay_sumamt FROM setl_record"
                        + " WHERE tenant_id=? AND status='1' ORDER BY id DESC LIMIT 1", IT_TENANT);
        String setlId = String.valueOf(rec.get("setl_id"));
        BigDecimal origMedfee = bd(rec.get("medfee_sumamt"));
        BigDecimal origFund = bd(rec.get("fund_pay_sumamt"));
        // 制造"机构多": 本地口径虚增 50(平台流水不动) → 3201 必不平
        jdbc.update("UPDATE setl_record SET medfee_sumamt = medfee_sumamt + 50, fund_pay_sumamt = fund_pay_sumamt + 35"
                + " WHERE tenant_id=? AND setl_id=? AND status='1'", IT_TENANT, setlId);

        reconService.reconcile(IT_TENANT, LocalDate.now());
        restoreCtx();

        Map<String, Object> total = jdbc.queryForMap(
                "SELECT result FROM his_recon_task WHERE tenant_id=? AND recon_type='TOTAL' AND deleted=0"
                        + " ORDER BY id DESC LIMIT 1", IT_TENANT);
        assertEquals(HisReconTask.RESULT_DIFF, total.get("result"), "本地虚增后 3201 应判不平 result=2");
        Map<String, Object> detail = jdbc.queryForMap(
                "SELECT result, file_qury_no FROM his_recon_task WHERE tenant_id=? AND recon_type='DETAIL' AND deleted=0"
                        + " ORDER BY id DESC LIMIT 1", IT_TENANT);
        assertEquals(HisReconTask.RESULT_DIFF, detail.get("result"), "3202 应核对出差异");
        assertNotNull(detail.get("file_qury_no"), "3202 前应经 9101 上传取得明细文件查询号");

        Map<String, Object> d = jdbc.queryForMap(
                "SELECT setl_id, stmt_rslt, memo, msgid, status FROM his_recon_diff"
                        + " WHERE tenant_id=? AND deleted=0 ORDER BY id DESC LIMIT 1", IT_TENANT);
        assertEquals(setlId, d.get("setl_id"));
        assertEquals("000103", d.get("stmt_rslt"), "金额不一致差异码应为表201 000103");
        assertTrue(String.valueOf(d.get("memo")).contains("不一致"), "差异说明应含平台回执");
        assertEquals(0, num(d.get("status")), "新差异应为待处理(0)");
        // 2601 冲正凭据链: 差异行 msgid 必须能回溯本院 2207 出站交易日志的 msgid(omsgid 唯一来源)
        Integer msgTrace = jdbc.queryForObject(
                "SELECT COUNT(*) FROM his_yb_txn_log WHERE tenant_id=? AND infno='2207' AND msgid=? AND deleted=0",
                Integer.class, IT_TENANT, String.valueOf(d.get("msgid")));
        assertTrue(msgTrace != null && msgTrace >= 1, "差异 msgid 应可回溯 his_yb_txn_log 2207(2601 omsgid 凭据)");

        // 人工核对处置: 1已核对(走管理端同一 Controller 入口, 断言响应体 code 而非 HTTP)
        Long diffId = jdbc.queryForObject(
                "SELECT id FROM his_recon_diff WHERE tenant_id=? AND deleted=0 ORDER BY id DESC LIMIT 1",
                Long.class, IT_TENANT);
        Map<String, Object> body = new HashMap<>();
        body.put("status", 1);
        body.put("handleMemo", "IT人工核对: 本地多记50, 已线下冲账");
        R<String> handled = reconController.handleDiff(diffId, body);
        assertEquals(0, code(handled), "差异处置接口应返回成功码");
        Map<String, Object> hd = jdbc.queryForMap(
                "SELECT status, handle_memo, handle_time FROM his_recon_diff WHERE id=?", diffId);
        assertEquals(1, num(hd.get("status")));
        assertNotNull(hd.get("handle_time"), "处置应留处理时间");

        // 数据修复后强制重对: 历史任务/差异作废, 平账收敛(重跑自愈)
        jdbc.update("UPDATE setl_record SET medfee_sumamt = ?, fund_pay_sumamt = ?"
                        + " WHERE tenant_id=? AND setl_id=? AND status='1'",
                origMedfee, origFund, IT_TENANT, setlId);
        reconService.reconcile(IT_TENANT, LocalDate.now(), true);
        restoreCtx();
        Map<String, Object> healed = jdbc.queryForMap(
                "SELECT result FROM his_recon_task WHERE tenant_id=? AND recon_type='TOTAL' AND deleted=0"
                        + " ORDER BY id DESC LIMIT 1", IT_TENANT);
        assertEquals(HisReconTask.RESULT_MATCH, healed.get("result"), "修复后 force 重应对账应收敛为平");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM his_recon_diff WHERE tenant_id=? AND deleted=0 AND status=0",
                Integer.class, IT_TENANT).intValue(), "force 重对后旧差异应作废, 不留未处理差异");
    }

    /* ==================== 用例J: 场景6 2601 冲正闭环(守卫→UNKNOWN→受理→复核平→幂等) ==================== */

    @Test
    @DisplayName("J: 对账差异 → 2601 守卫链/UNKNOWN 零变更/冲正受理 REVERSED → mock 平台流水消失 → force 重对复核平 → 重复拒绝")
    void reverseDiffClosure() {
        long visitId = seedChargeableVisit();
        chargeOk(visitId);
        String setlId = jdbc.queryForObject(
                "SELECT setl_id FROM setl_record WHERE tenant_id=? AND status='1' ORDER BY id DESC LIMIT 1",
                String.class, IT_TENANT);
        jdbc.update("UPDATE setl_record SET medfee_sumamt = medfee_sumamt + 50, fund_pay_sumamt = fund_pay_sumamt + 35"
                + " WHERE tenant_id=? AND setl_id=? AND status='1'", IT_TENANT, setlId);
        reconService.reconcile(IT_TENANT, LocalDate.now());
        restoreCtx();
        Map<String, Object> d = jdbc.queryForMap(
                "SELECT id, msgid FROM his_recon_diff WHERE tenant_id=? AND deleted=0 ORDER BY id DESC LIMIT 1", IT_TENANT);
        Long diffId = ((Number) d.get("id")).longValue();
        String omsgid = String.valueOf(d.get("msgid"));

        // 守卫1: 非可冲正清单交易 → 400 拒绝, 不发 2601
        Map<String, Object> bad = new HashMap<>();
        bad.put("oinfno", "3505");
        assertEquals(400, code(reconController.reverseDiff(diffId, bad)), "不在可冲正清单应 400 拒绝");
        restoreCtx();
        assertEquals(0, txnCountAny("2601"), "守卫拒绝不应发出 2601");

        // 守卫2: 差异无原交易 msgid(机构侧多记语义) → 400 不适用冲正; 还原后继续
        jdbc.update("UPDATE his_recon_diff SET msgid = '' WHERE id = ?", diffId);
        assertEquals(400, code(reconController.reverseDiff(diffId, null)), "无 msgid 应拒绝冲正");
        restoreCtx();
        jdbc.update("UPDATE his_recon_diff SET msgid = ? WHERE id = ?", omsgid, diffId);

        // UNKNOWN 注入: 本地零变更(交易日志不置 REVERSED, 不补冲销行, 差异不平账) —— 防双重冲正
        System.setProperty("yb.mock.unknown.2601", "true");
        R<Map<String, Object>> unk = reconController.reverseDiff(diffId, null);
        restoreCtx();
        assertEquals(0, code(unk), "UNKNOWN 仍属已受理响应流程, 外层 code=0 结果内未知");
        assertEquals(Boolean.FALSE, unk.getData().get("reversed"), "UNKNOWN 不得判已冲正");
        assertEquals(Boolean.TRUE, unk.getData().get("unknown"), "UNKNOWN 应显式标记待复核");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM his_yb_txn_log WHERE tenant_id=? AND infno='2207' AND msgid=? AND status='REVERSED' AND deleted=0",
                Integer.class, IT_TENANT, omsgid).intValue(), "UNKNOWN 后原交易不得置 REVERSED");
        assertTrue(mockYbServer.findSetlByMsgid(omsgid) != null, "UNKNOWN 注入未触达平台, 结算流水应仍在");
        System.clearProperty("yb.mock.unknown.2601");

        // 人工确认冲正受理: 原交易 REVERSED + mock 平台流水消失 + 本地同流水反向冲销行(infno=2601, status='0')
        R<Map<String, Object>> ok = reconController.reverseDiff(diffId, null);
        restoreCtx();
        assertEquals(0, code(ok), "冲正应受理成功");
        assertEquals(Boolean.TRUE, ok.getData().get("reversed"));
        assertEquals(Boolean.TRUE, ok.getData().get("offsetLocal"), "本地存在未冲销 '1' 行应补反向行");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM his_yb_txn_log WHERE tenant_id=? AND infno='2207' AND msgid=? AND status='REVERSED' AND deleted=0",
                Integer.class, IT_TENANT, omsgid).intValue(), "原交易日志应置 REVERSED");
        assertTrue(mockYbServer.findSetlByMsgid(omsgid) == null, "冲正后平台侧原结算流水应消失");
        Map<String, Object> net = jdbc.queryForMap(
                "SELECT IFNULL(SUM(CASE WHEN status='1' THEN medfee_sumamt ELSE -medfee_sumamt END),0) AS net_medfee,"
                        + " COUNT(*) AS n FROM setl_record WHERE tenant_id=? AND setl_id=?",
                IT_TENANT, setlId);
        assertEquals(0, bd(net.get("net_medfee")).signum(), "本地净额口径应归零");
        assertEquals(2, num(jdbc.queryForObject(
                "SELECT status FROM his_recon_diff WHERE id=?", Integer.class, diffId)), "差异应置已平账(2)");

        // 复核: force 重对 → 两侧净额均 0 → 3201 平(规范: 2601 无输出, 对账平才算生效)
        reconService.reconcile(IT_TENANT, LocalDate.now(), true);
        restoreCtx();
        Map<String, Object> total = jdbc.queryForMap(
                "SELECT result FROM his_recon_task WHERE tenant_id=? AND recon_type='TOTAL' AND deleted=0"
                        + " ORDER BY id DESC LIMIT 1", IT_TENANT);
        assertEquals(HisReconTask.RESULT_MATCH, total.get("result"), "冲正后复核重对应收敛为平");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM his_recon_diff WHERE tenant_id=? AND deleted=0", Integer.class, IT_TENANT)
                .intValue(), "复核后不应残留活动差异");

        // 幂等: 已平账差异重复冲正 → 400 拒绝
        assertEquals(400, code(reconController.reverseDiff(diffId, null)), "已平账差异不得重复冲正");
        restoreCtx();
    }

    /* ==================== 夹具辅助 ==================== */

    /** mock 平台侧结算流水清空(反射): 3201 平台口径汇总为 JVM 全局, 隔离同 fork 其他 IT 的当日结算污染 */
    private void clearMockSetls() throws Exception {
        for (String name : new String[]{"mockSetls", "mockCancelledSetls"}) {
            Field f = MockYbServer.class.getDeclaredField(name);
            f.setAccessible(true);
            Object holder = f.get(mockYbServer);
            if (holder instanceof Map) {
                ((Map<?, ?>) holder).clear();
            } else if (holder instanceof java.util.Collection) {
                ((java.util.Collection<?>) holder).clear();
            }
        }
    }

    /** reconcile/runUpload/retryFailed 内部 finally 会 TenantContext.clear(), 测试上下文需恢复后再断言/查询 */
    private void restoreCtx() {
        TenantContext.set(IT_TENANT);
        UserContext.set(loginUser());
    }

    private Map<String, Object> queueRow(String itemCode) {
        return jdbc.queryForMap(
                "SELECT status, batch_no, last_err FROM his_yb_upload_queue"
                        + " WHERE tenant_id=? AND item_code=? AND deleted=0 ORDER BY id DESC LIMIT 1",
                IT_TENANT, itemCode);
    }

    private int txnCount(String infno) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM his_yb_txn_log WHERE tenant_id=? AND infno=? AND status=? AND deleted=0",
                Integer.class, IT_TENANT, infno, HisYbTxnLog.ST_SUCCESS).intValue();
    }

    /** 不限状态的交易日志计数(守卫断言用: 任何状态都没发出过) */
    private int txnCountAny(String infno) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM his_yb_txn_log WHERE tenant_id=? AND infno=? AND deleted=0",
                Integer.class, IT_TENANT, infno).intValue();
    }

    private HisChargeBill chargeOk(long visitId) {
        ChargeReq r = new ChargeReq();
        r.setVisitId(visitId);
        r.setOrgId(IT_ORG);
        r.setPayType("yb");
        return (HisChargeBill) cashierService.charge(r).get("bill");
    }

    private LoginUser loginUser() {
        LoginUser u = new LoginUser();
        u.setUserId(990_003L);
        u.setTenantId(IT_TENANT);
        u.setUsername("it_recon");
        u.setRealName("IT对账员");
        u.setOrgId(IT_ORG);
        u.setRole("ADMIN");
        u.setRoles(new ArrayList<>(Collections.singletonList("ADMIN")));
        u.setLeadOrg(false);
        return u;
    }

    private long nid() {
        return seq.incrementAndGet();
    }

    /** 造"已接诊未收费、可医保结算"门诊就诊(100 元单明细), 结算后 setl_record 即本地对账口径 */
    private long seedChargeableVisit() {
        BigDecimal unitPrice = new BigDecimal("100.00");
        long patientId = nid();
        jdbc.update("INSERT INTO his_patient (id, tenant_id, patient_no, name, gender, age, id_card, deleted)"
                        + " VALUES (?,?,?,?,?,?,?,0)",
                patientId, IT_TENANT, "P" + patientId, "对账测试患者", "男", 45, "IDC" + patientId);
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
                visitId, IT_TENANT, regId, patientId, "对账测试患者", "OIT" + visitId, "PSN" + patientId,
                "310", "11", "D" + deptId, "IT门诊科", "DOC" + staffId, "IT医生");
        long rxId = nid();
        jdbc.update("INSERT INTO his_prescription (id, tenant_id, visit_id, rx_no, status, deleted)"
                        + " VALUES (?,?,?,?,1,0)", rxId, IT_TENANT, visitId, "RX" + rxId);
        jdbc.update("INSERT INTO his_prescription_item (id, tenant_id, prescription_id, item_code, item_name,"
                        + " unit, quantity, price, amount, med_list_codg, deleted)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,0)",
                nid(), IT_TENANT, rxId, "DC" + rxId, "IT医保药品", "盒", BigDecimal.ONE, unitPrice, unitPrice, "MLC0001");
        return visitId;
    }

    private static int num(Object v) {
        return v == null ? 0 : ((Number) v).intValue();
    }

    private static BigDecimal bd(Object v) {
        return v == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(v));
    }

    private static int code(R<?> r) {
        return r.getCode();
    }
}
