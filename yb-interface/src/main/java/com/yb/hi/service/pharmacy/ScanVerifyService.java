package com.yb.hi.service.pharmacy;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.community.HisDrugCatalog;
import com.yb.hi.entity.warehouse.HisDrugTraceCode;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.mapper.community.HisDrugCatalogMapper;
import com.yb.hi.mapper.warehouse.HisDrugTraceCodeMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P3 追溯码发药闭环核心服务: 三码校验(商品码/监管码/追溯码) + 追溯需求计算(窗口级 OR 药品级) + 发药绑定。
 *
 * <p>强制判定: 窗口级 his_pharmacy_window.trace_required=1 → 该单全部药品行需追溯;
 * 药品级 his_drug_catalog.trace_flag=1 → 该行独立需追溯。二者取或。
 *
 * <p>三码解析: 扫追溯码(his_drug_trace_code.trace_code)命中唯一物理码;
 * 扫商品码/监管码(his_drug_catalog.commodity_code/supervision_code)先定位药品, 再自动分配一条该药在库物理码
 * (无扫码器演示与产品级条码场景), 统一返回可绑定的具体追溯码。
 *
 * <p>复用既有 his_drug_trace_code 承载扫描明细(dispense_id/patient_id/visit_id/status/upload_status), 不新增扫描表。
 */
@Slf4j
@Service
public class ScanVerifyService {

    private static final int ST_INSTOCK = 0;
    private static final int ST_DISPENSED = 1;

    private final HisDrugTraceCodeMapper traceMapper;
    private final HisDrugCatalogMapper drugCatalogMapper;
    private final WindowDispatchService windowDispatchService;
    private final JdbcTemplate jdbcTemplate;

    public ScanVerifyService(HisDrugTraceCodeMapper traceMapper, HisDrugCatalogMapper drugCatalogMapper,
                             WindowDispatchService windowDispatchService, JdbcTemplate jdbcTemplate) {
        this.traceMapper = traceMapper;
        this.drugCatalogMapper = drugCatalogMapper;
        this.windowDispatchService = windowDispatchService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ================= 追溯需求计算 ================= */

    /**
     * 计算某处方的追溯需求(供前端扫描框展示与发药守卫复用)。
     * 窗口级信号: windowId 非空按该窗口 trace_required; windowId 空但 pharmacyId 非空则按该药房是否存在追溯强制开窗(供 UI 未分窗前预判)。
     * 药品级 trace_flag=1 的行独立纳入。返回 {traceRequired, windowTrace, lines:[...], allComplete}。
     */
    public Map<String, Object> requirement(Long prescriptionId, Long windowId, List<String> scannedCodes, Long pharmacyId) {
        if (prescriptionId == null) {
            throw new BizException(400, "处方ID不能为空");
        }
        boolean windowTrace = windowDispatchService.isTraceRequired(windowId)
                || (windowId == null && pharmacyHasTraceWindow(pharmacyId));
        List<Map<String, Object>> items = prescriptionItems(prescriptionId);

        // 统计已扫码归属药品(仅追溯码命中物理行才能直接归属; 商品/监管在 verifyOne 时已归一为物理码)
        Map<Long, Integer> scannedByDrug = new LinkedHashMap<>();
        Set<String> distinct = dedupe(scannedCodes);
        for (String code : distinct) {
            HisDrugTraceCode row = findByTraceCode(code);
            if (row != null && row.getDrugCatalogId() != null) {
                scannedByDrug.merge(row.getDrugCatalogId(), 1, Integer::sum);
            }
        }

        List<Map<String, Object>> lines = new ArrayList<>();
        boolean anyDrugTrace = false;
        boolean traceRequired = windowTrace;
        for (Map<String, Object> it : items) {
            Long drugId = toLong(it.get("drug_id"));
            if (drugId == null) {
                continue;
            }
            Integer flag = traceFlagOf(drugId);
            boolean requireTrace = windowTrace || (flag != null && flag == 1);
            if (flag != null && flag == 1) {
                anyDrugTrace = true;
            }
            if (!requireTrace) {
                continue;
            }
            int need = toQty(it.get("quantity"));
            int scanned = scannedByDrug.getOrDefault(drugId, 0);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("drugCatalogId", drugId);
            line.put("drugName", str(it.get("item_name")));
            line.put("needQty", need);
            line.put("scannedQty", scanned);
            line.put("requireTrace", true);
            line.put("complete", scanned >= need);
            lines.add(line);
        }
        traceRequired = traceRequired || anyDrugTrace;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("prescriptionId", prescriptionId);
        out.put("traceRequired", traceRequired);
        out.put("windowTrace", windowTrace);
        out.put("lines", lines);
        out.put("allComplete", computeComplete(lines));
        return out;
    }

    /**
     * 发药守卫: 校验扫描完整性。traceRequired=false 直接放行(返回 null 表示无需追溯)。
     * 需追溯时: 逐条 scannedCode 必须命中在库物理码且归属本处方需追溯药品, 且各需追溯行 scanned>=need。
     * 不通过抛 BizException(阻断); 通过返回已解析归一的物理追溯码清单(供绑定)。
     */
    public List<String> assertComplete(Long prescriptionId, Long windowId, List<String> scannedCodes) {
        Map<String, Object> req = requirement(prescriptionId, windowId, scannedCodes, null);
        boolean traceRequired = Boolean.TRUE.equals(req.get("traceRequired"));
        if (!traceRequired) {
            return new ArrayList<>();
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) req.get("lines");
        // 逐码校验归属与在库
        Map<Long, Integer> scannedByDrug = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        List<String> physical = new ArrayList<>();
        for (String raw : dedupeList(scannedCodes)) {
            HisDrugTraceCode row = findByTraceCode(raw);
            if (row == null) {
                throw new BizException("追溯码不存在或不在库: " + raw);
            }
            if (row.getStatus() == null || row.getStatus() != ST_INSTOCK) {
                throw new BizException("追溯码非在库状态, 不可发药绑定: " + raw);
            }
            if (!seen.add(row.getTraceCode())) {
                throw new BizException("追溯码重复扫描: " + raw);
            }
            Long drugId = row.getDrugCatalogId();
            boolean belongs = false;
            if (lines != null) {
                for (Map<String, Object> ln : lines) {
                    if (drugId != null && drugId.equals(toLong(ln.get("drugCatalogId")))) {
                        belongs = true;
                        break;
                    }
                }
            }
            if (!belongs) {
                throw new BizException("追溯码对应药品不在本处方需追溯清单: " + raw);
            }
            scannedByDrug.merge(drugId, 1, Integer::sum);
            physical.add(row.getTraceCode());
        }
        // 逐行完整性
        List<String> shortage = new ArrayList<>();
        if (lines != null) {
            for (Map<String, Object> ln : lines) {
                int need = toInt(ln.get("needQty"));
                int scanned = scannedByDrug.getOrDefault(toLong(ln.get("drugCatalogId")), 0);
                if (scanned < need) {
                    shortage.add(str(ln.get("drugName")) + " 需" + need + "/已扫" + scanned);
                }
            }
        }
        if (!shortage.isEmpty()) {
            throw new BizException("追溯码扫描不完整, 无法发药: " + String.join("; ", shortage));
        }
        return physical;
    }

    /* ================= 单码校验(前端实时扫描) ================= */

    /**
     * 校验单个扫描码(商品码/监管码/追溯码三码之一), 归一为可绑定物理追溯码。
     * 返回 {ok, codeType, traceCode, drugCatalogId, drugName, batchNo, message}。
     * excludeCodes: 本次已分配/已扫物理码, 商品码自动分配时排除, 避免重复占用。
     */
    public Map<String, Object> verifyOne(String code, Long prescriptionId, Set<String> excludeCodes) {
        Map<String, Object> out = new LinkedHashMap<>();
        String c = code == null ? "" : code.trim();
        if (c.isEmpty()) {
            return fail(out, "扫描码不能为空");
        }
        // 1. 追溯码(物理码)优先
        HisDrugTraceCode row = findByTraceCode(c);
        if (row != null) {
            if (row.getStatus() == null || row.getStatus() != ST_INSTOCK) {
                return fail(out, "该追溯码非在库状态(已发药/退货/报废): " + c);
            }
            if (!inPrescription(row.getDrugCatalogId(), prescriptionId)) {
                return fail(out, "该追溯码对应药品不在本处方: " + c);
            }
            return ok(out, "trace", row);
        }
        // 2. 商品码 / 监管码 → 定位药品目录, 再分配一条在库物理码
        HisDrugCatalog drug = findByProductCode(c);
        if (drug == null) {
            return fail(out, "未识别的编码(非追溯码/商品码/监管码): " + c);
        }
        if (!inPrescription(drug.getId(), prescriptionId)) {
            return fail(out, "该药品不在本处方: " + drug.getGenericName());
        }
        HisDrugTraceCode alloc = allocateInstock(drug.getId(), excludeCodes);
        if (alloc == null) {
            return fail(out, "该药品无可用在库追溯码, 请先在药库/药房采集追溯码: " + drug.getGenericName());
        }
        String type = c.equals(drug.getCommodityCode()) ? "commodity" : "supervision";
        return ok(out, type, alloc);
    }

    /* ================= 发药绑定 ================= */

    /**
     * 发药后绑定: 将在库(status=0)物理追溯码置为已发药(1), 绑定 dispense/patient/visit, ref_bill_type=dispense。
     * 返回成功绑定条数(供 his_dispense.trace_scanned 落账)。
     */
    @Transactional(rollbackFor = Exception.class)
    public int bindForDispense(Long dispenseId, Long patientId, Long visitId, List<String> traceCodes) {
        if (traceCodes == null || traceCodes.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (String c : dedupeList(traceCodes)) {
            HisDrugTraceCode row = findByTraceCode(c);
            if (row == null || row.getStatus() == null || row.getStatus() != ST_INSTOCK) {
                continue;
            }
            row.setStatus(ST_DISPENSED);
            row.setDispenseId(dispenseId);
            row.setPatientId(patientId);
            row.setVisitId(visitId);
            row.setRefBillType("dispense");
            row.setRefBillId(dispenseId);
            traceMapper.updateById(row);
            n++;
        }
        log.info("P3 追溯码发药绑定: dispenseId={}, 请求={}, 绑定={}", dispenseId, traceCodes.size(), n);
        return n;
    }

    /* ================= 内部实现 ================= */

    /** 药房是否存在开启态追溯强制窗口(供 UI 未分窗前预判窗口级追溯) */
    private boolean pharmacyHasTraceWindow(Long pharmacyId) {
        if (pharmacyId == null) {
            return false;
        }
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_pharmacy_window"
                        + " WHERE tenant_id = ? AND pharmacy_id = ? AND trace_required = 1 AND open_status = 1 AND status = 1 AND deleted = 0",
                Long.class, tenantId(), pharmacyId);
        return n != null && n > 0;
    }

    private List<Map<String, Object>> prescriptionItems(Long prescriptionId) {
        return jdbcTemplate.queryForList(
                "SELECT drug_id, item_name, quantity FROM his_prescription_item"
                        + " WHERE prescription_id = ? AND tenant_id = ? AND drug_id IS NOT NULL AND deleted = 0 ORDER BY id",
                prescriptionId, tenantId());
    }

    private Integer traceFlagOf(Long drugCatalogId) {
        HisDrugCatalog d = drugCatalogMapper.selectById(drugCatalogId);
        return d == null ? null : d.getTraceFlag();
    }

    private boolean inPrescription(Long drugCatalogId, Long prescriptionId) {
        if (drugCatalogId == null || prescriptionId == null) {
            return false;
        }
        for (Map<String, Object> it : prescriptionItems(prescriptionId)) {
            if (drugCatalogId.equals(toLong(it.get("drug_id")))) {
                return true;
            }
        }
        return false;
    }

    private HisDrugTraceCode findByTraceCode(String code) {
        List<HisDrugTraceCode> rows = traceMapper.selectList(Wrappers.<HisDrugTraceCode>lambdaQuery()
                .eq(HisDrugTraceCode::getTraceCode, code)
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private HisDrugCatalog findByProductCode(String code) {
        List<HisDrugCatalog> rows = drugCatalogMapper.selectList(Wrappers.<HisDrugCatalog>lambdaQuery()
                .and(w -> w.eq(HisDrugCatalog::getCommodityCode, code).or().eq(HisDrugCatalog::getSupervisionCode, code))
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 为指定药品分配一条在库且未被本次占用的物理追溯码 */
    private HisDrugTraceCode allocateInstock(Long drugCatalogId, Set<String> excludeCodes) {
        List<HisDrugTraceCode> rows = traceMapper.selectList(Wrappers.<HisDrugTraceCode>lambdaQuery()
                .eq(HisDrugTraceCode::getDrugCatalogId, drugCatalogId)
                .eq(HisDrugTraceCode::getStatus, ST_INSTOCK)
                .orderByAsc(HisDrugTraceCode::getId)
                .last("LIMIT 200"));
        for (HisDrugTraceCode row : rows) {
            if (excludeCodes == null || !excludeCodes.contains(row.getTraceCode())) {
                return row;
            }
        }
        return null;
    }

    private Map<String, Object> ok(Map<String, Object> out, String codeType, HisDrugTraceCode row) {
        HisDrugCatalog d = row.getDrugCatalogId() == null ? null : drugCatalogMapper.selectById(row.getDrugCatalogId());
        out.put("ok", true);
        out.put("codeType", codeType);
        out.put("traceCode", row.getTraceCode());
        out.put("drugCatalogId", row.getDrugCatalogId());
        out.put("drugName", d != null ? firstNonEmpty(d.getGenericName(), row.getDrugCode()) : row.getDrugCode());
        out.put("batchNo", row.getBatchNo());
        out.put("message", "追溯码校验通过");
        return out;
    }

    private Map<String, Object> fail(Map<String, Object> out, String message) {
        out.put("ok", false);
        out.put("message", message);
        return out;
    }

    private boolean computeComplete(List<Map<String, Object>> lines) {
        if (lines == null || lines.isEmpty()) {
            return true;
        }
        for (Map<String, Object> ln : lines) {
            if (!Boolean.TRUE.equals(ln.get("complete"))) {
                return false;
            }
        }
        return true;
    }

    private Set<String> dedupe(List<String> codes) {
        Set<String> s = new HashSet<>();
        if (codes != null) {
            for (String c : codes) {
                if (StringUtils.hasText(c)) {
                    s.add(c.trim());
                }
            }
        }
        return s;
    }

    private List<String> dedupeList(List<String> codes) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (codes != null) {
            for (String c : codes) {
                if (StringUtils.hasText(c) && seen.add(c.trim())) {
                    out.add(c.trim());
                }
            }
        }
        return out;
    }

    private static int toQty(Object o) {
        if (o == null) {
            return 0;
        }
        if (o instanceof BigDecimal) {
            return ((BigDecimal) o).setScale(0, java.math.RoundingMode.CEILING).intValue();
        }
        if (o instanceof Number) {
            return (int) Math.ceil(((Number) o).doubleValue());
        }
        try {
            return (int) Math.ceil(Double.parseDouble(o.toString()));
        } catch (Exception e) {
            return 0;
        }
    }

    private static int toInt(Object o) {
        if (o == null) {
            return 0;
        }
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        try {
            return Integer.parseInt(o.toString());
        } catch (Exception e) {
            return 0;
        }
    }

    private static Long toLong(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        try {
            return Long.parseLong(o.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String firstNonEmpty(String a, String b) {
        return StringUtils.hasText(a) ? a : (b == null ? "" : b);
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }
}
