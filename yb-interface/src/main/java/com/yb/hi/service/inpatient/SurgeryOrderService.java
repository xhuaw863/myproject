package com.yb.hi.service.inpatient;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yb.hi.dto.inpatient.InpOrderDTO;
import com.yb.hi.dto.inpatient.OrderTemplateItemDTO;
import com.yb.hi.entity.inpatient.HisInpOrder;
import com.yb.hi.entity.inpatient.HisInpOrderExec;
import com.yb.hi.entity.inpatient.HisOrderTemplate;
import com.yb.hi.entity.inpatient.HisSurgery;
import com.yb.hi.entity.inpatient.HisSurgeryApply;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.inpatient.HisInpOrderExecMapper;
import com.yb.hi.mapper.inpatient.HisInpOrderMapper;
import com.yb.hi.mapper.inpatient.HisSurgeryApplyMapper;
import com.yb.hi.mapper.inpatient.HisSurgeryMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 手术医嘱编排服务(手麻P1): 复用住院医嘱开立链(InpOrderService.createOrder), 附加手术/申请单关联与阶段解析;
 * 提供按手术/申请单查询、发送药房(send_pharm_status 0/2->1)与撤回(1->2, 已发药拒绝)闸门;
 * 手麻P3a: 手术侧执行留痕(execute 直写 his_inp_order_exec, 转抄即执行)与退药申请(returnApply 列标志承接)。
 * 门诊/日间手术无住院就诊, 其费用走 SurgeryFee 双写, 不进住院医嘱链, 故仅住院手术可开手术医嘱。
 * 机构隔离: 写以 currentOrgId 校验, 读按手术/申请归属机构。
 */
@Slf4j
@Service
public class SurgeryOrderService {

    /** 手术医嘱执行/退药锁定与发药状态常量(对齐 InpOrderExecService/InpDispenseService 口径) */
    private static final int ORDER_NEW = 1;
    private static final int ORDER_AUDITED = 2;
    private static final int EXEC_DONE = 2;
    private static final int DISPENSE_DONE = 1;

    private final InpOrderService inpOrderService;
    private final OrderTemplateService orderTemplateService;
    private final HisSurgeryMapper surgeryMapper;
    private final HisSurgeryApplyMapper applyMapper;
    private final HisInpOrderMapper orderMapper;
    private final HisInpOrderExecMapper execMapper;
    private final OrgAccessGuard guard;
    private final JdbcTemplate jdbcTemplate;

    public SurgeryOrderService(InpOrderService inpOrderService, OrderTemplateService orderTemplateService,
                               HisSurgeryMapper surgeryMapper,
                               HisSurgeryApplyMapper applyMapper, HisInpOrderMapper orderMapper,
                               HisInpOrderExecMapper execMapper,
                               OrgAccessGuard guard, JdbcTemplate jdbcTemplate) {
        this.inpOrderService = inpOrderService;
        this.orderTemplateService = orderTemplateService;
        this.surgeryMapper = surgeryMapper;
        this.applyMapper = applyMapper;
        this.orderMapper = orderMapper;
        this.execMapper = execMapper;
        this.guard = guard;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 按手术查询手术医嘱(术中/术后, 按阶段与ID排序); P3a 批量回填执行留痕瞬态列 */
    public List<HisInpOrder> listBySurgery(Long surgeryId) {
        if (surgeryId == null) {
            throw new BizException(400, "手术ID不能为空");
        }
        List<HisInpOrder> orders = orderMapper.selectList(new LambdaQueryWrapper<HisInpOrder>()
                .eq(HisInpOrder::getSurgeryId, surgeryId)
                .orderByAsc(HisInpOrder::getOrderPhase)
                .orderByDesc(HisInpOrder::getId));
        fillExecInfo(orders);
        return orders;
    }

    /** 按申请单查询术前医嘱 */
    public List<HisInpOrder> listByApply(Long applyId) {
        if (applyId == null) {
            throw new BizException(400, "申请单ID不能为空");
        }
        List<HisInpOrder> orders = orderMapper.selectList(new LambdaQueryWrapper<HisInpOrder>()
                .eq(HisInpOrder::getSurgeryApplyId, applyId)
                .orderByAsc(HisInpOrder::getOrderPhase)
                .orderByDesc(HisInpOrder::getId));
        fillExecInfo(orders);
        return orders;
    }

    /**
     * 执行留痕回填(P3a): 对结果集一条 IN 查询取各医嘱最新已执行时间(exec_status=2),
     * 命中则置 execDone=true + execTimeLast; 无执行记录的行保持两字段 null(fastjson2 裁剪后前端按 undefined 渲染)。
     */
    private void fillExecInfo(List<HisInpOrder> orders) {
        if (CollectionUtils.isEmpty(orders)) {
            return;
        }
        List<Long> ids = new ArrayList<>(orders.size());
        for (HisInpOrder o : orders) {
            ids.add(o.getId());
        }
        Map<Long, LocalDateTime> latest = new HashMap<>();
        String in = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT order_id, MAX(exec_time) last_time FROM his_inp_order_exec"
                        + " WHERE order_id IN (" + in + ") AND exec_status = " + EXEC_DONE + " AND deleted = 0"
                        + " AND tenant_id = ? GROUP BY order_id",
                execArgs(ids));
        for (Map<String, Object> row : rows) {
            Long orderId = row.get("order_id") == null ? null : ((Number) row.get("order_id")).longValue();
            LocalDateTime lastTime = toLocalDateTime(row.get("last_time"));
            if (orderId != null && lastTime != null) {
                latest.put(orderId, lastTime);
            }
        }
        for (HisInpOrder o : orders) {
            LocalDateTime lastTime = latest.get(o.getId());
            if (lastTime != null) {
                o.setExecDone(true);
                o.setExecTimeLast(lastTime);
            }
        }
    }

    /**
     * 开立手术医嘱: 依 surgeryId(术中/术后)或 surgeryApplyId(术前)回填住院就诊ID后委托标准开立链。
     * 门诊/日间手术无住院就诊, 拒绝走医嘱链(改由 SurgeryFee 记账)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisInpOrder addOrder(InpOrderDTO dto) {
        if (dto == null) {
            throw new BizException(400, "医嘱参数不能为空");
        }
        if (dto.getSurgeryId() != null) {
            HisSurgery s = surgeryMapper.selectById(dto.getSurgeryId());
            if (s == null) {
                throw new BizException(404, "手术记录不存在");
            }
            requireSameOrg(s.getOrgId());
            if (s.getVisitType() != null && s.getVisitType() != 1) {
                throw new BizException("门诊/日间手术不进住院医嘱链, 其用药请走手术费用记账");
            }
            dto.setInpVisitId(s.getInpVisitId());
            dto.setSurgeryApplyId(null);
        } else if (dto.getSurgeryApplyId() != null) {
            HisSurgeryApply a = applyMapper.selectById(dto.getSurgeryApplyId());
            if (a == null) {
                throw new BizException(404, "手术申请单不存在");
            }
            requireSameOrg(a.getOrgId());
            if (a.getVisitType() != null && a.getVisitType() != 1) {
                throw new BizException("门诊/日间手术申请不进住院医嘱链, 其用药请走手术费用记账");
            }
            dto.setInpVisitId(a.getInpVisitId());
            dto.setSurgeryId(null);
        } else {
            throw new BizException(400, "手术医嘱须关联手术ID或申请单ID");
        }
        HisInpOrder o = inpOrderService.createOrder(dto);
        log.info("开立手术医嘱: id={}, surgeryId={}, applyId={}, phase={}, visitId={}",
                o.getId(), o.getSurgeryId(), o.getSurgeryApplyId(), o.getOrderPhase(), o.getInpVisitId());
        return o;
    }

    /**
     * 手术模板批量开嘱(P2b): 解析 his_order_template items 逐条经 addOrder 走标准开立链,
     * 回填手术/申请单关联与阶段; 同批共享 groupNo, 开成后回写来源模板(scope_type=2→order_set_id, 否则→order_template_id)
     * 并递增使用次数。阶段优先取模板 surgery_phase, 缺省回落入参 phase; 不一致时由 createOrder 拒绝。
     * 个人模板仅归属医生本人; 门诊/日间经 addOrder 拒绝(与 P1 一致)。整批一个事务, 任一条失败全回滚。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<HisInpOrder> createFromTemplate(Long surgeryId, Long applyId, Long templateId,
                                                Integer phase, Long proxyDoctorId, String proxyReason) {
        if (templateId == null) {
            throw new BizException(400, "模板ID不能为空");
        }
        if (surgeryId == null && applyId == null) {
            throw new BizException(400, "手术模板套用须关联手术ID或申请单ID");
        }
        HisOrderTemplate tpl = orderTemplateService.getDetail(templateId).getData();
        if (tpl.getStatus() != null && tpl.getStatus() != 1) {
            throw new BizException(400, "模板已停用: " + tpl.getTemplateName());
        }
        if (tpl.getTemplateType() != null && tpl.getTemplateType() == 1
                && !currentStaffId().equals(tpl.getDoctorId())) {
            throw new BizException(403, "个人模板仅归属医生本人可使用");
        }
        if (!StringUtils.hasText(tpl.getItems())) {
            throw new BizException(400, "模板未配置医嘱项: " + tpl.getTemplateName());
        }
        List<OrderTemplateItemDTO> items;
        try {
            items = JSON.parseArray(tpl.getItems(), OrderTemplateItemDTO.class);
        } catch (Exception e) {
            throw new BizException(400, "模板医嘱项解析失败: " + tpl.getTemplateName());
        }
        if (CollectionUtils.isEmpty(items)) {
            throw new BizException(400, "模板未配置医嘱项: " + tpl.getTemplateName());
        }
        Integer resolvedPhase = tpl.getSurgeryPhase() != null ? tpl.getSurgeryPhase() : phase;
        if (resolvedPhase == null) {
            throw new BizException(400, "手术模板未指定阶段且调用未传阶段");
        }
        String groupNo = "SGRP" + System.currentTimeMillis();
        List<HisInpOrder> created = new ArrayList<>(items.size());
        List<Long> createdIds = new ArrayList<>(items.size());
        for (OrderTemplateItemDTO item : items) {
            if (item == null || !StringUtils.hasText(item.getOrderContent())) {
                throw new BizException(400, "模板存在无效医嘱项(缺少医嘱内容): " + tpl.getTemplateName());
            }
            InpOrderDTO dto = new InpOrderDTO();
            dto.setOrderType(item.getOrderType());
            dto.setOrderCategory(item.getOrderCategory());
            dto.setOrderContent(item.getOrderContent());
            dto.setChargeItemId(item.getChargeItemId());
            dto.setDrugId(item.getDrugId());
            dto.setSpec(item.getSpec());
            dto.setDosage(item.getDosage());
            dto.setDosageUnit(item.getDosageUnit());
            dto.setUsageCode(item.getUsageCode());
            dto.setFreqCode(item.getFreqCode());
            dto.setQuantity(item.getQuantity());
            dto.setUnitPrice(item.getUnitPrice());
            dto.setGroupNo(groupNo);
            dto.setOrderPhase(resolvedPhase);
            dto.setProxyDoctorId(proxyDoctorId);
            dto.setProxyReason(proxyReason);
            if (surgeryId != null) {
                dto.setSurgeryId(surgeryId);
            } else {
                dto.setSurgeryApplyId(applyId);
            }
            HisInpOrder o = addOrder(dto);
            created.add(o);
            createdIds.add(o.getId());
        }
        boolean asSet = tpl.getScopeType() != null && tpl.getScopeType() == 2;
        if (!createdIds.isEmpty()) {
            orderMapper.update(null, new LambdaUpdateWrapper<HisInpOrder>()
                    .set(asSet, HisInpOrder::getOrderSetId, templateId)
                    .set(!asSet, HisInpOrder::getOrderTemplateId, templateId)
                    .in(HisInpOrder::getId, createdIds));
            for (HisInpOrder o : created) {
                if (asSet) {
                    o.setOrderSetId(templateId);
                } else {
                    o.setOrderTemplateId(templateId);
                }
            }
        }
        orderTemplateService.incrementUsage(templateId);
        log.info("手术模板开嘱: surgeryId={}, applyId={}, templateId={}, phase={}, 条数={}, groupNo={}",
                surgeryId, applyId, templateId, resolvedPhase, created.size(), groupNo);
        return created;
    }

    /** 当前登录职工ID(个人模板归属校验用)。 */
    private Long currentStaffId() {
        LoginUser lu = UserContext.get();
        if (lu == null || lu.getStaffId() == null) {
            throw new BizException(401, "未登录或账号未关联职工档案");
        }
        return lu.getStaffId();
    }

    /** 发送药房: 手术类药品医嘱 0/2->1, 置后方进入发药队列; 已发药不可重复发送; P3a 已执行锁定 */
    @Transactional(rollbackFor = Exception.class)
    public int sendPharmacy(Long orderId) {
        HisInpOrder o = requireSurgeryDrugOrder(orderId);
        int affected = jdbcTemplate.update(
                "UPDATE his_inp_order SET send_pharm_status = 1, update_time = NOW()"
                        + " WHERE id = ? AND tenant_id = ? AND org_id = ? AND deleted = 0"
                        + " AND dispense_status = 0 AND (send_pharm_status IS NULL OR send_pharm_status IN (0, 2))"
                        + EXEC_LOCK_CLAUSE,
                orderId, tenantId(), o.getOrgId());
        if (affected == 0) {
            if (hasExecutedRow(orderId)) {
                throw new BizException("医嘱已执行, 不可发送药房");
            }
            throw new BizException("医嘱已发送或已发药, 状态已变化, 请刷新后重试");
        }
        log.info("手术医嘱发送药房: orderId={}, orgId={}", orderId, o.getOrgId());
        return affected;
    }

    /** 撤回发送: 1->2, 仅药房未发药(dispense_status=0)可撤回; 已发药须走退药; P3a 已执行锁定 */
    @Transactional(rollbackFor = Exception.class)
    public int recallPharmacy(Long orderId) {
        HisInpOrder o = requireSurgeryDrugOrder(orderId);
        int affected = jdbcTemplate.update(
                "UPDATE his_inp_order SET send_pharm_status = 2, update_time = NOW()"
                        + " WHERE id = ? AND tenant_id = ? AND org_id = ? AND deleted = 0"
                        + " AND dispense_status = 0 AND send_pharm_status = 1"
                        + EXEC_LOCK_CLAUSE,
                orderId, tenantId(), o.getOrgId());
        if (affected == 0) {
            Integer dispensed = jdbcTemplate.queryForObject(
                    "SELECT dispense_status FROM his_inp_order WHERE id = ? AND tenant_id = ? AND deleted = 0",
                    Integer.class, orderId, tenantId());
            if (Integer.valueOf(1).equals(dispensed)) {
                throw new BizException("药房已发药, 不可撤回发送, 请走退药流程");
            }
            if (hasExecutedRow(orderId)) {
                throw new BizException("医嘱已执行, 不可撤回发送");
            }
            throw new BizException("医嘱未处于可撤回状态(仅已发送且未发药可撤回), 请刷新后重试");
        }
        log.info("手术医嘱撤回发送: orderId={}, orgId={}", orderId, o.getOrgId());
        return affected;
    }

    /**
     * 手术侧执行留痕(P3a): 绕过病区转抄链, 直写 his_inp_order_exec(exec_status=2 已执行)。
     * 守卫: 手术类医嘱(含术前/术中/术后) + 住院手术 + 药品或收费项目类 + order_status∈{1,2} + 同机构写;
     * 幂等: 同单已存在已执行记录则拒绝(转抄即执行双留痕: 若 order_status=1 同步乐观 1->2 并记审核护士=执行人)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisInpOrderExec execute(Long orderId, String remark) {
        if (orderId == null) {
            throw new BizException(400, "医嘱ID不能为空");
        }
        HisInpOrder o = orderMapper.selectById(orderId);
        if (o == null) {
            throw new BizException(404, "医嘱不存在");
        }
        requireSameOrg(o.getOrgId());
        if (o.getSurgeryId() == null && o.getSurgeryApplyId() == null) {
            throw new BizException("普通住院医嘱请走护士站执行链, 不经手术侧执行");
        }
        if (o.getInpVisitId() == null) {
            throw new BizException("门诊/日间手术医嘱无住院就诊, 不支持执行留痕");
        }
        boolean drug = Integer.valueOf(1).equals(o.getOrderCategory()) && o.getDrugId() != null;
        boolean chargeItem = Integer.valueOf(5).equals(o.getOrderCategory()) && o.getChargeItemId() != null;
        if (!drug && !chargeItem) {
            throw new BizException("仅药品或收费项目类手术医嘱支持手术侧执行留痕");
        }
        Integer st = o.getOrderStatus();
        if (st == null || (st != ORDER_NEW && st != ORDER_AUDITED)) {
            throw new BizException("仅新开(1)或已审核(2)状态的医嘱可执行, 当前状态: " + st);
        }
        if (hasExecutedRow(orderId)) {
            throw new BizException("医嘱已执行, 不可重复执行");
        }
        LocalDateTime now = LocalDateTime.now();
        Long staffId = currentStaffId();
        HisInpOrderExec exec = new HisInpOrderExec();
        exec.setOrgId(o.getOrgId());
        exec.setOrderId(orderId);
        exec.setInpVisitId(o.getInpVisitId());
        exec.setPlanTime(now);
        exec.setExecTime(now);
        exec.setExecNurseId(staffId);
        exec.setExecStatus(EXEC_DONE);
        exec.setExecRemark(StringUtils.hasText(remark) ? remark.trim() : "手术侧执行");
        execMapper.insert(exec);
        /* 转抄即执行: 新开态医嘱同步留审核痕(乐观 1->2, 失败仅说明已被病区链处理, 不回滚执行记录) */
        if (st == ORDER_NEW) {
            orderMapper.update(null, new LambdaUpdateWrapper<HisInpOrder>()
                    .set(HisInpOrder::getOrderStatus, ORDER_AUDITED)
                    .set(HisInpOrder::getAuditNurseId, staffId)
                    .set(HisInpOrder::getAuditTime, now)
                    .set(HisInpOrder::getUpdateBy, currentUserName())
                    .eq(HisInpOrder::getId, orderId)
                    .eq(HisInpOrder::getOrderStatus, ORDER_NEW));
        }
        log.info("手术医嘱执行留痕: orderId={}, surgeryId={}, staffId={}, remark={}",
                orderId, o.getSurgeryId(), staffId, exec.getExecRemark());
        return exec;
    }

    /**
     * 退药申请(P3a): 手术侧发起退药请求, 以 his_inp_order.return_apply_flag 列承接(0/2->1),
     * 住院药房 InpDispenseService.returnDrug 完成时闭环回写为2。
     * 守卫: 手术药品医嘱 + 已发药(dispense_status=1) + 未执行(药已用上退药无意义) + 原因必填 + 同机构写。
     */
    @Transactional(rollbackFor = Exception.class)
    public int returnApply(Long orderId, String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new BizException(400, "退药原因不能为空");
        }
        HisInpOrder o = requireSurgeryDrugOrder(orderId);
        if (!Integer.valueOf(DISPENSE_DONE).equals(o.getDispenseStatus())) {
            throw new BizException("仅已发药医嘱可申请退药, 未发药请走撤回发送");
        }
        if (hasExecutedRow(orderId)) {
            throw new BizException("医嘱已执行(药已使用), 不可申请退药");
        }
        if (Integer.valueOf(1).equals(o.getReturnApplyFlag())) {
            throw new BizException("退药申请已提交, 待药房处理");
        }
        int affected = jdbcTemplate.update(
                "UPDATE his_inp_order SET return_apply_flag = 1, return_apply_reason = ?, return_apply_time = NOW(),"
                        + " return_apply_by = ?, update_time = NOW()"
                        + " WHERE id = ? AND tenant_id = ? AND org_id = ? AND deleted = 0"
                        + " AND dispense_status = 1 AND (return_apply_flag IS NULL OR return_apply_flag IN (0, 2))",
                reason.trim(), currentUserName(), orderId, tenantId(), o.getOrgId());
        if (affected == 0) {
            throw new BizException("退药申请状态已变化(可能已提交待药房处理), 请刷新后重试");
        }
        log.info("手术医嘱退药申请: orderId={}, orgId={}, reason={}", orderId, o.getOrgId(), reason);
        return affected;
    }

    /** 存在已执行记录(exec_status=2): 执行锁与幂等判定依据 */
    private boolean hasExecutedRow(Long orderId) {
        Long cnt = execMapper.selectCount(new LambdaQueryWrapper<HisInpOrderExec>()
                .eq(HisInpOrderExec::getOrderId, orderId)
                .eq(HisInpOrderExec::getExecStatus, EXEC_DONE));
        return cnt != null && cnt > 0;
    }

    /** JDBC 取回的 DATETIME 随驱动/连接参数可能是 LocalDateTime 或 java.sql.Timestamp, 统一转 LocalDateTime */
    private static LocalDateTime toLocalDateTime(Object v) {
        if (v instanceof LocalDateTime) {
            return (LocalDateTime) v;
        }
        if (v instanceof java.sql.Timestamp) {
            return ((java.sql.Timestamp) v).toLocalDateTime();
        }
        return null;
    }

    /** IN 占位参数 = 医嘱ID序列 + 尾部租户ID(必须 .toArray(), 不可传 List) */
    private Object[] execArgs(List<Long> ids) {
        Object[] args = new Object[ids.size() + 1];
        for (int i = 0; i < ids.size(); i++) {
            args[i] = ids.get(i);
        }
        args[ids.size()] = tenantId();
        return args;
    }

    /** 当前登录用户名(退药申请人/更新人留痕, 真实姓名优先) */
    private String currentUserName() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            return "system";
        }
        return StringUtils.hasText(lu.getRealName()) ? lu.getRealName() : lu.getUsername();
    }

    /** 校验目标为手术类药品医嘱且归属当前机构 */
    private HisInpOrder requireSurgeryDrugOrder(Long orderId) {
        if (orderId == null) {
            throw new BizException(400, "医嘱ID不能为空");
        }
        HisInpOrder o = orderMapper.selectById(orderId);
        if (o == null) {
            throw new BizException(404, "医嘱不存在");
        }
        requireSameOrg(o.getOrgId());
        if (o.getSurgeryId() == null && o.getSurgeryApplyId() == null) {
            throw new BizException("普通住院医嘱自动进入药房队列, 无需发送/撤回操作");
        }
        if (o.getOrderCategory() == null || o.getOrderCategory() != 1 || o.getDrugId() == null) {
            throw new BizException("仅手术药品医嘱支持发送药房/撤回");
        }
        return o;
    }

    /** 执行锁(P3a): 存在已执行记录的医嘱不可发送/撤回发送药房 */
    private static final String EXEC_LOCK_CLAUSE =
            " AND NOT EXISTS (SELECT 1 FROM his_inp_order_exec x"
                    + " WHERE x.order_id = his_inp_order.id AND x.exec_status = 2 AND x.deleted = 0)";

    private void requireSameOrg(Long orgId) {
        Long cur = guard.currentOrgId();
        if (cur == null || orgId == null || !cur.equals(orgId)) {
            throw new BizException(403, "无权操作其他机构的手术医嘱");
        }
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }
}
