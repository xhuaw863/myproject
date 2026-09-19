package com.yb.hi.service.doctor;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.common.DateUtil;
import com.yb.hi.dto.doctor.OrderReq;
import com.yb.hi.entity.doctor.HisDiagnosis;
import com.yb.hi.entity.doctor.HisOrder;
import com.yb.hi.entity.doctor.HisOrderItem;
import com.yb.hi.entity.doctor.HisVisit;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.mapper.doctor.HisOrderItemMapper;
import com.yb.hi.mapper.doctor.HisOrderMapper;
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
 * 检查/检验/治疗单服务: 开单编排(主表+明细, 自动补全患者/科室/医师/金额/单据号)
 */
@Slf4j
@Service
public class HisOrderService extends ServiceImpl<HisOrderMapper, HisOrder> {

    private static final AtomicInteger SEQ = new AtomicInteger(0);

    private final HisVisitService visitService;
    private final HisDiagnosisService diagnosisService;
    private final HisOrderItemMapper itemMapper;

    public HisOrderService(HisVisitService visitService, HisDiagnosisService diagnosisService,
                           HisOrderItemMapper itemMapper) {
        this.visitService = visitService;
        this.diagnosisService = diagnosisService;
        this.itemMapper = itemMapper;
    }

    /** 查询某次就诊的单据列表 */
    public List<HisOrder> listByVisit(Long visitId) {
        return lambdaQuery().eq(HisOrder::getVisitId, visitId).orderByDesc(HisOrder::getId).list();
    }

    /** 查询单据明细 */
    public List<HisOrderItem> listItems(Long orderId) {
        return itemMapper.selectList(new QueryWrapper<HisOrderItem>()
                .eq("order_id", orderId).eq("deleted", 0).orderByAsc("id"));
    }

    /**
     * 开单: 校验就诊 -> 补全主表 -> 计算金额 -> 落库主表+明细
     */
    @Transactional(rollbackFor = Exception.class)
    public HisOrder create(OrderReq req) {
        if (req == null || req.getVisitId() == null) {
            throw new BizException(400, "就诊ID不能为空");
        }
        if (CollectionUtils.isEmpty(req.getItems())) {
            throw new BizException("单据明细不能为空");
        }
        HisVisit visit = visitService.getById(req.getVisitId());
        if (visit == null) {
            throw new BizException(400, "就诊记录不存在");
        }

        HisOrder o = new HisOrder();
        o.setVisitId(visit.getId());
        o.setOrderNo(genNo("OD"));
        o.setPatientId(visit.getPatientId());
        o.setPatientName(visit.getPatientName());
        o.setDeptId(visit.getDeptId());
        o.setDeptName(visit.getDeptName());
        o.setDrId(visit.getStaffId());
        o.setDrName(visit.getDrName());
        o.setOrderType(StringUtils.hasText(req.getOrderType()) ? req.getOrderType() : "检查");
        o.setDiagName(buildDiagName(visit.getId()));
        o.setStatus(1);

        BigDecimal total = BigDecimal.ZERO;
        for (HisOrderItem item : req.getItems()) {
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
        o.setTotalAmount(total.setScale(2, BigDecimal.ROUND_HALF_UP));
        save(o);

        for (HisOrderItem item : req.getItems()) {
            item.setOrderId(o.getId());
            itemMapper.insert(item);
        }
        log.info("开单成功: orderNo={}, visitId={}, total={}", o.getOrderNo(), visit.getId(), o.getTotalAmount());
        return o;
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
