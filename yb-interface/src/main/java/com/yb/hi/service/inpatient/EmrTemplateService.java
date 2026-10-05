package com.yb.hi.service.inpatient;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.dto.inpatient.EmrFieldDefDTO;
import com.yb.hi.dto.inpatient.EmrTemplateDTO;
import com.yb.hi.entity.inpatient.HisEmrDataset;
import com.yb.hi.entity.inpatient.HisEmrDatasetElement;
import com.yb.hi.entity.inpatient.HisEmrTemplate;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.inpatient.EmrDatasetElementMapper;
import com.yb.hi.mapper.inpatient.EmrDatasetMapper;
import com.yb.hi.mapper.inpatient.HisEmrTemplateMapper;
import com.yb.hi.platform.entity.SysTenant;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.platform.service.SysTenantService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 病历结构化模板服务: 维护 8 类标准文书模板(入院记录/首次病程/日常病程/上级医师查房/手术记录/术后病程/出院小结/死亡记录)
 * 及其结构化字段定义, 并在启动时按租户幂等播种标准模板(@Order(7), 表由 DictSchemaMigration@0 建, 机构由 RbacInitializer@3 建)。
 *
 * 编码约定: his_emr_template 唯一键 uk_emr_tpl_code(template_code, deleted) 不含租户 —— 标准编码为全租户共享的
 * 全局主数据(tenant_id=1 优先获得标准编码); 播种前用 JdbcTemplate 全局查重(绕开租户插件), 编码已被其他租户
 * 占用则跳过, 各租户可自建扩展编码模板。creatTemplate 对重复编码返回友好 400。
 *
 * 三级继承: scope_level 0全院/1科室/2个人, 子模板经 parent_template_id 挂母板; 母板 locked_sections 锁定
 * 章节内容由 propagate 统一下发, listByScope 返回个人覆盖科室覆盖全院的有效合并视图。
 * Tiptap 文档: document 列承载 ProseMirror JSON(章节 emrSection/数据元 emrField 节点), 可由数据集生成
 * (createFromDataset)或设计器产出; 批量维护见 batchUpdateElementAttr/batchReplaceSection/batchReplaceHeader。
 */
@Slf4j
@Order(7)
@Service
public class EmrTemplateService implements ApplicationRunner {

    /** 种子模板: {模板编码, 模板名称, record_type, template_category, scope(1住院 2门诊)} */
    private static final String[][] SEED_TEMPLATES = {
            {"EMR_ADMIT", "入院记录", "1", "1", "1"},
            {"EMR_FIRST_PROG", "首次病程记录", "1", "2", "1"},
            {"EMR_DAILY_PROG", "日常病程记录", "1", "3", "1"},
            {"EMR_SENIOR_ROUND", "上级医师查房记录", "1", "4", "1"},
            {"EMR_SURGERY", "手术记录", "6", "5", "1"},
            {"EMR_POST_SURGERY", "术后病程记录", "1", "6", "1"},
            {"EMR_DISCHARGE", "出院小结", "7", "7", "1"},
            {"EMR_DEATH", "死亡记录", "1", "8", "1"},
            {"EMR_OUTP_GENERAL", "门诊病历(通用)", "2", "21", "2"},
            {"EMR_OUTP_TCM", "中医门诊病历", "2", "22", "2"},
            /* P2 扩展: 10-15 类文书(病案首页/交接班/转科/知情同意/讨论/会诊), record_type 与签名规则口径一致 */
            {"EMR_HOMEPAGE", "病案首页", "10", "10", "1"},
            {"EMR_HANDOVER", "交接班记录", "11", "11", "1"},
            {"EMR_TRANSFER", "转科记录", "12", "12", "1"},
            {"EMR_CONSENT", "知情同意书", "13", "13", "1"},
            {"EMR_DISCUSSION", "疑难病例讨论记录", "14", "14", "1"},
            {"EMR_CONSULTATION", "会诊记录", "15", "15", "1"},
            /* P8 中医三模板: 与西医同文书类型并列(templateCategory 与 record_type 同值), 医生选"入院记录/日常病程/病案首页"时中西医模板同屏可选 */
            {"EMR_TCM_HOMEPAGE", "中医病案首页", "10", "10", "1"},
            {"EMR_TCM_ADMIT", "中医入院记录", "1", "1", "1"},
            {"EMR_TCM_PROG", "中医病程记录", "3", "3", "1"}
    };

    /** 播种时附加 Tiptap 文档骨架的模板编码(P2 新增 10-15 类 + P3 门诊两模板 + 8 类住院基本文书): 由种子字段定义生成章节/数据元节点, 书写器可直接渲染 */
    private static final Set<String> SEED_DOC_CODES = new HashSet<>(Arrays.asList(
            "EMR_HOMEPAGE", "EMR_HANDOVER", "EMR_TRANSFER", "EMR_CONSENT", "EMR_DISCUSSION", "EMR_CONSULTATION",
            "EMR_OUTP_GENERAL", "EMR_OUTP_TCM",
            "EMR_TCM_HOMEPAGE", "EMR_TCM_ADMIT", "EMR_TCM_PROG",
            "EMR_ADMIT", "EMR_FIRST_PROG", "EMR_DAILY_PROG", "EMR_SENIOR_ROUND",
            "EMR_SURGERY", "EMR_POST_SURGERY", "EMR_DISCHARGE", "EMR_DEATH"));

    /**
     * 8 类住院基本文书编码(按病历书写规范补齐分节结构化模板): 存量回填时, 对"未改动的全院标准行"
     * 同时回写充实后的 fields 与 document(其余 SEED_DOC_CODES 只补 document, 不动其 fields)。
     */
    private static final Set<String> INP_BASIC_DOC_CODES = new HashSet<>(Arrays.asList(
            "EMR_ADMIT", "EMR_FIRST_PROG", "EMR_DAILY_PROG", "EMR_SENIOR_ROUND",
            "EMR_SURGERY", "EMR_POST_SURGERY", "EMR_DISCHARGE", "EMR_DEATH"));

    /**
     * 需在升级时刷新"未改动全院标准行"(含已有文档)的种子编码: 8 类住院基本文书 + 门诊通用病历。
     * 这些均为全租户共享的标准主数据(机构定制走 dept/personal 克隆行), 故升级时从种子重刷 fields+document 以传播规范缺项补充。
     */
    private static final Set<String> STD_TEMPLATE_REFRESH_CODES;
    static {
        Set<String> s = new HashSet<>(INP_BASIC_DOC_CODES);
        s.add("EMR_OUTP_GENERAL");
        STD_TEMPLATE_REFRESH_CODES = Collections.unmodifiableSet(s);
    }

    /** 中医三模板文档编码(P8): 走 buildTcmSeedDocument 逐章节生成(与 SEED_OUTP_CODES 同级分流) */
    private static final Set<String> SEED_TCM_CODES = new HashSet<>(Arrays.asList(
            "EMR_TCM_HOMEPAGE", "EMR_TCM_ADMIT", "EMR_TCM_PROG"));

    /** 门诊多章节(SOAP)文档编码: 走 buildOutpSeedDocument 逐章节生成(其余为单章节骨架); 章节标识与前端编辑器 sectionKey/后端 attrs.key 双写一致 */
    private static final Set<String> SEED_OUTP_CODES = new HashSet<>(Arrays.asList(
            "EMR_OUTP_GENERAL", "EMR_OUTP_TCM"));

    /** Tiptap 节点类型常量 */
    private static final String NODE_DOC = "doc";
    private static final String NODE_SECTION = "emrSection";
    private static final String NODE_FIELD = "emrField";
    private static final String NODE_HEADER = "emrHeader";

    /** 传播同步阈值: 子模板数超过该值转 @Async 后台执行, 避免接口长事务 */
    private static final int PROPAGATE_ASYNC_THRESHOLD = 10;

    private final HisEmrTemplateMapper templateMapper;
    private final OrgAccessGuard guard;
    private final SysTenantService tenantService;
    private final JdbcTemplate jdbcTemplate;
    private final EmrDatasetMapper datasetMapper;
    private final EmrDatasetElementMapper datasetElementMapper;
    /** 模板版本快照服务(高级版): 保存/发布/回滚留存版本 */
    private final com.yb.hi.service.emr.EmrTemplateVersionService versionService;
    /** 模板引用反查索引服务(高级版): 保存时重算引用 */
    private final com.yb.hi.service.emr.EmrRefIndexService refIndexService;
    /** 模板发布审批流水服务(高级版): 提交/通过/驳回留痕 */
    private final com.yb.hi.service.emr.EmrTemplateApprovalService approvalService;
    /** 自注入代理: @Async 传播方法须经代理调用才异步(同类内直调不走代理) */
    private final ObjectProvider<EmrTemplateService> selfProvider;

    public EmrTemplateService(HisEmrTemplateMapper templateMapper, OrgAccessGuard guard,
                              SysTenantService tenantService, JdbcTemplate jdbcTemplate,
                              EmrDatasetMapper datasetMapper, EmrDatasetElementMapper datasetElementMapper,
                              com.yb.hi.service.emr.EmrTemplateVersionService versionService,
                              com.yb.hi.service.emr.EmrRefIndexService refIndexService,
                              com.yb.hi.service.emr.EmrTemplateApprovalService approvalService,
                              ObjectProvider<EmrTemplateService> selfProvider) {
        this.templateMapper = templateMapper;
        this.guard = guard;
        this.tenantService = tenantService;
        this.jdbcTemplate = jdbcTemplate;
        this.datasetMapper = datasetMapper;
        this.datasetElementMapper = datasetElementMapper;
        this.versionService = versionService;
        this.refIndexService = refIndexService;
        this.approvalService = approvalService;
        this.selfProvider = selfProvider;
    }

    /* ================= 启动播种 ================= */

    @Override
    public void run(ApplicationArguments args) {
        List<SysTenant> tenants;
        try {
            tenants = tenantService.listAll();
        } catch (Exception e) {
            log.warn("病历模板种子跳过(租户表未就绪): {}", e.getMessage());
            return;
        }
        if (tenants == null) {
            return;
        }
        tenants.sort(Comparator.comparing(SysTenant::getId));
        for (SysTenant t : tenants) {
            if (t == null || t.getId() == null) {
                continue;
            }
            if (SysTenantService.PLATFORM_TENANT_CODE.equals(t.getTenantCode())) {
                continue; // 平台运营方租户无医院业务数据
            }
            Long prev = TenantContext.get();
            try {
                TenantContext.set(t.getId());
                seedTenant(t.getId());
            } catch (Exception e) {
                log.warn("租户[{}] 病历模板种子跳过(可能表未建): {}", t.getId(), e.getMessage());
            } finally {
                if (prev == null) {
                    TenantContext.clear();
                } else {
                    TenantContext.set(prev);
                }
            }
        }
    }

    /** 按租户播种缺失的标准模板(逐码判空, 部分缺失可补种; 全局编码被其他租户占用则跳过) */
    private void seedTenant(Long tenantId) {
        Long orgId = resolveLeadOrgId(tenantId);
        if (orgId == null) {
            return; // 机构未建(RbacInitializer 未跑完), 下次启动补种
        }
        int added = 0;
        int occupied = 0;
        for (String[] def : SEED_TEMPLATES) {
            String code = def[0];
            boolean localExists = !templateMapper.selectList(Wrappers.<HisEmrTemplate>lambdaQuery()
                    .eq(HisEmrTemplate::getTemplateCode, code)).isEmpty();
            if (localExists) {
                backfillSeedDocIfMissing(code); // 存量行缺 Tiptap 文档则幂等回填(P3 门诊升级/早期漏种), 不覆盖已有文档
                continue;
            }
            if (existsGlobal(code)) {
                occupied++; // 标准编码已被其他租户占用(全局唯一), 本租户跳过
                continue;
            }
            try {
                HisEmrTemplate t = new HisEmrTemplate();
                t.setOrgId(orgId);
                t.setTemplateCode(code);
                t.setTemplateName(def[1]);
                t.setRecordType(Integer.valueOf(def[2]));
                t.setTemplateCategory(Integer.valueOf(def[3]));
                t.setFields(buildSeedFields(code));
                if (SEED_DOC_CODES.contains(code)) {
                    t.setDocument(buildSeedDocument(code, def[1]));
                }
                t.setDeptId(0L);
                t.setScope(Integer.valueOf(def[4]));
                t.setScopeLevel(0); // 全院级种子(与 DDL 默认一致, 显式声明)
                t.setVersion(1);
                t.setStatus(1);
                templateMapper.insert(t);
                added++;
            } catch (DuplicateKeyException e) {
                occupied++;
            }
        }
        if (added > 0) {
            log.info("租户[{}] 病历标准模板初始化完成(新增{}个)", tenantId, added);
        }
        if (occupied > 0) {
            log.info("租户[{}] 标准模板编码已由其他租户占用, 跳过{}个(标准编码全租户共享, 可自建扩展模板)", tenantId, occupied);
        }
    }

    /** 解析租户牵头机构: 优先 is_lead=1, 兜底最小 id(his_emr_template.org_id 非空) */
    private Long resolveLeadOrgId(Long tenantId) {
        List<Long> lead = jdbcTemplate.query(
                "SELECT id FROM sys_org WHERE tenant_id = ? AND deleted = 0 AND is_lead = 1 ORDER BY id LIMIT 1",
                (rs, i) -> rs.getLong(1), tenantId);
        if (!lead.isEmpty()) {
            return lead.get(0);
        }
        List<Long> any = jdbcTemplate.query(
                "SELECT id FROM sys_org WHERE tenant_id = ? AND deleted = 0 ORDER BY id LIMIT 1",
                (rs, i) -> rs.getLong(1), tenantId);
        return any.isEmpty() ? null : any.get(0);
    }

    /** 全局(跨租户)编码查重: JdbcTemplate 直查 deliberate 绕开租户插件, 与 uk_emr_tpl_code 口径一致 */
    private boolean existsGlobal(String code) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_emr_template WHERE template_code = ? AND deleted = 0", Integer.class, code);
        return n != null && n > 0;
    }

    /**
     * 存量模板 Tiptap 文档/字段幂等补种与升级:
     * (1) document 缺失的行(SEED_DOC_CODES 命中): 回填种子文档(不覆盖已有文档)。
     * (2) STD_TEMPLATE_REFRESH_CODES 命中且为"未改动全院标准行"(scope_level=0/dept_id=0/staff_id 为空):
     *     即使已有文档也从最新种子重刷 fields+document, 以传播规范缺项补充(如门诊通用病历的就诊日期/医师签名);
     *     租户定制行(scope_level>0 或已挂 dept/staff)与非刷新集的标准行一律不覆盖。
     * 失败只告警不阻断后续种子。
     */
    private void backfillSeedDocIfMissing(String code) {
        if (!SEED_DOC_CODES.contains(code)) {
            return;
        }
        try {
            List<HisEmrTemplate> rows = templateMapper.selectList(Wrappers.<HisEmrTemplate>lambdaQuery()
                    .eq(HisEmrTemplate::getTemplateCode, code));
            boolean refreshable = STD_TEMPLATE_REFRESH_CODES.contains(code);
            for (HisEmrTemplate t : rows) {
                boolean docEmpty = t.getDocument() == null || t.getDocument().isEmpty();
                boolean pristineGlobal = (t.getScopeLevel() == null || t.getScopeLevel() == 0)
                        && (t.getDeptId() == null || t.getDeptId() == 0L)
                        && t.getStaffId() == null;
                boolean rewriteFields = pristineGlobal && refreshable; // 全局标准行且属刷新集: fields↔document 同步
                if (!docEmpty && !rewriteFields) {
                    continue; // 已有文档且非需刷新的标准行(定制/非刷新集): 不覆盖
                }
                HisEmrTemplate upd = new HisEmrTemplate();
                upd.setId(t.getId());
                upd.setDocument(buildSeedDocument(code, t.getTemplateName()));
                if (rewriteFields) {
                    upd.setFields(buildSeedFields(code));
                }
                templateMapper.updateById(upd); // 仅非 null 字段更新
                log.info("模板[{}] 存量行{}{}", code, docEmpty ? "缺 Tiptap 文档已回填" : "为全院标准行已从种子重刷",
                        rewriteFields ? "并同步 fields" : "");
            }
        } catch (Exception e) {
            log.warn("模板[{}] Tiptap 文档/字段补种跳过: {}", code, e.getMessage());
        }
    }

    /* ================= 模板查询 ================= */

    /** 模板列表(recordType/category/scope 可选筛选; mine=true 仅返回当前用户可见的启用模板[全院+本科室+本人]; 否则 deptId 非空时含全院 dept_id=0) */
    public R<List<HisEmrTemplate>> listTemplates(Integer recordType, Integer category, Long deptId, Integer scope, boolean mine) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<HisEmrTemplate> qw =
                Wrappers.<HisEmrTemplate>lambdaQuery()
                .eq(recordType != null, HisEmrTemplate::getRecordType, recordType)
                .eq(category != null, HisEmrTemplate::getTemplateCategory, category)
                .eq(scope != null, HisEmrTemplate::getScope, scope);
        LoginUser lu = UserContext.get();
        if (mine && lu != null) {
            final Long sId = lu.getStaffId();
            final Long dId = lu.getDeptId();
            qw.eq(HisEmrTemplate::getStatus, 1);
            qw.and(w -> {
                w.nested(g -> g.eq(HisEmrTemplate::getDeptId, 0L).isNull(HisEmrTemplate::getStaffId));
                if (dId != null) {
                    w.or(d -> d.eq(HisEmrTemplate::getDeptId, dId).isNull(HisEmrTemplate::getStaffId));
                }
                if (sId != null) {
                    w.or(s -> s.eq(HisEmrTemplate::getStaffId, sId));
                }
            });
        } else if (deptId != null) {
            qw.and(w -> w.eq(HisEmrTemplate::getDeptId, deptId).or().eq(HisEmrTemplate::getDeptId, 0L));
        }
        qw.orderByAsc(HisEmrTemplate::getScope)
                .orderByAsc(HisEmrTemplate::getRecordType)
                .orderByAsc(HisEmrTemplate::getTemplateCategory)
                .orderByAsc(HisEmrTemplate::getId);
        return R.ok(templateMapper.selectList(qw));
    }

    /** 模板详情 */
    public R<HisEmrTemplate> getTemplate(Long id) {
        HisEmrTemplate t = id == null ? null : templateMapper.selectById(id);
        if (t == null) {
            throw new BizException(400, "病历模板不存在");
        }
        return R.ok(t);
    }

    /** 按编码获取模板(取版本号最大的启用版本) */
    public R<HisEmrTemplate> getTemplateByCode(String code) {
        if (!StringUtils.hasText(code)) {
            throw new BizException(400, "模板编码不能为空");
        }
        List<HisEmrTemplate> list = templateMapper.selectList(Wrappers.<HisEmrTemplate>lambdaQuery()
                .eq(HisEmrTemplate::getTemplateCode, code.trim())
                .orderByDesc(HisEmrTemplate::getVersion)
                .orderByDesc(HisEmrTemplate::getId));
        if (list.isEmpty()) {
            throw new BizException(400, "模板编码不存在: " + code);
        }
        return R.ok(list.get(0));
    }

    /* ================= 模板维护 ================= */

    /** 创建模板(fields 列表序列化为规范字段定义 JSON) */
    public R<HisEmrTemplate> createTemplate(EmrTemplateDTO dto) {
        if (dto == null || !StringUtils.hasText(dto.getTemplateCode())) {
            throw new BizException(400, "模板编码不能为空");
        }
        if (!StringUtils.hasText(dto.getTemplateName())) {
            throw new BizException(400, "模板名称不能为空");
        }
        String code = dto.getTemplateCode().trim();
        ensureCodeAvailable(code);
        LoginUser lu = UserContext.get();
        String ownerScope = StringUtils.hasText(dto.getOwnerScope()) ? dto.getOwnerScope().trim().toLowerCase() : "global";
        Long staffId = null;
        Long deptId;
        if ("personal".equals(ownerScope)) {
            if (lu == null || lu.getStaffId() == null) {
                throw new BizException(400, "个人模板须绑定当前登录职工");
            }
            staffId = lu.getStaffId();
            deptId = lu.getDeptId() != null ? lu.getDeptId() : 0L;
        } else if ("dept".equals(ownerScope)) {
            if (lu == null || lu.getDeptId() == null) {
                throw new BizException(400, "科室模板须指定归属科室");
            }
            deptId = dto.getDeptId() != null ? dto.getDeptId() : lu.getDeptId();
            if (!deptId.equals(lu.getDeptId()) && !isAdmin(lu)) {
                throw new BizException(403, "无权为其他科室创建模板");
            }
        } else {
            guard.requireLeadOrg("仅牵头机构管理员可维护全院病历模板");
            deptId = 0L;
        }
        HisEmrTemplate t = new HisEmrTemplate();
        t.setOrgId(guard.currentOrgId());
        t.setTemplateCode(code);
        t.setTemplateName(dto.getTemplateName().trim());
        t.setRecordType(dto.getRecordType());
        t.setTemplateCategory(dto.getTemplateCategory());
        t.setFields(resolveFieldsJson(dto));
        t.setLayout(dto.getLayout());
        validateParentLink(null, dto.getParentTemplateId());
        t.setParentTemplateId(dto.getParentTemplateId());
        t.setScopeLevel(resolveScopeLevel(ownerScope, dto.getScopeLevel()));
        t.setLockedSections(normalizeLockedSections(dto.getLockedSections()));
        t.setDocument(validateDocument(dto.getDocument()));
        t.setPrintScript(dto.getPrintScript());
        t.setPrintConfig(normalizePrintConfig(dto.getPrintConfig()));
        t.setDatasetId(dto.getDatasetId());
        t.setScope(dto.getScope() != null ? dto.getScope() : 1);
        t.setStaffId(staffId);
        t.setDeptId(deptId);
        t.setVersion(dto.getVersion() != null ? dto.getVersion() : 1);
        t.setStatus(dto.getStatus() != null ? dto.getStatus() : 1);
        t.setPublishStatus(dto.getPublishStatus() != null ? dto.getPublishStatus() : 3);
        try {
            templateMapper.insert(t);
        } catch (DuplicateKeyException e) {
            throw new BizException(400, "模板编码已存在: " + code);
        }
        // 高级版: 新建即落初始版本快照 + 重算引用索引(失败不阻断新建, 仅告警)
        safeSnapshotAndReindex(t, dto.getChangeSummary() != null ? dto.getChangeSummary() : "初始创建", "save");
        log.info("新建病历模板: id={}, code={}, name={}, owner={}", t.getId(), code, t.getTemplateName(), ownerScope);
        return R.ok(templateMapper.selectById(t.getId()));
    }

    /** 更新模板(版本号自增, 支持停用/启用) */
    public R<Void> updateTemplate(Long id, EmrTemplateDTO dto) {
        HisEmrTemplate exist = id == null ? null : templateMapper.selectById(id);
        if (exist == null) {
            throw new BizException(400, "病历模板不存在");
        }
        guardEditable(exist);
        if (dto == null) {
            throw new BizException(400, "模板内容不能为空");
        }
        if (StringUtils.hasText(dto.getTemplateCode())) {
            String code = dto.getTemplateCode().trim();
            if (!code.equals(exist.getTemplateCode())) {
                ensureCodeAvailable(code);
                exist.setTemplateCode(code);
            }
        }
        if (StringUtils.hasText(dto.getTemplateName())) {
            exist.setTemplateName(dto.getTemplateName().trim());
        }
        if (dto.getRecordType() != null) {
            exist.setRecordType(dto.getRecordType());
        }
        if (dto.getTemplateCategory() != null) {
            exist.setTemplateCategory(dto.getTemplateCategory());
        }
        if (dto.getFields() != null || dto.getRawFields() != null) {
            exist.setFields(resolveFieldsJson(dto));
        }
        if (dto.getLayout() != null) {
            exist.setLayout(dto.getLayout());
        }
        if (dto.getScopeLevel() != null) {
            if (dto.getScopeLevel() < 0 || dto.getScopeLevel() > 2) {
                throw new BizException(400, "scopeLevel 仅支持 0全院/1科室/2个人");
            }
            exist.setScopeLevel(dto.getScopeLevel());
        }
        if (dto.getParentTemplateId() != null) {
            validateParentLink(exist.getId(), dto.getParentTemplateId());
            exist.setParentTemplateId(dto.getParentTemplateId());
        }
        if (dto.getLockedSections() != null) {
            exist.setLockedSections(normalizeLockedSections(dto.getLockedSections()));
        }
        if (dto.getDocument() != null) {
            exist.setDocument(validateDocument(dto.getDocument()));
        }
        if (dto.getPrintScript() != null) {
            exist.setPrintScript(dto.getPrintScript());
        }
        if (dto.getPrintConfig() != null) {
            exist.setPrintConfig(normalizePrintConfig(dto.getPrintConfig()));
        }
        if (dto.getDatasetId() != null) {
            exist.setDatasetId(dto.getDatasetId());
        }
        if (dto.getScope() != null) {
            exist.setScope(dto.getScope());
        }
        if (dto.getDeptId() != null) {
            exist.setDeptId(dto.getDeptId());
        }
        if (dto.getStatus() != null) {
            exist.setStatus(dto.getStatus());
        }
        exist.setVersion((exist.getVersion() == null ? 1 : exist.getVersion()) + 1);
        try {
            templateMapper.updateById(exist);
        } catch (DuplicateKeyException e) {
            throw new BizException(400, "模板编码已存在: " + exist.getTemplateCode());
        }
        // 高级版: 更新后落版本快照 + 重算引用索引(失败不阻断更新, 仅告警)
        safeSnapshotAndReindex(exist, dto.getChangeSummary(), "save");
        log.info("更新病历模板: id={}, code={}, version={}", id, exist.getTemplateCode(), exist.getVersion());
        return R.ok();
    }

    /**
     * 回滚到指定历史版本(高级版): 取目标版本四载荷写回模板并再自增 version,
     * 同时落一条 operate_type=rollback 的新快照(追加式, 不覆盖历史)。守卫同 updateTemplate。
     */
    public R<Void> rollbackVersion(Long templateId, Integer versionNo, String summary) {
        HisEmrTemplate exist = templateId == null ? null : templateMapper.selectById(templateId);
        if (exist == null) {
            throw new BizException(400, "病历模板不存在");
        }
        guardEditable(exist);
        com.yb.hi.entity.inpatient.HisEmrTemplateVersion target = versionService.getVersionByNo(templateId, versionNo);
        exist.setDocument(target.getDocument());
        exist.setFields(target.getFields());
        exist.setPrintConfig(target.getPrintConfig());
        exist.setLockedSections(target.getLockedSections());
        exist.setVersion((exist.getVersion() == null ? 1 : exist.getVersion()) + 1);
        templateMapper.updateById(exist);
        String note = summary != null ? summary : ("回滚自版本 " + versionNo);
        safeSnapshotAndReindex(exist, note, "rollback");
        log.info("回滚病历模板: id={}, fromVersion={}, toVersion={}", templateId, versionNo, exist.getVersion());
        return R.ok();
    }

    /** 保存类操作后置钩子: 落版本快照 + 重算引用索引; 任一失败仅告警, 不回滚主事务外的核心保存。 */
    private void safeSnapshotAndReindex(HisEmrTemplate state, String summary, String operateType) {
        try {
            versionService.snapshot(state, summary, operateType);
        } catch (Exception e) {
            log.warn("模板[{}]版本快照失败(不阻断保存): {}", state.getId(), e.getMessage());
        }
        try {
            refIndexService.rebuild(state.getId(), state.getOrgId(), state.getDocument());
        } catch (Exception e) {
            log.warn("模板[{}]引用索引失败(不阻断保存): {}", state.getId(), e.getMessage());
        }
    }

    /* ================= 高级版: 模板发布审批 ================= */

    /** 提交待审(0草稿/2驳回 → 1待审); 归属层级鉴权同 updateTemplate。 */
    public R<Void> submitForReview(Long templateId) {
        HisEmrTemplate exist = templateId == null ? null : templateMapper.selectById(templateId);
        if (exist == null) {
            throw new BizException(400, "病历模板不存在");
        }
        guardEditable(exist);
        int from = exist.getPublishStatus() == null ? 3 : exist.getPublishStatus();
        if (from == 1) {
            throw new BizException(400, "模板已在待审中");
        }
        exist.setPublishStatus(1);
        templateMapper.updateById(exist);
        approvalService.recordSubmit(templateId, from);
        log.info("提交模板审核: id={}, from={}", templateId, from);
        return R.ok();
    }

    /** 撤回草稿(1待审 → 0草稿): 提交人本人/归属层级维护者可撤回, 流水行同步回填为驳回动作留痕。 */
    public R<Void> retractReview(Long templateId) {
        HisEmrTemplate exist = templateId == null ? null : templateMapper.selectById(templateId);
        if (exist == null) {
            throw new BizException(400, "病历模板不存在");
        }
        guardEditable(exist);
        int from = exist.getPublishStatus() == null ? 3 : exist.getPublishStatus();
        if (from != 1) {
            throw new BizException(400, "仅待审模板可撤回草稿");
        }
        exist.setPublishStatus(0);
        templateMapper.updateById(exist);
        approvalService.recordReview(templateId, 1, 0, "retract", "提交人撤回草稿");
        log.info("撤回模板审核: id={}", templateId);
        return R.ok();
    }

    /** 审核通过(1待审 → 3已发布, 并置 status=1 启用); 审核人权限见 requireApproveReviewer。 */
    public R<Void> approveReview(Long templateId, String opinion) {
        HisEmrTemplate exist = templateId == null ? null : templateMapper.selectById(templateId);
        if (exist == null) {
            throw new BizException(400, "病历模板不存在");
        }
        requireApproveReviewer(exist);
        int from = exist.getPublishStatus() == null ? 3 : exist.getPublishStatus();
        if (from != 1) {
            throw new BizException(400, "仅待审状态可审核通过");
        }
        exist.setPublishStatus(3);
        exist.setStatus(1);
        templateMapper.updateById(exist);
        approvalService.recordReview(templateId, from, 3, "pass", opinion);
        versionService.snapshot(exist, "审核发布", "publish");
        log.info("模板审核通过: id={}", templateId);
        return R.ok();
    }

    /** 审核驳回(1待审 → 2已驳回, 保留原启用态不影响已发布使用); 审核人权限同 approve。 */
    public R<Void> rejectReview(Long templateId, String opinion) {
        HisEmrTemplate exist = templateId == null ? null : templateMapper.selectById(templateId);
        if (exist == null) {
            throw new BizException(400, "病历模板不存在");
        }
        requireApproveReviewer(exist);
        int from = exist.getPublishStatus() == null ? 3 : exist.getPublishStatus();
        if (from != 1) {
            throw new BizException(400, "仅待审状态可驳回");
        }
        exist.setPublishStatus(2);
        templateMapper.updateById(exist);
        approvalService.recordReview(templateId, from, 2, "reject", opinion);
        log.info("模板审核驳回: id={}", templateId);
        return R.ok();
    }

    /** 待审列表(全局+科室级 scope_level<=1): 仅管理员/审核人可见。 */
    public R<List<HisEmrTemplate>> listPendingReviews() {
        LoginUser lu = UserContext.get();
        if (!isAdmin(lu)) {
            throw new BizException(403, "仅管理员可查看待审列表");
        }
        List<HisEmrTemplate> rows = templateMapper.selectList(Wrappers.<HisEmrTemplate>lambdaQuery()
                .eq(HisEmrTemplate::getPublishStatus, 1)
                .le(HisEmrTemplate::getScopeLevel, 1)
                .orderByDesc(HisEmrTemplate::getUpdateTime)
                .orderByDesc(HisEmrTemplate::getId));
        return R.ok(rows);
    }

    /**
     * 审核人守卫: 必须是 ADMIN/SUPER_ADMIN; 全院模板(scope_level=0)额外要求牵头机构;
     * 非牵头管理员不得跨机构穿透审科(由 scopeOrgId 天然隔离, 此处不放宽)。
     */
    private void requireApproveReviewer(HisEmrTemplate t) {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            throw new BizException(401, "未登录");
        }
        if (!isAdmin(lu)) {
            throw new BizException(403, "仅管理员可审核模板");
        }
        boolean global = t.getScopeLevel() == null || t.getScopeLevel() == 0;
        if (global) {
            guard.requireLeadOrg("仅牵头机构管理员可审核发布全院模板");
        }
    }

    /** 删除模板(逻辑删除; 按归属层级鉴权) */
    public R<Void> removeTemplate(Long id) {
        HisEmrTemplate exist = id == null ? null : templateMapper.selectById(id);
        if (exist == null) {
            throw new BizException(400, "病历模板不存在");
        }
        guardEditable(exist);
        templateMapper.deleteById(id);
        log.info("删除病历模板: id={}", id);
        return R.ok();
    }

    /** 获取模板完整字段定义 JSON(含 dictRef/subFields/section 等新属性, 供设计器/增强渲染器使用) */
    public R<Object> getDefs(Long id) {
        HisEmrTemplate t = id == null ? null : templateMapper.selectById(id);
        if (t == null) {
            throw new BizException(400, "病历模板不存在");
        }
        return R.ok(parseArraySafe(t.getFields()));
    }

    /** 获取模板字段定义(供前端渲染表单): 规范化为纯字符串键值对列表 */
    public List<Map<String, String>> getFieldDefs(Long templateId) {
        HisEmrTemplate t = templateId == null ? null : templateMapper.selectById(templateId);
        if (t == null) {
            throw new BizException(400, "病历模板不存在");
        }
        List<Map<String, String>> list = new ArrayList<>();
        JSONArray arr = parseArraySafe(t.getFields());
        for (int i = 0; i < arr.size(); i++) {
            JSONObject f = arr.getJSONObject(i);
            Map<String, String> m = new LinkedHashMap<>();
            m.put("fieldKey", text(f.get("fieldKey")));
            m.put("label", text(f.get("label")));
            m.put("type", text(f.get("type")));
            m.put("required", String.valueOf(Boolean.TRUE.equals(asBoolean(f.get("required")))));
            m.put("maxLength", text(f.get("maxLength")));
            m.put("options", joinOptions(f.get("options")));
            m.put("defaultValue", text(f.get("defaultValue")));
            m.put("defaultMacro", text(f.get("defaultMacro")));
            m.put("placeholder", text(f.get("placeholder")));
            list.add(m);
        }
        return list;
    }

    /* ================= 三级继承: 母板锁定章节传播 ================= */

    /**
     * 母板传播: 将全院/科室母板 templateId 的 locked_sections 锁定章节内容下发到全部直接子模板
     * (parent_template_id = templateId): 母板章节节点的 attrs/content 深拷贝覆盖子模板同key节点并保存。
     * 子模板数 <= PROPAGATE_ASYNC_THRESHOLD 同步执行返回统计; 超过则提交 @Async 后台执行。
     * 个人模板不可作为母板下发(仅全院/科室母板可锁定章节)。
     */
    public R<Map<String, Object>> propagate(Long templateId) {
        HisEmrTemplate parent = templateId == null ? null : templateMapper.selectById(templateId);
        if (parent == null) {
            throw new BizException(400, "病历模板不存在");
        }
        if ((parent.getScopeLevel() != null && parent.getScopeLevel() == 2) || parent.getStaffId() != null) {
            throw new BizException(400, "个人模板不支持向下传播(仅全院/科室母板可锁定章节下发)");
        }
        guardEditable(parent);
        List<String> lockedKeys = parseLockedKeys(parent.getLockedSections());
        List<HisEmrTemplate> children = listChildren(templateId);
        Map<String, Object> stat = new LinkedHashMap<>();
        stat.put("children", children.size());
        if (lockedKeys.isEmpty()) {
            stat.put("updated", 0);
            stat.put("async", false);
            stat.put("message", "母板未锁定任何章节, 无需传播");
            return R.ok(stat);
        }
        JSONObject parentDoc = parseDocSafe(parent.getDocument());
        if (parentDoc == null) {
            throw new BizException(400, "母板缺少 Tiptap 文档, 无法传播");
        }
        if (children.size() > PROPAGATE_ASYNC_THRESHOLD) {
            selfProvider.getObject().propagateAsync(templateId, TenantContext.get(), UserContext.get());
            stat.put("updated", -1);
            stat.put("async", true);
            stat.put("message", "子模板较多, 已提交后台异步传播");
            return R.ok(stat);
        }
        int updated = applyLockedSections(parentDoc, lockedKeys, children);
        stat.put("updated", updated);
        stat.put("async", false);
        return R.ok(stat);
    }

    /** 后台异步传播(@Async; 租户/用户 ThreadLocal 由调用线程捕获后在本线程恢复, 失败仅记日志不影响主流程) */
    @Async
    public void propagateAsync(Long templateId, Long tenantId, LoginUser user) {
        Long prevTenant = TenantContext.get();
        LoginUser prevUser = UserContext.get();
        try {
            if (tenantId != null) {
                TenantContext.set(tenantId);
            }
            if (user != null) {
                UserContext.set(user);
            }
            HisEmrTemplate parent = templateMapper.selectById(templateId);
            if (parent == null) {
                log.warn("异步传播跳过: 母板不存在 id={}", templateId);
                return;
            }
            List<String> lockedKeys = parseLockedKeys(parent.getLockedSections());
            JSONObject parentDoc = parseDocSafe(parent.getDocument());
            if (lockedKeys.isEmpty() || parentDoc == null) {
                return;
            }
            int updated = applyLockedSections(parentDoc, lockedKeys, listChildren(templateId));
            log.info("母板传播完成(异步): parentId={}, updated={}", templateId, updated);
        } catch (Exception e) {
            log.error("母板传播异步执行失败: parentId={}", templateId, e);
        } finally {
            if (prevTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(prevTenant);
            }
            if (prevUser == null) {
                UserContext.clear();
            } else {
                UserContext.set(prevUser);
            }
        }
    }

    /** 直接下级子模板(parent_template_id = templateId) */
    private List<HisEmrTemplate> listChildren(Long templateId) {
        return templateMapper.selectList(Wrappers.<HisEmrTemplate>lambdaQuery()
                .eq(HisEmrTemplate::getParentTemplateId, templateId)
                .orderByAsc(HisEmrTemplate::getId));
    }

    /** 锁定章节下发: 母板节点 attrs/content 深拷贝覆盖各子模板同key节点, 返回实际更新子模板数 */
    private int applyLockedSections(JSONObject parentDoc, List<String> lockedKeys, List<HisEmrTemplate> children) {
        int updated = 0;
        for (HisEmrTemplate child : children) {
            JSONObject childDoc = parseDocSafe(child.getDocument());
            if (childDoc == null) {
                continue;
            }
            int replaced = 0;
            for (String key : lockedKeys) {
                JSONObject parentNode = findSectionNode(parentDoc, key);
                JSONObject childNode = findSectionNode(childDoc, key);
                if (parentNode == null || childNode == null) {
                    continue;
                }
                if (parentNode.get("attrs") != null) {
                    childNode.put("attrs", deepCopy(parentNode.get("attrs")));
                }
                if (parentNode.get("content") == null) {
                    childNode.remove("content");
                } else {
                    childNode.put("content", deepCopy(parentNode.get("content")));
                }
                replaced++;
            }
            if (replaced > 0) {
                child.setDocument(JSON.toJSONString(childDoc));
                templateMapper.updateById(child);
                updated++;
            }
        }
        return updated;
    }

    /** 递归定位章节/小节节点(emrSection 且 attrs.key 命中) */
    private JSONObject findSectionNode(JSONObject node, String key) {
        if (node == null || key == null) {
            return null;
        }
        if (NODE_SECTION.equals(node.getString("type"))) {
            JSONObject attrs = node.getJSONObject("attrs");
            if (attrs != null && key.equals(attrs.getString("key"))) {
                return node;
            }
        }
        JSONArray content = node.getJSONArray("content");
        if (content == null) {
            return null;
        }
        for (int i = 0; i < content.size(); i++) {
            Object el = content.get(i);
            if (el instanceof JSONObject) {
                JSONObject hit = findSectionNode((JSONObject) el, key);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    /** 解析母板锁定章节key列表(非法JSON返回空表, 不阻断传播) */
    private List<String> parseLockedKeys(String json) {
        List<String> keys = new ArrayList<>();
        if (!StringUtils.hasText(json)) {
            return keys;
        }
        try {
            JSONArray arr = JSON.parseArray(json);
            if (arr != null) {
                for (int i = 0; i < arr.size(); i++) {
                    String k = arr.getString(i);
                    if (StringUtils.hasText(k) && !keys.contains(k)) {
                        keys.add(k);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("locked_sections JSON解析失败: {}", e.getMessage());
        }
        return keys;
    }

    /* ================= 三级继承: 层级合并查询 ================= */

    /**
     * 按层级查询有效模板(合并视图): scopeLevel 限定合并深度 0=仅全院 1=全院+科室 2=全院+科室+个人(缺省2)。
     * 同一(记录类型,类别,适用范围)视为同一文书, 深层层级整体覆盖浅层(个人 > 科室 > 全院);
     * 未分类模板按编码并列共存。deptId/staffId 缺省回落当前登录科室/职工; 非管理员仅可查询本人个人模板。
     */
    public R<List<HisEmrTemplate>> listByScope(Integer scopeLevel, Long deptId, Long staffId) {
        int maxLevel;
        if (scopeLevel == null) {
            maxLevel = 2;
        } else if (scopeLevel < 0 || scopeLevel > 2) {
            throw new BizException(400, "scopeLevel 仅支持 0全院/1科室/2个人");
        } else {
            maxLevel = scopeLevel;
        }
        LoginUser lu = UserContext.get();
        Long effDept = deptId != null ? deptId : (lu == null ? null : lu.getDeptId());
        Long effStaff = staffId != null ? staffId : (lu == null ? null : lu.getStaffId());
        if (staffId != null && !isAdmin(lu) && (lu == null || !staffId.equals(lu.getStaffId()))) {
            throw new BizException(403, "仅管理员可查询他人个人模板");
        }
        Map<String, List<HisEmrTemplate>> merged = new LinkedHashMap<>();
        merged.putAll(groupByOverrideKey(templateMapper.selectList(Wrappers.<HisEmrTemplate>lambdaQuery()
                .eq(HisEmrTemplate::getStatus, 1)
                .eq(HisEmrTemplate::getPublishStatus, 3)
                .isNull(HisEmrTemplate::getStaffId)
                .and(w -> w.eq(HisEmrTemplate::getDeptId, 0L).or().isNull(HisEmrTemplate::getDeptId))
                .orderByAsc(HisEmrTemplate::getRecordType)
                .orderByAsc(HisEmrTemplate::getTemplateCategory)
                .orderByAsc(HisEmrTemplate::getId))));
        if (maxLevel >= 1 && effDept != null && effDept != 0L) {
            merged.putAll(groupByOverrideKey(templateMapper.selectList(Wrappers.<HisEmrTemplate>lambdaQuery()
                    .eq(HisEmrTemplate::getStatus, 1)
                    .eq(HisEmrTemplate::getPublishStatus, 3)
                    .isNull(HisEmrTemplate::getStaffId)
                    .eq(HisEmrTemplate::getDeptId, effDept)
                    .orderByAsc(HisEmrTemplate::getRecordType)
                    .orderByAsc(HisEmrTemplate::getTemplateCategory)
                    .orderByAsc(HisEmrTemplate::getId))));
        }
        if (maxLevel >= 2 && effStaff != null) {
            merged.putAll(groupByOverrideKey(templateMapper.selectList(Wrappers.<HisEmrTemplate>lambdaQuery()
                    .eq(HisEmrTemplate::getStatus, 1)
                    .eq(HisEmrTemplate::getStaffId, effStaff)
                    .orderByAsc(HisEmrTemplate::getRecordType)
                    .orderByAsc(HisEmrTemplate::getTemplateCategory)
                    .orderByAsc(HisEmrTemplate::getId))));
        }
        List<HisEmrTemplate> out = new ArrayList<>();
        for (List<HisEmrTemplate> group : merged.values()) {
            out.addAll(group);
        }
        return R.ok(out);
    }

    /** 按覆盖键分组(同键同层多个模板并存, 深层整组覆盖浅层) */
    private Map<String, List<HisEmrTemplate>> groupByOverrideKey(List<HisEmrTemplate> list) {
        Map<String, List<HisEmrTemplate>> m = new LinkedHashMap<>();
        for (HisEmrTemplate t : list) {
            m.computeIfAbsent(overrideKeyOf(t), k -> new ArrayList<>()).add(t);
        }
        return m;
    }

    /** 覆盖键: 同记录类型+类别+适用范围视为同一文书; 无类别模板按编码并列共存 */
    private String overrideKeyOf(HisEmrTemplate t) {
        if (t.getTemplateCategory() != null) {
            return "cat:" + t.getRecordType() + "|" + t.getTemplateCategory() + "|" + t.getScope();
        }
        return "code:" + (StringUtils.hasText(t.getTemplateCode()) ? t.getTemplateCode() : ("id:" + t.getId()));
    }

    /* ================= 数据集 → Tiptap 模板 ================= */

    /**
     * 由数据集生成 Tiptap 文档模板: 读取 his_emr_dataset + his_emr_dataset_element,
     * 按 章节 → 小节 → 数据元 生成 emrSection/emrField 节点树, 并同步回填 fields 定义(兼容既有渲染链路)。
     * scopeLevel: 0全院(牵头管理员) 1科室(当前登录科室) 2个人(当前登录职工), 归属与守卫同 createTemplate。
     */
    public R<HisEmrTemplate> createFromDataset(Long datasetId, String name, Integer scopeLevel) {
        if (datasetId == null) {
            throw new BizException(400, "数据集不能为空");
        }
        if (!StringUtils.hasText(name)) {
            throw new BizException(400, "模板名称不能为空");
        }
        if (scopeLevel != null && (scopeLevel < 0 || scopeLevel > 2)) {
            throw new BizException(400, "scopeLevel 仅支持 0全院/1科室/2个人");
        }
        int level = scopeLevel == null ? 0 : scopeLevel;
        HisEmrDataset ds = datasetMapper.selectById(datasetId);
        if (ds == null) {
            throw new BizException(400, "数据集不存在");
        }
        List<HisEmrDatasetElement> elements = datasetElementMapper.selectList(
                Wrappers.<HisEmrDatasetElement>lambdaQuery()
                        .eq(HisEmrDatasetElement::getDatasetId, datasetId)
                        .orderByAsc(HisEmrDatasetElement::getSortNo)
                        .orderByAsc(HisEmrDatasetElement::getId));
        if (elements.isEmpty()) {
            throw new BizException(400, "数据集下无数据元, 无法生成模板");
        }
        LoginUser lu = UserContext.get();
        Long staffId = null;
        Long deptId;
        if (level == 2) {
            if (lu == null || lu.getStaffId() == null) {
                throw new BizException(400, "个人模板须绑定当前登录职工");
            }
            staffId = lu.getStaffId();
            deptId = lu.getDeptId() != null ? lu.getDeptId() : 0L;
        } else if (level == 1) {
            if (lu == null || lu.getDeptId() == null || lu.getDeptId() == 0L) {
                throw new BizException(400, "科室模板须绑定当前登录科室");
            }
            deptId = lu.getDeptId();
        } else {
            guard.requireLeadOrg("仅牵头机构管理员可维护全院病历模板");
            deptId = 0L;
        }
        String code = generateDatasetTemplateCode(datasetId);
        ensureCodeAvailable(code);
        HisEmrTemplate t = new HisEmrTemplate();
        t.setOrgId(guard.currentOrgId());
        t.setTemplateCode(code);
        t.setTemplateName(name.trim());
        t.setRecordType(ds.getScope() != null && ds.getScope() == 2 ? 2 : 1);
        t.setFields(buildFieldsJson(elements));
        t.setScope(ds.getScope() != null && ds.getScope() == 2 ? 2 : 1);
        t.setStaffId(staffId);
        t.setDeptId(deptId);
        t.setScopeLevel(level);
        t.setDatasetId(datasetId);
        t.setDocument(buildTiptapDocument(elements));
        t.setVersion(1);
        t.setStatus(1);
        try {
            templateMapper.insert(t);
        } catch (DuplicateKeyException e) {
            throw new BizException(400, "模板编码已存在: " + code);
        }
        log.info("由数据集生成病历模板: id={}, code={}, datasetId={}, level={}", t.getId(), code, datasetId, level);
        return R.ok(templateMapper.selectById(t.getId()));
    }

    /** 数据集模板编码: EMR_DS_{datasetId}_{13位毫秒}(长度<=50, 跨租户唯一性校验同 createTemplate) */
    private String generateDatasetTemplateCode(Long datasetId) {
        return "EMR_DS_" + datasetId + "_" + System.currentTimeMillis();
    }

    /** 数据元序列 → Tiptap 文档JSON: 章节(emrSection/editMode=mixed) → 小节(emrSection/editMode=form) → 数据元段落(emrField) */
    private String buildTiptapDocument(List<HisEmrDatasetElement> elements) {
        JSONArray chapters = new JSONArray();
        Map<String, JSONObject> chapterNodes = new LinkedHashMap<>();
        Map<String, Map<String, JSONObject>> sectionNodes = new LinkedHashMap<>();
        for (HisEmrDatasetElement el : elements) {
            String chapterKey = StringUtils.hasText(el.getChapterKey()) ? el.getChapterKey().trim() : "chapter";
            String chapterName = StringUtils.hasText(el.getChapterName()) ? el.getChapterName() : chapterKey;
            JSONObject chapter = chapterNodes.get(chapterKey);
            if (chapter == null) {
                chapter = buildSectionNode(chapterKey, chapterName, "mixed");
                chapterNodes.put(chapterKey, chapter);
                chapters.add(chapter);
            }
            JSONArray holder = chapter.getJSONArray("content");
            if (StringUtils.hasText(el.getSectionKey())) {
                String sectionKey = el.getSectionKey().trim();
                Map<String, JSONObject> byChapter = sectionNodes.computeIfAbsent(chapterKey, k -> new LinkedHashMap<>());
                JSONObject section = byChapter.get(sectionKey);
                if (section == null) {
                    section = buildSectionNode(sectionKey,
                            StringUtils.hasText(el.getSectionName()) ? el.getSectionName() : sectionKey, "form");
                    byChapter.put(sectionKey, section);
                    holder.add(section);
                }
                holder = section.getJSONArray("content");
            }
            holder.add(buildFieldParagraph(el));
        }
        JSONObject doc = new JSONObject();
        doc.put("type", NODE_DOC);
        doc.put("content", chapters);
        return JSON.toJSONString(doc);
    }

    /** 章节/小节节点骨架 */
    private JSONObject buildSectionNode(String key, String title, String editMode) {
        JSONObject node = new JSONObject();
        node.put("type", NODE_SECTION);
        JSONObject attrs = new JSONObject();
        attrs.put("key", key);
        attrs.put("title", title);
        attrs.put("editMode", editMode);
        attrs.put("locked", false);
        node.put("attrs", attrs);
        node.put("content", new JSONArray());
        return node;
    }

    /** 单个数据元 → 段落节点(字段名文本 + emrField 内联节点) */
    private JSONObject buildFieldParagraph(HisEmrDatasetElement el) {
        JSONObject paragraph = new JSONObject();
        paragraph.put("type", "paragraph");
        JSONArray content = new JSONArray();
        JSONObject label = new JSONObject();
        label.put("type", "text");
        label.put("text", (StringUtils.hasText(el.getFieldName()) ? el.getFieldName() : el.getFieldKey()) + "：");
        content.add(label);
        content.add(buildFieldNode(el));
        paragraph.put("content", content);
        return paragraph;
    }

    /** 数据元 → emrField 内联节点(attrs 口径与前端 Tiptap 渲染器约定一致) */
    private JSONObject buildFieldNode(HisEmrDatasetElement el) {
        JSONObject node = new JSONObject();
        node.put("type", NODE_FIELD);
        JSONObject attrs = new JSONObject();
        attrs.put("fieldKey", el.getFieldKey());
        attrs.put("fieldName", el.getFieldName());
        attrs.put("valueType", mapValueType(el.getFieldType()));
        attrs.put("dictSource", el.getDictSource());
        attrs.put("required", el.getRequired() != null && el.getRequired() == 1);
        attrs.put("readonly", el.getReadonly() != null && el.getReadonly() == 1);
        attrs.put("noCopy", el.getNoCopy() != null && el.getNoCopy() == 1);
        attrs.put("value", null);
        if (el.getMaxLength() != null) {
            attrs.put("maxLength", el.getMaxLength());
        }
        if (StringUtils.hasText(el.getDefaultValue())) {
            attrs.put("defaultValue", el.getDefaultValue());
        }
        if (el.getPrintHidden() != null && el.getPrintHidden() == 1) {
            attrs.put("printHidden", true);
        }
        node.put("attrs", attrs);
        return node;
    }

    /** 数据集字段类型 → Tiptap valueType: 多选/复选归一为 multiSelect */
    private String mapValueType(String fieldType) {
        if (!StringUtils.hasText(fieldType)) {
            return "text";
        }
        String t = fieldType.trim().toLowerCase();
        switch (t) {
            case "number":
                return "number";
            case "date":
            case "datetime":
                return t;
            case "select":
                return "select";
            case "multiselect":
            case "checkbox":
                return "multiSelect";
            case "dict":
                return "dict";
            default:
                return "text";
        }
    }

    /** 数据元 → 模板 fields 定义数组(兼容既有渲染/设计器链路; 类型保留数据集原值) */
    private String buildFieldsJson(List<HisEmrDatasetElement> elements) {
        JSONArray arr = new JSONArray();
        for (HisEmrDatasetElement el : elements) {
            if (!StringUtils.hasText(el.getFieldKey())) {
                continue;
            }
            JSONObject o = new JSONObject();
            o.put("fieldKey", el.getFieldKey());
            o.put("label", el.getFieldName());
            o.put("type", StringUtils.hasText(el.getFieldType()) ? el.getFieldType() : "text");
            o.put("required", el.getRequired() != null && el.getRequired() == 1);
            if (el.getMaxLength() != null) {
                o.put("maxLength", el.getMaxLength());
            }
            if (StringUtils.hasText(el.getDefaultValue())) {
                o.put("defaultValue", el.getDefaultValue());
            }
            if (StringUtils.hasText(el.getDictSource())) {
                o.put("dictSource", el.getDictSource());
            }
            arr.add(o);
        }
        return JSON.toJSONString(arr);
    }

    /* ================= 批量维护(数据元/章节/页眉) ================= */

    /**
     * 批量更新数据元属性: 对多个模板中指定 fieldKey 的数据元逐键合并 attrs,
     * 同时更新 Tiptap 文档 emrField 节点与 fields 定义数组(两处存储保持一致)。
     */
    public R<Map<String, Object>> batchUpdateElementAttr(List<Long> templateIds, String fieldKey, Map<String, Object> attrs) {
        List<HisEmrTemplate> targets = requireBatchTargets(templateIds);
        if (!StringUtils.hasText(fieldKey)) {
            throw new BizException(400, "fieldKey 不能为空");
        }
        if (attrs == null || attrs.isEmpty()) {
            throw new BizException(400, "attrs 不能为空");
        }
        String key = fieldKey.trim();
        int updated = 0;
        int skipped = 0;
        for (HisEmrTemplate t : targets) {
            JSONArray fields = parseArraySafe(t.getFields());
            boolean fieldsChanged = false;
            for (int i = 0; i < fields.size(); i++) {
                JSONObject f = fields.getJSONObject(i);
                if (f != null && key.equals(text(f.get("fieldKey")))) {
                    for (Map.Entry<String, Object> e : attrs.entrySet()) {
                        f.put(e.getKey(), e.getValue());
                    }
                    fieldsChanged = true;
                }
            }
            JSONObject doc = parseDocSafe(t.getDocument());
            int docHit = doc == null ? 0 : updateFieldAttrsInTree(doc, key, attrs);
            if (!fieldsChanged && docHit == 0) {
                skipped++;
                continue;
            }
            if (fieldsChanged) {
                t.setFields(JSON.toJSONString(fields));
            }
            if (docHit > 0) {
                t.setDocument(JSON.toJSONString(doc));
            }
            templateMapper.updateById(t);
            updated++;
        }
        log.info("批量更新数据元属性: fieldKey={}, templates={}, updated={}", key, targets.size(), updated);
        return R.ok(batchStat(targets.size(), updated, skipped));
    }

    /**
     * 批量替换章节内容: 在多个模板的 Tiptap 文档中按章节key定位(emrSection.attrs.key), 用 newContent 替换其 content。
     * newContent 支持: JSON节点数组 / 单节点JSON对象 / 纯文本(包装为段落) / 空串(清空章节内容)。
     * 继承自母板且该章节被母板锁定的子模板跳过(锁定章节以母板为准)。
     */
    public R<Map<String, Object>> batchReplaceSection(List<Long> templateIds, String sectionKey, String newContent) {
        List<HisEmrTemplate> targets = requireBatchTargets(templateIds);
        if (!StringUtils.hasText(sectionKey)) {
            throw new BizException(400, "sectionKey 不能为空");
        }
        String key = sectionKey.trim();
        JSONArray content = normalizeSectionContent(newContent);
        Map<Long, List<String>> parentLockCache = new HashMap<>();
        int updated = 0;
        int skipped = 0;
        for (HisEmrTemplate t : targets) {
            if (isLockedByParent(t, key, parentLockCache)) {
                log.info("批量替换章节跳过(母板锁定): templateId={}, sectionKey={}", t.getId(), key);
                skipped++;
                continue;
            }
            JSONObject doc = parseDocSafe(t.getDocument());
            if (doc == null || !replaceSectionInTree(doc, key, content)) {
                skipped++;
                continue;
            }
            t.setDocument(JSON.toJSONString(doc));
            templateMapper.updateById(t);
            updated++;
        }
        log.info("批量替换章节: sectionKey={}, templates={}, updated={}", key, targets.size(), updated);
        return R.ok(batchStat(targets.size(), updated, skipped));
    }

    /**
     * 批量替换文档页眉: 根节点 attrs.header = newHeader, 并同步替换文档内 emrHeader 节点内容(存在时)。
     * 无 Tiptap 文档(纯 fields 旧模板)跳过。
     */
    public R<Map<String, Object>> batchReplaceHeader(List<Long> templateIds, String newHeader) {
        List<HisEmrTemplate> targets = requireBatchTargets(templateIds);
        String header = newHeader == null ? "" : newHeader;
        int updated = 0;
        int skipped = 0;
        for (HisEmrTemplate t : targets) {
            JSONObject doc = parseDocSafe(t.getDocument());
            if (doc == null) {
                skipped++;
                continue;
            }
            JSONObject attrs = doc.getJSONObject("attrs");
            if (attrs == null) {
                attrs = new JSONObject();
                doc.put("attrs", attrs);
            }
            attrs.put("header", header);
            replaceHeaderNodeInTree(doc, header);
            t.setDocument(JSON.toJSONString(doc));
            templateMapper.updateById(t);
            updated++;
        }
        log.info("批量替换页眉: templates={}, updated={}", targets.size(), updated);
        return R.ok(batchStat(targets.size(), updated, skipped));
    }

    /** 批量目标加载 + 逐模板可编辑性预校验(任一不可编辑即整体拒绝, 避免半量更新) */
    private List<HisEmrTemplate> requireBatchTargets(List<Long> templateIds) {
        if (templateIds == null || templateIds.isEmpty()) {
            throw new BizException(400, "templateIds 不能为空");
        }
        List<HisEmrTemplate> targets = new ArrayList<>();
        for (Long id : templateIds) {
            HisEmrTemplate t = id == null ? null : templateMapper.selectById(id);
            if (t == null) {
                throw new BizException(400, "病历模板不存在: " + id);
            }
            guardEditable(t);
            targets.add(t);
        }
        return targets;
    }

    /** 批量操作统计体 */
    private Map<String, Object> batchStat(int requested, int updated, int skipped) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requested", requested);
        m.put("updated", updated);
        m.put("skipped", skipped);
        return m;
    }

    /** 递归更新 emrField 节点 attrs(attrs.fieldKey 命中), 返回命中节点数 */
    private int updateFieldAttrsInTree(JSONObject node, String fieldKey, Map<String, Object> attrs) {
        int n = 0;
        if (NODE_FIELD.equals(node.getString("type"))) {
            JSONObject a = node.getJSONObject("attrs");
            if (a != null && fieldKey.equals(a.getString("fieldKey"))) {
                for (Map.Entry<String, Object> e : attrs.entrySet()) {
                    a.put(e.getKey(), e.getValue());
                }
                n++;
            }
        }
        JSONArray content = node.getJSONArray("content");
        if (content != null) {
            for (int i = 0; i < content.size(); i++) {
                Object el = content.get(i);
                if (el instanceof JSONObject) {
                    n += updateFieldAttrsInTree((JSONObject) el, fieldKey, attrs);
                }
            }
        }
        return n;
    }

    /** 章节新内容归一: JSON数组→节点数组, JSON对象→单节点数组, 纯文本→段落节点数组, 空→空数组 */
    private JSONArray normalizeSectionContent(String newContent) {
        JSONArray arr = new JSONArray();
        if (!StringUtils.hasText(newContent)) {
            return arr;
        }
        String s = newContent.trim();
        try {
            if (s.startsWith("[")) {
                JSONArray parsed = JSON.parseArray(s);
                if (parsed != null) {
                    return parsed;
                }
            } else if (s.startsWith("{")) {
                JSONObject parsed = JSON.parseObject(s);
                if (parsed != null) {
                    arr.add(parsed);
                    return arr;
                }
            }
        } catch (Exception ignore) {
            // 非合法JSON按纯文本处理
        }
        JSONObject paragraph = new JSONObject();
        paragraph.put("type", "paragraph");
        JSONArray inline = new JSONArray();
        JSONObject textNode = new JSONObject();
        textNode.put("type", "text");
        textNode.put("text", s);
        inline.add(textNode);
        paragraph.put("content", inline);
        arr.add(paragraph);
        return arr;
    }

    /** 递归替换章节节点 content(emrSection.attrs.key 命中); 返回是否命中 */
    private boolean replaceSectionInTree(JSONObject node, String sectionKey, JSONArray newContent) {
        if (NODE_SECTION.equals(node.getString("type"))) {
            JSONObject attrs = node.getJSONObject("attrs");
            if (attrs != null && sectionKey.equals(attrs.getString("key"))) {
                node.put("content", deepCopy(newContent));
                return true;
            }
        }
        JSONArray content = node.getJSONArray("content");
        if (content != null) {
            for (int i = 0; i < content.size(); i++) {
                Object el = content.get(i);
                if (el instanceof JSONObject && replaceSectionInTree((JSONObject) el, sectionKey, newContent)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 子模板归属的母板是否锁定了该章节(按父模板ID缓存锁定列表) */
    private boolean isLockedByParent(HisEmrTemplate t, String sectionKey, Map<Long, List<String>> cache) {
        Long pid = t.getParentTemplateId();
        if (pid == null) {
            return false;
        }
        List<String> locks = cache.computeIfAbsent(pid, k -> {
            HisEmrTemplate parent = templateMapper.selectById(k);
            return parent == null ? Collections.emptyList() : parseLockedKeys(parent.getLockedSections());
        });
        return locks.contains(sectionKey);
    }

    /** 递归替换 emrHeader 节点头内容为单段落纯文本(存在则替换; 不存在仅保留根 attrs.header) */
    private void replaceHeaderNodeInTree(JSONObject node, String header) {
        if (NODE_HEADER.equals(node.getString("type"))) {
            JSONArray content = new JSONArray();
            JSONObject paragraph = new JSONObject();
            paragraph.put("type", "paragraph");
            JSONArray inline = new JSONArray();
            JSONObject textNode = new JSONObject();
            textNode.put("type", "text");
            textNode.put("text", header);
            inline.add(textNode);
            paragraph.put("content", inline);
            content.add(paragraph);
            node.put("content", content);
        }
        JSONArray content = node.getJSONArray("content");
        if (content != null) {
            for (int i = 0; i < content.size(); i++) {
                Object el = content.get(i);
                if (el instanceof JSONObject) {
                    replaceHeaderNodeInTree((JSONObject) el, header);
                }
            }
        }
    }

    /* ================= 扩展字段内部校验 ================= */

    /** 新建层级推导: 显式 scopeLevel 优先(0-2), 缺省按归属 ownerScope 映射(global=0/dept=1/personal=2) */
    private int resolveScopeLevel(String ownerScope, Integer explicit) {
        if (explicit != null) {
            if (explicit < 0 || explicit > 2) {
                throw new BizException(400, "scopeLevel 仅支持 0全院/1科室/2个人");
            }
            return explicit;
        }
        if ("personal".equals(ownerScope)) {
            return 2;
        }
        if ("dept".equals(ownerScope)) {
            return 1;
        }
        return 0;
    }

    /** 锁定章节JSON归一: null 原样返回(不修改), 空白串→"[]"(清空), 字符串数组→规范化JSON, 非法→400 */
    private String normalizeLockedSections(String json) {
        if (json == null) {
            return null;
        }
        if (!StringUtils.hasText(json)) {
            return "[]";
        }
        try {
            JSONArray arr = JSON.parseArray(json.trim());
            if (arr == null) {
                return "[]";
            }
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.size(); i++) {
                String k = arr.getString(i);
                if (StringUtils.hasText(k)) {
                    out.add(k.trim());
                }
            }
            return JSON.toJSONString(out);
        } catch (Exception e) {
            throw new BizException(400, "lockedSections 须为章节key的JSON字符串数组");
        }
    }

    /** Tiptap文档校验: null/空白 原样返回(null不修改), 其余须为 type=doc 的JSON对象, 否则 400 */
    private String validateDocument(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        JSONObject doc;
        try {
            doc = JSON.parseObject(json.trim());
        } catch (Exception e) {
            throw new BizException(400, "document 须为合法的 Tiptap JSON 文档");
        }
        if (doc == null || !NODE_DOC.equals(doc.getString("type"))) {
            throw new BizException(400, "document 须为 type=doc 的 Tiptap 文档");
        }
        return json.trim();
    }

    /**
     * 结构化打印配置归一：历史模板允许为 null；非空配置固定为 A4、方向、毫米页边距、页眉页脚和页码。
     * 旧 printScript 继续独立保留，仅作为兼容样式，不参与结构化配置校验。
     */
    private String normalizePrintConfig(String json) {
        if (json == null) {
            return null;
        }
        JSONObject input;
        try {
            input = StringUtils.hasText(json) ? JSON.parseObject(json.trim()) : new JSONObject();
        } catch (Exception e) {
            throw new BizException(400, "printConfig 须为合法的 JSON 对象");
        }
        if (input == null) {
            input = new JSONObject();
        }
        String paperSize = input.getString("paperSize");
        if (!StringUtils.hasText(paperSize)) {
            paperSize = "A4";
        }
        if (!"A4".equalsIgnoreCase(paperSize)) {
            throw new BizException(400, "printConfig.paperSize 首期仅支持 A4");
        }
        String orientation = input.getString("orientation");
        if (!StringUtils.hasText(orientation)) {
            orientation = "portrait";
        }
        orientation = orientation.trim().toLowerCase();
        if (!"portrait".equals(orientation) && !"landscape".equals(orientation)) {
            throw new BizException(400, "printConfig.orientation 仅支持 portrait/landscape");
        }

        JSONObject margins = input.getJSONObject("margins");
        JSONObject normalizedMargins = new JSONObject();
        normalizedMargins.put("top", normalizePrintMargin(margins, "top", 18));
        normalizedMargins.put("right", normalizePrintMargin(margins, "right", 16));
        normalizedMargins.put("bottom", normalizePrintMargin(margins, "bottom", 18));
        normalizedMargins.put("left", normalizePrintMargin(margins, "left", 16));

        JSONObject out = new JSONObject();
        out.put("paperSize", "A4");
        out.put("orientation", orientation);
        out.put("margins", normalizedMargins);
        out.put("header", normalizePrintBand(input.getJSONObject("header")));
        out.put("footer", normalizePrintBand(input.getJSONObject("footer")));
        out.put("showPageNumber", input.getBoolean("showPageNumber") == null || input.getBooleanValue("showPageNumber"));
        return JSON.toJSONString(out);
    }

    private int normalizePrintMargin(JSONObject margins, String key, int defaultValue) {
        if (margins == null || margins.get(key) == null) {
            return defaultValue;
        }
        int value;
        try {
            value = margins.getIntValue(key);
        } catch (Exception e) {
            throw new BizException(400, "printConfig.margins." + key + " 须为毫米整数");
        }
        if (value < 0 || value > 50) {
            throw new BizException(400, "printConfig.margins." + key + " 须在 0-50 毫米之间");
        }
        return value;
    }

    private JSONObject normalizePrintBand(JSONObject band) {
        JSONObject out = new JSONObject();
        out.put("enabled", band != null && band.getBooleanValue("enabled"));
        String content = band == null ? "" : band.getString("content");
        if (content != null && content.length() > 500) {
            throw new BizException(400, "页眉或页脚内容不能超过 500 个字符");
        }
        out.put("content", content == null ? "" : content);
        return out;
    }

    /** 父模板链接校验: 父存在、非自身、不成环(三级继承最多向上两跳) */
    private void validateParentLink(Long templateId, Long parentId) {
        if (parentId == null) {
            return;
        }
        if (parentId.equals(templateId)) {
            throw new BizException(400, "父模板不能是自身");
        }
        HisEmrTemplate parent = templateMapper.selectById(parentId);
        if (parent == null) {
            throw new BizException(400, "父模板不存在: " + parentId);
        }
        if (templateId != null && templateId.equals(parent.getParentTemplateId())) {
            throw new BizException(400, "父模板链接成环(其父模板为当前模板)");
        }
    }

    /** Tiptap文档解析: 非 type=doc 或非法JSON返回 null(不阻断批量操作, 计为跳过) */
    private JSONObject parseDocSafe(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            JSONObject o = JSON.parseObject(json);
            return o != null && NODE_DOC.equals(o.getString("type")) ? o : null;
        } catch (Exception e) {
            log.warn("Tiptap文档JSON解析失败: {}", e.getMessage());
            return null;
        }
    }

    /** JSON节点深拷贝(母板内容跨模板复用须复制, 避免引用共享) */
    private Object deepCopy(Object node) {
        return node == null ? null : JSON.parse(JSON.toJSONString(node));
    }

    /* ================= 内部实现 ================= */

    /** 编码可用性校验: 本租户重复 + 全局唯一键预检(友好 400 避免落库才报唯一键冲突) */
    private void ensureCodeAvailable(String code) {
        boolean localDup = !templateMapper.selectList(Wrappers.<HisEmrTemplate>lambdaQuery()
                .eq(HisEmrTemplate::getTemplateCode, code)).isEmpty();
        if (localDup) {
            throw new BizException(400, "模板编码已存在: " + code);
        }
        if (existsGlobal(code)) {
            throw new BizException(400, "模板编码已被占用(标准编码全租户共享): " + code);
        }
    }

    /** 字段 JSON 落库: 设计器 rawFields 优先(原样序列化保留 dictRef/subFields/section), 否则回退 EmrFieldDefDTO 规范形状 */
    private String resolveFieldsJson(EmrTemplateDTO dto) {
        if (dto.getRawFields() != null) {
            return JSON.toJSONString(dto.getRawFields());
        }
        return JSON.toJSONString(toFieldJsonArray(dto.getFields()));
    }

    private boolean isAdmin(LoginUser lu) {
        return lu != null && lu.hasAnyRole(Roles.ADMIN, Roles.SUPER_ADMIN, Roles.ORG_ADMIN);
    }

    /** 模板可编辑性守卫: 个人=本人或管理员; 科室=同科室或管理员; 全院=牵头机构管理员 */
    private void guardEditable(HisEmrTemplate t) {
        LoginUser lu = UserContext.get();
        if (t.getStaffId() != null) {
            if (!isAdmin(lu) && (lu == null || !t.getStaffId().equals(lu.getStaffId()))) {
                throw new BizException(403, "无权编辑他人个人模板");
            }
        } else if (t.getDeptId() != null && t.getDeptId() != 0L) {
            if (!isAdmin(lu) && (lu == null || !t.getDeptId().equals(lu.getDeptId()))) {
                throw new BizException(403, "无权编辑其他科室模板");
            }
        } else {
            guard.requireLeadOrg("仅牵头机构管理员可维护全院病历模板");
        }
    }

    /** EmrFieldDefDTO 列表 → 模板 fields JSON 数组(按 sortNo 排序, 规范形状 fieldKey/label/type/required/...) */
    private JSONArray toFieldJsonArray(List<EmrFieldDefDTO> defs) {
        JSONArray arr = new JSONArray();
        if (defs == null) {
            return arr;
        }
        List<EmrFieldDefDTO> sorted = new ArrayList<>(defs);
        sorted.sort(Comparator.comparingInt(d -> d.getSortNo() == null ? Integer.MAX_VALUE : d.getSortNo()));
        for (EmrFieldDefDTO d : sorted) {
            if (!StringUtils.hasText(d.getFieldCode())) {
                continue;
            }
            JSONObject o = new JSONObject();
            o.put("fieldKey", d.getFieldCode().trim());
            o.put("label", d.getFieldName());
            o.put("type", StringUtils.hasText(d.getFieldType()) ? d.getFieldType().trim() : "text");
            o.put("required", d.getRequired() != null && d.getRequired() == 1);
            if (StringUtils.hasText(d.getOptions())) {
                o.put("options", splitOptions(d.getOptions()));
            }
            if (StringUtils.hasText(d.getDefaultValue())) {
                o.put("defaultValue", d.getDefaultValue());
            }
            if (StringUtils.hasText(d.getPlaceholder())) {
                o.put("placeholder", d.getPlaceholder());
            }
            arr.add(o);
        }
        return arr;
    }

    /** 选项归一: 支持 JSON 数组字符串或逗号/分号分隔文本 */
    private JSONArray splitOptions(String s) {
        String t = s.trim();
        if (t.startsWith("[")) {
            try {
                JSONArray arr = JSON.parseArray(t);
                if (arr != null) {
                    return arr;
                }
            } catch (Exception ignore) {
                // 非合法 JSON 则按分隔符拆分
            }
        }
        JSONArray arr = new JSONArray();
        for (String p : t.split("[,，;；]")) {
            if (StringUtils.hasText(p)) {
                arr.add(p.trim());
            }
        }
        return arr;
    }

    private String joinOptions(Object options) {
        if (options == null) {
            return "";
        }
        if (options instanceof JSONArray) {
            JSONArray arr = (JSONArray) options;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.size(); i++) {
                if (sb.length() > 0) {
                    sb.append(",");
                }
                sb.append(text(arr.get(i)));
            }
            return sb.toString();
        }
        return text(options);
    }

    private static String text(Object v) {
        return v == null ? "" : (v instanceof String ? (String) v : String.valueOf(v));
    }

    private static Boolean asBoolean(Object v) {
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        return v != null && "true".equalsIgnoreCase(String.valueOf(v));
    }

    private JSONArray parseArraySafe(String json) {
        if (!StringUtils.hasText(json)) {
            return new JSONArray();
        }
        try {
            JSONArray arr = JSON.parseArray(json);
            return arr == null ? new JSONArray() : arr;
        } catch (Exception e) {
            log.warn("模板字段定义JSON解析失败: {}", e.getMessage());
            return new JSONArray();
        }
    }

    /* ================= 种子字段定义 ================= */

    private static JSONObject f(String key, String label, String type, boolean required, Object... extra) {
        JSONObject o = new JSONObject();
        o.put("fieldKey", key);
        o.put("label", label);
        o.put("type", type);
        o.put("required", required);
        for (int i = 0; i + 1 < extra.length; i += 2) {
            o.put(String.valueOf(extra[i]), extra[i + 1]);
        }
        return o;
    }

    /** 快捷短语选项: 保持数组形态写入 fields/document，避免按中文标点误拆。 */
    private static JSONArray quickOptions(String... values) {
        JSONArray options = new JSONArray();
        if (values != null) {
            options.addAll(Arrays.asList(values));
        }
        return options;
    }

    /** 8 类标准模板的字段定义(与任务规格逐字段一致) */
    private static String buildSeedFields(String code) {
        JSONArray a = new JSONArray();
        switch (code) {
            case "EMR_ADMIT":
                a.add(f("sec_general", "患者一般信息", "section", false));
                a.add(f("generalInfo", "患者一般信息(姓名/性别/年龄/民族/婚姻/职业/籍贯地址/入院及记录时间/病史陈述者)", "textarea", false));
                a.add(f("sec_history", "病史", "section", false));
                a.add(f("chiefComplaint", "主诉", "textarea", true, "maxLength", 200));
                a.add(f("presentIllness", "现病史", "textarea", true));
                a.add(f("pastHistory", "既往史", "textarea", false, "defaultMacro", "past_history"));
                a.add(f("personalHistory", "个人史", "textarea", false));
                a.add(f("marriageHistory", "婚育史", "textarea", false));
                a.add(f("familyHistory", "家族史", "textarea", false));
                a.add(f("allergyHistory", "过敏史", "textarea", true, "defaultMacro", "allergy_info"));
                a.add(f("sec_exam", "体格检查", "section", false));
                a.add(f("vitals", "生命体征(T/P/R/BP)", "vitals", false, "defaultMacro", "vital_signs"));
                a.add(f("physicalExam", "体格检查(一般检查)", "textarea", true));
                a.add(f("systemExam", "系统查体(头颈胸腹脊柱四肢神经系统)", "textarea", false));
                a.add(f("specialExam", "专科检查", "textarea", false));
                a.add(f("auxiliaryExam", "辅助检查", "textarea", false));
                a.add(f("sec_diag", "诊断与计划", "section", false));
                a.add(f("admitDiagnosis", "初步诊断", "textarea", true, "defaultMacro", "admit_diag"));
                a.add(f("treatPlan", "诊疗计划", "textarea", true));
                a.add(f("physicianSign", "医师签名", "text", true, "defaultMacro", "attending_doctor"));
                break;
            case "EMR_FIRST_PROG":
                a.add(f("sec_features", "病例特点", "section", false));
                a.add(f("caseFeatures", "病例特点", "textarea", true));
                a.add(f("sec_discussion", "拟诊讨论", "section", false));
                a.add(f("diagBasis", "诊断依据", "textarea", true));
                a.add(f("diffDiag", "鉴别诊断", "textarea", true));
                a.add(f("sec_plan", "诊疗计划", "section", false));
                a.add(f("treatPlan", "诊疗计划", "textarea", true));
                a.add(f("physicianSign", "医师签名", "text", true, "defaultMacro", "attending_doctor"));
                break;
            case "EMR_DAILY_PROG":
                a.add(f("sec_subjective", "主观资料(S)", "section", false));
                a.add(f("subjective", "主观(S)", "textarea", true));
                a.add(f("sec_objective", "客观资料(O)", "section", false));
                a.add(f("vitals", "生命体征(T/P/R/BP)", "vitals", false, "defaultMacro", "vital_signs"));
                a.add(f("objective", "客观(O)", "textarea", true, "defaultMacro", "vital_signs"));
                a.add(f("sec_assessment", "病情评估(A)", "section", false));
                a.add(f("assessment", "评估(A)", "textarea", true));
                a.add(f("sec_plan", "诊疗计划(P)", "section", false));
                a.add(f("plan", "计划(P)", "textarea", true));
                a.add(f("physicianSign", "医师签名", "text", true, "defaultMacro", "attending_doctor"));
                break;
            case "EMR_SENIOR_ROUND": {
                JSONArray roundOpts = new JSONArray();
                roundOpts.add("主治医师查房");
                roundOpts.add("副主任医师查房");
                roundOpts.add("主任医师查房");
                a.add(f("sec_round", "查房记录", "section", false));
                a.add(f("roundLevel", "查房级别", "select", true, "options", roundOpts));
                a.add(f("attendingDoctor", "查房医师", "text", true, "defaultMacro", "attending_doctor"));
                a.add(f("patientCondition", "病情汇报", "textarea", true));
                a.add(f("attendingOpinion", "上级医师意见", "textarea", true));
                a.add(f("treatAdjust", "诊疗调整", "textarea", false));
                a.add(f("physicianSign", "记录医师签名", "text", true, "defaultMacro", "attending_doctor"));
                break;
            }
            case "EMR_SURGERY":
                a.add(f("sec_base", "手术基本信息", "section", false));
                a.add(f("surgeryName", "手术名称", "text", true, "defaultMacro", "surgery_name"));
                a.add(f("surgeryDate", "手术日期", "date", true, "defaultMacro", "surgery_date"));
                a.add(f("surgeon", "术者", "text", true, "defaultMacro", "surgeon_name"));
                a.add(f("assistant", "助手", "text", false));
                a.add(f("anesthesia", "麻醉方式", "text", true));
                a.add(f("sec_diag", "手术诊断", "section", false));
                a.add(f("preOpDiag", "术前诊断", "textarea", true));
                a.add(f("postOpDiag", "术后诊断", "textarea", true));
                a.add(f("sec_process", "手术经过", "section", false));
                a.add(f("surgeryProcess", "手术经过", "textarea", true));
                a.add(f("specimen", "术中标本", "textarea", false));
                a.add(f("bleeding", "术中出血量(ml)", "number", true));
                a.add(f("infusion", "术中输液量(ml)", "number", false));
                a.add(f("transfusion", "术中输血量", "text", false));
                a.add(f("sec_sign", "签名", "section", false));
                a.add(f("surgeonSign", "术者签名", "text", true, "defaultMacro", "surgeon_name"));
                a.add(f("recorderSign", "记录医师签名", "text", true));
                break;
            case "EMR_POST_SURGERY":
                a.add(f("sec_op", "手术情况", "section", false));
                a.add(f("surgeryName", "手术名称", "text", true));
                a.add(f("anesthesiaRecovery", "麻醉恢复", "textarea", true));
                a.add(f("sec_condition", "术后情况", "section", false));
                a.add(f("vitals", "生命体征(T/P/R/BP)", "vitals", false, "defaultMacro", "vital_signs"));
                a.add(f("postCondition", "术后情况", "textarea", true));
                a.add(f("drainTube", "引流/管路情况", "textarea", false));
                a.add(f("intakeOutput", "24小时出入量", "textarea", false));
                a.add(f("sec_orders", "术后医嘱", "section", false));
                a.add(f("postOrders", "术后医嘱", "textarea", true));
                a.add(f("attention", "注意事项", "textarea", false));
                a.add(f("physicianSign", "医师签名", "text", true, "defaultMacro", "attending_doctor"));
                break;
            case "EMR_DISCHARGE":
                a.add(f("sec_dates", "入院与出院时间", "section", false));
                a.add(f("admitDate", "入院日期", "date", true, "defaultMacro", "admit_date"));
                a.add(f("dischargeDate", "出院日期", "date", true, "defaultMacro", "discharge_date"));
                a.add(f("sec_diag", "诊断", "section", false));
                a.add(f("admitDiag", "入院诊断", "textarea", true, "defaultMacro", "admit_diag"));
                a.add(f("dischargeDiag", "出院诊断", "textarea", true, "defaultMacro", "discharge_diag"));
                a.add(f("sec_summary", "诊疗经过", "section", false));
                a.add(f("treatSummary", "诊疗经过", "textarea", true));
                a.add(f("sec_condition", "出院情况与医嘱", "section", false));
                a.add(f("dischargeCondition", "出院情况", "textarea", true));
                a.add(f("dischargeOrders", "出院医嘱", "textarea", true));
                a.add(f("followUp", "随访计划", "textarea", false));
                a.add(f("physicianSign", "医师签名", "text", true, "defaultMacro", "attending_doctor"));
                break;
            case "EMR_DEATH":
                a.add(f("sec_dates", "入院与死亡时间", "section", false));
                a.add(f("admitDate", "入院日期", "date", true, "defaultMacro", "admit_date"));
                a.add(f("deathTime", "死亡时间", "datetime", true));
                a.add(f("sec_diag", "诊断", "section", false));
                a.add(f("admitDiag", "入院诊断", "textarea", true));
                a.add(f("deathDiag", "死亡诊断", "textarea", true));
                a.add(f("sec_cause", "死亡原因与抢救", "section", false));
                a.add(f("deathPreCondition", "死亡前情况", "textarea", false));
                a.add(f("deathCause", "死亡原因", "textarea", true));
                a.add(f("treatProcess", "诊疗经过", "textarea", true));
                a.add(f("rescueProcess", "抢救经过", "textarea", false));
                a.add(f("physicianSign", "医师签名", "text", true, "defaultMacro", "attending_doctor"));
                break;
            case "EMR_OUTP_GENERAL": {
                JSONArray presentOptions = quickOptions(
                        "起病急，症状持续，未予特殊处理，精神、食欲、睡眠尚可，大小便正常。",
                        "起病缓，症状反复，院外治疗后效果欠佳。",
                        "复诊，症状较前好转，无新发不适。",
                        "复诊，症状无明显改善。"
                );
                JSONArray examOptions = quickOptions(
                        "一般情况可，神志清楚，查体合作。心肺听诊未见明显异常，腹软，无压痛及反跳痛。",
                        "一般情况可，咽部充血，双侧扁桃体无明显肿大，双肺呼吸音清，未闻及干湿性啰音。",
                        "一般情况可，腹软，局部压痛，无反跳痛及肌紧张。"
                );
                JSONArray auxOptions = quickOptions("暂未行辅助检查。", "辅助检查结果详见报告。", "院外检查结果已阅。");
                JSONArray treatmentOptions = quickOptions(
                        "予对症治疗，嘱按医嘱用药。",
                        "完善相关检查，根据结果进一步处理。",
                        "继续原治疗方案，观察病情变化。",
                        "建议转上级医院进一步诊治。"
                );
                JSONArray followupOptions = quickOptions(
                        "如症状加重或出现新发不适，及时复诊。",
                        "按时复诊，复诊时携带相关检查资料。",
                        "如出现高热、呼吸困难等情况立即就医。"
                );
                a.add(f("sec_1", "主诉与病史", "section", false));
                a.add(f("chiefComplaint", "主诉", "textarea", true,
                        "maxLength", 200, "placeholder", "症状＋部位＋时长，如：咳嗽、咳痰3天"));
                a.add(f("presentIllness", "现病史", "textarea", true,
                        "placeholder", "可点选下方常用描述后修改", "options", presentOptions));
                a.add(f("pastHistory", "既往史", "textarea", false,
                        "defaultMacro", "past_history", "placeholder", "自动带入既往史，可补充修改"));
                a.add(f("allergyHistory", "过敏史", "textarea", true,
                        "defaultMacro", "allergy_info", "placeholder", "自动带入过敏信息，请核对"));
                a.add(f("sec_2", "查体与辅助检查", "section", false));
                a.add(f("vitals", "生命体征", "vitals", false,
                        "placeholder", "按实测值录入，可一键填正常参考值"));
                a.add(f("physicalExam", "体格检查", "textarea", true,
                        "placeholder", "可点选常用查体后按实际情况修改", "options", examOptions));
                a.add(f("auxExam", "辅助检查", "textarea", false,
                        "placeholder", "填写本次或院外检查结果", "options", auxOptions));
                a.add(f("sec_3", "诊断与处理", "section", false));
                a.add(f("diagnosis", "门诊诊断", "diagnosis", false,
                        "defaultMacro", "main_diag", "placeholder", "同步主诊断，也可检索补充"));
                a.add(f("treatmentOpinion", "处理意见", "textarea", true,
                        "placeholder", "可点选常用处置后补充药品、检查或治疗", "options", treatmentOptions));
                a.add(f("followupNote", "随访建议", "textarea", false,
                        "placeholder", "交代复诊时间与警示症状", "options", followupOptions));
                a.add(f("sec_4", "就诊信息", "section", false));
                a.add(f("visitDate", "就诊日期", "date", true, "defaultMacro", "current_date"));
                a.add(f("physicianSign", "医师签名", "text", true, "defaultMacro", "attending_doctor"));
                break;
            }
            case "EMR_OUTP_TCM":
                a.add(f("chiefComplaint", "主诉", "textarea", true, "maxLength", 200));
                a.add(f("presentIllness", "现病史", "textarea", true));
                a.add(f("pastHistory", "既往史", "textarea", false, "defaultMacro", "past_history"));
                a.add(f("allergyHistory", "过敏史", "textarea", false, "defaultMacro", "allergy_info"));
                a.add(f("fourExams", "四诊合参(望闻问切)", "textarea", false));
                a.add(f("syndromeAnalysis", "辨证分析", "textarea", false));
                a.add(f("physicalExam", "体格检查", "textarea", true));
                a.add(f("auxExam", "辅助检查", "textarea", false));
                a.add(f("treatmentOpinion", "处理意见", "textarea", true));
                a.add(f("followupNote", "随访备注", "textarea", false));
                break;
            /* ---------- P2 扩展: 10-15 类文书 ---------- */
            case "EMR_HOMEPAGE": {
                JSONArray outcomeOpts = new JSONArray();
                outcomeOpts.add("治愈");
                outcomeOpts.add("好转");
                outcomeOpts.add("未愈");
                outcomeOpts.add("死亡");
                outcomeOpts.add("其他");
                a.add(f("admitDate", "入院日期", "date", true, "defaultMacro", "admit_date"));
                a.add(f("dischargeDate", "出院日期", "date", true, "defaultMacro", "discharge_date"));
                a.add(f("admitDiag", "入院诊断", "textarea", true, "defaultMacro", "admit_diag"));
                a.add(f("dischargeDiag", "出院诊断", "textarea", true, "defaultMacro", "discharge_diag"));
                a.add(f("mainDiag", "主要诊断", "textarea", true));
                a.add(f("surgeryName", "手术名称", "text", false));
                a.add(f("outcome", "治疗转归", "select", true, "options", outcomeOpts));
                a.add(f("totalCost", "住院总费用(元)", "number", false));
                a.add(f("attendingDoctor", "主管医师", "text", true, "defaultMacro", "attending_doctor"));
                break;
            }
            case "EMR_HANDOVER":
                a.add(f("handoverTime", "交接时间", "datetime", true));
                a.add(f("shiftType", "班次", "text", true));
                a.add(f("handoverFrom", "交班医师", "text", true));
                a.add(f("handoverTo", "接班医师", "text", true));
                a.add(f("patientSummary", "患者情况(S)", "textarea", true));
                a.add(f("background", "背景(B)", "textarea", true));
                a.add(f("assessment", "评估(A)", "textarea", true));
                a.add(f("recommendation", "建议(R)", "textarea", true));
                a.add(f("pendingMatters", "待办事项", "textarea", false));
                break;
            case "EMR_TRANSFER":
                a.add(f("transferTime", "转科时间", "datetime", true));
                a.add(f("fromDept", "转出科室", "text", true));
                a.add(f("toDept", "转入科室", "text", true));
                a.add(f("transferReason", "转科原因", "textarea", true));
                a.add(f("currentCondition", "目前情况", "textarea", true));
                a.add(f("diagnosis", "诊断", "textarea", true));
                a.add(f("transferAdvice", "转科建议/注意事项", "textarea", false));
                a.add(f("doctorSign", "医师签名", "text", true));
                break;
            case "EMR_CONSENT": {
                JSONArray consentOpts = new JSONArray();
                consentOpts.add("手术同意书");
                consentOpts.add("麻醉同意书");
                consentOpts.add("输血同意书");
                consentOpts.add("特殊检查同意书");
                consentOpts.add("特殊治疗同意书");
                consentOpts.add("自费项目同意书");
                consentOpts.add("病危通知书");
                a.add(f("consentType", "同意书类型", "select", true, "options", consentOpts));
                a.add(f("patientName", "患者姓名", "text", true));
                a.add(f("diagnosis", "诊断", "textarea", true));
                a.add(f("proposedPlan", "拟实施诊疗方案", "textarea", true));
                a.add(f("risks", "风险与并发症", "textarea", true));
                a.add(f("alternatives", "替代方案", "textarea", false));
                a.add(f("patientOpinion", "患者/家属意见", "textarea", true));
                a.add(f("patientSignTime", "患者签字时间", "datetime", false));
                a.add(f("doctorSignTime", "医师签字时间", "datetime", false));
                break;
            }
            case "EMR_DISCUSSION": {
                JSONArray discussOpts = new JSONArray();
                discussOpts.add("疑难病例讨论");
                discussOpts.add("危重病例讨论");
                discussOpts.add("术前讨论");
                discussOpts.add("死亡病例讨论");
                a.add(f("discussTime", "讨论时间", "datetime", true));
                a.add(f("discussType", "讨论类型", "select", true, "options", discussOpts));
                a.add(f("host", "主持人", "text", true));
                a.add(f("participants", "参加人员", "textarea", true));
                a.add(f("caseReport", "病例汇报", "textarea", true));
                a.add(f("discussOpinions", "讨论意见", "textarea", true));
                a.add(f("conclusion", "讨论结论", "textarea", true));
                a.add(f("recordDoctor", "记录医师", "text", true));
                break;
            }
            case "EMR_CONSULTATION": {
                JSONArray consultOpts = new JSONArray();
                consultOpts.add("普通会诊");
                consultOpts.add("急会诊");
                consultOpts.add("MDT多学科会诊");
                a.add(f("applyTime", "申请时间", "datetime", true));
                a.add(f("consultType", "会诊类型", "select", true, "options", consultOpts));
                a.add(f("applyDept", "申请科室", "text", true));
                a.add(f("consultDept", "会诊科室", "text", true));
                a.add(f("consultReason", "会诊理由", "textarea", true));
                a.add(f("currentCondition", "患者目前情况", "textarea", true));
                a.add(f("consultOpinion", "会诊意见", "textarea", true));
                a.add(f("consultDoctor", "会诊医师", "text", true));
                a.add(f("consultTime", "会诊完成时间", "datetime", false));
                break;
            }
            /* ---------- P8 中医三模板: 数据元 key 与前端中医辨证书写器口径一致 ---------- */
            case "EMR_TCM_HOMEPAGE": {
                JSONArray tcmTreatOpts = new JSONArray();
                tcmTreatOpts.add("辨证论治");
                tcmTreatOpts.add("辨病论治");
                tcmTreatOpts.add("其他");
                a.add(f("sec_1", "中医诊断", "section", false));
                a.add(f("tcm_outpatient_diag", "门急诊诊断(中医)", "textarea", true));
                a.add(f("tcm_discharge_diag", "出院诊断(中医)", "textarea", true));
                a.add(f("sec_2", "治疗与辨证施护", "section", false));
                a.add(f("tcm_treatment_type", "中医治疗类别", "select", true, "options", tcmTreatOpts));
                a.add(f("tcm_syndrome_nursing", "辨证施护", "textarea", false));
                break;
            }
            case "EMR_TCM_ADMIT":
                a.add(f("sec_1", "四诊合参", "section", false));
                a.add(f("tcm_inspection", "望诊", "textarea", false));
                a.add(f("tcm_auscultation", "闻诊", "textarea", false));
                a.add(f("tcm_inquiry", "问诊", "textarea", false));
                a.add(f("tcm_palpation", "切诊", "textarea", false));
                a.add(f("sec_2", "辨证论治", "section", false));
                a.add(f("tcm_syndrome_analysis", "证候分析", "textarea", true));
                a.add(f("tcm_treatment_method", "治法", "textarea", true));
                a.add(f("tcm_prescription", "方药", "textarea", true));
                break;
            case "EMR_TCM_PROG":
                a.add(f("tcm_syndrome_reasoning", "辨证思路", "textarea", true));
                a.add(f("tcm_prescription_adjust", "方药调整", "textarea", false));
                a.add(f("tcm_efficacy_evaluation", "疗效评价", "textarea", true));
                break;
            default:
                break;
        }
        return JSON.toJSONString(a);
    }

    /* ================= 种子文档骨架(P2 新增 10-15 类) ================= */

    /**
     * 门诊模板 Tiptap 文档(P3): 从 fields 单一真源逐字段生成章节，完整保留控件类型、快捷短语、占位提示和默认宏。
     * 每个 SOAP 字段继续使用同名 sectionKey，保证病历引用、报告引用、NLG 与历史兼容逻辑可精确定位。
     */
    private static String buildOutpSeedDocument(String code) {
        JSONArray definitions = JSON.parseArray(buildSeedFields(code));
        JSONArray docContent = new JSONArray();
        for (int i = 0; i < definitions.size(); i++) {
            JSONObject def = definitions.getJSONObject(i);
            if (def == null || "section".equals(def.getString("type"))) {
                continue;
            }
            String fieldKey = def.getString("fieldKey");
            String labelText = def.getString("label");
            String fieldType = def.getString("type");

            JSONObject fieldNode = new JSONObject();
            fieldNode.put("type", NODE_FIELD);
            JSONObject attrs = new JSONObject();
            attrs.put("fieldKey", fieldKey);
            attrs.put("fieldName", labelText);
            attrs.put("valueType", seedValueType(fieldType));
            attrs.put("required", Boolean.TRUE.equals(def.getBoolean("required")));
            attrs.put("value", null);
            copyFieldAttr(def, attrs, "options");
            copyFieldAttr(def, attrs, "placeholder");
            copyFieldAttr(def, attrs, "unit");
            copyFieldAttr(def, attrs, "defaultMacro");
            if ("diagnosis".equals(fieldType)) {
                attrs.put("dictSource", "diag");
            } else if (def.getJSONObject("dictRef") != null) {
                attrs.put("dictSource", def.getJSONObject("dictRef").getString("source"));
            }
            fieldNode.put("attrs", attrs);

            JSONArray paragraphContent = new JSONArray();
            paragraphContent.add(fieldNode);
            JSONObject paragraph = new JSONObject();
            paragraph.put("type", "paragraph");
            paragraph.put("content", paragraphContent);

            JSONArray sectionContent = new JSONArray();
            sectionContent.add(paragraph);
            JSONObject section = new JSONObject();
            section.put("type", NODE_SECTION);
            JSONObject sectionAttrs = new JSONObject();
            sectionAttrs.put("key", fieldKey);
            sectionAttrs.put("sectionKey", fieldKey);
            sectionAttrs.put("title", labelText);
            sectionAttrs.put("editMode", "form");
            sectionAttrs.put("locked", false);
            section.put("attrs", sectionAttrs);
            section.put("content", sectionContent);
            docContent.add(section);
        }
        JSONObject doc = new JSONObject();
        doc.put("type", NODE_DOC);
        doc.put("content", docContent);
        return JSON.toJSONString(doc);
    }

    private static void copyFieldAttr(JSONObject source, JSONObject target, String key) {
        if (source.containsKey(key) && source.get(key) != null) {
            target.put(key, source.get(key));
        }
    }

    /* ================= P8 中医三模板文档(多章节) ================= */

    /**
     * P8 中医模板 Tiptap 文档: 中医病案首页(中医诊断 + 治疗与辨证施护)与中医入院记录(四诊合参 + 辨证论治)
     * 为多章节结构, 中医病程记录为单章节; 章节标识供前端书写器 sectionKey 定位, 数据元 key 与种子字段定义一致。
     */
    private static String buildTcmSeedDocument(String code) {
        JSONArray docContent = new JSONArray();
        switch (code) {
            case "EMR_TCM_HOMEPAGE":
                docContent.add(buildSeedSection("tcmDiag", "中医诊断", new String[][]{
                        {"tcm_outpatient_diag", "门急诊诊断(中医)", "text", "1"},
                        {"tcm_discharge_diag", "出院诊断(中医)", "text", "1"}}));
                docContent.add(buildSeedSection("tcmTreat", "治疗与辨证施护", new String[][]{
                        {"tcm_treatment_type", "中医治疗类别", "select", "1"},
                        {"tcm_syndrome_nursing", "辨证施护", "text", "0"}}));
                break;
            case "EMR_TCM_ADMIT":
                docContent.add(buildSeedSection("tcmFourExams", "四诊合参", new String[][]{
                        {"tcm_inspection", "望诊", "text", "0"},
                        {"tcm_auscultation", "闻诊", "text", "0"},
                        {"tcm_inquiry", "问诊", "text", "0"},
                        {"tcm_palpation", "切诊", "text", "0"}}));
                docContent.add(buildSeedSection("tcmSyndrome", "辨证论治", new String[][]{
                        {"tcm_syndrome_analysis", "证候分析", "text", "1"},
                        {"tcm_treatment_method", "治法", "text", "1"},
                        {"tcm_prescription", "方药", "text", "1"}}));
                break;
            case "EMR_TCM_PROG":
            default:
                docContent.add(buildSeedSection("tcmProg", "辨证记录", new String[][]{
                        {"tcm_syndrome_reasoning", "辨证思路", "text", "1"},
                        {"tcm_prescription_adjust", "方药调整", "text", "0"},
                        {"tcm_efficacy_evaluation", "疗效评价", "text", "1"}}));
                break;
        }
        JSONObject doc = new JSONObject();
        doc.put("type", NODE_DOC);
        doc.put("content", docContent);
        return JSON.toJSONString(doc);
    }

    /**
     * 构建单个中医章节节点: 每字段一行段落 [文本标签 + emrField 内联节点], 节点口径与 buildOutpSeedDocument 一致;
     * 章节 attrs 双写 key=sectionKey(前端编辑器 schema 读 sectionKey, 后端章节维护以 attrs.key 命中)。
     * fields 每行: {fieldKey, 中文标签, valueType, required(1/0)}。
     */
    private static JSONObject buildSeedSection(String sectionKey, String title, String[][] fields) {
        JSONArray sContent = new JSONArray();
        for (String[] s : fields) {
            JSONObject fieldNode = new JSONObject();
            fieldNode.put("type", NODE_FIELD);
            JSONObject attrs = new JSONObject();
            attrs.put("fieldKey", s[0]);
            attrs.put("fieldName", s[1]);
            attrs.put("valueType", s[2]);
            attrs.put("required", "1".equals(s[3]));
            attrs.put("value", null);
            fieldNode.put("attrs", attrs);
            JSONObject label = new JSONObject();
            label.put("type", "text");
            label.put("text", s[1] + "：");
            JSONArray pContent = new JSONArray();
            pContent.add(label);
            pContent.add(fieldNode);
            JSONObject paragraph = new JSONObject();
            paragraph.put("type", "paragraph");
            paragraph.put("content", pContent);
            sContent.add(paragraph);
        }
        JSONObject section = new JSONObject();
        section.put("type", NODE_SECTION);
        JSONObject sectionAttrs = new JSONObject();
        sectionAttrs.put("key", sectionKey);
        sectionAttrs.put("sectionKey", sectionKey); // 编辑器 schema 键(设计器保存归一双写 key=sectionKey)
        sectionAttrs.put("title", title);
        sectionAttrs.put("editMode", "mixed");
        sectionAttrs.put("locked", false);
        section.put("attrs", sectionAttrs);
        section.put("content", sContent);
        return section;
    }

    /**
     * 由种子字段定义生成 Tiptap 文档骨架: 顺序扫描 fields, 遇 type=section 标记即开新 emrSection
     * (key=标记 fieldKey, title=标记 label), 首个标记前的字段并入以模板名为标题的引导章节; 每个数据元
     * 生成 [文本标签 + emrField 内联节点] 段落, 节点口径经 buildSeedSection 与 createFromDataset 一致, 书写器可直接渲染。
     * 门诊两模板(P3)分流至 buildOutpSeedDocument, 中医三模板分流至 buildTcmSeedDocument;
     * 无 section 标记的扩展模板退化为单引导章节(与旧行为等价)。
     */
    private static String buildSeedDocument(String code, String title) {
        if (SEED_OUTP_CODES.contains(code)) {
            return buildOutpSeedDocument(code);
        }
        if (SEED_TCM_CODES.contains(code)) {
            return buildTcmSeedDocument(code);
        }
        JSONArray fields = JSON.parseArray(buildSeedFields(code));
        JSONArray docContent = new JSONArray();
        String secKey = code.toLowerCase();
        String secTitle = title;
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            JSONObject def = fields.getJSONObject(i);
            if ("section".equals(def.getString("type"))) {
                if (!rows.isEmpty()) {
                    docContent.add(buildSeedSection(secKey, secTitle, rows.toArray(new String[0][])));
                    rows = new ArrayList<>();
                }
                secKey = def.getString("fieldKey");
                secTitle = def.getString("label");
                continue; // 分组标记行自身不生成数据元节点, 仅切换当前章节
            }
            rows.add(new String[]{
                    def.getString("fieldKey"),
                    def.getString("label"),
                    seedValueType(def.getString("type")),
                    Boolean.TRUE.equals(def.getBoolean("required")) ? "1" : "0"
            });
        }
        if (!rows.isEmpty()) {
            docContent.add(buildSeedSection(secKey, secTitle, rows.toArray(new String[0][])));
        }
        JSONObject doc = new JSONObject();
        doc.put("type", NODE_DOC);
        doc.put("content", docContent);
        return JSON.toJSONString(doc);
    }

    /** 种子字段类型 → Tiptap valueType；门诊书写器原生支持长文本、生命体征和诊断字典。 */
    private static String seedValueType(String fieldType) {
        if (fieldType == null) {
            return "text";
        }
        switch (fieldType) {
            case "number":
                return "number";
            case "date":
                return "date";
            case "datetime":
                return "datetime";
            case "textarea":
                return "textarea";
            case "vitals":
                return "vitals";
            case "diagnosis":
                return "dict";
            case "select":
                return "select";
            case "multiselect":
                return "multiselect";
            case "checkbox":
                return "checkbox";
            default:
                return "text";
        }
    }
}
