package com.yb.hi.service.basedata;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.entity.basedata.HisSchedule;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.basedata.HisScheduleMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 排班号源服务:
 * - 列表/周视图/统计/模板查询走 JdbcTemplate 手动租户过滤(MyBatis-Plus 租户插件仅作用于
 *   Mapper 语句, 原生 SQL 需显式 tenant_id), JOIN his_dept/his_staff 带出冗余名称;
 * - CRUD 含冲突校验: 同租户+同医师+同日+同时段不可重复排班;
 * - 号源守恒: leftNum 不允许直接编辑, 由 totalNum 差值同步(不能低于已挂号数);
 *   删除仅在无人挂号(leftNum==totalNum)时允许;
 * - 批量停诊/按模板生成/复制周排班对冲突项幂等跳过, 返回 {created, skipped, total};
 * - 模板表 his_schedule_template 由模板管理模块维护, 本服务仅 JdbcTemplate 只读引用
 *   (不建 Entity/Mapper);
 * - weekday 统一 ISO 语义: 1=周一...7=周日(Java DayOfWeek.getValue(), 与模板 weekday 一致,
 *   注意 MySQL DAYOFWEEK() 返回 1=周日, 不使用)。
 */
@Slf4j
@Service
public class HisScheduleService extends ServiceImpl<HisScheduleMapper, HisSchedule> {

    /** 存量硬编码三值: 仅作班次字典(his_shift_dict, L1 受控源)未初始化时的回落, 新班次由字典维护页自定义 */
    private static final String[] TIME_ORDER = {"am", "pm", "night"};
    /** ISO weekday(1=周一...7=周日) -> 周视图 slot 键前缀 */
    private static final String[] WEEKDAY_PREFIX = {null, "mon", "tue", "wed", "thu", "fri", "sat", "sun"};
    /** 生成排班日期范围上限(防误操作超大范围) */
    private static final long MAX_GENERATE_DAYS = 366;

    private final HisScheduleMapper scheduleMapper;
    private final JdbcTemplate jdbc;
    private final com.yb.hi.service.community.HisShiftDictService shiftDictService;

    public HisScheduleService(HisScheduleMapper scheduleMapper, JdbcTemplate jdbc,
                              com.yb.hi.service.community.HisShiftDictService shiftDictService) {
        this.scheduleMapper = scheduleMapper;
        this.jdbc = jdbc;
        this.shiftDictService = shiftDictService;
    }

    /** 启用班次编码有序列表(周视图列序/号源按钮序); 字典未初始化时回落存量三值防排班锁死 */
    private List<String> timeCodes() {
        List<String> l = shiftDictService.enabledCodes();
        return l.isEmpty() ? Arrays.asList(TIME_ORDER) : l;
    }

    // ===== 排班列表(分页+增强筛选) =====

    /**
     * 分页查询排班列表(JOIN 科室/职工带冗余名称; orgId 经科室归属过滤;
     * Java 侧补 weekday: ISO 1=周一...7=周日)。
     */
    public IPage<Map<String, Object>> listPage(Long orgId, Long deptId, Long staffId,
                                               String from, String to, Integer status, long page, long size) {
        long p = safePage(page);
        long s = safeSize(size);
        LocalDate fromDate = parseDate(from, "开始日期");
        LocalDate toDate = parseDate(to, "结束日期");

        StringBuilder where = new StringBuilder(" WHERE s.tenant_id = ? AND s.deleted = 0");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        if (orgId != null) {
            where.append(" AND EXISTS (SELECT 1 FROM his_dept dd WHERE dd.id = s.dept_id AND dd.org_id = ?)");
            args.add(orgId);
        }
        if (deptId != null) {
            where.append(" AND s.dept_id = ?");
            args.add(deptId);
        }
        if (staffId != null) {
            where.append(" AND s.staff_id = ?");
            args.add(staffId);
        }
        if (fromDate != null) {
            where.append(" AND s.work_date >= ?");
            args.add(fromDate);
        }
        if (toDate != null) {
            where.append(" AND s.work_date <= ?");
            args.add(toDate);
        }
        if (status != null) {
            where.append(" AND s.status = ?");
            args.add(status);
        }

        String joins = " FROM his_schedule s"
                + " LEFT JOIN his_dept d ON d.id = s.dept_id AND d.deleted = 0"
                + " LEFT JOIN his_staff st ON st.id = s.staff_id AND st.deleted = 0";
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + joins + where, Long.class, args.toArray());

        String dataSql = "SELECT s.id, s.tenant_id, s.dept_id, s.staff_id,"
                + " DATE_FORMAT(s.work_date, '%Y-%m-%d') AS work_date,"
                + " s.time_type, s.reg_level_code, s.reg_level_name, s.reg_fee,"
                + " s.total_num, s.left_num, s.status, s.room, s.template_id, s.stop_reason,"
                + " d.dept_name, d.org_id, st.staff_name, st.staff_no, d.yb_dept_code, st.atddr_no"
                + joins + where
                + " ORDER BY s.work_date ASC, s.time_type ASC, s.id ASC LIMIT ?, ?";
        List<Object> dataArgs = new ArrayList<>(args);
        dataArgs.add((p - 1) * s);
        dataArgs.add(s);
        List<Map<String, Object>> rows = jdbc.queryForList(dataSql, dataArgs.toArray());

        // weekday 用 Java 计算(ISO: 1=周一...7=周日), 规避 MySQL DAYOFWEEK 的 1=周日差异
        for (Map<String, Object> row : rows) {
            Object wd = row.get("work_date");
            if (wd != null) {
                row.put("weekday", LocalDate.parse(wd.toString()).getDayOfWeek().getValue());
            }
        }

        Page<Map<String, Object>> result = new Page<>(p, s);
        result.setTotal(total == null ? 0L : total);
        result.setRecords(rows);
        return result;
    }

    // ===== 排班CRUD(含冲突校验) =====

    /** 新增排班: 冲突校验(同租户+同医师+同日+同时段不可重复), leftNum 初始化为 totalNum */
    @Transactional(rollbackFor = Exception.class)
    public HisSchedule create(HisSchedule schedule) {
        return create(schedule, null);
    }

    /**
     * 新增排班(机构级): orgId 非空时校验排班科室必须属于该机构且为开诊的门诊科室。
     * 出诊科室(deptId)与医师行政所属科室解耦: 允许院外专家/跨科室出诊挂到本机构任一开诊门诊科室。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisSchedule create(HisSchedule schedule, Long orgId) {
        if (schedule == null) {
            throw new BizException(400, "排班数据不能为空");
        }
        if (schedule.getDeptId() == null) {
            throw new BizException(400, "科室不能为空");
        }
        if (schedule.getStaffId() == null) {
            throw new BizException(400, "医师不能为空");
        }
        if (schedule.getWorkDate() == null) {
            throw new BizException(400, "出诊日期不能为空");
        }
        if (schedule.getTotalNum() == null || schedule.getTotalNum() < 0) {
            throw new BizException(400, "总号源数不能为空且不能为负");
        }
        assertDeptSchedulable(schedule.getDeptId(), orgId != null ? orgId : currentOrgId());
        // 出诊医师仅限当前登录机构本级(院外专家须先建本院虚拟职工记录)
        assertStaffInOrg(schedule.getStaffId(), orgId != null ? orgId : currentOrgId());
        String timeType = normalizeTimeType(schedule.getTimeType());
        // 同一医师同日同时段可排多个班次(跨科室), 仅拦截同科室重复排班
        assertNoConflict(schedule.getStaffId(), schedule.getWorkDate(), timeType, schedule.getDeptId(), null);

        schedule.setTimeType(timeType);
        if (!StringUtils.hasText(schedule.getRegLevelCode())) {
            schedule.setRegLevelCode("01");
        }
        if (!StringUtils.hasText(schedule.getRegLevelName())) {
            schedule.setRegLevelName("普通号");
        }
        if (schedule.getRegFee() == null) {
            schedule.setRegFee(BigDecimal.ZERO);
        }
        if (schedule.getStatus() == null) {
            schedule.setStatus(1);
        }
        schedule.setLeftNum(schedule.getTotalNum());
        scheduleMapper.insert(schedule);
        log.info("新增排班: id={}, staffId={}, workDate={}, timeType={}, totalNum={}",
                schedule.getId(), schedule.getStaffId(), schedule.getWorkDate(),
                schedule.getTimeType(), schedule.getTotalNum());
        return schedule;
    }

    /**
     * 编辑排班: 修改医师/日期/时段时重新做冲突校验(排除自身);
     * totalNum 不能小于已挂号数(原total-原left), leftNum 不允许直接编辑、随 totalNum 差值同步。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisSchedule update(HisSchedule schedule) {
        if (schedule == null || schedule.getId() == null) {
            throw new BizException(400, "排班ID不能为空");
        }
        HisSchedule old = scheduleMapper.selectById(schedule.getId());
        if (old == null) {
            throw new BizException(400, "排班记录不存在");
        }

        // 生效值: 未传字段沿用原值
        Long staffId = schedule.getStaffId() != null ? schedule.getStaffId() : old.getStaffId();
        LocalDate workDate = schedule.getWorkDate() != null ? schedule.getWorkDate() : old.getWorkDate();
        String timeType = StringUtils.hasText(schedule.getTimeType())
                ? schedule.getTimeType().trim() : old.getTimeType();
        // 新设时段须为启用班次; 未换时段时沿用原值放行(停用班次存量排班仍可编辑号数等其他字段)
        if (!timeCodes().contains(timeType) && !Objects.equals(timeType, old.getTimeType())) {
            throw new BizException(400, "时段不合法(须为启用班次): " + schedule.getTimeType());
        }
        // 排班科室(未传沿用原值)必须仍属本机构且为开诊门诊科室
        Long effDeptId = schedule.getDeptId() != null ? schedule.getDeptId() : old.getDeptId();
        schedule.setDeptId(effDeptId);
        assertDeptSchedulable(effDeptId, currentOrgId());
        // 换医师时校验新医师必须属本机构(未换人不对历史脏数据回溯拦截)
        if (!Objects.equals(staffId, old.getStaffId())) {
            assertStaffInOrg(staffId, currentOrgId());
        }

        // 关键字段变化时重新做冲突校验(排除自身; 冲突粒度含科室)
        if (!Objects.equals(staffId, old.getStaffId()) || !Objects.equals(workDate, old.getWorkDate())
                || !Objects.equals(timeType, old.getTimeType()) || !Objects.equals(effDeptId, old.getDeptId())) {
            assertNoConflict(staffId, workDate, timeType, effDeptId, old.getId());
        }

        // 号源守恒: 新 totalNum 不低于已挂号数; leftNum 由差值同步, 不允许直接编辑
        int oldTotal = nvi(old.getTotalNum());
        int oldLeft = nvi(old.getLeftNum());
        int used = oldTotal - oldLeft;
        int newTotal = schedule.getTotalNum() != null ? schedule.getTotalNum() : oldTotal;
        if (newTotal < 0) {
            throw new BizException(400, "总号源数不能为负");
        }
        if (newTotal < used) {
            throw new BizException("总号源数不能小于已挂号数(" + used + ")");
        }
        schedule.setTotalNum(newTotal);
        schedule.setLeftNum(oldLeft + (newTotal - oldTotal));
        schedule.setTimeType(timeType);

        scheduleMapper.updateById(schedule);
        log.info("编辑排班: id={}, totalNum {} -> {}, leftNum -> {}",
                schedule.getId(), oldTotal, newTotal, schedule.getLeftNum());
        return scheduleMapper.selectById(schedule.getId());
    }

    /** 删除排班: 仅无人挂号(leftNum==totalNum)时允许 */
    public void delete(Long id) {
        if (id == null) {
            throw new BizException(400, "排班ID不能为空");
        }
        HisSchedule old = scheduleMapper.selectById(id);
        if (old == null) {
            throw new BizException(400, "排班记录不存在");
        }
        if (nvi(old.getLeftNum()) != nvi(old.getTotalNum())) {
            throw new BizException("该排班已有挂号记录，不能删除");
        }
        scheduleMapper.deleteById(id);
        log.info("删除排班: id={}, staffId={}, workDate={}", id, old.getStaffId(), old.getWorkDate());
    }

    /**
     * 加号: 原子 UPDATE 同步增加总号源与剩余号源(total_num+1, left_num+1),
     * 返回更新后的记录(租户/逻辑删除由 Mapper 自动过滤, 原生 SQL 显式 tenant_id)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisSchedule addSlot(Long scheduleId) {
        if (scheduleId == null) {
            throw new BizException(400, "排班ID不能为空");
        }
        int affected = jdbc.update(
                "UPDATE his_schedule SET total_num = total_num + 1, left_num = left_num + 1, update_time = NOW()"
                        + " WHERE id = ? AND tenant_id = ? AND deleted = 0",
                scheduleId, tenantId());
        if (affected == 0) {
            throw new BizException(400, "排班记录不存在");
        }
        HisSchedule updated = scheduleMapper.selectById(scheduleId);
        log.info("排班加号: id={}, totalNum={}, leftNum={}", scheduleId,
                updated == null ? null : updated.getTotalNum(),
                updated == null ? null : updated.getLeftNum());
        return updated;
    }

    // ===== 批量操作 =====

    /** 批量停诊: 置 status=0 并记录停诊原因, 返回受影响行数 */
    @Transactional(rollbackFor = Exception.class)
    public int batchStop(List<Long> ids, String reason) {
        if (CollectionUtils.isEmpty(ids)) {
            throw new BizException(400, "排班ID列表不能为空");
        }
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (distinct.isEmpty()) {
            throw new BizException(400, "排班ID列表不能为空");
        }
        String inSql = distinct.stream().map(v -> "?").collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>();
        String reasonVal = StringUtils.hasText(reason) ? reason.trim() : null;
        args.add(reasonVal);
        args.add(tenantId());
        args.addAll(distinct);
        int affected = jdbc.update("UPDATE his_schedule SET status = 0, stop_reason = ?, update_time = NOW()"
                + " WHERE tenant_id = ? AND deleted = 0 AND id IN (" + inSql + ")", args.toArray());
        log.info("批量停诊: ids={}, reason={}, affected={}", distinct, reasonVal, affected);
        return affected;
    }

    /**
     * 按模板生成排班(幂等): 遍历 [startDate, endDate] 每一天, 模板 weekday(ISO 1=周一...7=周日)
     * 与当天匹配则生成(同医师+同日+同时段已存在则跳过); 新排班 left_num=total_num、template_id=模板ID。
     * 返回 {created: 新建数, skipped: 跳过数(含停用/不存在模板与已存在排班), total: 总处理数}。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> generateFromTemplate(List<Long> templateIds, String startDate, String endDate) {
        if (CollectionUtils.isEmpty(templateIds)) {
            throw new BizException(400, "模板ID列表不能为空");
        }
        LocalDate start = requireDate(startDate, "开始日期");
        LocalDate end = requireDate(endDate, "结束日期");
        if (end.isBefore(start)) {
            throw new BizException(400, "结束日期不能早于开始日期");
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_GENERATE_DAYS) {
            throw new BizException(400, "日期范围过大(最多" + MAX_GENERATE_DAYS + "天)");
        }

        List<Long> distinctIds = templateIds.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (distinctIds.isEmpty()) {
            throw new BizException(400, "模板ID列表不能为空");
        }
        String inSql = distinctIds.stream().map(v -> "?").collect(Collectors.joining(","));
        Long genOrg = currentOrgId();
        List<Object> tplArgs = new ArrayList<>();
        tplArgs.add(tenantId());
        if (genOrg != null) {
            tplArgs.add(genOrg);
        }
        tplArgs.addAll(distinctIds);
        List<Map<String, Object>> templates = jdbc.queryForList(
                "SELECT id, dept_id, staff_id, weekday, time_type, reg_level_code, reg_level_name,"
                        + " reg_fee, total_num, room, status"
                        + " FROM his_schedule_template WHERE tenant_id = ? AND deleted = 0"
                        + (genOrg != null ? " AND EXISTS (SELECT 1 FROM his_dept dd WHERE dd.id = his_schedule_template.dept_id AND dd.org_id = ?)" : "")
                        + " AND id IN (" + inSql + ")",
                tplArgs.toArray());

        int created = 0;
        int skipped = 0;
        // 传入但不存在/已删除的模板计入跳过
        skipped += Math.max(0, distinctIds.size() - templates.size());

        // 日期范围内已有排班 key(staffId|date|timeType), 同批新建即时入集防模板间互撞
        Set<String> existKeys = loadExistKeys(start, end);

        for (Map<String, Object> t : templates) {
            Integer tplStatus = toInt(t.get("status"));
            if (tplStatus == null || tplStatus != 1) {
                skipped++; // 停用模板整体跳过
                continue;
            }
            int weekday = nvi(toInt(t.get("weekday")));
            if (weekday < 1 || weekday > 7) {
                skipped++;
                continue;
            }
            Long staffId = toLong(t.get("staff_id"));
            String timeType = str(t.get("time_type"));
            if (staffId == null || timeType == null) {
                skipped++;
                continue;
            }
            int total = nvi(toInt(t.get("total_num")));
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                if (d.getDayOfWeek().getValue() != weekday) {
                    continue;
                }
                String key = staffId + "|" + d + "|" + timeType + "|" + t.get("dept_id");
                if (existKeys.contains(key)) {
                    skipped++;
                    continue;
                }
                HisSchedule s = new HisSchedule();
                s.setDeptId(toLong(t.get("dept_id")));
                s.setStaffId(staffId);
                s.setWorkDate(d);
                s.setTimeType(timeType);
                s.setRegLevelCode(str(t.get("reg_level_code")));
                s.setRegLevelName(str(t.get("reg_level_name")));
                s.setRegFee(toBd(t.get("reg_fee")));
                s.setTotalNum(total);
                s.setLeftNum(total);
                s.setStatus(1);
                s.setRoom(str(t.get("room")));
                s.setTemplateId(toLong(t.get("id")));
                scheduleMapper.insert(s);
                existKeys.add(key);
                created++;
            }
        }
        log.info("按模板生成排班: templates={}, range={}~{}, created={}, skipped={}",
                distinctIds.size(), start, end, created, skipped);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", created);
        result.put("skipped", skipped);
        result.put("total", created + skipped);
        return result;
    }

    /**
     * 复制某周排班到目标周: 源周(weekStart~+6天)每条排班按同 weekday 映射到目标周
     * (冲突即同医师+目标日期+同时段已存在则跳过); 新排班复制源字段、left_num=total_num、
     * template_id 置空、状态恢复开放。返回 {created, skipped, total}。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> copyWeek(String sourceWeekStart, String targetWeekStart, Long deptId) {
        LocalDate srcStart = requireDate(sourceWeekStart, "源周起始日期");
        LocalDate tgtStart = requireDate(targetWeekStart, "目标周起始日期");
        if (srcStart.equals(tgtStart)) {
            throw new BizException(400, "目标周不能与源周相同");
        }
        LocalDate srcEnd = srcStart.plusDays(6);
        LocalDate tgtEnd = tgtStart.plusDays(6);

        // 源周排班(Mapper 查询走租户插件自动过滤), 仅取本机构科室且可选按科室过滤
        Long curOrg = currentOrgId();
        List<HisSchedule> srcList = scheduleMapper.selectList(new LambdaQueryWrapper<HisSchedule>()
                .eq(deptId != null, HisSchedule::getDeptId, deptId)
                .exists(curOrg != null,
                        "SELECT 1 FROM his_dept dd WHERE dd.id = his_schedule.dept_id AND dd.org_id = " + (curOrg == null ? 0 : curOrg))
                .ge(HisSchedule::getWorkDate, srcStart)
                .le(HisSchedule::getWorkDate, srcEnd)
                .orderByAsc(HisSchedule::getWorkDate)
                .orderByAsc(HisSchedule::getTimeType)
                .orderByAsc(HisSchedule::getId));

        Set<String> existKeys = loadExistKeys(tgtStart, tgtEnd);

        int created = 0;
        int skipped = 0;
        for (HisSchedule src : srcList) {
            // 源日期在源周内的偏移 => 目标周同 weekday 日期
            long offset = ChronoUnit.DAYS.between(srcStart, src.getWorkDate());
            LocalDate tgtDate = tgtStart.plusDays(offset);
            String key = src.getStaffId() + "|" + tgtDate + "|" + src.getTimeType() + "|" + src.getDeptId();
            if (existKeys.contains(key)) {
                skipped++;
                continue;
            }
            HisSchedule ns = new HisSchedule();
            ns.setDeptId(src.getDeptId());
            ns.setStaffId(src.getStaffId());
            ns.setWorkDate(tgtDate);
            ns.setTimeType(src.getTimeType());
            ns.setRegLevelCode(src.getRegLevelCode());
            ns.setRegLevelName(src.getRegLevelName());
            ns.setRegFee(src.getRegFee());
            ns.setTotalNum(src.getTotalNum());
            ns.setLeftNum(src.getTotalNum());
            ns.setStatus(1);
            ns.setRoom(src.getRoom());
            ns.setTemplateId(null);
            scheduleMapper.insert(ns);
            existKeys.add(key);
            created++;
        }
        log.info("复制周排班: {} -> {}, deptId={}, source={}, created={}, skipped={}",
                srcStart, tgtStart, deptId, srcList.size(), created, skipped);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", created);
        result.put("skipped", skipped);
        result.put("total", created + skipped);
        return result;
    }

    // ===== 周视图数据 =====

    /**
     * 周视图矩阵: 按医师分组的 7天x3时段 槽位视图。
     * 返回 [{staffId, staffName, staffNo, deptId, deptName, slots: {mon_am|mon_pm|...|sun_night: slot|null}}]
     */
    public List<Map<String, Object>> weekView(String weekStart, Long deptId, Long orgId) {
        LocalDate start = requireDate(weekStart, "周起始日期");
        LocalDate end = start.plusDays(6);

        StringBuilder where = new StringBuilder(" WHERE s.tenant_id = ? AND s.deleted = 0 AND s.work_date BETWEEN ? AND ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        args.add(start);
        args.add(end);
        if (deptId != null) {
            where.append(" AND s.dept_id = ?");
            args.add(deptId);
        }
        if (orgId != null) {
            where.append(" AND EXISTS (SELECT 1 FROM his_dept dd WHERE dd.id = s.dept_id AND dd.org_id = ?)");
            args.add(orgId);
        }

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.id, s.dept_id, s.staff_id, DATE_FORMAT(s.work_date, '%Y-%m-%d') AS work_date,"
                        + " s.time_type, s.reg_level_code, s.reg_level_name, s.reg_fee,"
                        + " s.total_num, s.left_num, s.status, s.room, s.template_id, s.stop_reason,"
                        + " d.dept_name, st.staff_name, st.staff_no, st.dept_id AS staff_dept_id, sd.dept_name AS staff_dept_name"
                        + " FROM his_schedule s"
                        + " LEFT JOIN his_dept d ON d.id = s.dept_id AND d.deleted = 0"
                        + " LEFT JOIN his_staff st ON st.id = s.staff_id AND st.deleted = 0"
                        + " LEFT JOIN his_dept sd ON sd.id = st.dept_id AND sd.deleted = 0"
                        + where
                        + " ORDER BY d.dept_name, st.staff_no, s.work_date, s.time_type",
                args.toArray());

        Map<Long, Map<String, Object>> byStaff = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Long staffId = toLong(r.get("staff_id"));
            Map<String, Object> staffRow = byStaff.get(staffId);
            if (staffRow == null) {
                staffRow = new LinkedHashMap<>();
                staffRow.put("staffId", staffId);
                staffRow.put("staffName", r.get("staff_name"));
                staffRow.put("staffNo", r.get("staff_no"));
                // 行首科室锚定医师归属科室(不随本周排班增删而摇摆), 无归属时退化为首条排班科室
                Long anchorDeptId = toLong(r.get("staff_dept_id"));
                Object anchorDeptName = r.get("staff_dept_name");
                if (anchorDeptId == null) {
                    anchorDeptId = toLong(r.get("dept_id"));
                    anchorDeptName = r.get("dept_name");
                }
                staffRow.put("deptId", anchorDeptId);
                staffRow.put("deptName", anchorDeptName);
                Map<String, Object> slots = new LinkedHashMap<>();
                for (int wd = 1; wd <= 7; wd++) {
                    for (String tt : timeCodes()) {
                        slots.put(WEEKDAY_PREFIX[wd] + "_" + tt, null);
                    }
                }
                staffRow.put("slots", slots);
                byStaff.put(staffId, staffRow);
            }
            Object workDate = r.get("work_date");
            if (workDate == null) {
                continue;
            }
            int weekday = LocalDate.parse(workDate.toString()).getDayOfWeek().getValue();
            String timeType = str(r.get("time_type"));
            if (weekday < 1 || weekday > 7 || timeType == null) {
                continue;
            }
            Map<String, Object> slot = new LinkedHashMap<>();
            slot.put("id", r.get("id"));
            slot.put("deptId", toLong(r.get("dept_id")));
            slot.put("deptName", r.get("dept_name"));
            slot.put("workDate", workDate);
            slot.put("timeType", timeType);
            slot.put("regLevelCode", r.get("reg_level_code"));
            slot.put("regLevelName", r.get("reg_level_name"));
            slot.put("regFee", r.get("reg_fee"));
            slot.put("totalNum", r.get("total_num"));
            slot.put("leftNum", r.get("left_num"));
            slot.put("status", r.get("status"));
            slot.put("room", r.get("room"));
            slot.put("templateId", r.get("template_id"));
            slot.put("stopReason", r.get("stop_reason"));
            @SuppressWarnings("unchecked")
            Map<String, Object> slots = (Map<String, Object>) staffRow.get("slots");
            String slotKey = WEEKDAY_PREFIX[weekday] + "_" + timeType;
            // 同医师同日同时段可多班次(跨科室): 单元格值为列表, 追加而非覆盖
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> slotList = (List<Map<String, Object>>) slots.get(slotKey);
            if (slotList == null) {
                slotList = new ArrayList<>();
                slots.put(slotKey, slotList);
            }
            slotList.add(slot);
        }
        return new ArrayList<>(byStaff.values());
    }

    // ===== 统计 =====

    /**
     * 号源统计(按科室汇总, 仅开放排班): {dept_id, dept_name, total_num, used_num, left_num, usage_rate}
     * date 为空默认当天; deptId/orgId 可选过滤。
     */
    public List<Map<String, Object>> stats(String date, Long deptId, Long orgId) {
        LocalDate d = parseDate(date, "统计日期");
        if (d == null) {
            d = LocalDate.now();
        }
        StringBuilder where = new StringBuilder(" WHERE s.tenant_id = ? AND s.deleted = 0 AND s.status = 1 AND s.work_date = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        args.add(d);
        if (deptId != null) {
            where.append(" AND s.dept_id = ?");
            args.add(deptId);
        }
        if (orgId != null) {
            where.append(" AND EXISTS (SELECT 1 FROM his_dept dd WHERE dd.id = s.dept_id AND dd.org_id = ?)");
            args.add(orgId);
        }
        return jdbc.queryForList(
                "SELECT d.id AS dept_id, d.dept_name,"
                        + " IFNULL(SUM(s.total_num), 0) AS total_num,"
                        + " IFNULL(SUM(s.total_num - s.left_num), 0) AS used_num,"
                        + " IFNULL(SUM(s.left_num), 0) AS left_num,"
                        + " ROUND(IFNULL(SUM(s.total_num - s.left_num) / NULLIF(SUM(s.total_num), 0) * 100, 0), 1) AS usage_rate"
                        + " FROM his_schedule s"
                        + " JOIN his_dept d ON d.id = s.dept_id AND d.deleted = 0"
                        + where
                        + " GROUP BY d.id, d.dept_name"
                        + " ORDER BY usage_rate DESC",
                args.toArray());
    }

    // ===== 内部实现 =====

    /** 冲突校验: 同租户+同医师+同日+同时段+同科室已存在排班则抛异常(excludeId 用于编辑排除自身)。
     *  医师同日同时段跨科室排多个班次属正常业务, 不拦截。 */
    private void assertNoConflict(Long staffId, LocalDate workDate, String timeType, Long deptId, Long excludeId) {
        String sql = "SELECT COUNT(*) FROM his_schedule"
                + " WHERE tenant_id = ? AND staff_id = ? AND work_date = ? AND time_type = ? AND deleted = 0"
                + (deptId == null ? "" : " AND dept_id = ?")
                + (excludeId == null ? "" : " AND id <> ?");
        List<Object> args = new ArrayList<>(Arrays.asList(tenantId(), staffId, workDate, timeType));
        if (deptId != null) {
            args.add(deptId);
        }
        if (excludeId != null) {
            args.add(excludeId);
        }
        Long cnt = jdbc.queryForObject(sql, Long.class, args.toArray());
        if (cnt != null && cnt > 0) {
            throw new BizException("该医师在该日该时段于该科室已有排班(换科室可继续加排班次)");
        }
    }

    /** 查日期范围内已有排班 key 集合: staffId|yyyy-MM-dd|timeType|deptId(与 assertNoConflict 同粒度, 含科室) */
    private Set<String> loadExistKeys(LocalDate start, LocalDate end) {
        Set<String> keys = new HashSet<>();
        jdbc.queryForList(
                "SELECT staff_id, DATE_FORMAT(work_date, '%Y-%m-%d') AS wd, time_type, dept_id FROM his_schedule"
                        + " WHERE tenant_id = ? AND deleted = 0 AND work_date BETWEEN ? AND ?",
                tenantId(), start, end)
                .forEach(r -> keys.add(toLong(r.get("staff_id")) + "|" + r.get("wd") + "|" + r.get("time_type") + "|" + r.get("dept_id")));
        return keys;
    }

    /** 时段规范化: 空默认 am, 校验须为启用班次(字典未初始化时回落存量三值) */
    private String normalizeTimeType(String timeType) {
        String tt = StringUtils.hasText(timeType) ? timeType.trim() : "am";
        if (!timeCodes().contains(tt)) {
            throw new BizException(400, "时段不合法(须为启用班次): " + timeType);
        }
        return tt;
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }

    /** 当前登录机构ID(无登录上下文返回 null, 则不做机构限定) */
    private static Long currentOrgId() {
        LoginUser lu = UserContext.get();
        return lu == null ? null : lu.getOrgId();
    }

    /**
     * 排班科室合法性校验(机构业务边界): orgId 为空时不校验(供内部无上下文场景);
     * 否则要求科室存在、归属该机构、大类为门诊科室、层级为科室(2)/诊室(3)、启用且门诊开诊。
     */
    private void assertDeptSchedulable(Long deptId, Long orgId) {
        if (orgId == null || deptId == null) {
            return;
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT org_id, dept_category, dept_level, status, open_clinic, dept_name"
                        + " FROM his_dept WHERE id = ? AND tenant_id = ? AND deleted = 0",
                deptId, tenantId());
        if (rows.isEmpty()) {
            throw new BizException(400, "排班科室不存在");
        }
        Map<String, Object> d = rows.get(0);
        Long dOrg = toLong(d.get("org_id"));
        if (dOrg == null || dOrg.longValue() != orgId.longValue()) {
            throw new BizException("排班科室必须属于本机构, 不可跨机构排班");
        }
        if (!HisDeptService.hasCategory(str(d.get("dept_category")), "门诊科室")) {
            throw new BizException("仅「门诊科室」可排班: " + str(d.get("dept_name")));
        }
        Integer lv = toInt(d.get("dept_level"));
        if (lv == null || (lv != 2 && lv != 3)) {
            throw new BizException("仅科室/诊室层级可排班(大类节点不可作挂号科室)");
        }
        Integer st = toInt(d.get("status"));
        if (st == null || st != 1) {
            throw new BizException("该科室已停用, 不可排班: " + str(d.get("dept_name")));
        }
        Integer oc = toInt(d.get("open_clinic"));
        // 开诊标志仅约束科室级(2); 诊室(3)只看启用, 不受开诊标志限制
        if (lv != 3 && oc != null && oc == 0) {
            throw new BizException("该门诊科室未开诊, 不可排班: " + str(d.get("dept_name")));
        }
    }

    /**
     * 出诊医师机构归属校验(机构业务边界): orgId 为空时不校验;
     * 行政所属匹配或 staff_employment 存在该机构任职行均放行(多点执业免重复建档);
     * 两者皆不匹配才拦截; 未配机构的存量数据不拦截。
     */
    private void assertStaffInOrg(Long staffId, Long orgId) {
        if (orgId == null || staffId == null) {
            return;
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT org_id, staff_name, staff_no FROM his_staff WHERE id = ? AND tenant_id = ? AND deleted = 0",
                staffId, tenantId());
        if (rows.isEmpty()) {
            throw new BizException(400, "出诊医师不存在");
        }
        Map<String, Object> s = rows.get(0);
        Long sOrg = toLong(s.get("org_id"));
        if (sOrg != null && sOrg.longValue() != orgId.longValue()) {
            // 任职兜底: 多点执业员工在分院排班不再要求重复建档(原生 SQL 手动带租户, 与本类口径一致)
            Long employed = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM staff_employment WHERE staff_id = ? AND org_id = ? AND tenant_id = ?",
                    Long.class, staffId, orgId, tenantId());
            if (employed == null || employed == 0L) {
                throw new BizException("出诊医师必须属于本机构或有本院任职记录: " + str(s.get("staff_name"))
                        + "(" + str(s.get("staff_no")) + "), 院外专家请先在职工管理中维护本院任职再排班");
            }
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

    /** 解析 yyyy-MM-dd(空返回 null, 格式错误抛 400) */
    private static LocalDate parseDate(String d, String field) {
        if (!StringUtils.hasText(d)) {
            return null;
        }
        try {
            return LocalDate.parse(d.trim());
        } catch (DateTimeParseException e) {
            throw new BizException(400, field + "格式错误, 应为 yyyy-MM-dd: " + d);
        }
    }

    /** 解析必填日期(空抛 400) */
    private static LocalDate requireDate(String d, String field) {
        LocalDate x = parseDate(d, field);
        if (x == null) {
            throw new BizException(400, field + "不能为空(yyyy-MM-dd)");
        }
        return x;
    }

    private static int nvi(Integer v) {
        return v == null ? 0 : v;
    }

    private static Integer toInt(Object v) {
        return v == null ? null : ((Number) v).intValue();
    }

    private static Long toLong(Object v) {
        return v == null ? null : ((Number) v).longValue();
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

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
