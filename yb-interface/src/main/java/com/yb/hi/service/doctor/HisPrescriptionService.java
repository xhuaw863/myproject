package com.yb.hi.service.doctor;

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
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.service.pharmacy.PharmacyDefService;
import com.yb.hi.service.pharmacy.PharmacyPriceService;
import com.yb.hi.mapper.doctor.HisPrescriptionItemMapper;
import com.yb.hi.mapper.doctor.HisPrescriptionMapper;
import com.yb.hi.mapper.outpatient.HisPatientMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
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

    public HisPrescriptionService(HisVisitService visitService, HisDiagnosisService diagnosisService,
                                  HisPrescriptionItemMapper itemMapper, HisPatientMapper patientMapper,
                                  PharmacyDefService pharmacyDefService, PharmacyPriceService pharmacyPriceService,
                                  HisOrderFreqService orderFreqService) {
        this.visitService = visitService;
        this.diagnosisService = diagnosisService;
        this.itemMapper = itemMapper;
        this.patientMapper = patientMapper;
        this.pharmacyDefService = pharmacyDefService;
        this.pharmacyPriceService = pharmacyPriceService;
        this.orderFreqService = orderFreqService;
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

        // 发药药房(三期): 医生手选优先并校验归属/启停; 未手选按科室×中西药渠道默认回落; 均无则不绑(发药全院FIFO兼容存量)
        Long pharmacyId = req.getPharmacyId();
        if (pharmacyId != null) {
            LoginUser lu = UserContext.get();
            pharmacyDefService.requireEnabled(pharmacyId, lu == null ? null : lu.getOrgId());
        } else {
            pharmacyId = pharmacyDefService.resolveDefaultPharmacyId(visit.getDeptId(), rxType);
        }
        p.setPharmacyId(pharmacyId);

        // 服务端重算价(药房维度定价): 药品行按所选药房生效价覆盖前端传价(不信任客户端), 非药品行(drugId 空)保持原价
        List<Long> drugIds = req.getItems().stream()
                .map(HisPrescriptionItem::getDrugId).filter(java.util.Objects::nonNull)
                .distinct().collect(Collectors.toList());
        Map<Long, BigDecimal> effPrices = pharmacyPriceService.effectivePriceBatch(pharmacyId, drugIds);

        BigDecimal total = BigDecimal.ZERO;
        for (HisPrescriptionItem item : req.getItems()) {
            item.setId(null);
            BigDecimal price = item.getPrice() == null ? BigDecimal.ZERO : item.getPrice();
            if (item.getDrugId() != null) {
                BigDecimal eff = effPrices.get(item.getDrugId());
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
}
