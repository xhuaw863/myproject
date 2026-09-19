package com.yb.hi.service.doctor;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.common.DateUtil;
import com.yb.hi.dto.doctor.PrescriptionReq;
import com.yb.hi.entity.doctor.HisDiagnosis;
import com.yb.hi.entity.doctor.HisPrescription;
import com.yb.hi.entity.doctor.HisPrescriptionItem;
import com.yb.hi.entity.doctor.HisVisit;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.mapper.doctor.HisPrescriptionItemMapper;
import com.yb.hi.mapper.doctor.HisPrescriptionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;
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

    public HisPrescriptionService(HisVisitService visitService, HisDiagnosisService diagnosisService,
                                  HisPrescriptionItemMapper itemMapper) {
        this.visitService = visitService;
        this.diagnosisService = diagnosisService;
        this.itemMapper = itemMapper;
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

        HisPrescription p = new HisPrescription();
        p.setVisitId(visit.getId());
        p.setRxNo(genNo("RX"));
        p.setPatientId(visit.getPatientId());
        p.setPatientName(visit.getPatientName());
        p.setDeptId(visit.getDeptId());
        p.setDeptName(visit.getDeptName());
        p.setDrId(visit.getStaffId());
        p.setDrName(visit.getDrName());
        p.setRxType(StringUtils.hasText(req.getRxType()) ? req.getRxType() : "西药");
        p.setDiagName(buildDiagName(visit.getId()));
        p.setStatus(1);

        BigDecimal total = BigDecimal.ZERO;
        for (HisPrescriptionItem item : req.getItems()) {
            item.setId(null);
            BigDecimal price = item.getPrice() == null ? BigDecimal.ZERO : item.getPrice();
            BigDecimal qty = item.getQuantity() == null ? BigDecimal.ZERO : item.getQuantity();
            BigDecimal amount = item.getAmount();
            if (amount == null) {
                amount = price.multiply(qty).setScale(2, BigDecimal.ROUND_HALF_UP);
            }
            item.setAmount(amount);
            total = total.add(amount);
        }
        p.setTotalAmount(total.setScale(2, BigDecimal.ROUND_HALF_UP));
        save(p);

        for (HisPrescriptionItem item : req.getItems()) {
            item.setPrescriptionId(p.getId());
            itemMapper.insert(item);
        }
        log.info("开处方成功: rxNo={}, visitId={}, total={}", p.getRxNo(), visit.getId(), p.getTotalAmount());
        return p;
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
