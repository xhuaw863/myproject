package com.yb.hi.service.inpatient;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.dto.inpatient.SurgeryDTO;
import com.yb.hi.dto.inpatient.SurgeryScheduleDTO;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.entity.inpatient.HisSurgery;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.mapper.inpatient.HisSurgeryMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 手术服务: 申请(1)→排程(2)→术中(3)→术后(4)→完成(5) / 取消(6) 状态机,
 * 手术列表与详情(JOIN患者/手术团队/科室), 今日排程(按手术间分组), 手术间列表(预置, 后续可配置化)。
 * 说明: JdbcTemplate 手写 SQL 不走 MyBatis-Plus 租户插件, 必须显式带 tenant_id AND deleted=0;
 * 状态流转使用乐观更新(WHERE status=旧值), 影响行数为0表示状态已变化, 提示刷新后重试。
 */
@Slf4j
@Service
public class SurgeryService {

    /** 预置手术间(后续可配置化: 改为读手术间配置表) */
    private static final List<String> PRESET_ROOMS = Collections.unmodifiableList(java.util.Arrays.asList(
            "手术间1", "手术间2", "手术间3", "手术间4",
            "手术间5", "手术间6", "手术间7", "手术间8"));

    private final HisSurgeryMapper surgeryMapper;
    private final HisInpVisitMapper visitMapper;
    private final OrgAccessGuard guard;
    private final JdbcTemplate jdbcTemplate;

    public SurgeryService(HisSurgeryMapper surgeryMapper, HisInpVisitMapper visitMapper,
                          OrgAccessGuard guard, JdbcTemplate jdbcTemplate) {
        this.surgeryMapper = surgeryMapper;
        this.visitMapper = visitMapper;
        this.guard = guard;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ==================== 查询 ==================== */

    /**
     * 手术列表(分页): 支持机构/科室/手术日期区间/状态筛选,
     * JOIN his_inp_visit+his_patient 取住院号与患者信息, JOIN his_staff 取主刀医师姓名。
     */
    public IPage<Map<String, Object>> listSurgeries(Long orgId, Long deptId, LocalDate startDate,
                                                    LocalDate endDate, Integer status, int page, int size) {
        long p = safePage(page);
        long s = safeSize(size);

        StringBuilder where = new StringBuilder(
                " FROM his_surgery s"
                        + " LEFT JOIN his_inp_visit v ON v.id = s.inp_visit_id AND v.deleted = 0"
                        + " LEFT JOIN his_patient p ON p.id = v.patient_id AND p.deleted = 0"
                        + " LEFT JOIN his_staff st ON st.id = s.surgeon_id AND st.deleted = 0"
                        + " LEFT JOIN his_dept d ON d.id = s.dept_id AND d.deleted = 0"
                        + " WHERE s.deleted = 0 AND s.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        if (orgId != null) {
            where.append(" AND s.org_id = ?");
            args.add(orgId);
        }
        if (deptId != null) {
            where.append(" AND s.dept_id = ?");
            args.add(deptId);
        }
        if (startDate != null) {
            where.append(" AND s.schedule_date >= ?");
            args.add(startDate);
        }
        if (endDate != null) {
            where.append(" AND s.schedule_date <= ?");
            args.add(endDate);
        }
        if (status != null) {
            where.append(" AND s.status = ?");
            args.add(status);
        }

        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + where, Long.class, args.toArray());
        long cnt = total == null ? 0L : total;

        String cols = "SELECT s.id, s.inp_visit_id, v.inp_no, p.name patient_name, p.gender_name, p.age,"
                + " s.surgery_code, s.surgery_name, s.surgery_level, s.surgeon_id, st.staff_name surgeon_name,"
                + " s.room_no, s.schedule_date, s.schedule_time, s.start_time, s.end_time,"
                + " s.asa_grade, s.incision_type, s.status, s.dept_id, d.dept_name";
        List<Map<String, Object>> records = cnt == 0 ? new ArrayList<>()
                : jdbcTemplate.queryForList(
                cols + where + " ORDER BY s.id DESC LIMIT ?, ?",
                appendOffset(args, (p - 1) * s, s));

        Page<Map<String, Object>> result = new Page<>(p, s, cnt);
        result.setRecords(records);
        return result;
    }

    /** 手术详情: 手术记录 + 患者信息(住院号/姓名/性别/年龄/证件) + 手术团队七角色姓名 + 科室名称 */
    public Map<String, Object> getDetail(Long id) {
        HisSurgery surgery = requireSurgery(id);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("surgery", surgery);

        // 患者信息(经 his_inp_visit JOIN his_patient)
        List<Map<String, Object>> pRows = jdbcTemplate.queryForList(
                "SELECT v.inp_no, v.admit_date, v.visit_status, p.id patient_id, p.name, p.gender_name,"
                        + " p.age, p.id_card, p.phone"
                        + " FROM his_inp_visit v"
                        + " LEFT JOIN his_patient p ON p.id = v.patient_id AND p.deleted = 0"
                        + " WHERE v.id = ? AND v.deleted = 0 AND v.tenant_id = ?",
                surgery.getInpVisitId(), tenantId());
        detail.put("patient", pRows.isEmpty() ? null : pRows.get(0));

        // 手术团队(七角色: 主刀/一助/二助/麻醉医师/麻醉护士/器械护士/巡回护士)
        Map<String, Long> roleIds = new LinkedHashMap<>();
        roleIds.put("surgeon", surgery.getSurgeonId());
        roleIds.put("firstAssistant", surgery.getFirstAssistantId());
        roleIds.put("secondAssistant", surgery.getSecondAssistantId());
        roleIds.put("anesthesiologist", surgery.getAnesthesiologistId());
        roleIds.put("anesthesiaNurse", surgery.getAnesthesiaNurseId());
        roleIds.put("instrumentNurse", surgery.getInstrumentNurseId());
        roleIds.put("circulatingNurse", surgery.getCirculatingNurseId());
        Map<Long, String> names = loadStaffNames(roleIds.values());
        Map<String, Object> team = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : roleIds.entrySet()) {
            Long sid = e.getValue();
            if (sid == null) {
                team.put(e.getKey(), null);
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", sid);
            m.put("name", names.get(sid));
            team.put(e.getKey(), m);
        }
        detail.put("team", team);

        // 科室名称
        if (surgery.getDeptId() != null) {
            List<Map<String, Object>> dRows = jdbcTemplate.queryForList(
                    "SELECT dept_name FROM his_dept WHERE id = ? AND deleted = 0 AND tenant_id = ?",
                    surgery.getDeptId(), tenantId());
            detail.put("deptName", dRows.isEmpty() ? null : dRows.get(0).get("dept_name"));
        } else {
            detail.put("deptName", null);
        }
        return detail;
    }

    /* ==================== 申请 / 编辑 / 排程 ==================== */

    /** 手术申请: 校验住院就诊 -> 创建记录(status=1申请中) */
    @Transactional(rollbackFor = Exception.class)
    public HisSurgery create(SurgeryDTO dto, Long orgId) {
        if (dto == null || dto.getInpVisitId() == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        if (!StringUtils.hasText(dto.getSurgeryName())) {
            throw new BizException(400, "手术名称不能为空");
        }
        requireActiveVisit(dto.getInpVisitId());

        HisSurgery s = new HisSurgery();
        s.setOrgId(orgId);
        s.setInpVisitId(dto.getInpVisitId());
        s.setSurgeryCode(trimOrNull(dto.getSurgeryCode()));
        s.setSurgeryName(dto.getSurgeryName().trim());
        s.setSurgeryLevel(dto.getSurgeryLevel());
        s.setSurgeonId(dto.getSurgeonId());
        s.setFirstAssistantId(dto.getFirstAssistantId());
        s.setSecondAssistantId(dto.getSecondAssistantId());
        s.setAnesthesiologistId(dto.getAnesthesiologistId());
        s.setAnesthesiaNurseId(dto.getAnesthesiaNurseId());
        s.setInstrumentNurseId(dto.getInstrumentNurseId());
        s.setCirculatingNurseId(dto.getCirculatingNurseId());
        s.setDeptId(dto.getDeptId());
        s.setAsaGrade(dto.getAsaGrade());
        s.setIncisionType(dto.getIncisionType());
        // 三级及以上手术需上级审批: 待审(1)前置; 一/二级无需审批(0)
        s.setApprovalStatus(dto.getSurgeryLevel() != null && dto.getSurgeryLevel() >= 3 ? 1 : 0);
        s.setStatus(1);
        surgeryMapper.insert(s);
        log.info("手术申请创建: id={}, visitId={}, name={}, orgId={}",
                s.getId(), dto.getInpVisitId(), s.getSurgeryName(), orgId);
        return s;
    }

    /** 编辑手术信息(仅 status=1申请中 / 2已排程可编辑) */
    @Transactional(rollbackFor = Exception.class)
    public HisSurgery update(Long id, SurgeryDTO dto) {
        HisSurgery exist = requireSurgery(id);
        if (exist.getStatus() == null || (exist.getStatus() != 1 && exist.getStatus() != 2)) {
            throw new BizException("仅申请中/已排程的手术可编辑, 当前状态: " + exist.getStatus());
        }
        if (dto == null) {
            throw new BizException(400, "手术信息不能为空");
        }
        if (dto.getInpVisitId() != null && !dto.getInpVisitId().equals(exist.getInpVisitId())) {
            requireActiveVisit(dto.getInpVisitId());
            exist.setInpVisitId(dto.getInpVisitId());
        }
        if (StringUtils.hasText(dto.getSurgeryName())) {
            exist.setSurgeryName(dto.getSurgeryName().trim());
        }
        if (dto.getSurgeryCode() != null) {
            exist.setSurgeryCode(trimOrNull(dto.getSurgeryCode()));
        }
        if (dto.getSurgeryLevel() != null) {
            exist.setSurgeryLevel(dto.getSurgeryLevel());
        }
        if (dto.getSurgeonId() != null) {
            exist.setSurgeonId(dto.getSurgeonId());
        }
        if (dto.getFirstAssistantId() != null) {
            exist.setFirstAssistantId(dto.getFirstAssistantId());
        }
        if (dto.getSecondAssistantId() != null) {
            exist.setSecondAssistantId(dto.getSecondAssistantId());
        }
        if (dto.getAnesthesiologistId() != null) {
            exist.setAnesthesiologistId(dto.getAnesthesiologistId());
        }
        if (dto.getAnesthesiaNurseId() != null) {
            exist.setAnesthesiaNurseId(dto.getAnesthesiaNurseId());
        }
        if (dto.getInstrumentNurseId() != null) {
            exist.setInstrumentNurseId(dto.getInstrumentNurseId());
        }
        if (dto.getCirculatingNurseId() != null) {
            exist.setCirculatingNurseId(dto.getCirculatingNurseId());
        }
        if (dto.getDeptId() != null) {
            exist.setDeptId(dto.getDeptId());
        }
        if (dto.getAsaGrade() != null) {
            exist.setAsaGrade(dto.getAsaGrade());
        }
        if (dto.getIncisionType() != null) {
            exist.setIncisionType(dto.getIncisionType());
        }
        surgeryMapper.updateById(exist);
        log.info("手术信息编辑: id={}, name={}", id, exist.getSurgeryName());
        return surgeryMapper.selectById(id);
    }

    /** 手术排程: 指定手术日期/时间段/手术间, status 1->2(乐观更新) */
    @Transactional(rollbackFor = Exception.class)
    public HisSurgery schedule(Long id, SurgeryScheduleDTO dto) {
        HisSurgery exist = requireSurgery(id);
        if (exist.getApprovalStatus() != null && exist.getApprovalStatus() == 1) {
            throw new BizException("该手术为待审批状态, 三级及以上手术须审批通过后方可排程");
        }
        if (dto == null || dto.getScheduleDate() == null) {
            throw new BizException(400, "手术日期不能为空");
        }
        int affected = surgeryMapper.update(null, new LambdaUpdateWrapper<HisSurgery>()
                .set(HisSurgery::getScheduleDate, dto.getScheduleDate())
                .set(HisSurgery::getScheduleTime, trimOrNull(dto.getScheduleTime()))
                .set(HisSurgery::getRoomNo, trimOrNull(dto.getRoomNo()))
                .set(HisSurgery::getStatus, 2)
                .set(HisSurgery::getUpdateTime, LocalDateTime.now())
                .eq(HisSurgery::getId, id)
                .eq(HisSurgery::getStatus, 1));
        if (affected == 0) {
            throw new BizException("仅申请中的手术可排程(状态已变化), 请刷新后重试");
        }
        log.info("手术排程: id={}, date={}, room={}", id, dto.getScheduleDate(), dto.getRoomNo());
        return surgeryMapper.selectById(id);
    }

    /* ==================== 审批 / 安全核查 ==================== */

    /**
     * 三级及以上手术审批: approval_status 1->2(通过)/3(拒绝), 乐观更新并记录审批医师。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisSurgery approveSurgery(Long id, boolean approved) {
        HisSurgery exist = requireSurgery(id);
        if (exist.getApprovalStatus() == null || exist.getApprovalStatus() != 1) {
            throw new BizException("仅待审批(1)的手术可审批, 当前状态: " + exist.getApprovalStatus());
        }
        Long doctorId = currentStaffId();
        int affected = surgeryMapper.update(null, new LambdaUpdateWrapper<HisSurgery>()
                .set(HisSurgery::getApprovalStatus, approved ? 2 : 3)
                .set(HisSurgery::getApprovalDoctorId, doctorId)
                .set(HisSurgery::getUpdateTime, LocalDateTime.now())
                .eq(HisSurgery::getId, id)
                .eq(HisSurgery::getApprovalStatus, 1));
        if (affected == 0) {
            throw new BizException("审批状态已变化, 请刷新后重试");
        }
        log.info("手术审批: id={}, approved={}, approvalDoctorId={}", id, approved, doctorId);
        return surgeryMapper.selectById(id);
    }

    /** WHO手术安全核查清单保存(JSON: 麻醉前/手术前/离室前三阶段), 已取消手术不可保存 */
    @Transactional(rollbackFor = Exception.class)
    public HisSurgery saveSafetyChecklist(Long id, String checklistJson) {
        if (!StringUtils.hasText(checklistJson)) {
            throw new BizException(400, "核查清单内容不能为空");
        }
        HisSurgery exist = requireSurgery(id);
        if (exist.getStatus() != null && exist.getStatus() == 6) {
            throw new BizException("已取消的手术不能保存安全核查清单");
        }
        int affected = surgeryMapper.update(null, new LambdaUpdateWrapper<HisSurgery>()
                .set(HisSurgery::getSafetyChecklist, checklistJson.trim())
                .set(HisSurgery::getUpdateTime, LocalDateTime.now())
                .eq(HisSurgery::getId, id)
                .ne(HisSurgery::getStatus, 6));
        if (affected == 0) {
            throw new BizException("保存失败: 手术状态已变化, 请刷新后重试");
        }
        log.info("手术安全核查保存: id={}, 清单长度={}", id, checklistJson.trim().length());
        return surgeryMapper.selectById(id);
    }

    /* ==================== 状态流转(乐观更新) ==================== */

    /** 开始手术: status 2->3, 记录 start_time=now */
    public HisSurgery start(Long id) {
        HisSurgery exist = requireSurgery(id);
        if (exist.getApprovalStatus() != null && exist.getApprovalStatus() == 1) {
            throw new BizException("该手术为待审批状态, 三级及以上手术须审批通过后方可开始");
        }
        return transition(id, 2, 3, "开始手术", true, false);
    }

    /** 结束手术: status 3->4, 记录 end_time=now */
    public HisSurgery end(Long id) {
        return transition(id, 3, 4, "结束手术", false, true);
    }

    /** 完成手术: status 4->5 */
    public HisSurgery complete(Long id) {
        return transition(id, 4, 5, "完成手术", false, false);
    }

    /** 取消手术: status ->6, 仅申请中/已排程可取消 */
    @Transactional(rollbackFor = Exception.class)
    public HisSurgery cancel(Long id) {
        requireSurgery(id);
        int affected = surgeryMapper.update(null, new LambdaUpdateWrapper<HisSurgery>()
                .set(HisSurgery::getStatus, 6)
                .set(HisSurgery::getUpdateTime, LocalDateTime.now())
                .eq(HisSurgery::getId, id)
                .in(HisSurgery::getStatus, 1, 2));
        if (affected == 0) {
            throw new BizException("仅申请中/已排程的手术可取消(状态已变化), 请刷新后重试");
        }
        log.info("手术取消: id={}", id);
        return surgeryMapper.selectById(id);
    }

    /** 状态流转公共实现: 校验归属 -> 乐观更新(WHERE status=旧值) -> 返回最新记录 */
    private HisSurgery transition(Long id, int fromStatus, int toStatus, String action,
                                  boolean setStartTime, boolean setEndTime) {
        requireSurgery(id);
        LambdaUpdateWrapper<HisSurgery> u = new LambdaUpdateWrapper<>();
        u.set(HisSurgery::getStatus, toStatus)
                .set(HisSurgery::getUpdateTime, LocalDateTime.now());
        if (setStartTime) {
            u.set(HisSurgery::getStartTime, LocalDateTime.now());
        }
        if (setEndTime) {
            u.set(HisSurgery::getEndTime, LocalDateTime.now());
        }
        u.eq(HisSurgery::getId, id).eq(HisSurgery::getStatus, fromStatus);
        int affected = surgeryMapper.update(null, u);
        if (affected == 0) {
            throw new BizException(action + "失败: 手术状态已变化, 请刷新后重试");
        }
        log.info("手术状态流转: id={}, {}->{}, action={}", id, fromStatus, toStatus, action);
        return surgeryMapper.selectById(id);
    }

    /* ==================== 今日排程 / 手术间 ==================== */

    /**
     * 今日手术排程表: schedule_date=today 且未取消(6) 的手术,
     * 按手术间分组(预置8间固定占位, 未安排与其他房间追加在后, 便于前端固定列渲染)。
     */
    public Map<String, Object> todaySchedule(Long orgId) {
        LocalDate today = LocalDate.now();
        StringBuilder sql = new StringBuilder(
                "SELECT s.id, s.inp_visit_id, v.inp_no, p.name patient_name, p.gender_name, p.age,"
                        + " s.surgery_code, s.surgery_name, s.surgery_level, s.surgeon_id, st.staff_name surgeon_name,"
                        + " s.room_no, s.schedule_time, s.start_time, s.end_time, s.status, s.dept_id, d.dept_name"
                        + " FROM his_surgery s"
                        + " LEFT JOIN his_inp_visit v ON v.id = s.inp_visit_id AND v.deleted = 0"
                        + " LEFT JOIN his_patient p ON p.id = v.patient_id AND p.deleted = 0"
                        + " LEFT JOIN his_staff st ON st.id = s.surgeon_id AND st.deleted = 0"
                        + " LEFT JOIN his_dept d ON d.id = s.dept_id AND d.deleted = 0"
                        + " WHERE s.deleted = 0 AND s.tenant_id = ? AND s.schedule_date = ? AND s.status <> 6");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        args.add(today);
        if (orgId != null) {
            sql.append(" AND s.org_id = ?");
            args.add(orgId);
        }
        sql.append(" ORDER BY s.schedule_time, s.id");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());

        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (String room : PRESET_ROOMS) {
            grouped.put(room, new ArrayList<>());
        }
        List<Map<String, Object>> unassigned = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object roomObj = row.get("room_no");
            String room = roomObj == null ? null : String.valueOf(roomObj).trim();
            if (room == null || room.isEmpty()) {
                unassigned.add(row);
            } else {
                grouped.computeIfAbsent(room, k -> new ArrayList<>()).add(row);
            }
        }
        List<Map<String, Object>> rooms = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : grouped.entrySet()) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("roomNo", e.getKey());
            g.put("count", e.getValue().size());
            g.put("surgeries", e.getValue());
            rooms.add(g);
        }
        if (!unassigned.isEmpty()) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("roomNo", "未安排");
            g.put("count", unassigned.size());
            g.put("surgeries", unassigned);
            rooms.add(g);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", today.toString());
        result.put("total", rows.size());
        result.put("rooms", rooms);
        return result;
    }

    /** 手术间列表(预置8间; 后续可配置化) */
    public List<String> roomList() {
        return new ArrayList<>(PRESET_ROOMS);
    }

    /* ==================== 校验 / 工具 ==================== */

    /** 手术存在性 + 机构归属校验(非牵头机构仅本机构可访问) */
    private HisSurgery requireSurgery(Long id) {
        if (id == null) {
            throw new BizException(400, "手术ID不能为空");
        }
        HisSurgery s = surgeryMapper.selectById(id);
        if (s == null) {
            throw new BizException(404, "手术记录不存在");
        }
        Long scope = guard.scopeOrgId(s.getOrgId());
        if (scope == null || !scope.equals(s.getOrgId())) {
            throw new BizException(403, "无权访问其他机构的手术数据");
        }
        return s;
    }

    /** 住院就诊校验: 存在 + 机构归属 + 非出院/取消 */
    private HisInpVisit requireActiveVisit(Long visitId) {
        HisInpVisit v = visitMapper.selectById(visitId);
        if (v == null) {
            throw new BizException(400, "住院就诊记录不存在");
        }
        Long scope = guard.scopeOrgId(v.getOrgId());
        if (scope == null || !scope.equals(v.getOrgId())) {
            throw new BizException(403, "无权访问其他机构的住院就诊数据");
        }
        if (v.getVisitStatus() != null && (v.getVisitStatus() == 4 || v.getVisitStatus() == 5)) {
            throw new BizException("该就诊已出院或已取消, 不能申请手术");
        }
        return v;
    }

    /** 批量查职工姓名(id 集合 -> 姓名映射) */
    private Map<Long, String> loadStaffNames(java.util.Collection<Long> staffIds) {
        List<Long> ids = staffIds.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        Map<Long, String> names = new HashMap<>();
        if (ids.isEmpty()) {
            return names;
        }
        String inMarks = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>(ids);
        args.add(tenantId());
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, staff_name FROM his_staff WHERE id IN (" + inMarks + ")"
                        + " AND deleted = 0 AND tenant_id = ?",
                args.toArray());
        for (Map<String, Object> row : rows) {
            names.put(((Number) row.get("id")).longValue(), str(row.get("staff_name")));
        }
        return names;
    }

    /** 当前登录职工ID(his_staff.id): 无职工关联的账号不能执行审批 */
    private static Long currentStaffId() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            throw new BizException(401, "未登录");
        }
        if (lu.getStaffId() == null) {
            throw new BizException(403, "当前账号未关联职工档案, 无法执行审批");
        }
        return lu.getStaffId();
    }

    private static String trimOrNull(String v) {
        return StringUtils.hasText(v) ? v.trim() : null;
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    /** 分页参数安全化 */
    private static long safePage(long page) {
        return page < 1 ? 1 : page;
    }

    private static long safeSize(long size) {
        if (size < 1) {
            return 20;
        }
        return size > 200 ? 200 : size;
    }

    /** 追加分页偏移参数(共用 WHERE 参数列表拷贝) */
    private static Object[] appendOffset(List<Object> args, long offset, long size) {
        List<Object> all = new ArrayList<>(args);
        all.add(offset);
        all.add(size);
        return all.toArray();
    }

    /** 当前租户ID(null 回落 0, 与 MyBatis-Plus 租户插件默认一致) */
    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }
}
