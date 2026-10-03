package com.yb.hi.service.doctor;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.common.DateUtil;
import com.yb.hi.dto.doctor.PrescriptionBatchReq;
import com.yb.hi.dto.doctor.PrescriptionReq;
import com.yb.hi.entity.doctor.HisDiagnosis;
import com.yb.hi.entity.doctor.HisPrescription;
import com.yb.hi.entity.doctor.HisPrescriptionItem;
import com.yb.hi.entity.doctor.HisVisit;
import com.yb.hi.entity.outpatient.HisPatient;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.service.emr.EmrDocumentService;
import com.yb.hi.service.pharmacy.PharmacyDefService;
import com.yb.hi.service.pharmacy.PharmacyPriceService;
import com.yb.hi.mapper.doctor.HisPrescriptionItemMapper;
import com.yb.hi.mapper.doctor.HisPrescriptionMapper;
import com.yb.hi.mapper.outpatient.HisPatientMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 处方服务: 开方编排(主表+明细, 自动补全患者/科室/医师/金额/处方号)
 */
@Slf4j
@Service
public class HisPrescriptionService extends ServiceImpl<HisPrescriptionMapper, HisPrescription> {

    private static final AtomicInteger SEQ = new AtomicInteger(0);

    private final HisVisitService visitService;
    private final HisDiagnosisService diagnosisService;
    private final HisPrescriptionItemMapper itemMapper;
    private final HisPatientMapper patientMapper;
    private final PharmacyDefService pharmacyDefService;
    private final PharmacyPriceService pharmacyPriceService;
    private final HisOrderFreqService orderFreqService;
    /* P8a-2 草药方引用: JdbcTemplate 直查/直写显式携带 tenant_id(绕开租户插件);
     * 病历 content 为 AES-GCM 密文轨, 追加段落须经 EmrDocumentService 加解密 */
    private final JdbcTemplate jdbcTemplate;
    private final EmrDocumentService emrDocumentService;

    public HisPrescriptionService(HisVisitService visitService, HisDiagnosisService diagnosisService,
                                  HisPrescriptionItemMapper itemMapper, HisPatientMapper patientMapper,
                                  PharmacyDefService pharmacyDefService, PharmacyPriceService pharmacyPriceService,
                                  HisOrderFreqService orderFreqService, JdbcTemplate jdbcTemplate,
                                  EmrDocumentService emrDocumentService) {
        this.visitService = visitService;
        this.diagnosisService = diagnosisService;
        this.itemMapper = itemMapper;
        this.patientMapper = patientMapper;
        this.pharmacyDefService = pharmacyDefService;
        this.pharmacyPriceService = pharmacyPriceService;
        this.orderFreqService = orderFreqService;
        this.jdbcTemplate = jdbcTemplate;
        this.emrDocumentService = emrDocumentService;
    }

    /** 查询某次就诊的处方列表 */
    public List<HisPrescription> listByVisit(Long visitId) {
        return lambdaQuery().eq(HisPrescription::getVisitId, visitId).orderByDesc(HisPrescription::getId).list();
    }

    /** 查询处方明细 */
    public List<HisPrescriptionItem> listItems(Long prescriptionId) {
        return itemMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<HisPrescriptionItem>()
                .eq("prescription_id", prescriptionId).eq("deleted", 0).orderByAsc("id"));
    }

    /** 仅未收费且未发药的处方允许作废(收费/发药状态取所属就诊与处方实际链路字段)。 */
    @Transactional(rollbackFor = Exception.class)
    public HisPrescription cancel(Long id) {
        HisPrescription rx = getById(id);
        if (rx == null) {
            throw new BizException(400, "处方不存在");
        }
        if (rx.getStatus() != null && rx.getStatus() < 0) {
            throw new BizException("该处方已作废, 请勿重复操作");
        }
        // 医生站科室判权(B4)
        visitService.requireVisitScope(rx.getVisitId());
        // 处方自身 status 只在开立/作废间变迁, 不能用来判断收费; 收费看就诊 charge_status, 发药看 dispense_status
        Integer dispenseStatus = rx.getDispenseStatus();
        if (dispenseStatus != null && dispenseStatus != 0) {
            throw new BizException("该处方已发药或已退药(发药状态:" + dispenseStatus + "), 不可作废");
        }
        HisVisit visit = rx.getVisitId() == null ? null : visitService.getById(rx.getVisitId());
        if (visit != null && visit.getChargeStatus() != null && visit.getChargeStatus() != 0) {
            throw new BizException("该处方所属就诊已收费或已退费(收费状态:" + visit.getChargeStatus() + "), 请先退费再作废");
        }
        rx.setStatus(-1);
        updateById(rx);
        log.info("处方作废: id={}, rxNo={}, visitId={}", rx.getId(), rx.getRxNo(), rx.getVisitId());
        return rx;
    }

    /**
     * 处方笺打印数据: 前记(医院/患者/诊断)、正文(处方明细)、后记(医师/金额)，
     * 同时保留各原始对象字段，便于不同打印模板按需排版。
     */
    public Map<String, Object> printData(Long id) {
        HisPrescription rx = getById(id);
        if (rx == null) {
            throw new BizException(400, "处方不存在");
        }
        List<HisPrescriptionItem> items = listItems(id);
        HisPatient patient = rx.getPatientId() == null ? null : patientMapper.selectById(rx.getPatientId());
        List<HisDiagnosis> diagnoses = diagnosisService.listByVisit(rx.getVisitId());
        LoginUser user = UserContext.get();
        String hospitalName = user == null ? null : user.getTenantName();

        Map<String, Object> preface = new LinkedHashMap<>();
        preface.put("hospitalName", hospitalName);
        preface.put("prescription", rx);
        preface.put("patient", patient);
        preface.put("diagnoses", diagnoses);

        Map<String, Object> postscript = new LinkedHashMap<>();
        postscript.put("deptName", rx.getDeptName());
        postscript.put("doctorName", rx.getDrName());
        postscript.put("totalAmount", rx.getTotalAmount());
        postscript.put("createTime", rx.getCreateTime());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hospitalName", hospitalName);
        result.put("prescription", rx);
        result.put("items", items);
        result.put("patient", patient);
        result.put("diagnoses", diagnoses);
        result.put("preface", preface);
        result.put("body", items);
        result.put("postscript", postscript);
        return result;
    }

    /**
     * 开处方: 校验就诊 -> 补全主表 -> 计算金额 -> 落库主表+明细
     */
    @Transactional(rollbackFor = Exception.class)
    public HisPrescription create(PrescriptionReq req) {
        if (req == null || req.getVisitId() == null) {
            throw new BizException(400, "就诊ID不能为空");
        }
        if (CollectionUtils.isEmpty(req.getItems())) {
            throw new BizException("处方明细不能为空");
        }
        HisVisit visit = visitService.getById(req.getVisitId());
        if (visit == null) {
            throw new BizException(400, "就诊记录不存在");
        }
        // 医生站科室判权(B4)
        visitService.requireVisitScope(req.getVisitId());

        // OP-C 草药专业化(需求2.2.2.3.14.3): 命中 multiple_base 的单味剂量服务端强制整倍校验
        validateHerbMultiples(req.getItems());

        HisPrescription p = new HisPrescription();
        p.setVisitId(visit.getId());
        p.setRxNo(genNo("RX"));
        p.setPatientId(visit.getPatientId());
        p.setPatientName(visit.getPatientName());
        p.setDeptId(visit.getDeptId());
        p.setDeptName(visit.getDeptName());
        p.setDrId(visit.getStaffId());
        p.setDrName(visit.getDrName());
        String rxType = StringUtils.hasText(req.getRxType()) ? req.getRxType() : "西药";
        p.setRxType(rxType);
        p.setDiagName(buildDiagName(visit.getId()));
        p.setStatus(1);
        // P2 门诊药审: 含药品明细的处方开立即进入待审队列(audit_status=1), 纯非药品处方无需审方(0)
        boolean hasDrugItem = req.getItems().stream().anyMatch(i -> i.getDrugId() != null);
        p.setAuditStatus(hasDrugItem ? 1 : 0);

        // 发药药房(三期): 医生手选优先并校验归属/启停; 未手选按科室×中西药渠道默认回落; 均无则不绑(发药全院FIFO兼容存量)
        Long pharmacyId = req.getPharmacyId();
        if (pharmacyId != null) {
            LoginUser lu = UserContext.get();
            pharmacyDefService.requireEnabled(pharmacyId, lu == null ? null : lu.getOrgId());
        } else {
            pharmacyId = pharmacyDefService.resolveDefaultPharmacyId(visit.getDeptId(), rxType);
        }
        p.setPharmacyId(pharmacyId);

        // 服务端重算价(批次驱动): 药品行按发药药房 FIFO 批次加权价覆盖前端传价(不信任客户端),
        // 在库不足/无库存位回落目录零售价; 非药品行(drugId 空)保持原价。数量口径=最小单位, 与明细 quantity 一致。
        Map<Long, BigDecimal> qtyByDrug = new java.util.LinkedHashMap<>();
        for (HisPrescriptionItem it : req.getItems()) {
            if (it.getDrugId() != null) {
                BigDecimal q = it.getQuantity() == null ? BigDecimal.ZERO : it.getQuantity();
                qtyByDrug.merge(it.getDrugId(), q, BigDecimal::add);
            }
        }
        Map<Long, BigDecimal> chargePrices = pharmacyPriceService.chargePriceBatch(pharmacyId, qtyByDrug);

        BigDecimal total = BigDecimal.ZERO;
        for (HisPrescriptionItem item : req.getItems()) {
            item.setId(null);
            BigDecimal price = item.getPrice() == null ? BigDecimal.ZERO : item.getPrice();
            if (item.getDrugId() != null) {
                BigDecimal eff = chargePrices.get(item.getDrugId());
                if (eff != null) {
                    price = eff;
                    item.setPrice(eff);
                }
            }
            BigDecimal qty = item.getQuantity() == null ? BigDecimal.ZERO : item.getQuantity();
            BigDecimal amount = price.multiply(qty).setScale(2, BigDecimal.ROUND_HALF_UP);
            item.setAmount(amount);
            total = total.add(amount);
        }
        p.setTotalAmount(total.setScale(2, BigDecimal.ROUND_HALF_UP));
        save(p);

        for (HisPrescriptionItem item : req.getItems()) {
            item.setPrescriptionId(p.getId());
            itemMapper.insert(item);
        }
        // OP-C 医嘱处方高频沉淀: 开立成功后按个人/科室累计药品项目频次(best-effort, 不阻断开方)
        recordRxUsage(req.getItems(), visit.getStaffId(), visit.getDeptId());
        log.info("开处方成功: rxNo={}, visitId={}, total={}, pharmacyId={}", p.getRxNo(), visit.getId(), p.getTotalAmount(), pharmacyId);
        return p;
    }

    /**
     * 批量开处方(C7 拆方原子性): 一次请求一个事务开立全部批次(中药饮片自动拆方),
     * 任一批失败整体回滚 —— 杜绝前端分批串行提交中途失败重试导致已成功批次重复开立。
     * 逐批复用 create()(判权/药房回落/服务端重算价/处方号), 外层事务覆盖全部批次。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<HisPrescription> createBatch(PrescriptionBatchReq req) {
        if (req == null || req.getVisitId() == null) {
            throw new BizException(400, "就诊ID不能为空");
        }
        if (CollectionUtils.isEmpty(req.getBatches())) {
            throw new BizException("处方批次不能为空");
        }
        List<HisPrescription> out = new ArrayList<>();
        for (PrescriptionReq batch : req.getBatches()) {
            PrescriptionReq sub = new PrescriptionReq();
            sub.setVisitId(req.getVisitId());
            sub.setRxType(batch.getRxType());
            sub.setPharmacyId(req.getPharmacyId());
            sub.setItems(batch.getItems());
            out.add(create(sub));
        }
        log.info("批量开方成功: visitId={}, 处方数={}", req.getVisitId(), out.size());
        return out;
    }

    /** 草药倍数校验: multiple_base>0 时, 单味剂量(quantity)须为其正整数倍, 否则拒绝保存。 */
    private void validateHerbMultiples(List<HisPrescriptionItem> items) {
        for (HisPrescriptionItem it : items) {
            Integer base = it.getMultipleBase();
            if (base == null || base <= 0) {
                continue;
            }
            BigDecimal qty = it.getQuantity();
            if (qty == null) {
                continue;
            }
            BigDecimal[] dm = qty.divideAndRemainder(new BigDecimal(base));
            if (dm[1].compareTo(BigDecimal.ZERO) != 0 || dm[0].compareTo(BigDecimal.ZERO) <= 0) {
                throw new BizException(400, "药材[" + it.getItemName() + "]剂量必须为 " + base + " 的整数倍, 当前:" + qty.stripTrailingZeros().toPlainString());
            }
        }
    }

    /** 处方高频累计: 药品项目按 itemCode 记录(个人/科室), 异常仅告警不影响开方事务。 */
    private void recordRxUsage(List<HisPrescriptionItem> items, Long staffId, Long deptId) {
        try {
            List<HisOrderFreqService.FreqKey> keys = new ArrayList<>();
            for (HisPrescriptionItem it : items) {
                if (StringUtils.hasText(it.getItemCode())) {
                    keys.add(new HisOrderFreqService.FreqKey(it.getItemCode(), it.getItemName(), "rx"));
                }
            }
            orderFreqService.recordUsage(keys, staffId, deptId);
        } catch (Exception e) {
            log.warn("处方高频累计失败(不影响开方): {}", e.getMessage());
        }
    }

    /** 汇总就诊诊断名称 */
    private String buildDiagName(Long visitId) {
        List<HisDiagnosis> ds = diagnosisService.listByVisit(visitId);
        if (CollectionUtils.isEmpty(ds)) {
            return null;
        }
        return ds.stream().map(HisDiagnosis::getDiagName)
                .filter(StringUtils::hasText).collect(Collectors.joining(","));
    }

    private String genNo(String prefix) {
        int s = SEQ.incrementAndGet() % 1000;
        return prefix + DateUtil.currentTimeCompact() + String.format("%03d", s);
    }

    /* ================= P8a-2: 草药方引用(病历引用草药处方) ================= */

    /** 草药处方时间格式化口径 */
    private static final DateTimeFormatter HERB_DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 查询患者当前住院/门诊的草药处方列表(P8a-2, 供病历编辑器"引用草药方"选择)。
     *
     * 检索口径: 按患者维度取草药方(rx_type 含 中药/草药/饮片, 即门诊开方落库的"中药饮片处方"等标识),
     * 排除已作废(status<0), 限近 50 条。visitId 非空时先精确过滤(his_prescription.visit_id 为门诊
     * 就诊ID, 门诊场景直接命中); 未命中回退患者全量 —— 住院病历引用时前端传的是 inpVisitId(住院就诊ID),
     * 与门诊 visit_id 不同源, 等值过滤必然空, 回退保证跨场景可用。
     *
     * 返回行: {prescriptionId, id, rxNo, rxName, formulaName, rxType, drName, deptName, createTime, herbCount}
     */
    public List<Map<String, Object>> listHerbFormulas(Long patientId, Long visitId) {
        if (patientId == null) {
            throw new BizException(400, "患者ID不能为空");
        }
        Long tid = TenantContext.require();
        String baseSql = "SELECT p.id, p.rx_no, p.rx_type, p.patient_name, p.dr_name, p.dept_name, p.create_time, "
                + "(SELECT COUNT(*) FROM his_prescription_item i "
                + " WHERE i.prescription_id = p.id AND i.deleted = 0) AS herb_count "
                + "FROM his_prescription p "
                + "WHERE p.tenant_id = ? AND p.deleted = 0 AND p.patient_id = ? "
                + "AND (p.status IS NULL OR p.status >= 0) "
                + "AND (p.rx_type LIKE '%中药%' OR p.rx_type LIKE '%草药%' OR p.rx_type LIKE '%饮片%')";
        try {
            if (visitId != null) {
                List<Map<String, Object>> byVisit = jdbcTemplate.query(
                        baseSql + " AND p.visit_id = ? ORDER BY p.create_time DESC, p.id DESC LIMIT 50",
                        (rs, i) -> herbFormulaRow(rs), tid, patientId, visitId);
                if (!byVisit.isEmpty()) {
                    return byVisit;
                }
            }
            return jdbcTemplate.query(
                    baseSql + " ORDER BY p.create_time DESC, p.id DESC LIMIT 50",
                    (rs, i) -> herbFormulaRow(rs), tid, patientId);
        } catch (BadSqlGrammarException e) {
            log.warn("草药处方查询失败(表/列缺失): patientId={}, {}", patientId, e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 草药处方行映射(前端 inp-emr-writer.js 预埋字段兼容: prescriptionId/id 双键 + formulaName/rxName 双名) */
    private static Map<String, Object> herbFormulaRow(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        Long id = rs.getLong("id");
        String rxNo = rs.getString("rx_no");
        java.sql.Timestamp ts = rs.getTimestamp("create_time");
        m.put("prescriptionId", id);
        m.put("id", id);
        m.put("rxNo", rxNo);
        m.put("rxName", "草药方 " + (rxNo == null ? String.valueOf(id) : rxNo));
        m.put("formulaName", "草药方 " + (rxNo == null ? String.valueOf(id) : rxNo));
        m.put("rxType", rs.getString("rx_type"));
        m.put("drName", rs.getString("dr_name"));
        m.put("deptName", rs.getString("dept_name"));
        m.put("createTime", ts == null ? null : ts.toLocalDateTime());
        m.put("herbCount", rs.getInt("herb_count"));
        return m;
    }

    /**
     * 将草药处方转为病历引用文本格式(P8a-2):
     * 【草药方】处方号（处方类型） + 开方信息行 + 药味明细(每味一行: 药名 剂量 [煎法/炮制标注])
     * + 用法 + 服法(X剂 频次), 含膏方说明(药名/药剂形式含"膏"时)。
     */
    public String formulaToText(Long prescriptionId) {
        if (prescriptionId == null) {
            throw new BizException(400, "处方ID不能为空");
        }
        Long tid = TenantContext.require();
        try {
            List<Map<String, Object>> heads = jdbcTemplate.query(
                    "SELECT id, rx_no, rx_type, dr_name, dept_name, create_time "
                            + "FROM his_prescription WHERE id = ? AND tenant_id = ? AND deleted = 0",
                    (rs, i) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getLong("id"));
                        m.put("rxNo", rs.getString("rx_no"));
                        m.put("rxType", rs.getString("rx_type"));
                        m.put("drName", rs.getString("dr_name"));
                        m.put("deptName", rs.getString("dept_name"));
                        java.sql.Timestamp ts = rs.getTimestamp("create_time");
                        m.put("createTime", ts == null ? null : ts.toLocalDateTime());
                        return m;
                    }, prescriptionId, tid);
            if (heads.isEmpty()) {
                throw new BizException(400, "草药处方不存在: " + prescriptionId);
            }
            List<Map<String, Object>> items = jdbcTemplate.query(
                    "SELECT item_name, dosage, dosage_unit, quantity, unit, usage_method, frequency, days, "
                            + "decoction, processing, herb_form "
                            + "FROM his_prescription_item WHERE prescription_id = ? AND tenant_id = ? AND deleted = 0 "
                            + "ORDER BY id",
                    (rs, i) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("itemName", rs.getString("item_name"));
                        m.put("dosage", rs.getString("dosage"));
                        m.put("dosageUnit", rs.getString("dosage_unit"));
                        m.put("quantity", rs.getBigDecimal("quantity"));
                        m.put("unit", rs.getString("unit"));
                        m.put("usageMethod", rs.getString("usage_method"));
                        m.put("frequency", rs.getString("frequency"));
                        m.put("days", rs.getObject("days"));
                        m.put("decoction", rs.getString("decoction"));
                        m.put("processing", rs.getString("processing"));
                        m.put("herbForm", rs.getString("herb_form"));
                        return m;
                    }, prescriptionId, tid);
            return buildFormulaText(heads.get(0), items);
        } catch (BadSqlGrammarException e) {
            log.warn("草药处方文本化失败(表/列缺失): prescriptionId={}, {}", prescriptionId, e.getMessage());
            throw new BizException(500, "处方表结构异常, 无法生成草药方文本");
        }
    }

    /** 草药方文本拼装(任务规范格式; 明细剂量优先单次剂量 dosage+单位, 回退数量 quantity+单位) */
    private static String buildFormulaText(Map<String, Object> head, List<Map<String, Object>> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("【草药方】").append(str(head.get("rxNo")) != null ? str(head.get("rxNo")) : str(head.get("id")));
        String rxType = str(head.get("rxType"));
        if (rxType != null) {
            sb.append("（").append(rxType).append("）");
        }
        sb.append('\n');
        LocalDateTime ct = (LocalDateTime) head.get("createTime");
        if (ct != null || str(head.get("drName")) != null || str(head.get("deptName")) != null) {
            sb.append("开方信息：");
            if (ct != null) {
                sb.append(ct.format(HERB_DT));
            }
            if (str(head.get("drName")) != null) {
                sb.append("  医师：").append(str(head.get("drName")));
            }
            if (str(head.get("deptName")) != null) {
                sb.append("  科室：").append(str(head.get("deptName")));
            }
            sb.append('\n');
        }
        sb.append("药味明细").append(items.isEmpty() ? "：" : "（共" + items.size() + "味）：").append('\n');
        for (Map<String, Object> it : items) {
            sb.append("- ").append(str(it.get("itemName")) != null ? str(it.get("itemName")) : "-");
            String dose = herbDoseOf(it);
            if (dose != null) {
                sb.append(' ').append(dose);
            }
            String note = herbNoteOf(it);
            if (note != null) {
                sb.append(note);
            }
            sb.append('\n');
        }
        String usage = str(firstHerbItem(items, "usageMethod"));
        sb.append("用法：").append(usage != null ? usage : "水煎服").append('\n');
        String freq = str(firstHerbItem(items, "frequency"));
        Object days = firstHerbItem(items, "days");
        sb.append("服法：");
        if (days instanceof Number && ((Number) days).intValue() > 0) {
            sb.append(((Number) days).intValue()).append("剂 ");
        }
        sb.append(freq != null ? freq : "遵医嘱").append('\n');
        if (isGaoFang(items)) {
            sb.append("（膏方：本方含膏类用药，按膏方熬制工艺制备，遵医嘱服用）\n");
        }
        return sb.toString();
    }

    /** 单味剂量文本: 优先单次剂量 dosage+dosageUnit(如 30g), 回退数量 quantity+unit */
    private static String herbDoseOf(Map<String, Object> it) {
        String dosage = str(it.get("dosage"));
        if (dosage != null) {
            String unit = str(it.get("dosageUnit"));
            return dosage + (unit != null ? unit : "g");
        }
        BigDecimal qty = (BigDecimal) it.get("quantity");
        if (qty != null) {
            String unit = str(it.get("unit"));
            return qty.stripTrailingZeros().toPlainString() + (unit != null ? unit : "g");
        }
        return null;
    }

    /** 煎法/炮制标注(如 （先煎/酒制）; 两项均缺返回 null) */
    private static String herbNoteOf(Map<String, Object> it) {
        String decoction = str(it.get("decoction"));
        String processing = str(it.get("processing"));
        if (decoction == null && processing == null) {
            return null;
        }
        StringBuilder note = new StringBuilder("（");
        if (decoction != null) {
            note.append(decoction);
        }
        if (processing != null) {
            if (decoction != null) {
                note.append('/');
            }
            note.append(processing);
        }
        return note.append('）').toString();
    }

    /** 膏方判定: 任一味药剂形式或药名含"膏" */
    private static boolean isGaoFang(List<Map<String, Object>> items) {
        for (Map<String, Object> it : items) {
            String form = str(it.get("herbForm"));
            String name = str(it.get("itemName"));
            if ((form != null && form.contains("膏")) || (name != null && name.contains("膏"))) {
                return true;
            }
        }
        return false;
    }

    /** 首条非空明细字段值(用法/频次/天数等处方级语义取首味口径) */
    private static Object firstHerbItem(List<Map<String, Object>> items, String key) {
        for (Map<String, Object> it : items) {
            Object v = it.get(key);
            if (v != null && StringUtils.hasText(String.valueOf(v))) {
                return v;
            }
        }
        return null;
    }

    /**
     * 将草药方引用插入病历 content(P8a-2):
     * 读取 his_inp_medical_record(优先明文 structure_data, 否则解密 content)→ 解析 Tiptap JSON
     * → 在文档 content 数组末尾按行追加 paragraph 节点 → 重新加密回写 content + structure_data。
     * 仅草稿态病历可插入(与病历编辑权限口径一致); 老库 structure_data 列缺失/明文兼容均逐段兑底。
     */
    @Transactional(rollbackFor = Exception.class)
    public void insertFormulaToRecord(Long recordId, Long prescriptionId) {
        if (recordId == null) {
            throw new BizException(400, "病历记录ID不能为空");
        }
        if (prescriptionId == null) {
            throw new BizException(400, "处方ID不能为空");
        }
        Long tid = TenantContext.require();
        String text = formulaToText(prescriptionId);
        try {
            List<Map<String, Object>> rows = jdbcTemplate.query(
                    "SELECT content, status FROM his_inp_medical_record "
                            + "WHERE id = ? AND tenant_id = ? AND deleted = 0",
                    (rs, i) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("content", rs.getString("content"));
                        m.put("status", rs.getObject("status"));
                        return m;
                    }, recordId, tid);
            if (rows.isEmpty()) {
                throw new BizException(400, "病历记录不存在: " + recordId);
            }
            Object status = rows.get(0).get("status");
            if (status instanceof Number && ((Number) status).intValue() != 1) {
                throw new BizException("仅草稿状态的病历可插入草药方引用, 当前状态: " + status);
            }
            String content = str(rows.get(0).get("content"));
            String structure = queryStructureDataQuietly(recordId, tid);
            /* 明文优先(双轨明文 structure_data), 否则解密 content(历史明文兼容透传) */
            String plain = StringUtils.hasText(structure) ? structure : emrDocumentService.decrypt(content);
            if (!StringUtils.hasText(plain)) {
                plain = "{\"type\":\"doc\",\"content\":[]}";   /* 空白病历构造空文档骨架 */
            }
            String newJson = appendFormulaParagraphs(plain, text);
            /* 存量明文病历(以 {/[ 开头且从未加密)保持明文回写, 其余统一加密落库 */
            boolean wasPlain = looksLikeJson(content);
            String newContent = wasPlain ? newJson : emrDocumentService.encrypt(newJson);
            jdbcTemplate.update(
                    "UPDATE his_inp_medical_record SET content = ?, update_time = NOW() "
                            + "WHERE id = ? AND tenant_id = ?",
                    newContent, recordId, tid);
            updateStructureDataQuietly(recordId, tid, newJson);
            log.info("草药方引用插入病历: recordId={}, prescriptionId={}, 段落数={}",
                    recordId, prescriptionId, countLines(text));
        } catch (BadSqlGrammarException e) {
            log.warn("草药方引用插入病历失败(表/列缺失): recordId={}, {}", recordId, e.getMessage());
            throw new BizException(500, "病历表结构异常, 草药方引用插入失败");
        }
    }

    /** 读取病历明文轨 structure_data(列缺失/无值返回 null, 不阻断主链路) */
    private String queryStructureDataQuietly(Long recordId, Long tid) {
        try {
            List<String> rows = jdbcTemplate.query(
                    "SELECT structure_data FROM his_inp_medical_record WHERE id = ? AND tenant_id = ? AND deleted = 0",
                    (rs, i) -> rs.getString(1), recordId, tid);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (BadSqlGrammarException e) {
            log.debug("structure_data 列缺失, 草药方引用按密文轨处理: recordId={}", recordId);
            return null;
        }
    }

    /** 回写病历明文轨 structure_data(列缺失仅告警, 不阻断主链路) */
    private void updateStructureDataQuietly(Long recordId, Long tid, String newJson) {
        try {
            jdbcTemplate.update(
                    "UPDATE his_inp_medical_record SET structure_data = ? WHERE id = ? AND tenant_id = ?",
                    newJson, recordId, tid);
        } catch (BadSqlGrammarException e) {
            log.warn("structure_data 回写跳过(列缺失): recordId={}, {}", recordId, e.getMessage());
        }
    }

    /** 解析 Tiptap 文档并在 content 数组末尾按行追加 paragraph 节点; 非 JSON 文档拒绝并提示 */
    private static String appendFormulaParagraphs(String plainJson, String text) {
        JSONObject doc;
        try {
            doc = JSON.parseObject(plainJson);
        } catch (Exception e) {
            throw new BizException(400, "病历内容不是结构化 Tiptap 文档, 无法插入草药方引用");
        }
        if (doc == null) {
            throw new BizException(400, "病历内容为空, 无法插入草药方引用");
        }
        JSONArray content = doc.getJSONArray("content");
        if (content == null) {
            content = new JSONArray();
            doc.put("content", content);
        }
        for (String line : text.split("\n")) {
            JSONObject para = new JSONObject();
            para.put("type", "paragraph");
            if (!line.isEmpty()) {
                JSONArray children = new JSONArray();
                JSONObject t = new JSONObject();
                t.put("type", "text");
                t.put("text", line);
                children.add(t);
                para.put("content", children);
            }
            content.add(para);
        }
        return doc.toJSONString();
    }

    /** 明文 JSON 特征判定(与 EmrDocumentService 口径一致: 以 { 或 [ 开头) */
    private static boolean looksLikeJson(String s) {
        if (!StringUtils.hasText(s)) {
            return false;
        }
        char c = s.trim().charAt(0);
        return c == '{' || c == '[';
    }

    /** 非空行计数(日志用) */
    private static int countLines(String text) {
        int n = 0;
        for (String line : text.split("\n")) {
            if (!line.trim().isEmpty()) {
                n++;
            }
        }
        return n;
    }

    /** null 安全 trim 字符串 */
    private static String str(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }
}
