package com.yb.hi.service.inpatient;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.inpatient.HisNursingTemplate;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.inpatient.HisNursingTemplateMapper;
import com.yb.hi.platform.entity.SysTenant;
import com.yb.hi.platform.service.SysTenantService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 护理文书模板服务(P4a): 启动时按租户幂等播种 3 个标准护理 Tiptap 模板(@Order(10),
 * 表 his_nursing_template 由 DictSchemaMigration@0 建, 机构由 RbacInitializer@3 建);
 * 并提供模板 CRUD 与按文书类型/病区作用域的查询(P4a-2: listByType/getById/save/delete)。
 *
 * 与医生站 EmrTemplateService(@Order(7)) 解耦: 护理模板入独立表 his_nursing_template,
 * code 仅租户内判空(无跨租户全局唯一键, 各租户独立维护同名标准码即可); Tiptap 文档节点口径
 * 与医生站一致(doc → emrSection[attrs.key/sectionKey/title/editMode/locked] → paragraph →
 * [text 标签 + emrField[attrs.fieldKey/fieldName/valueType/required/value]]), 护理书写器
 * (emr-editor.js)可直接渲染。
 *
 * 种子模板:
 * - NURS_GENERAL 一般护理记录: 病情观察/护理措施/生命体征/特殊情况四章节;
 * - NURS_CRITICAL 危重护理记录: 时间线格式(时间/病情变化/治疗/护理/医嘱执行五章节);
 * - NURS_SURGERY 手术护理记录: 三段式(术前评估/术中记录/术后护理)。
 */
@Slf4j
@Order(10)
@Service
public class NursingTemplateService implements ApplicationRunner {

    /** Tiptap 节点类型常量(与 EmrTemplateService 口径一致) */
    private static final String NODE_DOC = "doc";
    private static final String NODE_SECTION = "emrSection";
    private static final String NODE_FIELD = "emrField";

    /**
     * 种子模板: {模板编码, 模板名称, 文书类型 record_type}。
     * 文书类型取值: nursing_record(护理记录)/assessment(评估)/transfer(转运)/consent(知情同意)/nursing_plan(护理计划)。
     */
    private static final String[][] SEED_TEMPLATES = {
            {"NURS_GENERAL", "一般护理记录", "nursing_record"},
            {"NURS_CRITICAL", "危重护理记录", "nursing_record"},
            {"NURS_SURGERY", "手术护理记录", "nursing_record"}
    };

    /** 允许的文书类型(与 DDL 注释口径一致) */
    private static final Set<String> RECORD_TYPES = new HashSet<>(Arrays.asList(
            "nursing_record", "assessment", "transfer", "consent", "nursing_plan"));

    /**
     * 章节字段定义(驱动 fields JSON 与 Tiptap 文档同步生成):
     * {模板编码, 章节 key, 章节标题, fieldKey, 字段名, 字段类型, 是否必填}
     * 字段类型口径与 EmrTemplateService.seedValueType 一致(text/textarea/number/datetime/select)。
     */
    private static final String[][] SEED_SECTIONS = {
            /* ---------- NURS_GENERAL 一般护理记录: 观察→措施→体征→特殊情况 ---------- */
            {"NURS_GENERAL", "observation", "病情观察", "consciousnessLevel", "意识状态", "select", "1"},
            {"NURS_GENERAL", "observation", "病情观察", "mentalState", "精神状态", "text", "0"},
            {"NURS_GENERAL", "observation", "病情观察", "complaint", "主诉不适", "textarea", "0"},
            {"NURS_GENERAL", "observation", "病情观察", "skinMucosa", "皮肤黏膜", "textarea", "0"},
            {"NURS_GENERAL", "observation", "病情观察", "tubeCare", "导管情况", "textarea", "0"},
            {"NURS_GENERAL", "intervention", "护理措施", "baseCare", "基础护理", "textarea", "1"},
            {"NURS_GENERAL", "intervention", "护理措施", "specialtyCare", "专科护理", "textarea", "0"},
            {"NURS_GENERAL", "intervention", "护理措施", "position", "体位", "text", "0"},
            {"NURS_GENERAL", "intervention", "护理措施", "safetyMeasures", "安全措施", "textarea", "0"},
            {"NURS_GENERAL", "vitals", "生命体征", "temperature", "体温(℃)", "number", "1"},
            {"NURS_GENERAL", "vitals", "生命体征", "pulse", "脉搏(次/分)", "number", "1"},
            {"NURS_GENERAL", "vitals", "生命体征", "respiration", "呼吸(次/分)", "number", "1"},
            {"NURS_GENERAL", "vitals", "生命体征", "bloodPressure", "血压(mmHg)", "text", "1"},
            {"NURS_GENERAL", "vitals", "生命体征", "spo2", "血氧饱和度(%)", "number", "0"},
            {"NURS_GENERAL", "special", "特殊情况", "conditionChange", "病情变化", "textarea", "0"},
            {"NURS_GENERAL", "special", "特殊情况", "notifyDoctor", "通知医生", "text", "0"},
            {"NURS_GENERAL", "special", "特殊情况", "handleMeasures", "处理措施", "textarea", "0"},
            /* ---------- NURS_CRITICAL 危重护理记录: 时间线五章节 ---------- */
            {"NURS_CRITICAL", "time", "时间", "recordTime", "记录时间", "datetime", "1"},
            {"NURS_CRITICAL", "time", "时间", "nurseSign", "记录护士", "text", "1"},
            {"NURS_CRITICAL", "condition", "病情变化", "consciousness", "意识状态", "text", "1"},
            {"NURS_CRITICAL", "condition", "病情变化", "pupil", "瞳孔观察", "textarea", "0"},
            {"NURS_CRITICAL", "condition", "病情变化", "vitalsChange", "生命体征变化", "textarea", "1"},
            {"NURS_CRITICAL", "treatment", "治疗", "medication", "用药", "textarea", "1"},
            {"NURS_CRITICAL", "treatment", "治疗", "oxygenTherapy", "氧疗", "text", "0"},
            {"NURS_CRITICAL", "treatment", "治疗", "ventilation", "通气支持", "text", "0"},
            {"NURS_CRITICAL", "care", "护理", "nursingCare", "护理措施", "textarea", "1"},
            {"NURS_CRITICAL", "care", "护理", "intakeOutput", "出入量(ml)", "text", "0"},
            {"NURS_CRITICAL", "order_exec", "医嘱执行", "orderContent", "执行医嘱内容", "textarea", "1"},
            {"NURS_CRITICAL", "order_exec", "医嘱执行", "execTime", "执行时间", "datetime", "0"},
            {"NURS_CRITICAL", "order_exec", "医嘱执行", "execNurse", "执行护士", "text", "0"},
            /* ---------- NURS_SURGERY 手术护理记录: 三段式 ---------- */
            {"NURS_SURGERY", "pre_op", "术前评估", "patientVerify", "患者核对", "text", "1"},
            {"NURS_SURGERY", "pre_op", "术前评估", "preDiag", "术前诊断", "text", "1"},
            {"NURS_SURGERY", "pre_op", "术前评估", "allergyHistory", "过敏史", "text", "0"},
            {"NURS_SURGERY", "pre_op", "术前评估", "skinPrep", "皮肤准备", "textarea", "0"},
            {"NURS_SURGERY", "pre_op", "术前评估", "psychState", "心理状态", "text", "0"},
            {"NURS_SURGERY", "intra_op", "术中记录", "surgeryName", "手术名称", "text", "1"},
            {"NURS_SURGERY", "intra_op", "术中记录", "roomNo", "手术间", "text", "0"},
            {"NURS_SURGERY", "intra_op", "术中记录", "position", "体位", "text", "0"},
            {"NURS_SURGERY", "intra_op", "术中记录", "instrumentCheck", "器械清点", "textarea", "1"},
            {"NURS_SURGERY", "intra_op", "术中记录", "bloodLoss", "出血量(ml)", "number", "1"},
            {"NURS_SURGERY", "intra_op", "术中记录", "infusionVolume", "输液量(ml)", "number", "0"},
            {"NURS_SURGERY", "intra_op", "术中记录", "specimen", "标本处理", "textarea", "0"},
            {"NURS_SURGERY", "post_op", "术后护理", "awakeTime", "苏醒时间", "datetime", "0"},
            {"NURS_SURGERY", "post_op", "术后护理", "vitals", "生命体征", "textarea", "1"},
            {"NURS_SURGERY", "post_op", "术后护理", "woundCondition", "伤口情况", "textarea", "1"},
            {"NURS_SURGERY", "post_op", "术后护理", "drainage", "引流情况", "textarea", "0"},
            {"NURS_SURGERY", "post_op", "术后护理", "postOrders", "术后医嘱", "textarea", "0"}
    };

    private final HisNursingTemplateMapper templateMapper;
    private final SysTenantService tenantService;
    private final JdbcTemplate jdbcTemplate;

    public NursingTemplateService(HisNursingTemplateMapper templateMapper,
                                  SysTenantService tenantService, JdbcTemplate jdbcTemplate) {
        this.templateMapper = templateMapper;
        this.tenantService = tenantService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ================= 启动播种 ================= */

    @Override
    public void run(ApplicationArguments args) {
        List<SysTenant> tenants;
        try {
            tenants = tenantService.listAll();
        } catch (Exception e) {
            log.warn("护理文书模板种子跳过(租户表未就绪): {}", e.getMessage());
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
                log.warn("租户[{}] 护理文书模板种子跳过(可能表未建): {}", t.getId(), e.getMessage());
            } finally {
                if (prev == null) {
                    TenantContext.clear();
                } else {
                    TenantContext.set(prev);
                }
            }
        }
    }

    /** 按租户播种缺失的标准护理模板(逐码判空, 部分缺失可补种; 存量行缺文档则幂等回填) */
    private void seedTenant(Long tenantId) {
        Long orgId = resolveLeadOrgId(tenantId);
        if (orgId == null) {
            return; // 机构未建(RbacInitializer 未跑完), 下次启动补种
        }
        int added = 0;
        for (String[] def : SEED_TEMPLATES) {
            String code = def[0];
            List<HisNursingTemplate> exists = templateMapper.selectList(Wrappers.<HisNursingTemplate>lambdaQuery()
                    .eq(HisNursingTemplate::getCode, code));
            if (!exists.isEmpty()) {
                backfillSeedDocIfMissing(code, exists);
                continue;
            }
            try {
                HisNursingTemplate t = new HisNursingTemplate();
                t.setOrgId(orgId);
                t.setCode(code);
                t.setName(def[1]);
                t.setRecordType(def[2]);
                t.setWardScope(null); // 全院通用(空=不限病区)
                t.setPaperSize("A4");
                t.setOrientation("portrait");
                t.setFields(buildSeedFields(code));
                t.setDocument(buildSeedDocument(code, def[1]));
                t.setScopeLevel(0); // 全院级种子(与 DDL 默认一致, 显式声明)
                templateMapper.insert(t);
                added++;
            } catch (DuplicateKeyException e) {
                log.warn("租户[{}] 护理模板编码碰撞: {}", tenantId, code);
            }
        }
        if (added > 0) {
            log.info("租户[{}] 护理文书标准模板初始化完成(新增{}个)", tenantId, added);
        }
    }

    /** 解析租户牵头机构: 优先 is_lead=1, 兜底最小 id(his_nursing_template.org_id 非空) */
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

    /**
     * 存量模板 Tiptap 文档幂等补种: 本地已存在但 document 为空的模板回填种子文档
     * (仅补空不覆盖已有文档; 失败只告警不阻断后续种子)。
     */
    private void backfillSeedDocIfMissing(String code, List<HisNursingTemplate> exists) {
        for (HisNursingTemplate t : exists) {
            if (t.getDocument() != null && !t.getDocument().isEmpty()) {
                continue;
            }
            try {
                HisNursingTemplate upd = new HisNursingTemplate();
                upd.setId(t.getId());
                upd.setDocument(buildSeedDocument(code, t.getName()));
                templateMapper.updateById(upd); // 仅非 null 字段更新, 只回填 document
                log.info("护理模板[{}] 存量行缺 Tiptap 文档, 已回填种子文档", code);
            } catch (Exception e) {
                log.warn("护理模板[{}] Tiptap 文档补种跳过: {}", code, e.getMessage());
            }
        }
    }

    /* ================= 模板 CRUD(P4a-2) ================= */

    /**
     * 模板列表: 按文书类型过滤(可选); 指定 wardId 时按 ward_scope 病区作用域命中
     * (ward_scope 为空/空数组=全院通用始终命中, 含该病区 ID 的模板命中);
     * 排序: scope_level 升序(全院→病区→个人), 名称升序。
     */
    public List<HisNursingTemplate> listByType(String recordType, Long wardId) {
        List<HisNursingTemplate> rows = templateMapper.selectList(Wrappers.<HisNursingTemplate>lambdaQuery()
                .eq(StringUtils.hasText(recordType), HisNursingTemplate::getRecordType, recordType)
                .orderByAsc(HisNursingTemplate::getScopeLevel)
                .orderByAsc(HisNursingTemplate::getName)
                .orderByAsc(HisNursingTemplate::getId));
        if (wardId == null) {
            return rows;
        }
        List<HisNursingTemplate> out = new ArrayList<>(rows.size());
        for (HisNursingTemplate t : rows) {
            if (wardScopeContains(t.getWardScope(), wardId)) {
                out.add(t);
            }
        }
        return out;
    }

    /** 模板详情(不存在返回 404) */
    public HisNursingTemplate getById(Long id) {
        if (id == null) {
            throw new BizException(400, "模板ID不能为空");
        }
        HisNursingTemplate t = templateMapper.selectById(id);
        if (t == null) {
            throw new BizException(404, "护理模板不存在");
        }
        return t;
    }

    /**
     * 保存/更新模板: 编码与名称为必填; 新增补默认文书类型/纸张/方向/作用域与归属机构(当前登录机构,
     * 未归属则为空); 更新仅覆盖非 null 字段(MyBatis-Plus 默认策略)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisNursingTemplate save(HisNursingTemplate tpl) {
        if (tpl == null) {
            throw new BizException(400, "请求体不能为空");
        }
        if (!StringUtils.hasText(tpl.getCode())) {
            throw new BizException(400, "模板编码不能为空");
        }
        if (!StringUtils.hasText(tpl.getName())) {
            throw new BizException(400, "模板名称不能为空");
        }
        if (StringUtils.hasText(tpl.getRecordType()) && !RECORD_TYPES.contains(tpl.getRecordType().trim())) {
            throw new BizException(400, "文书类型无效(nursing_record/assessment/transfer/consent/nursing_plan)");
        }
        if (tpl.getId() == null) {
            if (!StringUtils.hasText(tpl.getRecordType())) {
                tpl.setRecordType("nursing_record");
            }
            if (!StringUtils.hasText(tpl.getPaperSize())) {
                tpl.setPaperSize("A4");
            }
            if (!StringUtils.hasText(tpl.getOrientation())) {
                tpl.setOrientation("portrait");
            }
            if (tpl.getScopeLevel() == null) {
                tpl.setScopeLevel(0); // 默认全院(可改)
            }
            if (tpl.getOrgId() == null) {
                LoginUser lu = UserContext.get();
                tpl.setOrgId(lu == null ? null : lu.getOrgId());
            }
            templateMapper.insert(tpl);
            return tpl;
        }
        if (templateMapper.selectById(tpl.getId()) == null) {
            throw new BizException(404, "护理模板不存在");
        }
        templateMapper.updateById(tpl); // 仅非 null 字段更新
        return templateMapper.selectById(tpl.getId());
    }

    /** 删除(逻辑删除) */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        if (id == null) {
            throw new BizException(400, "模板ID不能为空");
        }
        if (templateMapper.selectById(id) == null) {
            throw new BizException(404, "护理模板不存在");
        }
        templateMapper.deleteById(id);
    }

    /** ward_scope 命中判定: 空/空数组=全院通用命中; JSON 数组含 wardId 命中; 非法 JSON 防御性不命中 */
    private static boolean wardScopeContains(String wardScope, Long wardId) {
        if (!StringUtils.hasText(wardScope)) {
            return true;
        }
        try {
            JSONArray arr = JSON.parseArray(wardScope);
            if (arr == null || arr.isEmpty()) {
                return true;
            }
            String target = String.valueOf(wardId);
            for (int i = 0; i < arr.size(); i++) {
                if (target.equals(String.valueOf(arr.get(i)))) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /* ================= 种子载荷构建 ================= */

    /** 章节字段定义 → 模板 fields JSON 数组(按章节分组顺序排列, 口径对齐 EmrTemplateService.toFieldJsonArray) */
    private static String buildSeedFields(String code) {
        JSONArray a = new JSONArray();
        String lastSection = null;
        for (String[] s : SEED_SECTIONS) {
            if (!code.equals(s[0])) {
                continue;
            }
            if (!s[1].equals(lastSection)) {
                JSONObject sectionMark = new JSONObject();
                sectionMark.put("fieldKey", "sec_" + s[1]);
                sectionMark.put("label", s[2]);
                sectionMark.put("type", "section");
                sectionMark.put("required", false);
                a.add(sectionMark);
                lastSection = s[1];
            }
            JSONObject f = new JSONObject();
            f.put("fieldKey", s[3]);
            f.put("label", s[4]);
            f.put("type", s[5]);
            f.put("required", "1".equals(s[6]));
            a.add(f);
        }
        return JSON.toJSONString(a);
    }

    /**
     * 种子 Tiptap 文档: 逐章节生成 emrSection(每章节独立标题, 内含各字段段落[文本标签 + emrField 内联节点]),
     * 节点口径与 EmrTemplateService.buildOutpSeedDocument 一致(attrs 双写 key=sectionKey,
     * emrField 为 inline 节点须经 paragraph 包裹才满足 emrSection 的 block+ 内容规格)。
     */
    private static String buildSeedDocument(String code, String title) {
        JSONArray docContent = new JSONArray();
        JSONObject current = null;
        String currentKey = null;
        for (String[] s : SEED_SECTIONS) {
            if (!code.equals(s[0])) {
                continue;
            }
            if (current == null || !s[1].equals(currentKey)) {
                currentKey = s[1];
                JSONObject section = new JSONObject();
                section.put("type", NODE_SECTION);
                JSONObject attrs = new JSONObject();
                attrs.put("key", s[1]);
                attrs.put("sectionKey", s[1]); // 编辑器 schema 键(设计器保存归一双写 key=sectionKey)
                attrs.put("title", s[2]);
                attrs.put("editMode", "mixed");
                attrs.put("locked", false);
                section.put("attrs", attrs);
                section.put("content", new JSONArray());
                docContent.add(section);
                current = section;
            }
            JSONObject fieldNode = new JSONObject();
            fieldNode.put("type", NODE_FIELD);
            JSONObject fieldAttrs = new JSONObject();
            fieldAttrs.put("fieldKey", s[3]);
            fieldAttrs.put("fieldName", s[4]);
            fieldAttrs.put("valueType", seedValueType(s[5]));
            fieldAttrs.put("required", "1".equals(s[6]));
            fieldAttrs.put("value", null);
            fieldNode.put("attrs", fieldAttrs);
            JSONObject label = new JSONObject();
            label.put("type", "text");
            label.put("text", s[4] + "：");
            JSONArray pContent = new JSONArray();
            pContent.add(label);
            pContent.add(fieldNode);
            JSONObject paragraph = new JSONObject();
            paragraph.put("type", "paragraph");
            paragraph.put("content", pContent);
            current.getJSONArray("content").add(paragraph);
        }
        JSONObject doc = new JSONObject();
        doc.put("type", NODE_DOC);
        doc.put("content", docContent);
        return JSON.toJSONString(doc);
    }

    /** 种子字段类型 → Tiptap valueType(与 EmrTemplateService.seedValueType 口径一致; textarea/text 归一文本) */
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
            case "select":
                return "select";
            default:
                return "text";
        }
    }
}
