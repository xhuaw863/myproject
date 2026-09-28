package com.yb.hi.service.yb;

import com.alibaba.fastjson2.JSONObject;
import com.yb.hi.common.MockYbServer;
import com.yb.hi.common.YbResponse;
import com.yb.hi.config.YbConfig;
import com.yb.hi.dto.FileUploadReq;
import com.yb.hi.dto.ReconDetailReq;
import com.yb.hi.dto.ReconTotalReq;
import com.yb.hi.entity.yb.HisReconDiff;
import com.yb.hi.entity.yb.HisReconTask;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.mapper.yb.HisReconDiffMapper;
import com.yb.hi.mapper.yb.HisReconTaskMapper;
import com.yb.hi.service.OutpatientService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 医保对账服务(批次4 M3): 3201 对总账 + 3202 对明细账 + 9101 文件上传闭环。
 * 本地口径: setl_record(2207/2208 结算留存) status='1' 加 / status='0' 减, 按结算时间 setl_time 归属对账日;
 * 3201 按险种逐组上报, 平(stmt_rslt=000000)结束, 不平再跑 3202(明细 TXT 按表200: TAB分隔/UTF-8/空值null/ZIP,
 * 退费数据传正向+反向两条, refd_setl_flag 行级 3 位); 差异按表201 解析落 his_recon_diff 人工核对。
 * 规范约定: 数值型为空传"0"、其他空串""; refd_setl_flag 3201 为 6 位 / 3202 为 3 位, 取值与平台确认(配置化)。
 * 原生 SQL 显式 tenant_id(租户插件不作用于 jdbcTemplate; 调度线程无请求上下文)。
 */
@Slf4j
@Service
public class ReconService {

    /** 表200/201 行级退费结算标志(3位): 正常/退费(字典值待与平台确认, 按通用口径占位) */
    private static final String ROW_FLAG_NORMAL = "000";
    private static final String ROW_FLAG_REFUND = "001";

    private final JdbcTemplate jdbcTemplate;
    private final OutpatientService outpatientService;
    private final MockYbServer mockYbServer;
    private final YbConfig ybConfig;
    private final HisReconTaskMapper reconTaskMapper;
    private final HisReconDiffMapper reconDiffMapper;

    public ReconService(JdbcTemplate jdbcTemplate, OutpatientService outpatientService,
                        MockYbServer mockYbServer, YbConfig ybConfig,
                        HisReconTaskMapper reconTaskMapper, HisReconDiffMapper reconDiffMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.outpatientService = outpatientService;
        this.mockYbServer = mockYbServer;
        this.ybConfig = ybConfig;
        this.reconTaskMapper = reconTaskMapper;
        this.reconDiffMapper = reconDiffMapper;
    }

    /**
     * 对账主流程(tenantId 显式传入: 供调度线程调用, 内部设置 TenantContext 使 Mapper 自动带租户):
     * 3201 按险种对总账 -> 平直接落任务(1); 不平/交易失败落任务(2/9)并对不平组跑 3202 明细对账。
     * 幂等: 同租户+对账日+险种已有 TOTAL 任务且非失败(9)时跳过。
     */
    public void reconcile(Long tenantId, LocalDate stmtDate) {
        reconcile(tenantId, stmtDate, false);
    }

    /**
     * force=true: 先作废该租户+对账日的全部历史任务与差异(软删 deleted=1), 强制重跑
     * (差异修正/补数后人工重对; 注意 3202 结果文件不会重复处理, 重新生成)。
     */
    public void reconcile(Long tenantId, LocalDate stmtDate, boolean force) {
        TenantContext.set(tenantId);
        try {
            if (force) {
                jdbcTemplate.update("UPDATE his_recon_task SET deleted = 1 WHERE tenant_id = ? AND stmt_date = ? AND deleted = 0",
                        tenantId, stmtDate);
                jdbcTemplate.update("UPDATE his_recon_diff SET deleted = 1 WHERE tenant_id = ? AND stmt_date = ? AND deleted = 0",
                        tenantId, stmtDate);
                log.info("对账强制重跑: tenantId={}, stmtDate={} 历史任务/差异已作废", tenantId, stmtDate);
            }
            List<Map<String, Object>> groups = jdbcTemplate.queryForList(
                    "SELECT insutype,"
                            + " IFNULL(SUM(CASE WHEN status = '1' THEN medfee_sumamt ELSE -medfee_sumamt END), 0) AS medfee,"
                            + " IFNULL(SUM(CASE WHEN status = '1' THEN fund_pay_sumamt ELSE -fund_pay_sumamt END), 0) AS fund,"
                            + " IFNULL(SUM(CASE WHEN status = '1' THEN acct_pay ELSE -acct_pay END), 0) AS acct,"
                            + " SUM(CASE WHEN status = '1' THEN 1 ELSE -1 END) AS cnt"
                            + " FROM setl_record"
                            + " WHERE tenant_id = ? AND insutype IS NOT NULL AND insutype <> ''"
                            + " AND DATE(setl_time) = ?"
                            + " GROUP BY insutype ORDER BY insutype",
                    tenantId, stmtDate);
            if (groups.isEmpty()) {
                log.info("对账跳过: tenantId={}, stmtDate={} 无结算流水", tenantId, stmtDate);
                return;
            }
            boolean anyDiff = false;
            for (Map<String, Object> g : groups) {
                String insutype = str(g.get("insutype"));
                if (hasTotalTask(tenantId, stmtDate, insutype)) {
                    log.info("对账跳过(已对): tenantId={}, date={}, insutype={}", tenantId, stmtDate, insutype);
                    continue;
                }
                boolean diff = reconcileTotalGroup(tenantId, stmtDate, insutype,
                        toBd(g.get("medfee")), toBd(g.get("fund")), toBd(g.get("acct")), toInt(g.get("cnt")));
                if (diff) {
                    anyDiff = true;
                }
            }
            // 重跑自愈: 历史 TOTAL 已有不平但明细对账缺失/失败时, 补跑 3202
            if (anyDiff || needDetailRetry(tenantId, stmtDate)) {
                reconcileDetail(tenantId, stmtDate);
            }
        } catch (Exception e) {
            log.error("对账执行异常: tenantId={}, stmtDate={}", tenantId, stmtDate, e);
            throw e;
        } finally {
            TenantContext.clear();
        }
    }

    /** 3201 单险种组对总账: 返回 true=不平(需 3202 明细对账) */
    private boolean reconcileTotalGroup(Long tenantId, LocalDate d, String insutype,
                                        BigDecimal medfee, BigDecimal fund, BigDecimal acct, int cnt) {
        String date = d.toString();
        ReconTotalReq req = new ReconTotalReq();
        req.setInsutype(insutype);
        req.setClrType(nvlCfg(ybConfig.getClrType()));
        req.setSetlOptins(nvlCfg(ybConfig.getSetlOptins()));
        req.setStmtBegndate(date);
        req.setStmtEnddate(date);
        req.setMedfeeSumamt(amt(medfee));
        req.setFundPaySumamt(amt(fund));
        req.setAcctPay(amt(acct));
        req.setFixmedinsSetlCnt(String.valueOf(Math.max(cnt, 0)));
        req.setRefdSetlFlag(nvlCfg(ybConfig.getRefdSetlFlag3201()));
        req.setExpContent("");
        YbResponse resp;
        try {
            resp = outpatientService.reconcileTotal(req);
        } catch (Exception e) {
            log.error("3201 调用异常: tenantId={}, insutype={}, 原因: {}", tenantId, insutype, e.getMessage());
            saveTask(tenantId, d, insutype, HisReconTask.RESULT_FAIL, "TOTAL", medfee, fund, acct, cnt, null, null,
                    null, "3201 调用异常: " + e.getMessage());
            return false;
        }
        JSONObject stmtinfo = resp == null ? null : resp.getOutputNode("stmtinfo");
        String stmtRslt = stmtinfo == null ? null : stmtinfo.getString("stmt_rslt");
        String dscr = stmtinfo == null ? null : stmtinfo.getString("stmt_rslt_dscr");
        if (resp == null || resp.isUnknown()) {
            saveTask(tenantId, d, insutype, HisReconTask.RESULT_FAIL, "TOTAL", medfee, fund, acct, cnt, null, null,
                    stmtRslt, "3201 结果未知: " + (dscr == null ? "无响应" : dscr));
            return false;
        }
        if (!resp.isSuccess()) {
            saveTask(tenantId, d, insutype, HisReconTask.RESULT_FAIL, "TOTAL", medfee, fund, acct, cnt, null, null,
                    stmtRslt, "3201 失败: " + resp.getErrMsg());
            return false;
        }
        boolean match = "000000".equals(stmtRslt);
        saveTask(tenantId, d, insutype, match ? HisReconTask.RESULT_MATCH : HisReconTask.RESULT_DIFF, "TOTAL",
                medfee, fund, acct, cnt, null, null, stmtRslt, dscr);
        log.info("3201 对总账: tenantId={}, date={}, insutype={}, 结果={}",
                tenantId, date, insutype, match ? "平" : "不平(" + stmtRslt + ")");
        return !match;
    }

    /**
     * 3202 对明细账(3201 不平时): 明细 TXT(表200, TAB分隔/UTF-8/空值null, 退费正向+反向两条)
     * -> ZIP -> 9101 上传取 file_qury_no -> 3202 申报 -> 结果文件(表201)解析差异落 his_recon_diff。
     */
    private void reconcileDetail(Long tenantId, LocalDate d) {
        String date = d.toString();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT setl_id, mdtrt_id, psn_no, insutype, status,"
                        + " medfee_sumamt, fund_pay_sumamt, acct_pay, psn_cash_pay"
                        + " FROM setl_record WHERE tenant_id = ? AND DATE(setl_time) = ?"
                        + " ORDER BY setl_id, status DESC",
                tenantId, date);
        if (rows.isEmpty()) {
            return;
        }
        BigDecimal medfeeNet = BigDecimal.ZERO;
        BigDecimal fundNet = BigDecimal.ZERO;
        BigDecimal cashNet = BigDecimal.ZERO;
        int cnt = 0;
        StringBuilder txt = new StringBuilder();
        for (Map<String, Object> r : rows) {
            boolean settled = "1".equals(str(r.get("status")));
            BigDecimal m = toBd(r.get("medfee_sumamt"));
            BigDecimal f = toBd(r.get("fund_pay_sumamt"));
            BigDecimal a = toBd(r.get("acct_pay"));
            BigDecimal c = toBd(r.get("psn_cash_pay"));
            medfeeNet = medfeeNet.add(settled ? m : m.negate());
            fundNet = fundNet.add(settled ? f : f.negate());
            cashNet = cashNet.add(settled ? c : c.negate());
            if (settled) {
                cnt++;
            } else {
                cnt--;
            }
            String flag = settled ? ROW_FLAG_NORMAL : ROW_FLAG_REFUND;
            appendRow(txt, str(r.get("setl_id")), str(r.get("mdtrt_id")), str(r.get("psn_no")),
                    m.toPlainString(), f.toPlainString(), a.toPlainString(), flag);
            if (!settled) {
                // 退费数据: 正向+反向两条(规范3202重点说明2)
                appendRow(txt, str(r.get("setl_id")), str(r.get("mdtrt_id")), str(r.get("psn_no")),
                        m.negate().toPlainString(), f.negate().toPlainString(), a.negate().toPlainString(), flag);
            }
        }
        // 9101 上传明细文件
        byte[] zip = zipTxt(txt.toString());
        FileUploadReq uploadReq = new FileUploadReq();
        uploadReq.setIn(zip);
        uploadReq.setFilename("3202_" + tenantId + "_" + date + ".zip");
        uploadReq.setFixmedinsCode(ybConfig.getFixmedinsCode());
        YbResponse upResp = outpatientService.fileUpload(uploadReq);
        String fileQuryNo = upResp == null ? null : upResp.getOutputObject() == null ? null
                : upResp.getOutputObject().getString("file_qury_no");
        if (fileQuryNo == null) {
            saveTask(tenantId, d, null, HisReconTask.RESULT_FAIL, "DETAIL", medfeeNet, fundNet, cashNet, cnt,
                    null, null, null, "9101 上传失败: " + (upResp == null ? "无响应" : upResp.getErrMsg()));
            return;
        }
        // 3202 申报
        ReconDetailReq req = new ReconDetailReq();
        req.setSetlOptins(nvlCfg(ybConfig.getSetlOptins()));
        req.setFileQuryNo(fileQuryNo);
        req.setStmtBegndate(date);
        req.setStmtEnddate(date);
        req.setMedfeeSumamt(amt(medfeeNet));
        req.setFundPaySumamt(amt(fundNet));
        req.setCashPayamt(amt(cashNet));
        req.setFixmedinsSetlCnt(String.valueOf(Math.max(cnt, 0)));
        req.setClrType(nvlCfg(ybConfig.getClrType()));
        req.setRefdSetlFlag(nvlCfg(ybConfig.getRefdSetlFlag3202()));
        req.setExpContent("");
        YbResponse resp;
        try {
            resp = outpatientService.reconcileDetail(req);
        } catch (Exception e) {
            log.error("3202 调用异常: tenantId={}, 原因: {}", tenantId, e.getMessage());
            saveTask(tenantId, d, null, HisReconTask.RESULT_FAIL, "DETAIL", medfeeNet, fundNet, cashNet, cnt,
                    fileQuryNo, null, null, "3202 调用异常: " + e.getMessage());
            return;
        }
        JSONObject fileinfo = resp == null ? null : resp.getOutputNode("fileinfo");
        String resultFqn = fileinfo == null ? null : fileinfo.getString("file_qury_no");
        if (resp == null || !resp.isSuccess() || resultFqn == null) {
            saveTask(tenantId, d, null, HisReconTask.RESULT_FAIL, "DETAIL", medfeeNet, fundNet, cashNet, cnt,
                    fileQuryNo, null, null, "3202 失败: " + (resp == null ? "无响应" : resp.getErrMsg()));
            return;
        }
        // 结果文件解析(表201): mock 本地取流; 真实模式经 9102 下载(设计§5.2, 文件交易流式, 预留人工下载)
        List<String[]> diffs;
        if (ybConfig.isMockEnabled()) {
            diffs = parseDetailResult(mockYbServer.getMockFile(resultFqn));
        } else {
            diffs = null;
            log.warn("【对账】真实模式 3202 差异文件需经 9102 下载核对: file_qury_no={}", resultFqn);
        }
        int diffCount = 0;
        if (diffs != null) {
            HisReconTask detailTask = saveTask(tenantId, d, null, diffs.isEmpty() ? HisReconTask.RESULT_MATCH : HisReconTask.RESULT_DIFF,
                    "DETAIL", medfeeNet, fundNet, cashNet, cnt, fileQuryNo, resultFqn, null,
                    diffs.isEmpty() ? "明细账核对一致" : "明细账存在差异 " + diffs.size() + " 条");
            for (String[] row : diffs) {
                if (detailTask == null) {
                    continue;
                }
                saveDiff(detailTask, d, row);
                diffCount++;
            }
        } else {
            saveTask(tenantId, d, null, HisReconTask.RESULT_DIFF, "DETAIL", medfeeNet, fundNet, cashNet, cnt,
                    fileQuryNo, resultFqn, null, "真实模式差异文件待 9102 下载核对(查询号=" + resultFqn + ")");
        }
        log.info("3202 对明细账: tenantId={}, date={}, 差异{}条", tenantId, date, diffCount);
    }

    /** 表200 明细行(TAB分隔, 空值null): setl_id/mdtrt_id/psn_no/medfee_sumamt/fund_pay_sumamt/acct_pay/refd_setl_flag/exp_content */
    private void appendRow(StringBuilder txt, String setlId, String mdtrtId, String psnNo,
                           String medfee, String fund, String acct, String flag) {
        String[] cells = {setlId, mdtrtId, psnNo, medfee, fund, acct, flag, "null"};
        for (String c : cells) {
            txt.append(c == null || c.isEmpty() ? "null" : c).append('\t');
        }
        txt.setLength(txt.length() - 1);
        txt.append('\n');
    }

    /** 解析 3202 结果文件(表201, ZIP内TXT): psn_no/mdtrt_id/setl_id/msgid/stmt_rslt/refd_setl_flag/memo/medfee_sumamt/fund_pay_sumamt/acct_pay/exp_content */
    private List<String[]> parseDetailResult(byte[] zip) {
        java.util.ArrayList<String[]> rows = new java.util.ArrayList<>();
        if (zip == null || zip.length == 0) {
            return rows;
        }
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = zis.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                String content = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                for (String line : content.split("\n")) {
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    String[] cells = line.split("\t", -1);
                    String[] r = new String[11];
                    for (int i = 0; i < r.length; i++) {
                        String c = i < cells.length ? cells[i] : "null";
                        r[i] = "null".equals(c) || c.isEmpty() ? null : c;
                    }
                    rows.add(r);
                }
            }
        } catch (Exception e) {
            log.warn("3202 结果文件解析失败: {}", e.getMessage());
        }
        return rows;
    }

    private void saveDiff(HisReconTask task, LocalDate d, String[] row) {
        try {
            HisReconDiff diff = new HisReconDiff();
            diff.setReconTaskId(task.getId());
            diff.setStmtDate(d);
            diff.setSetlId(row[2]);
            diff.setMdtrtId(row[1]);
            diff.setPsnNo(row[0]);
            diff.setMsgid(row[3]);
            diff.setStmtRslt(row[4]);
            diff.setRefdSetlFlag(row[5]);
            diff.setMemo(row[6]);
            diff.setMedfeeSumamt(toBd(row[7]));
            diff.setFundPaySumamt(toBd(row[8]));
            diff.setAcctPay(toBd(row[9]));
            diff.setStatus(0);
            reconDiffMapper.insert(diff);
        } catch (Exception e) {
            log.error("对账差异留痕失败: setlId={}", row[2], e);
        }
    }

    private HisReconTask saveTask(Long tenantId, LocalDate d, String insutype, String result, String type,
                                  BigDecimal medfee, BigDecimal fund, BigDecimal acct, int cnt,
                                  String fileQuryNo, String resultFqn, String stmtRslt, String memo) {
        HisReconTask task = new HisReconTask();
        task.setStmtDate(d);
        task.setInsutype(insutype);
        task.setReconType(type);
        task.setResult(result);
        task.setMedfeeLocal(medfee);
        task.setFundLocal(fund);
        task.setAcctLocal(acct);
        task.setCntLocal(cnt);
        task.setFileQuryNo(fileQuryNo);
        task.setStmtRslt(stmtRslt);
        task.setMemo(memo);
        task.setReconTime(LocalDateTime.now());
        reconTaskMapper.insert(task);
        return task;
    }

    /** 幂等守卫: 同租户+对账日+险种已有 TOTAL 任务(非失败)则跳过 */
    private boolean hasTotalTask(Long tenantId, LocalDate d, String insutype) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_recon_task WHERE tenant_id = ? AND stmt_date = ?"
                        + " AND insutype = ? AND recon_type = 'TOTAL' AND result <> '9' AND deleted = 0",
                Integer.class, tenantId, d, insutype);
        return n != null && n > 0;
    }

    /** 重跑自愈: 当日存在 TOTAL 不平(result=2)且尚无 DETAIL 任务时, 补跑 3202 */
    private boolean needDetailRetry(Long tenantId, LocalDate d) {
        Integer diff = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_recon_task WHERE tenant_id = ? AND stmt_date = ?"
                        + " AND recon_type = 'TOTAL' AND result = '2' AND deleted = 0",
                Integer.class, tenantId, d);
        Integer detail = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_recon_task WHERE tenant_id = ? AND stmt_date = ?"
                        + " AND recon_type = 'DETAIL' AND deleted = 0",
                Integer.class, tenantId, d);
        return diff != null && diff > 0 && (detail == null || detail == 0);
    }

    private String nvlCfg(String v) {
        return v == null ? "" : v;
    }

    /** 数值型为空传"0"(规范约定), 保留两位小数 */
    private String amt(BigDecimal v) {
        return v == null ? "0" : v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private byte[] zipTxt(String txt) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(bos)) {
                zos.putNextEntry(new ZipEntry("3202_detail.txt"));
                zos.write(txt.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("3202 明细文件打包失败: " + e.getMessage(), e);
        }
    }

    private static BigDecimal toBd(Object v) {
        return v == null ? BigDecimal.ZERO : new BigDecimal(v.toString());
    }

    private static int toInt(Object v) {
        return v == null ? 0 : ((Number) v).intValue();
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
