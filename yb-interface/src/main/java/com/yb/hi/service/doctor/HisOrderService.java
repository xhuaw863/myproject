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
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.doctor.HisOrderItemMapper;
import com.yb.hi.mapper.doctor.HisOrderMapper;
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
 * 检查/检验/治疗单服务: 开单编排(主表+明细, 自动补全患者/科室/医师/金额/单据号)
 */
@Slf4j
@Service
public class HisOrderService extends ServiceImpl<HisOrderMapper, HisOrder> {

    private static final AtomicInteger SEQ = new AtomicInteger(0);

    private final HisVisitService visitService;
    private final HisDiagnosisService diagnosisService;
    private final HisOrderItemMapper itemMapper;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public HisOrderService(HisVisitService visitService, HisDiagnosisService diagnosisService,
                           HisOrderItemMapper itemMapper, org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.visitService = visitService;
        this.diagnosisService = diagnosisService;
        this.itemMapper = itemMapper;
        this.jdbcTemplate = jdbcTemplate;
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

    /** 仅未收费、未作废且下游未开始执行的医嘱单允许作废(收费状态取所属就诊 charge_status)。 */
    @Transactional(rollbackFor = Exception.class)
    public HisOrder cancel(Long id) {
        HisOrder order = getById(id);
        if (order == null) {
            throw new BizException(400, "医嘱单不存在");
        }
        if (order.getStatus() != null && order.getStatus() < 0) {
            throw new BizException("该医嘱单已作废, 请勿重复操作");
        }
        // 医生站科室判权(B4)
        visitService.requireVisitScope(order.getVisitId());
        // 医嘱单 status 仅在开立(1)/作废(-1)间变迁, 无中间态可用; 收费进度只能看就诊 charge_status
        HisVisit visit = order.getVisitId() == null ? null : visitService.getById(order.getVisitId());
        if (visit != null && visit.getChargeStatus() != null && visit.getChargeStatus() != 0) {
            throw new BizException("该医嘱单所属就诊已收费或已退费(收费状态:" + visit.getChargeStatus() + "), 请先退费再作废");
        }
        // 下游已开始执行(护士已执行/治疗已签到/标本已采集)时禁止作废, 否则产生孤立执行轨迹与已收费未退项
        String started = describeStartedDownstream(id, order.getExecStatus());
        if (started != null) {
            throw new BizException("该医嘱" + started + ", 不能作废(如需停止请走取消执行/退费流程)");
        }
        order.setStatus(-1);
        updateById(order);
        log.info("医嘱单作废: id={}, orderNo={}, visitId={}", order.getId(), order.getOrderNo(), order.getVisitId());
        return order;
    }

    /**
     * 下游执行痕迹探测: 返回可读的"已开始"描述, 无痕迹时 null。
     * 口径: 医嘱级 exec_status>0(整单已执行/执行中) 或 三类执行单已离开初始态
     * (护士执行单开始过 / 治疗单已签到或开始 / 标本已采集或签收)。
     */
    private String describeStartedDownstream(Long orderId, Integer execStatus) {
        if (execStatus != null && execStatus > 0) {
            return "已执行(执行状态:" + execStatus + ")";
        }
        long tid = com.yb.hi.framework.tenant.TenantContext.get() == null
                ? 0L : com.yb.hi.framework.tenant.TenantContext.get();
        Integer nurse = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_nurse_exec WHERE order_id = ? AND tenant_id = ? AND deleted = 0 AND exec_status <> 0",
                Integer.class, orderId, tid);
        if (nurse != null && nurse > 0) {
            return "已有护理执行单开始/完成";
        }
        Integer treat = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_treatment_exec e JOIN his_treatment_plan p ON p.id = e.plan_id AND p.deleted = 0"
                        + " WHERE p.order_id = ? AND e.tenant_id = ? AND e.deleted = 0"
                        + " AND (e.exec_status <> 0 OR e.checkin_time IS NOT NULL)",
                Integer.class, orderId, tid);
        if (treat != null && treat > 0) {
            return "已有治疗单签到/执行";
        }
        Integer spec = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_specimen WHERE order_id = ? AND tenant_id = ? AND deleted = 0 AND status <> 0",
                Integer.class, orderId, tid);
        if (spec != null && spec > 0) {
            return "已有标本采集/签收";
        }
        return null;
    }

    /**
     * 患者检查/检验/治疗报告: 本系统未建检查执行/报告回传链路, 因此口径为
     * "已完成接诊且未作废的医嘱单"(而非依赖不存在的 status>=2 执行态, 那会导致报告页恒为空);
     * 按开单时间倒序, 附单据明细。
     */
    public List<Map<String, Object>> listReports(Long patientId) {
        if (patientId == null) {
            throw new BizException(400, "患者ID不能为空");
        }
        List<Long> finishedVisitIds = visitService.lambdaQuery()
                .select(HisVisit::getId)
                .eq(HisVisit::getPatientId, patientId)
                .eq(HisVisit::getVisitStatus, 3)
                .list().stream().map(HisVisit::getId).collect(Collectors.toList());
        if (finishedVisitIds.isEmpty()) {
            return new ArrayList<>();
        }
        List<HisOrder> orders = lambdaQuery()
                .eq(HisOrder::getPatientId, patientId)
                .in(HisOrder::getVisitId, finishedVisitIds)
                .gt(HisOrder::getStatus, 0)
                .orderByDesc(HisOrder::getCreateTime)
                .orderByDesc(HisOrder::getId)
                .list();
        List<Map<String, Object>> result = new ArrayList<>();
        for (HisOrder o : orders) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("order", o);
            row.put("items", listItems(o.getId()));
            result.add(row);
        }
        return result;
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
        // 医生站科室判权(B4): 管理员/牵头直通, 其余仅授权科室
        visitService.requireVisitScope(req.getVisitId());

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

        long tid = TenantContext.get() == null ? 0L : TenantContext.get();
        // 定价机构: 就诊科室归属机构优先, 回退当前登录机构(与医生站取数/收费执行价口径一致)
        Long priceOrgId = visit.getDeptId() == null ? null : jdbcTemplate.queryForObject(
                "SELECT org_id FROM his_dept WHERE id = ? AND tenant_id = ? AND deleted = 0",
                Long.class, visit.getDeptId(), tid);
        if (priceOrgId == null && UserContext.get() != null) {
            priceOrgId = UserContext.get().getOrgId();
        }
        Integer priceLv = null;
        if (priceOrgId != null) {
            List<Map<String, Object>> orgRows = jdbcTemplate.queryForList(
                    "SELECT price_lv FROM sys_org WHERE id = ? AND tenant_id = ? AND deleted = 0",
                    priceOrgId, tid);
            if (!orgRows.isEmpty()) {
                Number lv = (Number) orgRows.get(0).get("price_lv");
                priceLv = lv == null ? null : lv.intValue();
            }
        }
        BigDecimal total = BigDecimal.ZERO;
        for (HisOrderItem item : req.getItems()) {
            item.setId(null);
            BigDecimal qty = item.getQuantity() == null ? BigDecimal.ZERO : item.getQuantity();
            if (qty.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BizException(400, "医嘱明细数量必须大于0: " + item.getItemName());
            }
            // 金额以院内收费目录为准: 客户端单价/金额不可信, 服务端按 his_charge_item 启用行重算(名称同样以目录为准);
            // 执行价与医生站取数同口径: 按机构 price_lv 取档(price_l1/l2/l3), 缺档回退默认 price
            List<Map<String, Object>> catRows = item.getItemId() == null ? null : jdbcTemplate.queryForList(
                    "SELECT item_name, price, price_l1, price_l2, price_l3 FROM his_charge_item"
                            + " WHERE id = ? AND tenant_id = ? AND status = 1 AND deleted = 0",
                    item.getItemId(), tid);
            if (catRows == null || catRows.isEmpty()) {
                throw new BizException(400, "医嘱明细收费项目不存在或已停用: " + item.getItemName());
            }
            Map<String, Object> row = catRows.get(0);
            BigDecimal price = toBd(row.get("price"));
            if (priceLv != null) {
                String col = priceLv == 1 ? "price_l1" : priceLv == 2 ? "price_l2" : priceLv == 3 ? "price_l3" : null;
                if (col != null) {
                    BigDecimal v = toBd(row.get(col));
                    if (v != null) {
                        price = v;
                    }
                }
            }
            if (price == null) {
                throw new BizException(400, "收费项目价格未配置: " + str(row.get("item_name")));
            }
            item.setItemName(str(row.get("item_name")));
            item.setPrice(price);
            item.setAmount(price.multiply(qty).setScale(2, BigDecimal.ROUND_HALF_UP));
            total = total.add(item.getAmount());
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

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static BigDecimal toBd(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        return new BigDecimal(v.toString());
    }
}
