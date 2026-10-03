package com.yb.hi.service.basedata;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.dto.basedata.BatchTemplateReq;
import com.yb.hi.entity.basedata.HisDept;
import com.yb.hi.entity.basedata.HisScheduleTemplate;
import com.yb.hi.entity.basedata.HisStaff;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.basedata.HisDeptMapper;
import com.yb.hi.mapper.basedata.HisScheduleTemplateMapper;
import com.yb.hi.mapper.basedata.HisStaffMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 周排班模板服务(模板管理: 分页列表 / 单条新增编辑删除 / 批量创建)。
 * <p>冲突约束: 同一租户内 医师 + 星期几 + 时段 仅允许一条模板(租户由 MyBatis-Plus 租户插件自动过滤)。</p>
 * <p>冗余回填: staff_name/dept_name/template_name 由服务端按医师与科室数据回填, 保证列表直接可读。</p>
 */
@Service
public class ScheduleTemplateService {

    /** 时段受控值(与 his_schedule.time_type 一致) */
    private static final Set<String> TIME_TYPES = new HashSet<>(Arrays.asList("am", "pm", "night"));

    private final HisScheduleTemplateMapper templateMapper;
    private final HisStaffMapper staffMapper;
    private final HisDeptMapper deptMapper;

    public ScheduleTemplateService(HisScheduleTemplateMapper templateMapper, HisStaffMapper staffMapper, HisDeptMapper deptMapper) {
        this.templateMapper = templateMapper;
        this.staffMapper = staffMapper;
        this.deptMapper = deptMapper;
    }

    /**
     * 模板分页列表。
     * orgId 经科室归属(his_dept.org_id)关联过滤: 非牵头机构强制本院(调用方 Controller 经 OrgAccessGuard.scopeOrgId 收敛)。
     * 排序: 科室 → 医师 → 星期 → 时段。
     */
    public IPage<HisScheduleTemplate> listPage(Long orgId, Long deptId, Long staffId, long page, long size) {
        LambdaQueryWrapper<HisScheduleTemplate> w = Wrappers.<HisScheduleTemplate>lambdaQuery()
                .apply(orgId != null,
                        "EXISTS (SELECT 1 FROM his_dept d WHERE d.id = his_schedule_template.dept_id AND d.org_id = {0})", orgId)
                .eq(deptId != null, HisScheduleTemplate::getDeptId, deptId)
                .eq(staffId != null, HisScheduleTemplate::getStaffId, staffId)
                .orderByAsc(HisScheduleTemplate::getDeptId)
                .orderByAsc(HisScheduleTemplate::getStaffId)
                .orderByAsc(HisScheduleTemplate::getWeekday)
                .orderByAsc(HisScheduleTemplate::getTimeType);
        return templateMapper.selectPage(new Page<>(safePage(page), safeSize(size)), w);
    }

    /** 新增模板: 校验 → 冗余名称回填(含科室推导) → 冲突校验(同一医师同星期同时段同科室) → 保存 */
    public HisScheduleTemplate create(HisScheduleTemplate template) {
        validateBase(template);
        template.setId(null);
        fillNames(template);
        assertNoConflict(template.getStaffId(), template.getWeekday(), template.getTimeType(), template.getDeptId(), null);
        templateMapper.insert(template);
        return template;
    }

    /** 编辑模板: 校验同上(冲突校验排除自身) */
    public HisScheduleTemplate update(HisScheduleTemplate template) {
        validateBase(template);
        if (template.getId() == null) {
            throw new BizException(400, "模板ID缺失");
        }
        if (templateMapper.selectById(template.getId()) == null) {
            throw new BizException(400, "模板不存在");
        }
        fillNames(template);
        assertNoConflict(template.getStaffId(), template.getWeekday(), template.getTimeType(), template.getDeptId(), template.getId());
        templateMapper.updateById(template);
        return template;
    }

    /** 删除模板(逻辑删除, 不影响历史已生成的排班) */
    public void delete(Long id) {
        if (id == null) {
            throw new BizException(400, "模板ID缺失");
        }
        if (templateMapper.selectById(id) == null) {
            throw new BizException(400, "模板不存在");
        }
        templateMapper.deleteById(id);
    }

    /**
     * 批量创建模板: 为每个 staffId × 每个 slot 生成一条模板。
     * <p>跳过策略: 已存在(同医师+星期+时段)的组合跳过; 医师无效或无法推导科室的整组跳过。</p>
     *
     * @return {created: 新增条数, skipped: 跳过条数}
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> batchCreate(BatchTemplateReq req) {
        if (req == null || req.getStaffIds() == null || req.getStaffIds().isEmpty()) {
            throw new BizException(400, "医师列表不能为空");
        }
        if (req.getSlots() == null || req.getSlots().isEmpty()) {
            throw new BizException(400, "时段槽位不能为空");
        }
        for (BatchTemplateReq.SlotReq slot : req.getSlots()) {
            if (slot == null) {
                throw new BizException(400, "时段槽位不能为空");
            }
            validateWeekTime(slot.getWeekday(), slot.getTimeType());
        }

        // 医师ID去重(保序), 剔除空值
        Set<Long> wantedIds = new LinkedHashSet<>();
        for (Long sid : req.getStaffIds()) {
            if (sid != null) {
                wantedIds.add(sid);
            }
        }
        if (wantedIds.isEmpty()) {
            throw new BizException(400, "医师列表不能为空");
        }

        // 查医师(租户内有效; selectBatchIds 自动带租户过滤)
        List<HisStaff> staffs = staffMapper.selectBatchIds(wantedIds);

        // 已存在组合: 一次查出该批医师的全部模板, 内存判重(避免逐条 COUNT)
        Set<String> exists = new HashSet<>();
        if (!staffs.isEmpty()) {
            List<HisScheduleTemplate> olds = templateMapper.selectList(Wrappers.<HisScheduleTemplate>lambdaQuery()
                    .in(HisScheduleTemplate::getStaffId, wantedIds));
            for (HisScheduleTemplate o : olds) {
                exists.add(key(o.getStaffId(), o.getWeekday(), o.getTimeType(), o.getDeptId()));
            }
        }

        // created 新增数; skipped 跳过数(无效医师整组 + 已存在组合 + 无法推导/不可排班科室)
        int created = 0;
        int skipped = Math.max(0, wantedIds.size() - staffs.size()) * req.getSlots().size();
        // 显式指定科室时先整体校验(非本机构开诊门诊科室直接拒绝)
        if (req.getDeptId() != null) {
            assertSchedulableDept(req.getDeptId());
        }
        for (HisStaff staff : staffs) {
            // 非本机构医师整组跳过(模板与排班同为机构自己的业务过程)
            if (!staffInOrg(staff)) {
                skipped += req.getSlots().size();
                continue;
            }
            Long deptId = req.getDeptId() != null ? req.getDeptId() : staff.getDeptId();
            if (deptId == null) {
                skipped += req.getSlots().size();
                continue;
            }
            // 回退到医师归属科室时, 非本机构开诊门诊科室的整组跳过(不阻断其他医师)
            if (req.getDeptId() == null) {
                try {
                    assertSchedulableDept(deptId);
                } catch (BizException be) {
                    skipped += req.getSlots().size();
                    continue;
                }
            }
            String deptName = StringUtils.hasText(req.getDeptName()) ? req.getDeptName() : deptNameOf(deptId);
            for (BatchTemplateReq.SlotReq slot : req.getSlots()) {
                String k = key(staff.getId(), slot.getWeekday(), slot.getTimeType(), deptId);
                if (exists.contains(k)) {
                    skipped++;
                    continue;
                }
                HisScheduleTemplate t = new HisScheduleTemplate();
                t.setTemplateName(buildTemplateName(staff.getStaffName(), deptName, slot.getWeekday(), slot.getTimeType()));
                t.setDeptId(deptId);
                t.setDeptName(deptName);
                t.setStaffId(staff.getId());
                t.setStaffName(staff.getStaffName());
                t.setWeekday(slot.getWeekday());
                t.setTimeType(slot.getTimeType());
                t.setRegLevelCode(StringUtils.hasText(slot.getRegLevelCode()) ? slot.getRegLevelCode() : "01");
                t.setRegLevelName(StringUtils.hasText(slot.getRegLevelName()) ? slot.getRegLevelName() : "普通号");
                t.setRegFee(slot.getRegFee() != null ? slot.getRegFee() : BigDecimal.ZERO);
                t.setTotalNum(slot.getTotalNum() != null ? slot.getTotalNum() : 30);
                t.setRoom(slot.getRoom());
                t.setStatus(1);
                templateMapper.insert(t);
                exists.add(k); // 防 slots 内重复槽位被重复插入
                created++;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", created);
        out.put("skipped", skipped);
        return out;
    }

    /* ================= 内部工具 ================= */

    /** 基础校验: 医师/星期/时段必填且受控 */
    private static void validateBase(HisScheduleTemplate t) {
        if (t == null) {
            throw new BizException(400, "模板数据不能为空");
        }
        if (t.getStaffId() == null) {
            throw new BizException(400, "出诊医师必填");
        }
        validateWeekTime(t.getWeekday(), t.getTimeType());
    }

    /** 星期(1~7)与时段(am/pm/night)受控校验 */
    private static void validateWeekTime(Integer weekday, String timeType) {
        if (weekday == null || weekday < 1 || weekday > 7) {
            throw new BizException(400, "星期几取值须为 1(周一)~7(周日)");
        }
        if (timeType == null || !TIME_TYPES.contains(timeType)) {
            throw new BizException(400, "时段取值须为 am/pm/night");
        }
    }

    /** 冲突校验: 同医师 + 同星期 + 同时段 + 同科室不允许重复(excludeId 用于编辑时排除自身)。
     *  医师同星期同时段跨科室排多个班次属正常业务, 不拦截。 */
    private void assertNoConflict(Long staffId, Integer weekday, String timeType, Long deptId, Long excludeId) {
        Long cnt = templateMapper.selectCount(Wrappers.<HisScheduleTemplate>lambdaQuery()
                .eq(HisScheduleTemplate::getStaffId, staffId)
                .eq(HisScheduleTemplate::getWeekday, weekday)
                .eq(HisScheduleTemplate::getTimeType, timeType)
                .eq(deptId != null, HisScheduleTemplate::getDeptId, deptId)
                .ne(excludeId != null, HisScheduleTemplate::getId, excludeId));
        if (cnt != null && cnt > 0) {
            throw new BizException(400, "该医师在所选星期与时段于该科室已存在排班模板, 不可重复(换科室可继续加建)");
        }
    }

    /** 冗余回填: 医师姓名/科室(缺省按医师归属推导)/科室名称/模板名称 */
    private void fillNames(HisScheduleTemplate t) {
        HisStaff staff = staffMapper.selectById(t.getStaffId());
        if (staff == null) {
            throw new BizException(400, "医师不存在");
        }
        // 出诊医师仅限当前登录机构本级(院外专家须先建本院虚拟职工记录)
        assertStaffInOrg(staff);
        if (!StringUtils.hasText(t.getStaffName())) {
            t.setStaffName(staff.getStaffName());
        }
        if (t.getDeptId() == null) {
            t.setDeptId(staff.getDeptId());
        }
        if (t.getDeptId() == null) {
            throw new BizException(400, "科室不能为空且无法从医师归属推导");
        }
        // 排班模板科室必须属本机构且为开诊门诊科室(出诊科室与医师行政所属科室解耦)
        assertSchedulableDept(t.getDeptId());
        if (!StringUtils.hasText(t.getDeptName())) {
            t.setDeptName(deptNameOf(t.getDeptId()));
        }
        if (!StringUtils.hasText(t.getTemplateName())) {
            t.setTemplateName(buildTemplateName(t.getStaffName(), t.getDeptName(), t.getWeekday(), t.getTimeType()));
        }
    }

    /**
     * 校验模板科室为当前登录机构的开诊门诊科室(无登录上下文/无机构时跳过)。
     */
    private void assertSchedulableDept(Long deptId) {
        LoginUser lu = UserContext.get();
        Long orgId = lu == null ? null : lu.getOrgId();
        if (orgId == null || deptId == null) {
            return;
        }
        HisDept d = deptMapper.selectById(deptId);
        if (d == null) {
            throw new BizException(400, "排班模板科室不存在");
        }
        if (d.getOrgId() == null || !d.getOrgId().equals(orgId)) {
            throw new BizException("排班模板科室必须属于本机构, 不可跨机构");
        }
        if (!HisDeptService.hasCategory(d.getDeptCategory(), "门诊科室")) {
            throw new BizException("仅「门诊科室」可建排班模板: " + d.getDeptName());
        }
        Integer lv = d.getDeptLevel();
        if (lv == null || (lv != 2 && lv != 3)) {
            throw new BizException("仅科室/诊室层级可建排班模板(大类节点不可)");
        }
        if (d.getStatus() != null && d.getStatus() != 1) {
            throw new BizException("该科室已停用, 不可建模板: " + d.getDeptName());
        }
        if (d.getOpenClinic() != null && d.getOpenClinic() == 0) {
            throw new BizException("该门诊科室未开诊, 不可建模板: " + d.getDeptName());
        }
    }

    /**
     * 医师机构归属判定: 无登录上下文/无机构时不限制; 医师未配归属机构的存量数据不拦截。
     */
    private static boolean staffInOrg(HisStaff staff) {
        LoginUser lu = UserContext.get();
        Long orgId = lu == null ? null : lu.getOrgId();
        if (orgId == null || staff == null) {
            return true;
        }
        return staff.getOrgId() == null || staff.getOrgId().equals(orgId);
    }

    /** 医师机构归属校验(单条新增/编辑路径): 非本机构直接拒绝 */
    private static void assertStaffInOrg(HisStaff staff) {
        if (!staffInOrg(staff)) {
            throw new BizException("出诊医师必须属于本机构: " + staff.getStaffName()
                    + "(" + staff.getStaffNo() + "), 院外专家请先建本院职工档案再建模板");
        }
    }

    /** 科室名称(查不到返回 null, 不阻断) */
    private String deptNameOf(Long deptId) {
        HisDept dept = deptMapper.selectById(deptId);
        return dept == null ? null : dept.getDeptName();
    }

    /** 模板名称生成: 医师 科室 周X上午排班模板 */
    private static String buildTemplateName(String staffName, String deptName, Integer weekday, String timeType) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(staffName)) {
            sb.append(staffName).append(' ');
        }
        if (StringUtils.hasText(deptName)) {
            sb.append(deptName).append(' ');
        }
        return sb.append('周').append(weekdayText(weekday)).append(timeTypeText(timeType)).append("排班模板").toString();
    }

    private static String weekdayText(Integer weekday) {
        if (weekday == null || weekday < 1 || weekday > 7) {
            return "";
        }
        return "一二三四五六日".substring(weekday - 1, weekday);
    }

    private static String timeTypeText(String timeType) {
        if ("am".equals(timeType)) {
            return "上午";
        }
        if ("pm".equals(timeType)) {
            return "下午";
        }
        if ("night".equals(timeType)) {
            return "晚间";
        }
        return "";
    }

    /** 判重键: 医师|星期|时段|科室(与 assertNoConflict 同粒度, 支持跨科室多班次) */
    private static String key(Long staffId, Integer weekday, String timeType, Long deptId) {
        return staffId + "|" + weekday + "|" + timeType + "|" + deptId;
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
