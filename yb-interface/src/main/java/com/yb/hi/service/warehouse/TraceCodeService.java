package com.yb.hi.service.warehouse;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.common.YbHttpClient;
import com.yb.hi.common.YbResponse;
import com.yb.hi.dto.warehouse.TraceBindReq;
import com.yb.hi.dto.warehouse.TraceCollectReq;
import com.yb.hi.entity.community.HisDrugCatalog;
import com.yb.hi.entity.warehouse.HisDrugTraceCode;
import com.yb.hi.entity.yb.HisUploadStatus;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.community.HisDrugCatalogMapper;
import com.yb.hi.mapper.warehouse.HisDrugTraceCodeMapper;
import com.yb.hi.service.yb.UploadStatusService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * 医保药品追溯码服务(药库/药房合规): 入库采集(扫描录入或批量占位建码, 服务端 20 位数字格式校验 P1-6) → 在库 → 发药绑定患者/处方 → 退货/报废/调拨状态流转 → 报送。
 * 报送双通道(批次5 勘误后口径, 详见 07 号文 §〇): 通道A=结算侧 2207 挂 drug_trac_info 节点(buildSettlementNodes/finalizeSettlementUpload, M2 已实施);
 * 通道B=进销存台账 upload() 真实 **3505A 商品销售**报送(两阶段认领 + 按就诊/药品/批次/发药单分组成 selinfoDetail 行 + drugtracinfo 裸码节点,
 * 字段冻结见 07 号文 §十二; 两级判定 infcode+retRslt); 两侧均 upsert his_upload_status(biz_type=TRACE, biz_id=追溯码行id)。
 * 唯一键 tenant_id + trace_code 拦截重复扫码; 写: requireSelfOrgWrite(控制器层); 读: scopeOrgId。
 */
@Slf4j
@Service
public class TraceCodeService {

    private static final int ST_INSTOCK = 0;
    private static final int ST_DISPENSED = 1;
    private static final int ST_RETURNED = 2;
    private static final int ST_SCRAPPED = 3;

    /** 报送状态(his_drug_trace_code.upload_status, 与 his_upload_status 状态机口径对齐): 0未报送 1报送中 2失败待补 9已报送 */
    private static final int UP_PENDING = 0;
    private static final int UP_CLAIMING = 1;
    private static final int UP_FAILED = 2;
    private static final int UP_DONE = 9;

    /** 追溯码格式(审计 P1-6): 20 位数字; 位数/码制待规范【2404】前置确认清单#5 冻结(设计 07 号文 §十) */
    private static final Pattern TRACE_CODE_PATTERN = Pattern.compile("^\\d{20}$");

    private final HisDrugTraceCodeMapper traceMapper;
    private final HisDrugCatalogMapper drugCatalogMapper;
    private final UploadStatusService uploadStatusService;
    private final JdbcTemplate jdbcTemplate;
    private final YbHttpClient ybHttpClient;

    public TraceCodeService(HisDrugTraceCodeMapper traceMapper, HisDrugCatalogMapper drugCatalogMapper,
                            UploadStatusService uploadStatusService, JdbcTemplate jdbcTemplate, YbHttpClient ybHttpClient) {
        this.traceMapper = traceMapper;
        this.drugCatalogMapper = drugCatalogMapper;
        this.uploadStatusService = uploadStatusService;
        this.jdbcTemplate = jdbcTemplate;
        this.ybHttpClient = ybHttpClient;
    }

    /* ================= 采集(入库录入) ================= */

    /**
     * 采集追溯码: 指定库位+药品+批次, 优先使用扫描清单 codes; codes 为空则按 autoGenerateQty 生成占位码(便于无扫码器演示)。
     * 服务端格式校验(P1-6): 非 20 位数字一律拒绝(invalid); 逐条按 uk(tenant,trace_code) 去重: 已存在或本批重复的跳过并计数, 新码以 status=0(在库) 入库。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> collect(TraceCollectReq req) {
        if (req == null || req.getLocationId() == null || req.getDrugCatalogId() == null) {
            throw new BizException(400, "采集需指定库位与药品");
        }
        Long orgId = req.getOrgId() != null ? req.getOrgId() : currentOrgId();
        if (orgId == null) {
            throw new BizException(400, "机构不能为空");
        }
        if (!locationExists(req.getLocationId(), orgId)) {
            throw new BizException(400, "库位不存在或已停用: locationId=" + req.getLocationId());
        }
        HisDrugCatalog drug = drugCatalogMapper.selectById(req.getDrugCatalogId());
        if (drug == null) {
            throw new BizException(400, "药品目录不存在: id=" + req.getDrugCatalogId());
        }
        List<String> codes = new ArrayList<>();
        if (!CollectionUtils.isEmpty(req.getCodes())) {
            for (String c : req.getCodes()) {
                if (StringUtils.hasText(c)) {
                    codes.add(c.trim());
                }
            }
        } else if (req.getAutoGenerateQty() != null && req.getAutoGenerateQty() > 0) {
            for (int i = 0; i < req.getAutoGenerateQty(); i++) {
                codes.add(genPlaceholderCode());
            }
        } else {
            throw new BizException(400, "请录入追溯码或指定自动生成数量");
        }

        Set<String> distinct = new HashSet<>(codes);
        int collected = 0;
        int duplicated = codes.size() - distinct.size();
        List<String> rejected = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        for (String code : distinct) {
            if (!TRACE_CODE_PATTERN.matcher(code).matches()) {
                invalid.add(code);
                continue;
            }
            if (exists(code)) {
                rejected.add(code);
                continue;
            }
            HisDrugTraceCode row = new HisDrugTraceCode();
            row.setOrgId(orgId);
            row.setLocationId(req.getLocationId());
            row.setDrugCatalogId(drug.getId());
            row.setDrugCode(drug.getDrugCode());
            row.setBatchNo(req.getBatchNo());
            row.setTraceCode(code);
            row.setStatus(ST_INSTOCK);
            row.setMinPackQty(BigDecimal.ONE);
            row.setRefBillType(req.getStockInId() != null ? "in" : null);
            row.setRefBillId(req.getStockInId());
            row.setUploadStatus(0);
            try {
                traceMapper.insert(row);
                collected++;
            } catch (DuplicateKeyException e) {
                rejected.add(code);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("collected", collected);
        out.put("duplicated", duplicated);
        out.put("alreadyExists", rejected);
        out.put("invalid", invalid);
        log.info("追溯码采集: orgId={}, locationId={}, drug={}, 请求={}, 入库={}, 重复={}, 已存在={}, 格式非法={}",
                orgId, req.getLocationId(), drug.getDrugCode(), codes.size(), collected, duplicated, rejected.size(), invalid.size());
        return out;
    }

    /* ================= 发药绑定 ================= */

    /**
     * 发药绑定: 将一批在库(status=0)追溯码置为已发药(1), 绑定患者/就诊/发药记录。
     * 若给定 requiredPackQty 且与实际绑定码数不符, 返回软提示(mismatch, 不拦截); 未找到的码返回 missing。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> bind(TraceBindReq req) {
        if (req == null || CollectionUtils.isEmpty(req.getTraceCodes())) {
            throw new BizException(400, "待绑定追溯码清单不能为空");
        }
        Set<String> codes = new HashSet<>();
        for (String c : req.getTraceCodes()) {
            if (StringUtils.hasText(c)) {
                codes.add(c.trim());
            }
        }
        if (codes.isEmpty()) {
            throw new BizException(400, "待绑定追溯码清单不能为空");
        }
        List<HisDrugTraceCode> rows = traceMapper.selectList(Wrappers.<HisDrugTraceCode>lambdaQuery()
                .in(HisDrugTraceCode::getTraceCode, codes)
                .eq(HisDrugTraceCode::getStatus, ST_INSTOCK));
        Set<String> found = new HashSet<>();
        for (HisDrugTraceCode row : rows) {
            row.setStatus(ST_DISPENSED);
            row.setDispenseId(req.getDispenseId());
            row.setPatientId(req.getPatientId());
            row.setVisitId(req.getVisitId());
            row.setRefBillType("dispense");
            row.setRefBillId(req.getDispenseId());
            traceMapper.updateById(row);
            found.add(row.getTraceCode());
        }
        List<String> missing = new ArrayList<>();
        for (String c : codes) {
            if (!found.contains(c)) {
                missing.add(c);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bound", found.size());
        out.put("missing", missing);
        if (req.getRequiredPackQty() != null) {
            out.put("mismatch", req.getRequiredPackQty().compareTo(BigDecimal.valueOf(found.size())) != 0);
        }
        log.info("追溯码发药绑定: dispenseId={}, 请求={}, 绑定={}, 缺失={}",
                req.getDispenseId(), codes.size(), found.size(), missing.size());
        return out;
    }

    /** 状态流转(退货=2 / 报废·调拨在途=3 / 回库=0): 按追溯码批量更新; 退货/报废同步撤销 TRACE 状态机行(不续报, 批次5 M1) */
    @Transactional(rollbackFor = Exception.class)
    public int updateStatus(List<String> traceCodes, int targetStatus, String refBillType, Long refBillId) {
        if (CollectionUtils.isEmpty(traceCodes)) {
            throw new BizException(400, "追溯码清单不能为空");
        }
        if (targetStatus != ST_RETURNED && targetStatus != ST_SCRAPPED && targetStatus != ST_INSTOCK) {
            throw new BizException(400, "非法目标状态: " + targetStatus);
        }
        Set<String> codes = new HashSet<>();
        for (String c : traceCodes) {
            if (StringUtils.hasText(c)) {
                codes.add(c.trim());
            }
        }
        int n = 0;
        for (HisDrugTraceCode row : traceMapper.selectList(Wrappers.<HisDrugTraceCode>lambdaQuery()
                .in(HisDrugTraceCode::getTraceCode, codes))) {
            row.setStatus(targetStatus);
            row.setRefBillType(refBillType);
            row.setRefBillId(refBillId);
            traceMapper.updateById(row);
            n++;
            if (targetStatus == ST_RETURNED || targetStatus == ST_SCRAPPED) {
                uploadStatusService.markRevoked(tenantId(), HisUploadStatus.BIZ_TRACE, row.getId(), null);
            }
        }
        log.info("追溯码状态流转: targetStatus={}, ref={}#{}, 更新={}", targetStatus, refBillType, refBillId, n);
        return n;
    }

    /* ================= 通道B: 进销存台账 3505A 商品销售报送(批次5 M2b; 字段逐字冻结见 07号文§十二) ================= */

    /** 销售/退货时间格式(表206 #35: yyyy-MM-ddHH:mm:ss) */
    private static final DateTimeFormatter SEL_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-ddHH:mm:ss");

    /**
     * 通道B 台账报送(真实 3505A 商品销售A, 批量): 认领已发药(status=1)且未报送/失败待补(upload_status 0/2)的码,
     * 按(就诊,药品目录,批次,发药单)分组组装为销售明细行(selinfoDetail, 表208), 码清单挂 drugtracinfo 节点(开放版仅裸码,
     * 无 feedetl_sn——07号文§十二.5#3 口径待平台确认); 所有分组一次事务发送, 两级判定收口(传输 infcode + 业务 retRslt, 表209)。
     * 孤儿回收: 台账此前认领未收口(status=1 且 upload_status=1)的行先归 0 重建(同 fixmedins_bchno 重发幂等
     * 待平台确认 07号文§十二.5#5, mock 接受重发)。必填取数缺失的行不认领不报送计数 noRef(不臆造必填值)。
     * locationId 可选(仅报送某库位来源的码)。勘误: 湖北 2404=入院撤销, 旧 dispatch mock 接缝随本次替换退役。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> upload(Long orgId, Long locationId) {
        long tid = tenantId();
        StringBuilder rec = new StringBuilder("UPDATE his_drug_trace_code SET upload_status = 0"
                + " WHERE tenant_id = ? AND deleted = 0 AND status = 1 AND upload_status = 1");
        List<Object> recArgs = new ArrayList<>();
        recArgs.add(tid);
        if (orgId != null) {
            rec.append(" AND org_id = ?");
            recArgs.add(orgId);
        }
        if (locationId != null) {
            rec.append(" AND location_id = ?");
            recArgs.add(locationId);
        }
        int recovered = jdbcTemplate.update(rec.toString(), recArgs.toArray());
        List<HisDrugTraceCode> candidates = traceMapper.selectList(Wrappers.<HisDrugTraceCode>lambdaQuery()
                .eq(orgId != null, HisDrugTraceCode::getOrgId, orgId)
                .eq(locationId != null, HisDrugTraceCode::getLocationId, locationId)
                .eq(HisDrugTraceCode::getStatus, ST_DISPENSED)
                .in(HisDrugTraceCode::getUploadStatus, UP_PENDING, UP_FAILED));
        String batchNo = "TRCB" + LocalDateTime.now().toLocalDate().toString().replace("-", "")
                + "-" + ThreadLocalRandom.current().nextInt(100000, 999999);
        Map<String, List<HisDrugTraceCode>> groups = new LinkedHashMap<>();
        for (HisDrugTraceCode row : candidates) {
            String key = row.getVisitId() + "|" + row.getDrugCatalogId() + "|" + row.getBatchNo() + "|" + row.getDispenseId();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        List<Map<String, Object>> salesRows = new ArrayList<>();
        List<Long> claimedIds = new ArrayList<>();
        Map<Long, String> rowMdtrt = new LinkedHashMap<>();
        int noRef = 0;
        for (List<HisDrugTraceCode> group : groups.values()) {
            Map<String, Object> line = buildSalesRow(group, batchNo, claimedIds, rowMdtrt);
            if (line == null) {
                noRef += group.size();
            } else {
                salesRows.add(line);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("receipt", batchNo);
        if (salesRows.isEmpty()) {
            out.put("uploaded", 0);
            out.put("failed", 0);
            out.put("unknown", 0);
            out.put("skipped", 0);
            out.put("noRef", noRef);
            log.info("进销存报送(3505A): orgId={}, 批次={}, 无可报行(候选={}, 缺必填取数={}, 孤儿回收={})",
                    orgId, batchNo, candidates.size(), noRef, recovered);
            return out;
        }
        YbResponse resp = ybHttpClient.call("3505A", Collections.singletonMap("selinfoDetail", salesRows));
        int[] fin = finalizeSalesUpload(claimedIds, rowMdtrt, resp, batchNo);
        out.put("uploaded", fin[0]);
        out.put("failed", fin[1]);
        out.put("unknown", fin[2]);
        out.put("skipped", candidates.size() - claimedIds.size() - noRef);
        out.put("noRef", noRef);
        log.info("进销存报送(3505A): orgId={}, 批次={}, 明细行={}, 认领行={}, 成功={}, 失败={}, 未知={}, 抢占跳过={}, 缺数据={}, 孤儿回收={}",
                orgId, batchNo, salesRows.size(), claimedIds.size(), fin[0], fin[1], fin[2],
                candidates.size() - claimedIds.size() - noRef, noRef, recovered);
        return out;
    }

    /**
     * 组装一行 3505A 销售明细(表208 selinfoDetail): 逐行 affected-rows 认领后取码清单挂 drugtracinfo。
     * 任一取数环节缺失(就诊/发药单/目录/批次生产日期/医师/经办人)返回 null 不认领——必填不臆造(07号文§十二.5#4)。
     * hi_feesetl_type 值域取自第6章字典 std_cv_code(0非医保/1本地/2异地): 就诊有 2207 结算(账单 setl_id)→"1", 否则"0";
     * mdtrt_setl_type 医保结算=1/自费=2 同判据; mdtrt_sn 医保时=MDTRT_ID, 自费时=院内就诊流水(OP+visit_id)。
     */
    private Map<String, Object> buildSalesRow(List<HisDrugTraceCode> group, String batchNo,
                                              List<Long> claimedIds, Map<Long, String> rowMdtrt) {
        long tid = tenantId();
        HisDrugTraceCode head = group.get(0);
        Long visitId = head.getVisitId();
        Long dispenseId = head.getDispenseId();
        Long drugId = head.getDrugCatalogId();
        String lotNo = head.getBatchNo();
        if (visitId == null || dispenseId == null || drugId == null || !StringUtils.hasText(lotNo)) {
            return null;
        }
        Map<String, Object> visit = one("SELECT mdtrt_id, psn_no, patient_id FROM his_visit WHERE id = ? AND tenant_id = ? AND deleted = 0",
                visitId, tid);
        Map<String, Object> disp = one("SELECT dispense_no, doctor_name, dispense_by, dispense_time FROM his_dispense WHERE id = ? AND tenant_id = ? AND deleted = 0",
                dispenseId, tid);
        Map<String, Object> cat = one("SELECT drug_code, generic_name, trade_name, yb_drug_code, otc_flag, retail_price FROM his_drug_catalog WHERE id = ? AND tenant_id = ? AND deleted = 0",
                drugId, tid);
        Map<String, Object> lot = one("SELECT prod_date, exp_date FROM his_stock_in_item WHERE drug_catalog_id = ? AND batch_no = ? AND tenant_id = ? AND deleted = 0 ORDER BY id LIMIT 1",
                drugId, lotNo, tid);
        if (visit == null || disp == null || cat == null || lot == null || lot.get("prod_date") == null
                || !StringUtils.hasText(str(disp.get("doctor_name"))) || !StringUtils.hasText(str(disp.get("dispense_by")))) {
            return null;
        }
        Map<String, Object> bill = one("SELECT setl_id FROM his_charge_bill WHERE visit_id = ? AND tenant_id = ? AND deleted = 0 AND setl_id IS NOT NULL ORDER BY id DESC LIMIT 1",
                visitId, tid);
        String setlId = bill == null ? null : str(bill.get("setl_id"));
        boolean ybSetl = StringUtils.hasText(setlId);
        Long patientId = toLongObj(visit.get("patient_id"));
        Map<String, Object> pat = patientId == null ? null
                : one("SELECT mdtrt_cert_type, mdtrt_cert_no, id_card, name FROM his_patient WHERE id = ? AND tenant_id = ? AND deleted = 0", patientId, tid);
        List<String> codes = new ArrayList<>();
        for (HisDrugTraceCode row : group) {
            int claimed = traceMapper.update(null, Wrappers.<HisDrugTraceCode>lambdaUpdate()
                    .set(HisDrugTraceCode::getUploadStatus, UP_CLAIMING)
                    .eq(HisDrugTraceCode::getId, row.getId())
                    .in(HisDrugTraceCode::getUploadStatus, UP_PENDING, UP_FAILED));
            if (claimed == 0) {
                continue;
            }
            claimedIds.add(row.getId());
            rowMdtrt.put(row.getId(), str(visit.get("mdtrt_id")));
            codes.add(row.getTraceCode());
        }
        if (codes.isEmpty()) {
            return null;
        }
        Map<String, Object> line = new LinkedHashMap<>();
        putIfText(line, "med_list_codg", str(cat.get("yb_drug_code")));
        line.put("fixmedins_hilist_id", str(cat.get("drug_code")));
        line.put("fixmedins_hilist_name", StringUtils.hasText(str(cat.get("trade_name")))
                ? str(cat.get("trade_name")) : str(cat.get("generic_name")));
        line.put("fixmedins_bchno", batchNo);
        line.put("prsc_dr_name", str(disp.get("doctor_name")));
        putIfText(line, "phar_name", str(disp.get("dispense_by")));
        line.put("hi_feesetl_type", ybSetl ? "1" : "0");
        putIfText(line, "setl_id", setlId);
        line.put("mdtrt_sn", ybSetl && StringUtils.hasText(str(visit.get("mdtrt_id")))
                ? str(visit.get("mdtrt_id")) : "OP" + visitId);
        putIfText(line, "psn_no", str(visit.get("psn_no")));
        line.put("psn_cert_type", pat != null && StringUtils.hasText(str(pat.get("mdtrt_cert_type")))
                ? str(pat.get("mdtrt_cert_type")) : "01");
        if (pat != null) {
            putIfText(line, "certno", StringUtils.hasText(str(pat.get("mdtrt_cert_no")))
                    ? str(pat.get("mdtrt_cert_no")) : str(pat.get("id_card")));
            putIfText(line, "psn_name", str(pat.get("name")));
        }
        line.put("manu_lotnum", lotNo);
        line.put("manu_date", String.valueOf(lot.get("prod_date")));
        if (lot.get("exp_date") != null) {
            line.put("expy_end", String.valueOf(lot.get("exp_date")));
        }
        line.put("rx_flag", Integer.valueOf(1).equals(toIntObj(cat.get("otc_flag"))) ? "0" : "1");
        line.put("trdn_flag", "0");
        putIfText(line, "finl_trns_pric", str(cat.get("retail_price")));
        putIfText(line, "rtal_docno", str(disp.get("dispense_no")));
        line.put("sel_retn_cnt", String.valueOf(codes.size()));
        line.put("sel_retn_time", disp.get("dispense_time") instanceof java.sql.Timestamp
                ? ((java.sql.Timestamp) disp.get("dispense_time")).toLocalDateTime().format(SEL_TIME_FMT)
                : LocalDateTime.now().format(SEL_TIME_FMT));
        line.put("sel_retn_opter_name", str(disp.get("dispense_by")));
        line.put("mdtrt_setl_type", ybSetl ? "1" : "2");
        List<Map<String, Object>> tracNodes = new ArrayList<>();
        for (String code : codes) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("drug_trac_codg", code);
            tracNodes.add(node);
        }
        line.put("drugtracinfo", tracNodes);
        return line;
    }

    /**
     * 通道B 收口(两级判定): 传输层 infcode=0 且业务层 retRslt=1 → 行置 9+凭据+状态机成功;
     * 传输拒绝或 retRslt!=1 → 行置 2 待补+状态机退避; UNKNOWN/无响应 → 保持 1, 下次台账孤儿回收重建重发。
     */
    private int[] finalizeSalesUpload(List<Long> claimedIds, Map<Long, String> rowMdtrt, YbResponse resp, String batchNo) {
        long tid = tenantId();
        LocalDateTime now = LocalDateTime.now();
        boolean success = false;
        boolean unknown = false;
        String msgid = null;
        String err;
        if (resp == null) {
            unknown = true;
            err = "3505A无响应(UNKNOWN): 平台侧是否受理不可知, 下次台账报送将自动重建重发";
        } else if (resp.isUnknown()) {
            unknown = true;
            msgid = resp.getInfRefmsgid();
            err = "3505A超时(UNKNOWN): 平台侧是否受理不可知, 下次台账报送将自动重建重发";
        } else if (!resp.isSuccess()) {
            msgid = resp.getInfRefmsgid();
            err = "3505A被平台拒绝(传输层): " + resp.getErrMsg();
        } else {
            msgid = resp.getInfRefmsgid();
            JSONObject result = resp.getOutputNode("result");
            String retRslt = result == null ? null : result.getString("retRslt");
            if ("1".equals(retRslt)) {
                success = true;
                err = null;
            } else {
                err = "3505A业务层失败(retRslt=" + (retRslt == null ? "缺失" : retRslt) + "): "
                        + (result == null ? "无result节点" : result.getString("msgRslt"));
            }
        }
        int ok = 0;
        int fail = 0;
        int unk = 0;
        for (Long id : claimedIds) {
            String mdtrtId = rowMdtrt.get(id);
            if (success) {
                traceMapper.update(null, Wrappers.<HisDrugTraceCode>lambdaUpdate()
                        .set(HisDrugTraceCode::getUploadStatus, UP_DONE)
                        .set(HisDrugTraceCode::getUploadTime, now)
                        .set(HisDrugTraceCode::getUploadReceipt, batchNo)
                        .set(HisDrugTraceCode::getUploadBatchNo, batchNo)
                        .set(HisDrugTraceCode::getUploadMsgid, msgid)
                        .eq(HisDrugTraceCode::getId, id));
                uploadStatusService.record(tid, HisUploadStatus.BIZ_TRACE, id, mdtrtId, true, msgid, null);
                ok++;
            } else if (unknown) {
                uploadStatusService.record(tid, HisUploadStatus.BIZ_TRACE, id, mdtrtId, false, msgid, err);
                unk++;
            } else {
                traceMapper.update(null, Wrappers.<HisDrugTraceCode>lambdaUpdate()
                        .set(HisDrugTraceCode::getUploadStatus, UP_FAILED)
                        .eq(HisDrugTraceCode::getId, id));
                uploadStatusService.record(tid, HisUploadStatus.BIZ_TRACE, id, mdtrtId, false, msgid, err);
                fail++;
            }
        }
        log.info("进销存报送收口: 批次={}, success={}, unknown={}, 行数={}", batchNo, success, unknown, claimedIds.size());
        return new int[]{ok, fail, unk};
    }

    /* ================= 通道A: 2207 结算报文挂 drug_trac_info 节点(批次5 M2) ================= */

    /**
     * 结算链上下文: 认领成功的追溯码行 + 待挂 2207 的 drug_trac_info 节点(表90逐字冻结:
     * feedetl_sn/drug_trac_codg/trdn_flag/min_prcunt_type)。未认领到任何行时 isEmpty()=true, 结算报文不带节点。
     */
    public static final class SettlementTrace {
        private final String batchNo;
        private final List<Map<String, Object>> nodes = new ArrayList<>();
        private final List<Long> claimedIds = new ArrayList<>();

        SettlementTrace(String batchNo) {
            this.batchNo = batchNo;
        }

        public boolean isEmpty() {
            return claimedIds.isEmpty();
        }

        public List<Map<String, Object>> nodes() {
            return nodes;
        }
    }

    /**
     * 结算前认领本次就诊待报送的追溯码并组装 drug_trac_info 节点(通道A)。
     * 口径: status=1已发药 且 upload_status∈{0,2} 且 visit_id=本次就诊; 先做孤儿回收(上次结算认领后未收口的 1→0, 重建重发),
     * 再逐行 affected-rows 抢占置 1(报送中)。feedetl_sn 与 2204 同号(项目口径 P+处方明细id):
     * 仅在"有医保目录编码的处方明细"内按 his_prescription_item.drug_id = 码行 drug_catalog_id 匹配首个明细;
     * 匹配不到(纯自费/无明细)的码不进节点, 保持未报送留给台账通道(通道B 3505)。
     * trdn_flag 固定 0、min_prcunt_type 固定 2(按最小包装单位, 扫码即整包装): 拆零/计价口径待平台确认(07号文§十#5#8)。
     */
    public SettlementTrace buildSettlementNodes(Long visitId, List<Map<String, Object>> billItems) {
        String batchNo = "TRCS" + LocalDateTime.now().toLocalDate().toString().replace("-", "")
                + "-" + ThreadLocalRandom.current().nextInt(100000, 999999);
        SettlementTrace st = new SettlementTrace(batchNo);
        if (visitId == null || CollectionUtils.isEmpty(billItems)) {
            return st;
        }
        // 孤儿回收: 仅本就诊, 上次结算链认领后崩溃/UNKNOWN未收敛的行重新纳入候选(重建重发幂等由平台侧待确认, mock受理)
        jdbcTemplate.update("UPDATE his_drug_trace_code SET upload_status = 0"
                        + " WHERE tenant_id = ? AND deleted = 0 AND visit_id = ? AND status = 1 AND upload_status = 1",
                tenantId(), visitId);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, trace_code, drug_catalog_id FROM his_drug_trace_code"
                        + " WHERE tenant_id = ? AND deleted = 0 AND visit_id = ? AND status = 1 AND upload_status IN (0, 2)"
                        + " ORDER BY id", tenantId(), visitId);
        if (rows.isEmpty()) {
            return st;
        }
        // 费用明细→药品映射: 与 buildFeeDetails 同口径(处方明细 P+refId, 仅 medListCodg 非空者已上传 2204)
        List<Long> itemIds = new ArrayList<>();
        for (Map<String, Object> it : billItems) {
            if ("order_item".equals(str(it.get("refType"))) || !StringUtils.hasText(str(it.get("medListCodg")))) {
                continue;
            }
            Long refId = toLongObj(it.get("refId"));
            if (refId != null) {
                itemIds.add(refId);
            }
        }
        Map<Long, Long> drugToFeedetlSn = mapFirstItemPerDrug(itemIds);
        for (Map<String, Object> r : rows) {
            Long id = toLongObj(r.get("id"));
            Long drugCatalogId = toLongObj(r.get("drug_catalog_id"));
            Long refId = drugCatalogId == null ? null : drugToFeedetlSn.get(drugCatalogId);
            if (id == null || refId == null) {
                continue;
            }
            int claimed = traceMapper.update(null, Wrappers.<HisDrugTraceCode>lambdaUpdate()
                    .set(HisDrugTraceCode::getUploadStatus, UP_CLAIMING)
                    .eq(HisDrugTraceCode::getId, id)
                    .in(HisDrugTraceCode::getUploadStatus, UP_PENDING, UP_FAILED));
            if (claimed == 0) {
                continue;
            }
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("feedetl_sn", "P" + refId);
            node.put("drug_trac_codg", str(r.get("trace_code")));
            node.put("trdn_flag", "0");
            node.put("min_prcunt_type", "2");
            st.nodes.add(node);
            st.claimedIds.add(id);
        }
        if (!st.claimedIds.isEmpty()) {
            log.info("追溯码结算节点(通道A): visitId={}, 批次={}, 候选={}, 挂节点={}",
                    visitId, batchNo, rows.size(), st.nodes.size());
        }
        return st;
    }

    /**
     * 2207 结算后收口(三分): SUCCESS→码行 9已报送+凭据列回填+状态机1; FAIL→2失败待补+状态机2(退避重传);
     * UNKNOWN→保持 1(报送中, 下次结算孤儿回收重建重发)+状态机2留报文ID待复核。每行 upsert his_upload_status(TRACE)。
     */
    public void finalizeSettlementUpload(SettlementTrace st, boolean success, boolean unknown,
                                         String mdtrtId, String msgid, String err) {
        if (st == null || st.claimedIds.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (Long id : st.claimedIds) {
            if (success) {
                traceMapper.update(null, Wrappers.<HisDrugTraceCode>lambdaUpdate()
                        .set(HisDrugTraceCode::getUploadStatus, UP_DONE)
                        .set(HisDrugTraceCode::getUploadTime, now)
                        .set(HisDrugTraceCode::getUploadReceipt, st.batchNo)
                        .set(HisDrugTraceCode::getUploadBatchNo, st.batchNo)
                        .set(HisDrugTraceCode::getUploadMsgid, msgid)
                        .eq(HisDrugTraceCode::getId, id));
                uploadStatusService.record(tenantId(), HisUploadStatus.BIZ_TRACE, id, mdtrtId, true, msgid, null);
            } else if (unknown) {
                uploadStatusService.record(tenantId(), HisUploadStatus.BIZ_TRACE, id, mdtrtId, false, msgid,
                        "2207结算超时(UNKNOWN): 追溯码是否随结算受理不可知, 下次结算自动重建重发(平台幂等待确认)");
            } else {
                traceMapper.update(null, Wrappers.<HisDrugTraceCode>lambdaUpdate()
                        .set(HisDrugTraceCode::getUploadStatus, UP_FAILED)
                        .eq(HisDrugTraceCode::getId, id));
                uploadStatusService.record(tenantId(), HisUploadStatus.BIZ_TRACE, id, mdtrtId, false, null, err);
            }
        }
        log.info("追溯码结算收口: 批次={}, success={}, unknown={}, 行数={}", st.batchNo, success, unknown, st.claimedIds.size());
    }

    /** 处方明细id集 → 药品目录id→首个明细id(同药多明细取首条, 严格对应待平台确认口径 07号文§十) */
    private Map<Long, Long> mapFirstItemPerDrug(List<Long> itemIds) {
        Map<Long, Long> map = new LinkedHashMap<>();
        if (itemIds.isEmpty()) {
            return map;
        }
        String ph = String.join(",", java.util.Collections.nCopies(itemIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        args.addAll(itemIds);
        for (Map<String, Object> r : jdbcTemplate.queryForList(
                "SELECT id, drug_id FROM his_prescription_item WHERE tenant_id = ? AND deleted = 0 AND id IN (" + ph + ")",
                args.toArray())) {
            Long drugId = toLongObj(r.get("drug_id"));
            Long id = toLongObj(r.get("id"));
            if (drugId != null && id != null) {
                map.putIfAbsent(drugId, id);
            }
        }
        return map;
    }

    /* ================= 查询 ================= */

    public IPage<HisDrugTraceCode> page(Long orgId, Long locationId, Long drugCatalogId, Integer status,
                                        Integer uploadStatus, String batchNo, String keyword, long page, long size) {
        LambdaQueryWrapper<HisDrugTraceCode> w = Wrappers.<HisDrugTraceCode>lambdaQuery()
                .eq(orgId != null, HisDrugTraceCode::getOrgId, orgId)
                .eq(locationId != null, HisDrugTraceCode::getLocationId, locationId)
                .eq(drugCatalogId != null, HisDrugTraceCode::getDrugCatalogId, drugCatalogId)
                .eq(status != null, HisDrugTraceCode::getStatus, status)
                .eq(uploadStatus != null, HisDrugTraceCode::getUploadStatus, uploadStatus)
                .eq(StringUtils.hasText(batchNo), HisDrugTraceCode::getBatchNo, batchNo)
                .like(StringUtils.hasText(keyword), HisDrugTraceCode::getTraceCode, keyword)
                .orderByDesc(HisDrugTraceCode::getId);
        return traceMapper.selectPage(new Page<>(page, size), w);
    }

    /** 状态/报送统计(库位可选): 供台账页顶部卡片展示 */
    public Map<String, Object> statistics(Long orgId, Long locationId) {
        StringBuilder base = new StringBuilder(
                "SELECT status, upload_status, COUNT(*) cnt FROM his_drug_trace_code"
                        + " WHERE tenant_id = ? AND deleted = 0");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        if (orgId != null) {
            base.append(" AND org_id = ?");
            args.add(orgId);
        }
        if (locationId != null) {
            base.append(" AND location_id = ?");
            args.add(locationId);
        }
        base.append(" GROUP BY status, upload_status");
        Map<String, Object> byStatus = new LinkedHashMap<>();
        long total = 0;
        long uploaded = 0;
        for (Map<String, Object> r : jdbcTemplate.queryForList(base.toString(), args.toArray())) {
            int st = ((Number) r.get("status")).intValue();
            int us = ((Number) r.get("upload_status")).intValue();
            long cnt = ((Number) r.get("cnt")).longValue();
            byStatus.merge("status_" + st, cnt, (a, b) -> ((Number) a).longValue() + ((Number) b).longValue());
            total += cnt;
            if (us == 9) {
                uploaded += cnt;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("uploaded", uploaded);
        out.put("pendingUpload", total - uploaded);
        out.put("byStatus", byStatus);
        return out;
    }

    /* ================= 内部实现 ================= */

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /** 单行查询(无结果返回 null) */
    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rs = jdbcTemplate.queryForList(sql, args);
        return rs.isEmpty() ? null : rs.get(0);
    }

    /** 非空才写入报文节点(规范非必填字段缺失即不送, fastjson2 null 不序列化但空串仍会被送) */
    private static void putIfText(Map<String, Object> map, String key, String val) {
        if (StringUtils.hasText(val)) {
            map.put(key, val);
        }
    }

    private static Integer toIntObj(Object v) {
        return v instanceof Number ? ((Number) v).intValue() : null;
    }

    private static Long toLongObj(Object v) {
        return v instanceof Number ? Long.valueOf(((Number) v).longValue()) : null;
    }

    private boolean exists(String code) {
        return traceMapper.selectCount(Wrappers.<HisDrugTraceCode>lambdaQuery()
                .eq(HisDrugTraceCode::getTraceCode, code)) > 0;
    }

    /** 库位(药库/药房库存位)存在且启用(原生SQL显式租户过滤) */
    private boolean locationExists(Long locationId, Long orgId) {
        Long cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_warehouse_def"
                        + " WHERE id = ? AND tenant_id = ? AND org_id = ? AND status = 1 AND deleted = 0",
                Long.class, locationId, tenantId(), orgId);
        return cnt != null && cnt > 0;
    }

    /** 占位追溯码(演示/无扫码器): 86 开头 20 位数字, 满足格式与唯一性即可(真实码由扫码录入) */
    private String genPlaceholderCode() {
        StringBuilder sb = new StringBuilder("86");
        for (int i = 0; i < 18; i++) {
            sb.append(ThreadLocalRandom.current().nextInt(0, 10));
        }
        return sb.toString();
    }

    private Long currentOrgId() {
        LoginUser u = UserContext.get();
        return u == null ? null : u.getOrgId();
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }
}
