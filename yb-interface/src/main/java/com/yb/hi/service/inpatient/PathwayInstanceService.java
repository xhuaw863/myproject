package com.yb.hi.service.inpatient;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.dto.inpatient.InpOrderDTO;
import com.yb.hi.dto.inpatient.PathwayStartDTO;
import com.yb.hi.entity.inpatient.HisInpOrder;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.entity.inpatient.HisPathwayExec;
import com.yb.hi.entity.inpatient.HisPathwayInstance;
import com.yb.hi.entity.inpatient.HisPathwayNode;
import com.yb.hi.entity.inpatient.HisPathwayTask;
import com.yb.hi.entity.inpatient.HisPathwayTemplate;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.inpatient.HisInpOrderMapper;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.mapper.inpatient.HisPathwayExecMapper;
import com.yb.hi.mapper.inpatient.HisPathwayInstanceMapper;
import com.yb.hi.mapper.inpatient.HisPathwayNodeMapper;
import com.yb.hi.mapper.inpatient.HisPathwayTaskMapper;
import com.yb.hi.mapper.inpatient.HisPathwayTemplateMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 患者临床路径实例服务(核心): 入径启动(预生成逐日执行记录) / 状态机流转 / 逐日任务执行(转医嘱) / 变异登记 / 统计视图。
 * - 入径: 校验患者在院(visit_status=2)且同就诊无进行中/暂停实例, 按模板节点×任务预生成 exec(status=1待执行);
 * - 状态机(乐观更新): 1进行中 ⇄ 4暂停, 1/4 → 2已完成(记 end_date) / 3已退出(记 exit_reason);
 * - exec-day: 取当天(current_day)待执行的必做任务, 逐条转 InpOrderDTO 经 InpOrderService.batchCreate 成组开嘱,
 *   回写 exec(status=2已执行, order_id) 与医嘱 pathway_instance_id 双向关联;
 * - 隔离: 就诊/实例/执行记录均经 guard.scopeOrgId 做机构校验(非牵头仅本机构)。
 */
@Slf4j
@Service
public class PathwayInstanceService extends ServiceImpl<HisPathwayInstanceMapper, HisPathwayInstance> {

    private final HisPathwayTemplateMapper templateMapper;
    private final HisPathwayNodeMapper nodeMapper;
    private final HisPathwayTaskMapper taskMapper;
    private final HisPathwayExecMapper execMapper;
    private final HisInpVisitMapper visitMapper;
    private final HisInpOrderMapper orderMapper;
    private final InpOrderService inpOrderService;
    private final OrgAccessGuard guard;

    public PathwayInstanceService(HisPathwayTemplateMapper templateMapper, HisPathwayNodeMapper nodeMapper,
                                  HisPathwayTaskMapper taskMapper, HisPathwayExecMapper execMapper,
                                  HisInpVisitMapper visitMapper, HisInpOrderMapper orderMapper,
                                  InpOrderService inpOrderService, OrgAccessGuard guard) {
        this.templateMapper = templateMapper;
        this.nodeMapper = nodeMapper;
        this.taskMapper = taskMapper;
        this.execMapper = execMapper;
        this.visitMapper = visitMapper;
        this.orderMapper = orderMapper;
        this.inpOrderService = inpOrderService;
        this.guard = guard;
    }

    /* ==================== 入径启动 ==================== */

    /**
     * 为患者启动路径:
     * 1. 校验患者在院(visit_status=2)与机构归属; 2. 校验无进行中/暂停实例; 3. 加载模板节点/任务;
     * 4. 创建实例(status=1, current_day=1, start_date=now); 5. 遍历节点×任务预生成 exec(exec_status=1待执行)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisPathwayInstance startPathway(PathwayStartDTO dto, Long orgId) {
        if (dto == null || dto.getInpVisitId() == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        if (dto.getTemplateId() == null) {
            throw new BizException(400, "路径模板ID不能为空");
        }
        HisInpVisit visit = requireVisit(dto.getInpVisitId());
        if (visit.getVisitStatus() == null || visit.getVisitStatus() != 2) {
            throw new BizException("患者当前不在院(就诊状态:" + visit.getVisitStatus() + "), 不能启动临床路径");
        }
        HisPathwayTemplate tpl = templateMapper.selectById(dto.getTemplateId());
        if (tpl == null) {
            throw new BizException(400, "路径模板不存在");
        }
        if (tpl.getStatus() != null && tpl.getStatus() != 1) {
            throw new BizException("路径模板已停用, 不能入径");
        }
        if (tpl.getOrgId() != null && visit.getOrgId() != null && !tpl.getOrgId().equals(visit.getOrgId())) {
            throw new BizException(403, "路径模板不属于患者所在机构, 不能入径");
        }
        // 同就诊无进行中/暂停的实例: 暂停中亦视为在途路径, 需先恢复或退出
        long active = count(new LambdaQueryWrapper<HisPathwayInstance>()
                .eq(HisPathwayInstance::getInpVisitId, visit.getId())
                .in(HisPathwayInstance::getStatus, 1, 4));
        if (active > 0) {
            throw new BizException("患者已有进行中或暂停的临床路径, 不能重复入径");
        }
        List<HisPathwayNode> nodes = nodeMapper.selectList(new LambdaQueryWrapper<HisPathwayNode>()
                .eq(HisPathwayNode::getTemplateId, tpl.getId())
                .orderByAsc(HisPathwayNode::getDayNo)
                .orderByAsc(HisPathwayNode::getSortNo));
        if (nodes.isEmpty()) {
            throw new BizException("路径模板未配置节点, 不能入径");
        }
        List<HisPathwayTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<HisPathwayTask>()
                .eq(HisPathwayTask::getTemplateId, tpl.getId())
                .orderByAsc(HisPathwayTask::getSortNo));
        if (tasks.isEmpty()) {
            throw new BizException("路径模板未配置任务, 不能入径");
        }
        HisPathwayInstance inst = new HisPathwayInstance();
        inst.setOrgId(visit.getOrgId() != null ? visit.getOrgId() : orgId);
        inst.setInpVisitId(visit.getId());
        inst.setTemplateId(tpl.getId());
        inst.setStartDate(LocalDateTime.now());
        inst.setCurrentDay(1);
        inst.setStatus(1);
        inst.setDoctorId(visit.getDoctorId());
        save(inst);
        // 预生成执行记录: 每个任务一条(exec_status=1待执行), day_no 取所属节点
        Map<Long, List<HisPathwayTask>> byNode = tasks.stream()
                .collect(Collectors.groupingBy(HisPathwayTask::getNodeId));
        int execCount = 0;
        for (HisPathwayNode n : nodes) {
            List<HisPathwayTask> ts = byNode.get(n.getId());
            if (ts == null || ts.isEmpty()) {
                continue;
            }
            for (HisPathwayTask t : ts) {
                HisPathwayExec e = new HisPathwayExec();
                e.setOrgId(inst.getOrgId());
                e.setInstanceId(inst.getId());
                e.setTaskId(t.getId());
                e.setNodeId(n.getId());
                e.setDayNo(n.getDayNo());
                e.setExecStatus(1);
                execMapper.insert(e);
                execCount++;
            }
        }
        log.info("启动临床路径: instanceId={}, visitId={}, templateId={}, 预生成exec={}条",
                inst.getId(), visit.getId(), tpl.getId(), execCount);
        return getById(inst.getId());
    }

    /* ==================== 查询 ==================== */

    /** 当前患者活跃路径(status=1进行中/4暂停, 暂停态同样返回便于恢复): 含模板名称与进度信息; 未入径返回 null */
    public Map<String, Object> getCurrentInstance(Long visitId) {
        if (visitId == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        requireVisit(visitId);
        List<HisPathwayInstance> list = list(new LambdaQueryWrapper<HisPathwayInstance>()
                .eq(HisPathwayInstance::getInpVisitId, visitId)
                .in(HisPathwayInstance::getStatus, 1, 4)
                .orderByDesc(HisPathwayInstance::getId));
        if (list.isEmpty()) {
            return null;
        }
        HisPathwayInstance inst = list.get(0);
        HisPathwayTemplate tpl = templateMapper.selectById(inst.getTemplateId());
        long totalTasks = execMapper.selectCount(new LambdaQueryWrapper<HisPathwayExec>()
                .eq(HisPathwayExec::getInstanceId, inst.getId()));
        long processedTasks = execMapper.selectCount(new LambdaQueryWrapper<HisPathwayExec>()
                .eq(HisPathwayExec::getInstanceId, inst.getId())
                .in(HisPathwayExec::getExecStatus, 2, 3, 4));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instance", inst);
        out.put("templateName", tpl == null ? null : tpl.getPathwayName());
        out.put("templateCode", tpl == null ? null : tpl.getPathwayCode());
        out.put("totalDays", maxDayNo(inst.getTemplateId()));
        out.put("totalTasks", totalTasks);
        out.put("processedTasks", processedTasks);
        out.put("progress", totalTasks == 0 ? 0 : (int) Math.round(processedTasks * 100.0 / totalTasks));
        return out;
    }

    /** 路径执行详情: 按 exec 快照(days → nodes → tasks 三层树)展示每天节点任务的执行状态 */
    public Map<String, Object> getInstanceDetail(Long id) {
        HisPathwayInstance inst = requireInstance(id);
        HisPathwayTemplate tpl = templateMapper.selectById(inst.getTemplateId());
        List<HisPathwayExec> execs = execMapper.selectList(new LambdaQueryWrapper<HisPathwayExec>()
                .eq(HisPathwayExec::getInstanceId, inst.getId())
                .orderByAsc(HisPathwayExec::getDayNo)
                .orderByAsc(HisPathwayExec::getNodeId)
                .orderByAsc(HisPathwayExec::getId));
        // 任务详情(可能已逻辑删除, 缺失时任务字段留空)
        List<Long> taskIds = execs.stream().map(HisPathwayExec::getTaskId)
                .filter(Objects::nonNull).distinct().collect(Collectors.toList());
        Map<Long, HisPathwayTask> taskMap = taskIds.isEmpty() ? new LinkedHashMap<>()
                : taskMapper.selectBatchIds(taskIds).stream()
                .collect(Collectors.toMap(HisPathwayTask::getId, t -> t, (a, b) -> a));
        // 节点名称(可能已删除, 缺失时留空)
        List<Long> nodeIds = execs.stream().map(HisPathwayExec::getNodeId)
                .filter(Objects::nonNull).distinct().collect(Collectors.toList());
        Map<Long, HisPathwayNode> nodeMap = nodeIds.isEmpty() ? new LinkedHashMap<>()
                : nodeMapper.selectBatchIds(nodeIds).stream()
                .collect(Collectors.toMap(HisPathwayNode::getId, n -> n, (a, b) -> a));

        Map<Integer, List<HisPathwayExec>> byDay = execs.stream()
                .collect(Collectors.groupingBy(e -> e.getDayNo() == null ? 0 : e.getDayNo(),
                        java.util.TreeMap::new, Collectors.toList()));
        List<Map<String, Object>> days = new ArrayList<>(byDay.size());
        int varianceCount = 0;
        long processed = 0;
        for (Map.Entry<Integer, List<HisPathwayExec>> de : byDay.entrySet()) {
            Map<Long, List<HisPathwayExec>> byNode = de.getValue().stream()
                    .collect(Collectors.groupingBy(e -> e.getNodeId() == null ? 0L : e.getNodeId(),
                            LinkedHashMap::new, Collectors.toList()));
            List<Map<String, Object>> nodeList = new ArrayList<>(byNode.size());
            for (Map.Entry<Long, List<HisPathwayExec>> ne : byNode.entrySet()) {
                HisPathwayNode node = nodeMap.get(ne.getKey());
                Map<String, Object> nm = new LinkedHashMap<>();
                nm.put("nodeId", ne.getKey());
                nm.put("nodeName", node == null ? null : node.getNodeName());
                nm.put("nodeDesc", node == null ? null : node.getNodeDesc());
                List<Map<String, Object>> tlist = new ArrayList<>(ne.getValue().size());
                for (HisPathwayExec e : ne.getValue()) {
                    HisPathwayTask t = e.getTaskId() == null ? null : taskMap.get(e.getTaskId());
                    Map<String, Object> tm = new LinkedHashMap<>();
                    tm.put("execId", e.getId());
                    tm.put("taskId", e.getTaskId());
                    tm.put("execStatus", e.getExecStatus());
                    tm.put("execDate", e.getExecDate());
                    tm.put("orderId", e.getOrderId());
                    tm.put("varianceReason", e.getVarianceReason());
                    tm.put("operatorId", e.getOperatorId());
                    tm.put("taskType", t == null ? null : t.getTaskType());
                    tm.put("orderType", t == null ? null : t.getOrderType());
                    tm.put("orderCategory", t == null ? null : t.getOrderCategory());
                    tm.put("orderContent", t == null ? null : t.getOrderContent());
                    tm.put("chargeItemId", t == null ? null : t.getChargeItemId());
                    tm.put("drugId", t == null ? null : t.getDrugId());
                    tm.put("spec", t == null ? null : t.getSpec());
                    tm.put("dosage", t == null ? null : t.getDosage());
                    tm.put("dosageUnit", t == null ? null : t.getDosageUnit());
                    tm.put("usageCode", t == null ? null : t.getUsageCode());
                    tm.put("freqCode", t == null ? null : t.getFreqCode());
                    tm.put("quantity", t == null ? null : t.getQuantity());
                    tm.put("unitPrice", t == null ? null : t.getUnitPrice());
                    tm.put("isMandatory", t == null ? null : t.getIsMandatory());
                    if (e.getExecStatus() != null && e.getExecStatus() >= 2) {
                        processed++;
                    }
                    if (e.getExecStatus() != null && e.getExecStatus() == 4) {
                        varianceCount++;
                    }
                    tlist.add(tm);
                }
                nm.put("tasks", tlist);
                nodeList.add(nm);
            }
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("dayNo", de.getKey());
            dm.put("nodes", nodeList);
            days.add(dm);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instance", inst);
        out.put("templateName", tpl == null ? null : tpl.getPathwayName());
        out.put("templateCode", tpl == null ? null : tpl.getPathwayCode());
        out.put("days", days);
        out.put("totalTasks", execs.size());
        out.put("processedTasks", processed);
        out.put("varianceCount", varianceCount);
        return out;
    }

    /* ==================== 状态流转(乐观更新) ==================== */

    /** 推进到下一天: current_day+1(仅进行中, 乐观更新) */
    @Transactional(rollbackFor = Exception.class)
    public HisPathwayInstance advanceDay(Long id) {
        HisPathwayInstance inst = requireInstance(id);
        if (inst.getStatus() == null || inst.getStatus() != 1) {
            throw new BizException("仅进行中的路径可推进天数, 当前状态: " + inst.getStatus());
        }
        int cur = inst.getCurrentDay() == null ? 1 : inst.getCurrentDay();
        boolean ok = lambdaUpdate()
                .setSql("current_day = current_day + 1")
                .eq(HisPathwayInstance::getId, id)
                .eq(HisPathwayInstance::getStatus, 1)
                .eq(HisPathwayInstance::getCurrentDay, cur)
                .update();
        if (!ok) {
            throw new BizException("路径状态已变化, 请刷新后重试");
        }
        log.info("临床路径推进天数: instanceId={}, {} -> {}", id, cur, cur + 1);
        return getById(id);
    }

    /** 暂停路径: 1进行中 → 4暂停(乐观更新) */
    @Transactional(rollbackFor = Exception.class)
    public HisPathwayInstance pause(Long id) {
        requireInstance(id);
        boolean ok = lambdaUpdate()
                .set(HisPathwayInstance::getStatus, 4)
                .eq(HisPathwayInstance::getId, id)
                .eq(HisPathwayInstance::getStatus, 1)
                .update();
        if (!ok) {
            throw new BizException("仅进行中的路径可暂停, 请刷新后重试");
        }
        log.info("临床路径暂停: instanceId={}", id);
        return getById(id);
    }

    /** 恢复路径: 4暂停 → 1进行中(乐观更新) */
    @Transactional(rollbackFor = Exception.class)
    public HisPathwayInstance resume(Long id) {
        requireInstance(id);
        boolean ok = lambdaUpdate()
                .set(HisPathwayInstance::getStatus, 1)
                .eq(HisPathwayInstance::getId, id)
                .eq(HisPathwayInstance::getStatus, 4)
                .update();
        if (!ok) {
            throw new BizException("仅暂停中的路径可恢复, 请刷新后重试");
        }
        log.info("临床路径恢复: instanceId={}", id);
        return getById(id);
    }

    /** 退出路径: 1/4 → 3已退出, 记录 exit_reason(乐观更新) */
    @Transactional(rollbackFor = Exception.class)
    public HisPathwayInstance exit(Long id, String reason) {
        requireInstance(id);
        boolean ok = lambdaUpdate()
                .set(HisPathwayInstance::getStatus, 3)
                .set(HisPathwayInstance::getExitReason,
                        StringUtils.hasText(reason) ? reason.trim() : null)
                .eq(HisPathwayInstance::getId, id)
                .in(HisPathwayInstance::getStatus, 1, 4)
                .update();
        if (!ok) {
            throw new BizException("路径已结束, 不能退出");
        }
        log.info("临床路径退出: instanceId={}, reason={}", id, reason);
        return getById(id);
    }

    /** 完成路径: 1/4 → 2已完成, 记录 end_date(乐观更新) */
    @Transactional(rollbackFor = Exception.class)
    public HisPathwayInstance complete(Long id) {
        requireInstance(id);
        boolean ok = lambdaUpdate()
                .set(HisPathwayInstance::getStatus, 2)
                .set(HisPathwayInstance::getEndDate, LocalDateTime.now())
                .eq(HisPathwayInstance::getId, id)
                .in(HisPathwayInstance::getStatus, 1, 4)
                .update();
        if (!ok) {
            throw new BizException("路径已结束, 不能重复完成");
        }
        log.info("临床路径完成: instanceId={}", id);
        return getById(id);
    }

    /* ==================== 当天任务执行(核心) ==================== */

    /**
     * 执行当天(current_day)全部必做且待执行的路径任务:
     * 1. 查 exec(instance_id + day_no + exec_status=1), 过滤 is_mandatory=1 的任务;
     * 2. 逐条构建 InpOrderDTO, 经 InpOrderService.batchCreate 成组开嘱(同事务);
     * 3. 回写 exec(status=2已执行, order_id=医嘱ID, exec_date, operator_id);
     * 4. 医嘱回写 pathway_instance_id 建立路径来源关联。
     * 说明: 可选任务不自动执行, 由医生逐条跳过(3)或登记变异(4)。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> executeDayTasks(Long instanceId) {
        HisPathwayInstance inst = requireInstance(instanceId);
        if (inst.getStatus() == null || inst.getStatus() != 1) {
            throw new BizException("仅进行中的路径可执行任务, 当前状态: " + inst.getStatus());
        }
        int day = inst.getCurrentDay() == null ? 1 : inst.getCurrentDay();
        List<HisPathwayExec> execs = execMapper.selectList(new LambdaQueryWrapper<HisPathwayExec>()
                .eq(HisPathwayExec::getInstanceId, instanceId)
                .eq(HisPathwayExec::getDayNo, day)
                .eq(HisPathwayExec::getExecStatus, 1)
                .orderByAsc(HisPathwayExec::getId));
        if (execs.isEmpty()) {
            throw new BizException("第" + day + "天没有待执行的任务");
        }
        List<Long> taskIds = execs.stream().map(HisPathwayExec::getTaskId)
                .filter(Objects::nonNull).distinct().collect(Collectors.toList());
        Map<Long, HisPathwayTask> taskMap = taskIds.isEmpty() ? new LinkedHashMap<>()
                : taskMapper.selectBatchIds(taskIds).stream()
                .collect(Collectors.toMap(HisPathwayTask::getId, t -> t, (a, b) -> a));
        List<HisPathwayExec> todo = new ArrayList<>();
        List<InpOrderDTO> dtos = new ArrayList<>();
        for (HisPathwayExec e : execs) {
            HisPathwayTask t = e.getTaskId() == null ? null : taskMap.get(e.getTaskId());
            if (t == null) {
                // 任务已被删除: 跳过(交由人工跳过/变异处理)
                continue;
            }
            if (t.getIsMandatory() == null || t.getIsMandatory() != 1) {
                // 可选任务不自动执行
                continue;
            }
            todo.add(e);
            dtos.add(buildOrderDTO(inst, t));
        }
        if (todo.isEmpty()) {
            throw new BizException("第" + day + "天没有待执行的必做任务");
        }
        List<HisInpOrder> orders = inpOrderService.batchCreate(dtos);
        if (orders.size() != todo.size()) {
            throw new BizException("路径任务转医嘱数量不一致, 请联系管理员排查");
        }
        Long operatorId = currentStaffId();
        for (int i = 0; i < todo.size(); i++) {
            HisPathwayExec e = todo.get(i);
            HisInpOrder o = orders.get(i);
            // 医嘱回写路径来源关联
            o.setPathwayInstanceId(instanceId);
            orderMapper.updateById(o);
            // exec 回写执行结果(乐观: 仅待执行可更新)
            execMapper.update(null, new LambdaUpdateWrapper<HisPathwayExec>()
                    .set(HisPathwayExec::getExecStatus, 2)
                    .set(HisPathwayExec::getOrderId, o.getId())
                    .set(HisPathwayExec::getExecDate, LocalDate.now())
                    .set(HisPathwayExec::getOperatorId, operatorId)
                    .eq(HisPathwayExec::getId, e.getId())
                    .eq(HisPathwayExec::getExecStatus, 1));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instanceId", instanceId);
        out.put("dayNo", day);
        out.put("execCount", todo.size());
        out.put("orderCount", orders.size());
        out.put("orderIds", orders.stream().map(HisInpOrder::getId).collect(Collectors.toList()));
        out.put("orders", orders);
        log.info("临床路径执行当天任务: instanceId={}, dayNo={}, 开嘱={}条", instanceId, day, orders.size());
        return out;
    }

    /** 跳过任务: exec_status 1待执行 → 3跳过(乐观更新) */
    @Transactional(rollbackFor = Exception.class)
    public HisPathwayExec skipExec(Long execId) {
        requireExec(execId);
        int affected = execMapper.update(null, new LambdaUpdateWrapper<HisPathwayExec>()
                .set(HisPathwayExec::getExecStatus, 3)
                .set(HisPathwayExec::getExecDate, LocalDate.now())
                .set(HisPathwayExec::getOperatorId, currentStaffId())
                .eq(HisPathwayExec::getId, execId)
                .eq(HisPathwayExec::getExecStatus, 1));
        if (affected == 0) {
            throw new BizException("仅待执行的任务可跳过, 请刷新后重试");
        }
        log.info("临床路径任务跳过: execId={}", execId);
        return execMapper.selectById(execId);
    }

    /** 标记变异: exec_status 1待执行 → 4变异, 记录 variance_reason(乐观更新) */
    @Transactional(rollbackFor = Exception.class)
    public HisPathwayExec markVariance(Long execId, String reason) {
        requireExec(execId);
        int affected = execMapper.update(null, new LambdaUpdateWrapper<HisPathwayExec>()
                .set(HisPathwayExec::getExecStatus, 4)
                .set(HisPathwayExec::getVarianceReason, StringUtils.hasText(reason) ? reason.trim() : null)
                .set(HisPathwayExec::getExecDate, LocalDate.now())
                .set(HisPathwayExec::getOperatorId, currentStaffId())
                .eq(HisPathwayExec::getId, execId)
                .eq(HisPathwayExec::getExecStatus, 1));
        if (affected == 0) {
            throw new BizException("仅待执行的任务可登记变异, 请刷新后重试");
        }
        log.info("临床路径任务变异登记: execId={}, reason={}", execId, reason);
        return execMapper.selectById(execId);
    }

    /* ==================== 内部实现 ==================== */

    /** 就诊归属校验: 不存在报400; 非牵头机构仅可访问本机构就诊(牵头机构全医共体) */
    private HisInpVisit requireVisit(Long visitId) {
        HisInpVisit v = visitMapper.selectById(visitId);
        if (v == null) {
            throw new BizException(400, "住院就诊记录不存在");
        }
        Long scope = guard.scopeOrgId(v.getOrgId());
        if (scope == null) {
            throw new BizException(403, "当前账号未归属任何机构, 无法访问住院数据");
        }
        if (!scope.equals(v.getOrgId())) {
            throw new BizException(403, "无权访问其他机构的住院就诊数据");
        }
        return v;
    }

    /** 实例存在性 + 机构归属校验 */
    private HisPathwayInstance requireInstance(Long id) {
        if (id == null) {
            throw new BizException(400, "路径实例ID不能为空");
        }
        HisPathwayInstance inst = getById(id);
        if (inst == null) {
            throw new BizException(404, "临床路径实例不存在");
        }
        Long scope = guard.scopeOrgId(inst.getOrgId());
        if (scope == null) {
            throw new BizException(403, "当前账号未归属任何机构, 无法访问临床路径数据");
        }
        if (!scope.equals(inst.getOrgId())) {
            throw new BizException(403, "无权访问其他机构的临床路径数据");
        }
        return inst;
    }

    /** 执行记录存在性 + 机构归属校验 */
    private HisPathwayExec requireExec(Long execId) {
        if (execId == null) {
            throw new BizException(400, "执行记录ID不能为空");
        }
        HisPathwayExec e = execMapper.selectById(execId);
        if (e == null) {
            throw new BizException(404, "路径执行记录不存在");
        }
        Long scope = guard.scopeOrgId(e.getOrgId());
        if (scope == null) {
            throw new BizException(403, "当前账号未归属任何机构, 无法访问临床路径数据");
        }
        if (!scope.equals(e.getOrgId())) {
            throw new BizException(403, "无权访问其他机构的临床路径数据");
        }
        return e;
    }

    /** 路径任务 → 住院医嘱开立请求(补缺省: orderType 默认长期, orderCategory 按任务类型映射) */
    private InpOrderDTO buildOrderDTO(HisPathwayInstance inst, HisPathwayTask t) {
        InpOrderDTO dto = new InpOrderDTO();
        dto.setInpVisitId(inst.getInpVisitId());
        dto.setOrderType(t.getOrderType() != null ? t.getOrderType() : 1);
        dto.setOrderCategory(t.getOrderCategory() != null ? t.getOrderCategory() : mapTaskTypeToCategory(t.getTaskType()));
        dto.setOrderContent(t.getOrderContent());
        dto.setChargeItemId(t.getChargeItemId());
        dto.setDrugId(t.getDrugId());
        dto.setSpec(t.getSpec());
        dto.setDosage(t.getDosage());
        dto.setDosageUnit(t.getDosageUnit());
        dto.setUsageCode(t.getUsageCode());
        dto.setFreqCode(t.getFreqCode());
        dto.setQuantity(t.getQuantity());
        dto.setUnitPrice(t.getUnitPrice());
        return dto;
    }

    /** 任务类型 → 医嘱分类缺省映射: 2护理→5护理 3检查→2检查 4检验→3检验 5宣教→7其他, 1医嘱/未知→7其他 */
    private Integer mapTaskTypeToCategory(Integer taskType) {
        if (taskType == null) {
            return 7;
        }
        switch (taskType) {
            case 2: return 5;
            case 3: return 2;
            case 4: return 3;
            case 5: return 7;
            default: return 7;
        }
    }

    /** 模板最大天数(节点 day_no 最大值) */
    private Integer maxDayNo(Long templateId) {
        if (templateId == null) {
            return null;
        }
        List<HisPathwayNode> nodes = nodeMapper.selectList(new LambdaQueryWrapper<HisPathwayNode>()
                .eq(HisPathwayNode::getTemplateId, templateId));
        return nodes.stream().map(HisPathwayNode::getDayNo).filter(Objects::nonNull)
                .max(Integer::compareTo).orElse(null);
    }

    /** 当前登录职工ID(可为空: 护士等非医生账号) */
    private Long currentStaffId() {
        LoginUser lu = UserContext.get();
        return lu == null ? null : lu.getStaffId();
    }
}

