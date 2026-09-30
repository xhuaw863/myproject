package com.yb.hi.service.inpatient;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.dto.inpatient.PathwayNodeDTO;
import com.yb.hi.dto.inpatient.PathwayTaskDTO;
import com.yb.hi.dto.inpatient.PathwayTemplateDTO;
import com.yb.hi.entity.inpatient.HisPathwayNode;
import com.yb.hi.entity.inpatient.HisPathwayTask;
import com.yb.hi.entity.inpatient.HisPathwayTemplate;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.mapper.inpatient.HisPathwayNodeMapper;
import com.yb.hi.mapper.inpatient.HisPathwayTaskMapper;
import com.yb.hi.mapper.inpatient.HisPathwayTemplateMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 临床路径模板服务: 模板(病种入径标准) → 节点(第X天网格) → 任务(医嘱模板) 三级定义维护。
 * - 读隔离: guard.scopeOrgId 语义(牵头可见全医共体, 非牵头强制本机构);
 * - 写归属: 新数据 orgId 一律取父级(模板/节点)机构, 不信任客户端入参;
 * - 版本化: copyTemplate 深拷贝模板+节点+任务, version+1 且 pathwayCode 追加 -vN 后缀;
 * - 删除: 删除节点级联逻辑删除其下任务(不影响已生成的实例执行记录快照)。
 */
@Slf4j
@Service
public class PathwayTemplateService extends ServiceImpl<HisPathwayTemplateMapper, HisPathwayTemplate> {

    private final HisPathwayNodeMapper nodeMapper;
    private final HisPathwayTaskMapper taskMapper;
    private final OrgAccessGuard guard;

    public PathwayTemplateService(HisPathwayNodeMapper nodeMapper, HisPathwayTaskMapper taskMapper,
                                  OrgAccessGuard guard) {
        this.nodeMapper = nodeMapper;
        this.taskMapper = taskMapper;
        this.guard = guard;
    }

    /* ==================== 模板 ==================== */

    /** 模板分页查询: 关键字匹配路径名/诊断名, status 可选(1启用 0停用) */
    public IPage<HisPathwayTemplate> listTemplates(Long orgId, String keyword, Integer status, int page, int size) {
        String kw = StringUtils.hasText(keyword) ? keyword.trim() : null;
        return lambdaQuery()
                .eq(orgId != null, HisPathwayTemplate::getOrgId, orgId)
                .eq(status != null, HisPathwayTemplate::getStatus, status)
                .and(StringUtils.hasText(kw), w -> w.like(HisPathwayTemplate::getPathwayName, kw)
                        .or().like(HisPathwayTemplate::getDiseaseName, kw))
                .orderByDesc(HisPathwayTemplate::getId)
                .page(new Page<>(safePage(page), safeSize(size)));
    }

    /** 模板详情: 模板本体 + 节点树(每个节点含其下任务列表, 按天/排序号) */
    public Map<String, Object> getDetail(Long id) {
        HisPathwayTemplate t = requireTemplate(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("template", t);
        out.put("nodes", buildNodesWithTasks(id));
        return out;
    }

    /** 创建模板: pathwayCode 缺省自动生成, 同机构内编码唯一; 初始 version=1, status=1启用 */
    @Transactional(rollbackFor = Exception.class)
    public HisPathwayTemplate create(PathwayTemplateDTO dto) {
        if (dto == null || !StringUtils.hasText(dto.getPathwayName())) {
            throw new BizException(400, "路径名称不能为空");
        }
        Long orgId = guard.currentOrgId();
        String code = StringUtils.hasText(dto.getPathwayCode()) ? dto.getPathwayCode().trim()
                : "PATH" + System.currentTimeMillis();
        long dup = lambdaQuery()
                .eq(HisPathwayTemplate::getOrgId, orgId)
                .eq(HisPathwayTemplate::getPathwayCode, code)
                .count();
        if (dup > 0) {
            throw new BizException("路径编码已存在: " + code);
        }
        HisPathwayTemplate t = new HisPathwayTemplate();
        t.setOrgId(orgId);
        t.setPathwayCode(code);
        t.setPathwayName(dto.getPathwayName().trim());
        t.setDiseaseCode(dto.getDiseaseCode());
        t.setDiseaseName(dto.getDiseaseName());
        t.setDeptId(dto.getDeptId());
        t.setAvgLength(dto.getAvgLength());
        t.setTotalCost(dto.getTotalCost());
        t.setVersion(1);
        t.setStatus(1);
        t.setDescription(dto.getDescription());
        save(t);
        log.info("创建临床路径模板: id={}, code={}, name={}, orgId={}", t.getId(), code, t.getPathwayName(), orgId);
        return t;
    }

    /** 编辑模板: pathwayCode/pathwayName 传空保留原值, 其余字段按表单全量覆盖(允许清空) */
    @Transactional(rollbackFor = Exception.class)
    public HisPathwayTemplate update(Long id, PathwayTemplateDTO dto) {
        if (dto == null) {
            throw new BizException(400, "请求参数不能为空");
        }
        HisPathwayTemplate t = requireTemplate(id);
        String code = StringUtils.hasText(dto.getPathwayCode()) ? dto.getPathwayCode().trim() : t.getPathwayCode();
        if (!code.equals(t.getPathwayCode())) {
            long dup = lambdaQuery()
                    .eq(HisPathwayTemplate::getOrgId, t.getOrgId())
                    .eq(HisPathwayTemplate::getPathwayCode, code)
                    .ne(HisPathwayTemplate::getId, id)
                    .count();
            if (dup > 0) {
                throw new BizException("路径编码已存在: " + code);
            }
        }
        String name = StringUtils.hasText(dto.getPathwayName()) ? dto.getPathwayName().trim() : t.getPathwayName();
        lambdaUpdate()
                .set(HisPathwayTemplate::getPathwayCode, code)
                .set(HisPathwayTemplate::getPathwayName, name)
                .set(HisPathwayTemplate::getDiseaseCode, dto.getDiseaseCode())
                .set(HisPathwayTemplate::getDiseaseName, dto.getDiseaseName())
                .set(HisPathwayTemplate::getDeptId, dto.getDeptId())
                .set(HisPathwayTemplate::getAvgLength, dto.getAvgLength())
                .set(HisPathwayTemplate::getTotalCost, dto.getTotalCost())
                .set(HisPathwayTemplate::getDescription, dto.getDescription())
                .eq(HisPathwayTemplate::getId, id)
                .update();
        return getById(id);
    }

    /** 启用/停用切换(乐观更新: 基于读取到的旧状态条件更新) */
    @Transactional(rollbackFor = Exception.class)
    public HisPathwayTemplate toggleStatus(Long id) {
        HisPathwayTemplate t = requireTemplate(id);
        int oldStatus = t.getStatus() == null ? 1 : t.getStatus();
        int newStatus = oldStatus == 1 ? 0 : 1;
        boolean ok = lambdaUpdate()
                .set(HisPathwayTemplate::getStatus, newStatus)
                .eq(HisPathwayTemplate::getId, id)
                .eq(HisPathwayTemplate::getStatus, oldStatus)
                .update();
        if (!ok) {
            throw new BizException("模板状态已变化, 请刷新后重试");
        }
        log.info("临床路径模板状态切换: id={}, {} -> {}", id, oldStatus, newStatus);
        return getById(id);
    }

    /** 复制为新版本: 深拷贝模板+节点+任务, version+1, pathwayCode 追加 -vN, 新版本默认启用 */
    @Transactional(rollbackFor = Exception.class)
    public HisPathwayTemplate copyTemplate(Long id) {
        HisPathwayTemplate src = requireTemplate(id);
        HisPathwayTemplate n = new HisPathwayTemplate();
        n.setOrgId(src.getOrgId());
        n.setPathwayCode(nextVersionCode(src.getOrgId(), src.getPathwayCode()));
        n.setPathwayName(src.getPathwayName());
        n.setDiseaseCode(src.getDiseaseCode());
        n.setDiseaseName(src.getDiseaseName());
        n.setDeptId(src.getDeptId());
        n.setAvgLength(src.getAvgLength());
        n.setTotalCost(src.getTotalCost());
        n.setVersion(src.getVersion() == null ? 2 : src.getVersion() + 1);
        n.setStatus(1);
        n.setDescription(src.getDescription());
        save(n);
        // 深拷贝节点 + 任务
        List<HisPathwayNode> nodes = nodeMapper.selectList(new LambdaQueryWrapper<HisPathwayNode>()
                .eq(HisPathwayNode::getTemplateId, src.getId())
                .orderByAsc(HisPathwayNode::getDayNo)
                .orderByAsc(HisPathwayNode::getSortNo));
        int taskCount = 0;
        for (HisPathwayNode oldNode : nodes) {
            HisPathwayNode nn = new HisPathwayNode();
            nn.setOrgId(src.getOrgId());
            nn.setTemplateId(n.getId());
            nn.setDayNo(oldNode.getDayNo());
            nn.setNodeName(oldNode.getNodeName());
            nn.setNodeDesc(oldNode.getNodeDesc());
            nn.setSortNo(oldNode.getSortNo());
            nodeMapper.insert(nn);
            List<HisPathwayTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<HisPathwayTask>()
                    .eq(HisPathwayTask::getNodeId, oldNode.getId())
                    .orderByAsc(HisPathwayTask::getSortNo));
            for (HisPathwayTask ot : tasks) {
                HisPathwayTask nt = new HisPathwayTask();
                nt.setOrgId(src.getOrgId());
                nt.setNodeId(nn.getId());
                nt.setTemplateId(n.getId());
                nt.setTaskType(ot.getTaskType());
                nt.setChargeItemId(ot.getChargeItemId());
                nt.setDrugId(ot.getDrugId());
                nt.setOrderType(ot.getOrderType());
                nt.setOrderCategory(ot.getOrderCategory());
                nt.setOrderContent(ot.getOrderContent());
                nt.setSpec(ot.getSpec());
                nt.setDosage(ot.getDosage());
                nt.setDosageUnit(ot.getDosageUnit());
                nt.setUsageCode(ot.getUsageCode());
                nt.setFreqCode(ot.getFreqCode());
                nt.setQuantity(ot.getQuantity());
                nt.setUnitPrice(ot.getUnitPrice());
                nt.setIsMandatory(ot.getIsMandatory());
                nt.setSortNo(ot.getSortNo());
                taskMapper.insert(nt);
                taskCount++;
            }
        }
        log.info("复制临床路径模板为新版本: srcId={}, newId={}, newCode={}, 节点={}, 任务={}",
                id, n.getId(), n.getPathwayCode(), nodes.size(), taskCount);
        return getById(n.getId());
    }

    /* ==================== 节点 ==================== */

    /** 节点列表(含每个节点下的任务列表, 按 day_no + sort_no 排序) */
    public List<Map<String, Object>> listNodes(Long templateId) {
        if (templateId == null) {
            throw new BizException(400, "模板ID不能为空");
        }
        requireTemplate(templateId);
        return buildNodesWithTasks(templateId);
    }

    /** 新增节点: 机构/模板归属取自模板, dayNo 必填且 >=1 */
    @Transactional(rollbackFor = Exception.class)
    public HisPathwayNode createNode(PathwayNodeDTO dto) {
        if (dto == null || dto.getTemplateId() == null) {
            throw new BizException(400, "模板ID不能为空");
        }
        if (dto.getDayNo() == null || dto.getDayNo() < 1) {
            throw new BizException(400, "第X天必须为不小于1的整数");
        }
        HisPathwayTemplate t = requireTemplate(dto.getTemplateId());
        HisPathwayNode n = new HisPathwayNode();
        n.setOrgId(t.getOrgId());
        n.setTemplateId(t.getId());
        n.setDayNo(dto.getDayNo());
        n.setNodeName(StringUtils.hasText(dto.getNodeName()) ? dto.getNodeName().trim()
                : "第" + dto.getDayNo() + "天");
        n.setNodeDesc(dto.getNodeDesc());
        n.setSortNo(dto.getSortNo() == null ? 0 : dto.getSortNo());
        nodeMapper.insert(n);
        log.info("新增临床路径节点: id={}, templateId={}, dayNo={}", n.getId(), t.getId(), n.getDayNo());
        return n;
    }

    /** 编辑节点(传空字段保留原值) */
    @Transactional(rollbackFor = Exception.class)
    public HisPathwayNode updateNode(Long id, PathwayNodeDTO dto) {
        if (dto == null) {
            throw new BizException(400, "请求参数不能为空");
        }
        requireNode(id);
        if (dto.getDayNo() != null && dto.getDayNo() < 1) {
            throw new BizException(400, "第X天必须为不小于1的整数");
        }
        nodeMapper.update(null, new LambdaUpdateWrapper<HisPathwayNode>()
                .set(dto.getDayNo() != null, HisPathwayNode::getDayNo, dto.getDayNo())
                .set(StringUtils.hasText(dto.getNodeName()), HisPathwayNode::getNodeName,
                        StringUtils.hasText(dto.getNodeName()) ? dto.getNodeName().trim() : null)
                .set(dto.getNodeDesc() != null, HisPathwayNode::getNodeDesc, dto.getNodeDesc())
                .set(dto.getSortNo() != null, HisPathwayNode::getSortNo, dto.getSortNo())
                .eq(HisPathwayNode::getId, id));
        return nodeMapper.selectById(id);
    }

    /** 删除节点: 级联逻辑删除其下全部任务(已入径实例的 exec 快照不受影响) */
    @Transactional(rollbackFor = Exception.class)
    public void deleteNode(Long id) {
        requireNode(id);
        nodeMapper.deleteById(id);
        taskMapper.delete(new LambdaQueryWrapper<HisPathwayTask>().eq(HisPathwayTask::getNodeId, id));
        log.info("删除临床路径节点(级联任务): nodeId={}", id);
    }

    /* ==================== 任务 ==================== */

    /** 新增任务: 归属取自节点, orderContent 必填, 枚举字段范围校验 */
    @Transactional(rollbackFor = Exception.class)
    public HisPathwayTask createTask(PathwayTaskDTO dto) {
        if (dto == null) {
            throw new BizException(400, "请求参数不能为空");
        }
        if (dto.getNodeId() == null) {
            throw new BizException(400, "节点ID不能为空");
        }
        if (!StringUtils.hasText(dto.getOrderContent())) {
            throw new BizException(400, "医嘱内容不能为空");
        }
        validateTaskEnums(dto.getTaskType(), dto.getOrderType(), dto.getOrderCategory());
        HisPathwayNode node = requireNode(dto.getNodeId());
        HisPathwayTask t = new HisPathwayTask();
        t.setOrgId(node.getOrgId());
        t.setNodeId(node.getId());
        t.setTemplateId(node.getTemplateId());
        t.setTaskType(dto.getTaskType() == null ? 1 : dto.getTaskType());
        t.setChargeItemId(dto.getChargeItemId());
        t.setDrugId(dto.getDrugId());
        t.setOrderType(dto.getOrderType());
        t.setOrderCategory(dto.getOrderCategory());
        t.setOrderContent(dto.getOrderContent().trim());
        t.setSpec(dto.getSpec());
        t.setDosage(dto.getDosage());
        t.setDosageUnit(dto.getDosageUnit());
        t.setUsageCode(dto.getUsageCode());
        t.setFreqCode(dto.getFreqCode());
        t.setQuantity(dto.getQuantity());
        t.setUnitPrice(dto.getUnitPrice());
        t.setIsMandatory(dto.getIsMandatory() == null ? 1 : dto.getIsMandatory());
        t.setSortNo(dto.getSortNo() == null ? 0 : dto.getSortNo());
        taskMapper.insert(t);
        log.info("新增临床路径任务: id={}, nodeId={}, content={}", t.getId(), node.getId(), t.getOrderContent());
        return t;
    }

    /** 编辑任务(传空字段保留原值) */
    @Transactional(rollbackFor = Exception.class)
    public HisPathwayTask updateTask(Long id, PathwayTaskDTO dto) {
        if (dto == null) {
            throw new BizException(400, "请求参数不能为空");
        }
        requireTask(id);
        validateTaskEnums(dto.getTaskType(), dto.getOrderType(), dto.getOrderCategory());
        taskMapper.update(null, new LambdaUpdateWrapper<HisPathwayTask>()
                .set(dto.getTaskType() != null, HisPathwayTask::getTaskType, dto.getTaskType())
                .set(dto.getChargeItemId() != null, HisPathwayTask::getChargeItemId, dto.getChargeItemId())
                .set(dto.getDrugId() != null, HisPathwayTask::getDrugId, dto.getDrugId())
                .set(dto.getOrderType() != null, HisPathwayTask::getOrderType, dto.getOrderType())
                .set(dto.getOrderCategory() != null, HisPathwayTask::getOrderCategory, dto.getOrderCategory())
                .set(StringUtils.hasText(dto.getOrderContent()), HisPathwayTask::getOrderContent,
                        StringUtils.hasText(dto.getOrderContent()) ? dto.getOrderContent().trim() : null)
                .set(dto.getSpec() != null, HisPathwayTask::getSpec, dto.getSpec())
                .set(dto.getDosage() != null, HisPathwayTask::getDosage, dto.getDosage())
                .set(dto.getDosageUnit() != null, HisPathwayTask::getDosageUnit, dto.getDosageUnit())
                .set(dto.getUsageCode() != null, HisPathwayTask::getUsageCode, dto.getUsageCode())
                .set(dto.getFreqCode() != null, HisPathwayTask::getFreqCode, dto.getFreqCode())
                .set(dto.getQuantity() != null, HisPathwayTask::getQuantity, dto.getQuantity())
                .set(dto.getUnitPrice() != null, HisPathwayTask::getUnitPrice, dto.getUnitPrice())
                .set(dto.getIsMandatory() != null, HisPathwayTask::getIsMandatory, dto.getIsMandatory())
                .set(dto.getSortNo() != null, HisPathwayTask::getSortNo, dto.getSortNo())
                .eq(HisPathwayTask::getId, id));
        return taskMapper.selectById(id);
    }

    /** 删除任务(逻辑删除; 已入径实例的 exec 快照保留, 执行时任务缺失自动跳过) */
    @Transactional(rollbackFor = Exception.class)
    public void deleteTask(Long id) {
        requireTask(id);
        taskMapper.deleteById(id);
        log.info("删除临床路径任务: taskId={}", id);
    }

    /** 批量添加任务(单事务, 任一条失败整批回滚) */
    @Transactional(rollbackFor = Exception.class)
    public List<HisPathwayTask> batchCreateTasks(List<PathwayTaskDTO> dtos) {
        if (CollectionUtils.isEmpty(dtos)) {
            throw new BizException(400, "批量任务不能为空");
        }
        List<HisPathwayTask> out = new ArrayList<>(dtos.size());
        for (PathwayTaskDTO dto : dtos) {
            out.add(createTask(dto));
        }
        log.info("批量新增临床路径任务: 条数={}", out.size());
        return out;
    }

    /* ==================== 内部实现 ==================== */

    /** 模板存在性 + 机构读隔离校验(非牵头仅可访问本机构, 牵头可访问全医共体) */
    private HisPathwayTemplate requireTemplate(Long id) {
        if (id == null) {
            throw new BizException(400, "模板ID不能为空");
        }
        HisPathwayTemplate t = getById(id);
        if (t == null) {
            throw new BizException(404, "路径模板不存在");
        }
        Long scope = guard.scopeOrgId(t.getOrgId());
        if (scope == null) {
            throw new BizException(403, "当前账号未归属任何机构, 无法访问路径模板");
        }
        if (!scope.equals(t.getOrgId())) {
            throw new BizException(403, "无权访问其他机构的路径模板");
        }
        return t;
    }

    private HisPathwayNode requireNode(Long id) {
        HisPathwayNode n = nodeMapper.selectById(id);
        if (n == null) {
            throw new BizException(404, "路径节点不存在");
        }
        requireTemplate(n.getTemplateId());
        return n;
    }

    private HisPathwayTask requireTask(Long id) {
        HisPathwayTask t = taskMapper.selectById(id);
        if (t == null) {
            throw new BizException(404, "路径任务不存在");
        }
        requireTemplate(t.getTemplateId());
        return t;
    }

    /** 员工模板的节点树: 节点按天排序, 每个节点携带其下任务列表 */
    private List<Map<String, Object>> buildNodesWithTasks(Long templateId) {
        List<HisPathwayNode> nodes = nodeMapper.selectList(new LambdaQueryWrapper<HisPathwayNode>()
                .eq(HisPathwayNode::getTemplateId, templateId)
                .orderByAsc(HisPathwayNode::getDayNo)
                .orderByAsc(HisPathwayNode::getSortNo)
                .orderByAsc(HisPathwayNode::getId));
        List<HisPathwayTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<HisPathwayTask>()
                .eq(HisPathwayTask::getTemplateId, templateId)
                .orderByAsc(HisPathwayTask::getSortNo)
                .orderByAsc(HisPathwayTask::getId));
        Map<Long, List<HisPathwayTask>> byNode = tasks.stream()
                .collect(Collectors.groupingBy(HisPathwayTask::getNodeId));
        List<Map<String, Object>> out = new ArrayList<>(nodes.size());
        for (HisPathwayNode n : nodes) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.getId());
            m.put("templateId", n.getTemplateId());
            m.put("dayNo", n.getDayNo());
            m.put("nodeName", n.getNodeName());
            m.put("nodeDesc", n.getNodeDesc());
            m.put("sortNo", n.getSortNo());
            m.put("tasks", byNode.getOrDefault(n.getId(), Collections.emptyList()));
            out.add(m);
        }
        return out;
    }

    /**
     * 生成新版本路径编码: 去除原编码 -vN 后缀后, 取同机构同前缀已用最大版本号 +1。
     * 例: PATH001 -> PATH001-v2; PATH001-v2 -> PATH001-v3。
     */
    private String nextVersionCode(Long orgId, String originalCode) {
        String base = originalCode == null ? "PATH" : originalCode.trim().replaceAll("-v\\d+$", "");
        List<HisPathwayTemplate> siblings = lambdaQuery()
                .eq(HisPathwayTemplate::getOrgId, orgId)
                .likeRight(HisPathwayTemplate::getPathwayCode, base)
                .list();
        Pattern p = Pattern.compile("^" + Pattern.quote(base) + "(?:-v(\\d+))?$");
        int max = 1;
        for (HisPathwayTemplate s : siblings) {
            if (s.getPathwayCode() == null) {
                continue;
            }
            Matcher m = p.matcher(s.getPathwayCode());
            if (m.matches()) {
                int v = m.group(1) == null ? 1 : Integer.parseInt(m.group(1));
                if (v > max) {
                    max = v;
                }
            }
        }
        return base + "-v" + (max + 1);
    }

    /** 任务枚举范围校验(taskType 1-5 / orderType 1-2 / orderCategory 1-7, 允许为空) */
    private void validateTaskEnums(Integer taskType, Integer orderType, Integer orderCategory) {
        if (taskType != null && (taskType < 1 || taskType > 5)) {
            throw new BizException(400, "任务类型必须为1-5(医嘱/护理/检查/检验/宣教)");
        }
        if (orderType != null && orderType != 1 && orderType != 2) {
            throw new BizException(400, "医嘱类型必须为1长期/2临时");
        }
        if (orderCategory != null && (orderCategory < 1 || orderCategory > 7)) {
            throw new BizException(400, "医嘱分类必须为1-7(药品/检查/检验/治疗/护理/膳食/其他)");
        }
    }

    private static long safePage(long page) {
        return page < 1 ? 1 : page;
    }

    private static long safeSize(long size) {
        if (size < 1) {
            return 20;
        }
        return size > 200 ? 200 : size;
    }
}

