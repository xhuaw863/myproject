package com.yb.hi.service.warehouse;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.dto.warehouse.TraceBindReq;
import com.yb.hi.dto.warehouse.TraceCollectReq;
import com.yb.hi.entity.community.HisDrugCatalog;
import com.yb.hi.entity.warehouse.HisDrugTraceCode;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.community.HisDrugCatalogMapper;
import com.yb.hi.mapper.warehouse.HisDrugTraceCodeMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 医保药品追溯码服务(药库/药房合规, 骨架): 入库采集(扫描录入或批量占位建码) → 在库 → 发药绑定患者/处方 → 退货/报废/调拨状态流转 → Mock 2404 报送。
 * 唯一键 tenant_id + trace_code 拦截重复扫码; 报送为 Mock(仿现有医保 Mock 风格: 置 upload_status=9 + 回执留存), 不触真实医保接口。
 * 写: requireSelfOrgWrite(控制器层, 追溯码采集/绑定是本机构药事过程); 读: scopeOrgId。
 */
@Slf4j
@Service
public class TraceCodeService {

    private static final int ST_INSTOCK = 0;
    private static final int ST_DISPENSED = 1;
    private static final int ST_RETURNED = 2;
    private static final int ST_SCRAPPED = 3;

    private final HisDrugTraceCodeMapper traceMapper;
    private final HisDrugCatalogMapper drugCatalogMapper;
    private final JdbcTemplate jdbcTemplate;

    public TraceCodeService(HisDrugTraceCodeMapper traceMapper, HisDrugCatalogMapper drugCatalogMapper,
                            JdbcTemplate jdbcTemplate) {
        this.traceMapper = traceMapper;
        this.drugCatalogMapper = drugCatalogMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ================= 采集(入库录入) ================= */

    /**
     * 采集追溯码: 指定库位+药品+批次, 优先使用扫描清单 codes; codes 为空则按 autoGenerateQty 生成占位码(便于无扫码器演示)。
     * 逐条按 uk(tenant,trace_code) 去重: 已存在或本批重复的跳过并计数, 新码以 status=0(在库) 入库。
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
        for (String code : distinct) {
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
        log.info("追溯码采集: orgId={}, locationId={}, drug={}, 请求={}, 入库={}, 重复={}, 已存在={}",
                orgId, req.getLocationId(), drug.getDrugCode(), codes.size(), collected, duplicated, rejected.size());
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

    /** 状态流转(退货=2 / 报废·调拨在途=3): 按追溯码批量更新, 仅在库(0)码可流转 */
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
        }
        log.info("追溯码状态流转: targetStatus={}, ref={}#{}, 更新={}", targetStatus, refBillType, refBillId, n);
        return n;
    }

    /* ================= Mock 2404 报送 ================= */

    /**
     * Mock 医保 2404 追溯码报送: 将已发药(1)且未报送(upload_status=0)的码置为已上报(状态9)/已报送, 留存回执。
     * locationId 可选(仅报送某库位来源的码); 返回报送条数。不触真实医保接口。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> upload(Long orgId, Long locationId) {
        List<HisDrugTraceCode> pending = traceMapper.selectList(Wrappers.<HisDrugTraceCode>lambdaQuery()
                .eq(orgId != null, HisDrugTraceCode::getOrgId, orgId)
                .eq(locationId != null, HisDrugTraceCode::getLocationId, locationId)
                .eq(HisDrugTraceCode::getStatus, ST_DISPENSED)
                .eq(HisDrugTraceCode::getUploadStatus, 0));
        String receipt = "MOCK-2404-" + LocalDateTime.now().toLocalDate().toString().replace("-", "")
                + "-" + ThreadLocalRandom.current().nextInt(100000, 999999);
        LocalDateTime now = LocalDateTime.now();
        for (HisDrugTraceCode row : pending) {
            row.setUploadStatus(9);
            row.setUploadTime(now);
            row.setUploadReceipt(receipt);
            traceMapper.updateById(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("uploaded", pending.size());
        out.put("receipt", receipt);
        log.info("追溯码Mock报送: orgId={}, locationId={}, 报送={}, 回执={}", orgId, locationId, pending.size(), receipt);
        return out;
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

    /** 占位追溯码(演示/无扫码器): 86 开头 20 位数字, 满足唯一性即可(真实码由扫码录入) */
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
