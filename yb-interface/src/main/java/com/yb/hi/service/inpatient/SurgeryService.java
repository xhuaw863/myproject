package com.yb.hi.service.inpatient;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.dto.inpatient.SurgeryDTO;
import com.yb.hi.dto.inpatient.SurgeryScheduleDTO;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.entity.inpatient.HisSurgery;
import com.yb.hi.entity.inpatient.HisSurgeryApply;
import com.yb.hi.entity.inpatient.HisSurgeryModuleExt;
import com.yb.hi.entity.inpatient.HisSurgeryRoom;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.mapper.inpatient.HisSurgeryMapper;
import com.yb.hi.mapper.inpatient.HisSurgeryModuleExtMapper;
import com.yb.hi.mapper.inpatient.HisSurgeryRoomMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

    /** 手术时间段解析(HH:mm-HH:mm) */
    private static final DateTimeFormatter HM_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final HisSurgeryMapper surgeryMapper;
    private final HisInpVisitMapper visitMapper;
    private final OrgAccessGuard guard;
    private final JdbcTemplate jdbcTemplate;
    private final SurgeryApplyService applyService;
    private final SurgeryAuthRuleService authRuleService;
    private final PacuService pacuService;
    private final HisSurgeryRoomMapper roomMapper;
    private final HisSurgeryModuleExtMapper moduleExtMapper;

    public SurgeryService(HisSurgeryMapper surgeryMapper, HisInpVisitMapper visitMapper,
                          OrgAccessGuard guard, JdbcTemplate jdbcTemplate,
                          SurgeryApplyService applyService, SurgeryAuthRuleService authRuleService,
                          PacuService pacuService, HisSurgeryRoomMapper roomMapper,
                          HisSurgeryModuleExtMapper moduleExtMapper) {
        this.surgeryMapper = surgeryMapper;
        this.visitMapper = visitMapper;
        this.guard = guard;
        this.jdbcTemplate = jdbcTemplate;
        this.applyService = applyService;
        this.authRuleService = authRuleService;
        this.pacuService = pacuService;
        this.roomMapper = roomMapper;
        this.moduleExtMapper = moduleExtMapper;
    }

    /* ==================== 查询 ==================== */

    /**
     * 手术列表(分页): 支持机构/科室/手术日期区间/状态(可逗号多值如"2,3,7")筛选,
     * JOIN his_inp_visit+his_patient 取住院号与患者信息, JOIN his_staff 取主刀医师姓名。
     */
    public IPage<Map<String, Object>> listSurgeries(Long orgId, Long deptId, LocalDate startDate,
                                                    LocalDate endDate, String statuses,
                                                    Integer visitType, Integer moduleType, int page, int size) {
        long p = safePage(page);
        long s = safeSize(size);

        StringBuilder where = new StringBuilder(
                " FROM his_surgery s"
                        + " LEFT JOIN his_inp_visit v ON v.id = s.inp_visit_id AND v.deleted = 0"
                        + " LEFT JOIN his_visit hv ON hv.id = s.visit_id AND hv.deleted = 0"
                        + " LEFT JOIN his_patient p ON p.id = COALESCE(v.patient_id, hv.patient_id) AND p.deleted = 0"
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
        if (statuses != null && !statuses.trim().isEmpty()) {
            List<String> marks = new ArrayList<>();
            for (String sv : statuses.split(",")) {
                if (sv.trim().matches("\\d+")) {
                    marks.add("?");
                    args.add(Integer.parseInt(sv.trim()));
                }
            }
            if (!marks.isEmpty()) {
                where.append(" AND s.status IN (").append(String.join(",", marks)).append(")");
            }
        }
        if (visitType != null) {
            where.append(" AND IFNULL(s.visit_type, 1) = ?");
            args.add(visitType);
        }
        if (moduleType != null) {
            where.append(" AND IFNULL(s.module_type, 1) = ?");
            args.add(moduleType);
        }

        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + where, Long.class, args.toArray());
        long cnt = total == null ? 0L : total;

        String cols = "SELECT s.id, s.inp_visit_id, s.visit_id, IFNULL(s.visit_type,1) visit_type, s.apply_id, s.register_time,"
                + " IFNULL(s.deadline_type,1) deadline_type, s.module_type,"
                + " COALESCE(v.inp_no, hv.ipt_otp_no) inp_no,"
                + " COALESCE(p.name, hv.patient_name) patient_name, p.gender_name, p.age,"
                + " s.surgery_code, s.surgery_name, s.surgery_level, s.surgeon_id, st.staff_name surgeon_name,"
                + " s.room_no, s.schedule_date, s.schedule_time, s.start_time, s.end_time,"
                + " s.asa_grade, s.incision_type, s.approval_status, s.status, s.dept_id, d.dept_name";
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
        // 手麻P4b: 一体化专属字段(DSA/内镜/产科)旁挂回带(无则 fastjson2 裁剪为 undefined)
        HisSurgeryModuleExt ext = moduleExtMapper.selectOne(new LambdaQueryWrapper<HisSurgeryModuleExt>()
                .eq(HisSurgeryModuleExt::getSurgeryId, id).last("LIMIT 1"));
        detail.put("moduleExt", ext);
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
        // 一体化模块归类(P2d): 缺省1手术室
        s.setModuleType(dto.getModuleType() != null ? dto.getModuleType() : 1);
        // 三级及以上手术需上级审批: 待审(1)前置; 一/二级无需审批(0)
        s.setApprovalStatus(dto.getSurgeryLevel() != null && dto.getSurgeryLevel() >= 3 ? 1 : 0);
        s.setStatus(1);
        surgeryMapper.insert(s);
        upsertModuleExt(s.getId(), orgId, s.getModuleType(),
                dto.getDsaEquipment(), dto.getDsaContrast(), dto.getDsaRadiationDose(),
                dto.getEndoScopeType(), dto.getEndoBiopsyCnt(),
                dto.getObstGestationalWeek(), dto.getObstBirthType());
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
        requireNoRoomConflict(trimOrNull(dto.getRoomNo()), dto.getScheduleDate(), dto.getScheduleTime(), id);
        requireRoomResource(dto.getRoomNo(), exist.getModuleType(), exist.getOrgId());
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

    /** 开始手术: status 2或7(已报到)->3, 记录 start_time=now */
    public HisSurgery start(Long id) {
        HisSurgery exist = requireSurgery(id);
        if (exist.getApprovalStatus() != null && exist.getApprovalStatus() == 1) {
            throw new BizException("该手术为待审批状态, 三级及以上手术须审批通过后方可开始");
        }
        if (exist.getStatus() == null || (exist.getStatus() != 2 && exist.getStatus() != 7)) {
            throw new BizException("仅已排程/已报到的手术可开始, 当前状态: " + exist.getStatus());
        }
        return transition(id, exist.getStatus(), 3, "开始手术", true, false);
    }

    /** 结束手术: status 3->4, 记录 end_time=now */
    public HisSurgery end(Long id) {
        return transition(id, 3, 4, "结束手术", false, true);
    }

    /** 完成手术: status 4->5, 来源申请单联动 4->5; P3b 软门禁: 存在未出复苏单则拒绝(不动七态枚举) */
    @Transactional(rollbackFor = Exception.class)
    public HisSurgery complete(Long id) {
        HisSurgery exist = requireSurgery(id);
        /* 手麻P3b PACU 软门禁: 患者仍在复苏室(PACU)时不可完成, 须先出复苏 */
        if (pacuService.hasInProgress(id)) {
            throw new BizException("患者仍在复苏室(PACU), 须先出复苏后再完成手术");
        }
        HisSurgery s = transition(id, 4, 5, "完成手术", false, false);
        if (exist.getApplyId() != null) {
            try {
                applyService.updateApplyStatus(exist.getApplyId(), 4, 5, null);
            } catch (BizException e) {
                log.warn("完成手术回写申请单失败: applyId={}, {}", exist.getApplyId(), e.getMessage());
            }
        }
        return s;
    }

    /** 取消手术: status ->6, 仅申请中/已排程/已报到可取消; 来源申请单回退为待安排 */
    @Transactional(rollbackFor = Exception.class)
    public HisSurgery cancel(Long id) {
        HisSurgery exist = requireSurgery(id);
        /* 手麻P3b PACU 软门禁: 在途复苏单存在时拒绝取消(主状态机仅1/2/7可取消, 此为防御性双保险) */
        if (pacuService.hasInProgress(id)) {
            throw new BizException("患者仍在复苏室(PACU), 须先出复苏后才能取消手术");
        }
        int affected = surgeryMapper.update(null, new LambdaUpdateWrapper<HisSurgery>()
                .set(HisSurgery::getStatus, 6)
                .set(HisSurgery::getUpdateTime, LocalDateTime.now())
                .eq(HisSurgery::getId, id)
                .in(HisSurgery::getStatus, 1, 2, 7));
        if (affected == 0) {
            throw new BizException("仅申请中/已排程/已报到的手术可取消(状态已变化), 请刷新后重试");
        }
        revertApplyOnCancel(exist);
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

    /* ==================== 手麻P0: 申请安排 / 急诊直排 / 登记报到 / 调配 / 排程板 ==================== */

    /** 从申请单安排手术: 申请(2待安排)→建手术(status=2已排程)+回填申请surgery_id+申请→4; 校验手术间冲突与术者权限 */
    @Transactional(rollbackFor = Exception.class)
    public HisSurgery scheduleFromApply(Long applyId, SurgeryScheduleDTO dto) {
        HisSurgeryApply a = applyService.requireApply(applyId);
        if (a.getStatus() == null || a.getStatus() != 2) {
            throw new BizException("仅已复核待安排的申请可安排手术, 当前状态: " + a.getStatus());
        }
        requireScheduleBase(dto);
        requireNoRoomConflict(dto.getRoomNo(), dto.getScheduleDate(), dto.getScheduleTime(), null);
        requireRoomResource(dto.getRoomNo(), dto.getModuleType(), a.getOrgId());
        requireSurgeonAuth(a.getSurgeonId(), a.getSurgeryCode(), a.getSurgeryName(), a.getSurgeryLevel());
        HisSurgery s = new HisSurgery();
        s.setOrgId(a.getOrgId());
        s.setApplyId(a.getId());
        s.setVisitType(a.getVisitType());
        s.setInpVisitId(a.getInpVisitId());
        s.setVisitId(a.getVisitId());
        s.setDeadlineType(a.getDeadlineType());
        s.setModuleType(dto.getModuleType() != null ? dto.getModuleType() : 1);
        s.setSurgeryCode(a.getSurgeryCode());
        s.setSurgeryName(a.getSurgeryName());
        s.setSurgeryLevel(a.getSurgeryLevel());
        s.setDeptId(a.getApplyDeptId());
        s.setSurgeonId(dto.getSurgeonId() != null ? dto.getSurgeonId() : a.getSurgeonId());
        s.setFirstAssistantId(dto.getFirstAssistantId());
        s.setSecondAssistantId(dto.getSecondAssistantId());
        s.setAnesthesiologistId(dto.getAnesthesiologistId());
        s.setAnesthesiaNurseId(dto.getAnesthesiaNurseId());
        s.setInstrumentNurseId(dto.getInstrumentNurseId());
        s.setCirculatingNurseId(dto.getCirculatingNurseId());
        s.setScheduleDate(dto.getScheduleDate());
        s.setScheduleTime(trimOrNull(dto.getScheduleTime()));
        s.setRoomNo(trimOrNull(dto.getRoomNo()));
        s.setApprovalStatus(a.getSurgeryLevel() != null && a.getSurgeryLevel() >= 3 ? 1 : 0);
        s.setStatus(2);
        surgeryMapper.insert(s);
        applyService.updateApplyStatus(applyId, 2, 4, s.getId());
        upsertModuleExt(s.getId(), a.getOrgId(), s.getModuleType(),
                dto.getDsaEquipment(), dto.getDsaContrast(), dto.getDsaRadiationDose(),
                dto.getEndoScopeType(), dto.getEndoBiopsyCnt(),
                dto.getObstGestationalWeek(), dto.getObstBirthType());
        Map<String, Object> pt = patientContact(a.getPatientId());
        applyService.createNotify(a.getId(), s.getId(), a.getPatientName(), str(pt.get("phone")), 3,
                "手术安排通知: " + s.getSurgeryName() + ", " + dto.getScheduleDate() + " "
                        + (s.getScheduleTime() == null ? "" : s.getScheduleTime())
                        + ", " + (s.getRoomNo() == null ? "手术间待定" : s.getRoomNo()) + ", 请准时到达手术室报到");
        log.info("申请单安排手术: applyId={}, surgeryId={}, date={}, room={}",
                applyId, s.getId(), dto.getScheduleDate(), dto.getRoomNo());
        return s;
    }

    /** 急诊直接安排(规范2.2.2.3.7.5): 跳过申请/复核直接建手术, 自动生成已安排状态申请单留档 */
    @Transactional(rollbackFor = Exception.class)
    public HisSurgery urgentSchedule(SurgeryScheduleDTO dto) {
        if (dto == null || !StringUtils.hasText(dto.getSurgeryName())) {
            throw new BizException(400, "急诊手术名称不能为空");
        }
        int visitType = dto.getVisitType() == null ? 1 : dto.getVisitType();
        if (visitType == 1 ? dto.getInpVisitId() == null : dto.getVisitId() == null) {
            throw new BizException(400, "急诊手术必须选择住院(1)或门诊(2/3)就诊记录");
        }
        requireScheduleBase(dto);
        requireNoRoomConflict(dto.getRoomNo(), dto.getScheduleDate(), dto.getScheduleTime(), null);
        requireRoomResource(dto.getRoomNo(), dto.getModuleType(), guard.currentOrgId());
        requireSurgeonAuth(dto.getSurgeonId(), dto.getSurgeryCode(), dto.getSurgeryName(), dto.getSurgeryLevel());
        HisSurgery s = new HisSurgery();
        s.setOrgId(guard.currentOrgId());
        s.setVisitType(visitType);
        s.setInpVisitId(visitType == 1 ? dto.getInpVisitId() : null);
        s.setVisitId(visitType == 1 ? null : dto.getVisitId());
        s.setDeadlineType(3);
        s.setModuleType(dto.getModuleType() != null ? dto.getModuleType() : 1);
        s.setSurgeryCode(trimOrNull(dto.getSurgeryCode()));
        s.setSurgeryName(dto.getSurgeryName().trim());
        s.setSurgeryLevel(dto.getSurgeryLevel());
        s.setDeptId(dto.getInpVisitId() != null ? visitDeptId(dto.getInpVisitId()) : null);
        s.setSurgeonId(dto.getSurgeonId());
        s.setFirstAssistantId(dto.getFirstAssistantId());
        s.setSecondAssistantId(dto.getSecondAssistantId());
        s.setAnesthesiologistId(dto.getAnesthesiologistId());
        s.setAnesthesiaNurseId(dto.getAnesthesiaNurseId());
        s.setInstrumentNurseId(dto.getInstrumentNurseId());
        s.setCirculatingNurseId(dto.getCirculatingNurseId());
        s.setScheduleDate(dto.getScheduleDate());
        s.setScheduleTime(trimOrNull(dto.getScheduleTime()));
        s.setRoomNo(trimOrNull(dto.getRoomNo()));
        s.setApprovalStatus(dto.getSurgeryLevel() != null && dto.getSurgeryLevel() >= 3 ? 1 : 0);
        s.setStatus(2);
        surgeryMapper.insert(s);
        HisSurgeryApply a = applyService.createUrgentApply(dto);
        applyService.updateApplyStatus(a.getId(), 4, 4, s.getId());
        s.setApplyId(a.getId());
        upsertModuleExt(s.getId(), s.getOrgId(), s.getModuleType(),
                dto.getDsaEquipment(), dto.getDsaContrast(), dto.getDsaRadiationDose(),
                dto.getEndoScopeType(), dto.getEndoBiopsyCnt(),
                dto.getObstGestationalWeek(), dto.getObstBirthType());
        log.info("急诊直接安排: surgeryId={}, applyId={}, surgery={}", s.getId(), a.getId(), s.getSurgeryName());
        return surgeryMapper.selectById(s.getId());
    }

    /** 报到登记: status 2->7, 记 register_time; 来源申请同步标记(失败不阻断) */
    @Transactional(rollbackFor = Exception.class)
    public HisSurgery checkIn(Long id) {
        HisSurgery exist = requireSurgery(id);
        if (exist.getStatus() == null || exist.getStatus() != 2) {
            throw new BizException("仅已排程手术可报到登记, 当前状态: " + exist.getStatus());
        }
        int affected = surgeryMapper.update(null, new LambdaUpdateWrapper<HisSurgery>()
                .set(HisSurgery::getStatus, 7)
                .set(HisSurgery::getRegisterTime, LocalDateTime.now())
                .set(HisSurgery::getUpdateTime, LocalDateTime.now())
                .eq(HisSurgery::getId, id)
                .eq(HisSurgery::getStatus, 2));
        if (affected == 0) {
            throw new BizException("报到失败: 手术状态已变化, 请刷新后重试");
        }
        log.info("手术报到登记: id={}", id);
        return surgeryMapper.selectById(id);
    }

    /** 报到检索: 按申请单号/病历号(住院号)/患者姓名定位待报到与已报到手术 */
    public List<Map<String, Object>> registerQuery(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            throw new BizException(400, "请输入申请单号/病历号/患者姓名");
        }
        String kw = keyword.trim();
        StringBuilder sql = new StringBuilder(
                "SELECT s.id, s.apply_id, a.apply_no, IFNULL(s.visit_type,1) visit_type,"
                        + " COALESCE(v.inp_no, hv.ipt_otp_no, a.medical_no) inp_no,"
                        + " COALESCE(p.name, hv.patient_name, a.patient_name) patient_name,"
                        + " s.surgery_name, s.surgery_level, s.room_no, s.schedule_date, s.schedule_time,"
                        + " s.register_time, s.status, st.staff_name surgeon_name"
                        + " FROM his_surgery s"
                        + " LEFT JOIN his_surgery_apply a ON a.id = s.apply_id AND a.deleted = 0"
                        + " LEFT JOIN his_inp_visit v ON v.id = s.inp_visit_id AND v.deleted = 0"
                        + " LEFT JOIN his_visit hv ON hv.id = s.visit_id AND hv.deleted = 0"
                        + " LEFT JOIN his_patient p ON p.id = COALESCE(v.patient_id, hv.patient_id) AND p.deleted = 0"
                        + " LEFT JOIN his_staff st ON st.id = s.surgeon_id AND st.deleted = 0"
                        + " WHERE s.deleted = 0 AND s.tenant_id = ? AND s.status IN (2, 7)");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        Long scope = guard.scopeOrgId(null);
        if (scope != null) {
            sql.append(" AND s.org_id = ?");
            args.add(scope);
        }
        String like = "%" + kw + "%";
        boolean exactNo = kw.startsWith("SQ");
        sql.append(" AND (a.apply_no = ? OR COALESCE(v.inp_no, hv.ipt_otp_no, a.medical_no) LIKE ?")
                .append(" OR COALESCE(p.name, hv.patient_name, a.patient_name) LIKE ?)");
        args.add(exactNo ? kw : "-1");
        args.add(like);
        args.add(like);
        sql.append(" ORDER BY s.schedule_date DESC, s.id DESC LIMIT 30");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    /** 手术间调配: 仅已排程/已报到, 换 room_no 并校验冲突, 产生安排变动通知(type2) */
    @Transactional(rollbackFor = Exception.class)
    public HisSurgery transfer(Long id, String roomNo) {
        if (!StringUtils.hasText(roomNo)) {
            throw new BizException(400, "目标手术间不能为空");
        }
        HisSurgery exist = requireSurgery(id);
        if (exist.getStatus() == null || (exist.getStatus() != 2 && exist.getStatus() != 7)) {
            throw new BizException("仅已排程/已报到的手术可调配, 当前状态: " + exist.getStatus());
        }
        String target = roomNo.trim();
        if (target.equals(exist.getRoomNo())) {
            throw new BizException("目标手术间与当前一致, 无需调配");
        }
        requireNoRoomConflict(target, exist.getScheduleDate(), exist.getScheduleTime(), id);
        requireRoomResource(target, exist.getModuleType(), exist.getOrgId());
        surgeryMapper.update(null, new LambdaUpdateWrapper<HisSurgery>()
                .set(HisSurgery::getRoomNo, target)
                .set(HisSurgery::getUpdateTime, LocalDateTime.now())
                .eq(HisSurgery::getId, id));
        Map<String, Object> pt = exist.getApplyId() != null ? applyPatient(exist.getApplyId()) : new HashMap<>();
        applyService.createNotify(exist.getApplyId(), id, str(pt.get("patient_name")),
                str(pt.get("phone")), 2,
                "手术调配通知: " + exist.getSurgeryName() + " 由 "
                        + (exist.getRoomNo() == null ? "未分配" : exist.getRoomNo()) + " 调配至 " + target
                        + (exist.getScheduleTime() == null ? "" : ", " + exist.getScheduleTime()));
        log.info("手术间调配: id={}, {}->{}, 通知已生成", id, exist.getRoomNo(), target);
        return surgeryMapper.selectById(id);
    }

    /** 排程修改(时间/手术间/团队变动): 已完成前可改, 旧值比对产生安排变动通知(type2, 前端红标) */
    @Transactional(rollbackFor = Exception.class)
    public HisSurgery reschedule(Long id, SurgeryScheduleDTO dto) {
        HisSurgery exist = requireSurgery(id);
        if (exist.getStatus() == null || (exist.getStatus() != 2 && exist.getStatus() != 7)) {
            throw new BizException("仅已排程/已报到的手术可修改安排, 当前状态: " + exist.getStatus());
        }
        if (dto == null || dto.getScheduleDate() == null) {
            throw new BizException(400, "手术日期不能为空");
        }
        List<String> changes = new ArrayList<>();
        String newRoom = trimOrNull(dto.getRoomNo()) == null ? exist.getRoomNo() : dto.getRoomNo().trim();
        String newTime = trimOrNull(dto.getScheduleTime()) == null ? exist.getScheduleTime() : dto.getScheduleTime().trim();
        boolean roomChanged = !Objects.equals(newRoom, exist.getRoomNo());
        boolean timeChanged = !Objects.equals(newTime, exist.getScheduleTime())
                || !Objects.equals(dto.getScheduleDate(), exist.getScheduleDate());
        boolean staffChanged = !Objects.equals(nvl(dto.getSurgeonId()), nvl(exist.getSurgeonId()))
                || !Objects.equals(nvl(dto.getAnesthesiologistId()), nvl(exist.getAnesthesiologistId()));
        if (roomChanged || timeChanged) {
            requireNoRoomConflict(newRoom, dto.getScheduleDate(), newTime, id);
        }
        if (roomChanged) {
            requireRoomResource(newRoom, exist.getModuleType(), exist.getOrgId());
        }
        if (timeChanged) {
            changes.add("时间: " + exist.getScheduleDate() + " " + nvlStr(exist.getScheduleTime())
                    + " → " + dto.getScheduleDate() + " " + nvlStr(newTime));
        }
        if (roomChanged) {
            changes.add("手术间: " + nvlStr(exist.getRoomNo()) + " → " + nvlStr(newRoom));
        }
        if (staffChanged) {
            changes.add("手术团队人员变更");
        }
        surgeryMapper.update(null, new LambdaUpdateWrapper<HisSurgery>()
                .set(HisSurgery::getScheduleDate, dto.getScheduleDate())
                .set(HisSurgery::getScheduleTime, newTime)
                .set(HisSurgery::getRoomNo, newRoom)
                .set(dto.getSurgeonId() != null, HisSurgery::getSurgeonId, dto.getSurgeonId())
                .set(dto.getFirstAssistantId() != null, HisSurgery::getFirstAssistantId, dto.getFirstAssistantId())
                .set(dto.getSecondAssistantId() != null, HisSurgery::getSecondAssistantId, dto.getSecondAssistantId())
                .set(dto.getAnesthesiologistId() != null, HisSurgery::getAnesthesiologistId, dto.getAnesthesiologistId())
                .set(dto.getAnesthesiaNurseId() != null, HisSurgery::getAnesthesiaNurseId, dto.getAnesthesiaNurseId())
                .set(dto.getInstrumentNurseId() != null, HisSurgery::getInstrumentNurseId, dto.getInstrumentNurseId())
                .set(dto.getCirculatingNurseId() != null, HisSurgery::getCirculatingNurseId, dto.getCirculatingNurseId())
                .set(HisSurgery::getUpdateTime, LocalDateTime.now())
                .eq(HisSurgery::getId, id));
        if (!changes.isEmpty()) {
            Map<String, Object> pt = exist.getApplyId() != null ? applyPatient(exist.getApplyId()) : new HashMap<>();
            applyService.createNotify(exist.getApplyId(), id, str(pt.get("patient_name")), str(pt.get("phone")), 2,
                "手术安排变动: " + exist.getSurgeryName() + "; " + String.join("; ", changes));
        }
        log.info("排程修改: id={}, changes={}", id, changes);
        return surgeryMapper.selectById(id);
    }

    /** 手术室退回手术(取消安排): 2/7->6, 来源申请单回退为待安排(4->2) */
    @Transactional(rollbackFor = Exception.class)
    public HisSurgery cancelSchedule(Long id, String reason) {
        HisSurgery exist = requireSurgery(id);
        if (exist.getStatus() == null || (exist.getStatus() != 2 && exist.getStatus() != 7)) {
            throw new BizException("仅已排程/已报到的手术可退回, 当前状态: " + exist.getStatus());
        }
        int affected = surgeryMapper.update(null, new LambdaUpdateWrapper<HisSurgery>()
                .set(HisSurgery::getStatus, 6)
                .set(HisSurgery::getUpdateTime, LocalDateTime.now())
                .eq(HisSurgery::getId, id)
                .in(HisSurgery::getStatus, 2, 7));
        if (affected == 0) {
            throw new BizException("退回失败: 手术状态已变化, 请刷新后重试");
        }
        revertApplyOnCancel(exist);
        log.info("手术退回: id={}, reason={}", id, reason);
        return surgeryMapper.selectById(id);
    }

    /** 取消完成(补退费场景): 5->4, 来源申请 5->4 */
    @Transactional(rollbackFor = Exception.class)
    public HisSurgery cancelComplete(Long id) {
        HisSurgery exist = requireSurgery(id);
        int affected = surgeryMapper.update(null, new LambdaUpdateWrapper<HisSurgery>()
                .set(HisSurgery::getStatus, 4)
                .set(HisSurgery::getUpdateTime, LocalDateTime.now())
                .eq(HisSurgery::getId, id)
                .eq(HisSurgery::getStatus, 5));
        if (affected == 0) {
            throw new BizException("仅已完成手术可取消完成(状态已变化), 请刷新后重试");
        }
        if (exist.getApplyId() != null) {
            try {
                applyService.updateApplyStatus(exist.getApplyId(), 5, 4, null);
            } catch (BizException e) {
                log.warn("取消完成回写申请单失败: applyId={}, {}", exist.getApplyId(), e.getMessage());
            }
        }
        log.info("取消完成: id={}", id);
        return surgeryMapper.selectById(id);
    }

    /** 未安排手术池: 已复核待安排(2)的申请单 */
    public List<Map<String, Object>> unarrangedList() {
        StringBuilder sql = new StringBuilder(
                "SELECT a.id, a.apply_no, a.visit_type, a.patient_name, a.gender, a.age, a.medical_no, a.bed_no,"
                        + " a.surgery_code, a.surgery_name, a.surgery_level, a.anesthesia_type,"
                        + " a.surgeon_id, a.surgeon_name, a.expect_time, a.deadline_type, a.pre_op_diag,"
                        + " a.apply_dept_name, a.apply_time" 
                        + " FROM his_surgery_apply a WHERE a.deleted = 0 AND a.tenant_id = ? AND a.status = 2");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        Long scope = guard.scopeOrgId(null);
        if (scope != null) {
            sql.append(" AND a.org_id = ?");
            args.add(scope);
        }
        sql.append(" ORDER BY a.deadline_type DESC, a.expect_time, a.id");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    /** 麻醉已安排列表: 已排程/已报到且存在麻醉记录的手术 */
    public List<Map<String, Object>> anesthesiaList() {
        StringBuilder sql = new StringBuilder(
                "SELECT s.id, s.apply_id, IFNULL(s.visit_type,1) visit_type, s.schedule_date, s.schedule_time, s.room_no, s.status,"
                        + " COALESCE(p.name, hv.patient_name) patient_name, p.gender_name, p.age,"
                        + " COALESCE(v.inp_no, hv.ipt_otp_no) inp_no, bed.bed_no,"
                        + " s.surgery_name, s.surgery_level, ast.staff_name anesthesiologist_name,"
                        + " an.id anesthesia_id, an.anesthesia_type, an.pre_assessment,"
                        + " CASE WHEN an.pre_assessment IS NULL THEN 0 ELSE 1 END assessed" 
                        + " FROM his_surgery s"
                        + " INNER JOIN his_anesthesia an ON an.surgery_id = s.id AND an.deleted = 0"
                        + " LEFT JOIN his_inp_visit v ON v.id = s.inp_visit_id AND v.deleted = 0"
                        + " LEFT JOIN his_visit hv ON hv.id = s.visit_id AND hv.deleted = 0"
                        + " LEFT JOIN his_patient p ON p.id = COALESCE(v.patient_id, hv.patient_id) AND p.deleted = 0"
                        + " LEFT JOIN his_bed bed ON bed.id = v.bed_id AND bed.deleted = 0"
                        + " LEFT JOIN his_staff ast ON ast.id = s.anesthesiologist_id AND ast.deleted = 0"
                        + " WHERE s.deleted = 0 AND s.tenant_id = ? AND s.status IN (2, 7)");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        Long scope = guard.scopeOrgId(null);
        if (scope != null) {
            sql.append(" AND s.org_id = ?");
            args.add(scope);
        }
        sql.append(" ORDER BY s.schedule_date, s.schedule_time, s.id");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    /** 手术排程板: 指定日期(默认今日)按手术间分组的占用视图(含门诊/日间, 状态<>6) */
    public Map<String, Object> roomBoard(Long orgId, LocalDate date, Integer moduleType) {
        LocalDate day = date == null ? LocalDate.now() : date;
        StringBuilder sql = new StringBuilder(
                "SELECT s.id, s.apply_id, IFNULL(s.visit_type,1) visit_type, s.schedule_time, s.room_no, s.status, s.register_time,"
                        + " COALESCE(p.name, hv.patient_name) patient_name, p.gender_name, p.age,"
                        + " COALESCE(v.inp_no, hv.ipt_otp_no) inp_no,"
                        + " s.surgery_name, s.surgery_level, st.staff_name surgeon_name, d.dept_name, a.apply_no"
                        + " FROM his_surgery s"
                        + " LEFT JOIN his_inp_visit v ON v.id = s.inp_visit_id AND v.deleted = 0"
                        + " LEFT JOIN his_visit hv ON hv.id = s.visit_id AND hv.deleted = 0"
                        + " LEFT JOIN his_patient p ON p.id = COALESCE(v.patient_id, hv.patient_id) AND p.deleted = 0"
                        + " LEFT JOIN his_staff st ON st.id = s.surgeon_id AND st.deleted = 0"
                        + " LEFT JOIN his_dept d ON d.id = s.dept_id AND d.deleted = 0"
                        + " LEFT JOIN his_surgery_apply a ON a.id = s.apply_id AND a.deleted = 0"
                        + " WHERE s.deleted = 0 AND s.tenant_id = ? AND s.schedule_date = ? AND s.status <> 6");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        args.add(day);
        if (orgId != null) {
            sql.append(" AND s.org_id = ?");
            args.add(orgId);
        }
        if (moduleType != null) {
            sql.append(" AND IFNULL(s.module_type, 1) = ?");
            args.add(moduleType);
        }
        sql.append(" ORDER BY s.schedule_time, s.id");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (String room : boardRoomColumns(moduleType)) {
            grouped.put(room, new ArrayList<>());
        }
        List<Map<String, Object>> unassigned = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String room = str(row.get("room_no"));
            if (room == null || room.trim().isEmpty()) {
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
        result.put("date", day.toString());
        result.put("total", rows.size());
        result.put("rooms", rooms);
        return result;
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

    /** 手术间列表(预置8间; 手麻P4b 保留无参口径与存量一致, 资源维度请用 roomList(moduleType)) */
    public List<String> roomList() {
        return new ArrayList<>(PRESET_ROOMS);
    }

    /* ==================== 校验 / 工具 ==================== */

    /** 排程基础校验: 日期必填; 选了手术间则时间段必填(冲突校验依赖) */
    private static void requireScheduleBase(SurgeryScheduleDTO dto) {
        if (dto == null || dto.getScheduleDate() == null) {
            throw new BizException(400, "手术日期不能为空");
        }
        if (StringUtils.hasText(dto.getRoomNo()) && !StringUtils.hasText(dto.getScheduleTime())) {
            throw new BizException(400, "指定手术间时手术时间段必填(如 09:00-11:00)");
        }
    }

    /** 手术间时间冲突校验: 同室同日 schedule_time(HH:mm-HH:mm) 区间重叠拒绝; 无法解析的存量数据跳过 */
    private void requireNoRoomConflict(String roomNo, LocalDate date, String scheduleTime, Long excludeId) {
        if (!StringUtils.hasText(roomNo) || date == null || !StringUtils.hasText(scheduleTime)) {
            return;
        }
        int[] win = parseTimeWindow(scheduleTime);
        if (win == null) {
            return;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, surgery_name, schedule_time FROM his_surgery"
                        + " WHERE deleted = 0 AND tenant_id = ? AND room_no = ? AND schedule_date = ?"
                        + " AND status IN (2, 3, 4, 7) AND id <> ?",
                tenantId(), roomNo.trim(), date, excludeId == null ? -1L : excludeId);
        for (Map<String, Object> r : rows) {
            int[] other = parseTimeWindow(str(r.get("schedule_time")));
            if (other == null) {
                continue;
            }
            if (win[0] < other[1] && other[0] < win[1]) {
                throw new BizException("手术间冲突: " + roomNo + " 在 " + r.get("surgery_name")
                        + "(" + r.get("schedule_time") + ") 时段已占用, 请调整时间或手术间");
            }
        }
    }

    /** 解析 HH:mm-HH:mm 为分钟区间; 不可解析返回 null */
    private static int[] parseTimeWindow(String v) {
        if (v == null) {
            return null;
        }
        String[] parts = v.trim().split("-");
        if (parts.length != 2) {
            return null;
        }
        try {
            LocalTime s = LocalTime.parse(parts[0].trim(), HM_FMT);
            LocalTime e = LocalTime.parse(parts[1].trim(), HM_FMT);
            int sm = s.getHour() * 60 + s.getMinute();
            int em = e.getHour() * 60 + e.getMinute();
            return em > sm ? new int[]{sm, em} : null;
        } catch (Exception ex) {
            return null;
        }
    }

    /* ==================== 手麻P4b: 手术间资源校验 / 一体化专属字段 ==================== */

    /** module_type -> 期望资源 room_type 映射(1手术室->1手术间, 2DSA->2机房, 4内镜->3内镜室, 3产科->4产房; 其余 null 不约束)。 */
    private static Integer roomTypeForModule(Integer moduleType) {
        if (moduleType == null) {
            return null;
        }
        switch (moduleType) {
            case 1:
                return 1;
            case 2:
                return 2;
            case 4:
                return 3;
            case 3:
                return 4;
            default:
                return null;
        }
    }

    private static String roomTypeName(Integer t) {
        if (t == null) {
            return "未知";
        }
        switch (t) {
            case 1: return "手术间";
            case 2: return "DSA机房";
            case 3: return "内镜室";
            case 4: return "产房";
            default: return "未知";
        }
    }

    private static String moduleTypeName(Integer m) {
        if (m == null) {
            return "未知";
        }
        switch (m) {
            case 1: return "手术室";
            case 2: return "DSA";
            case 3: return "产科分娩";
            case 4: return "内镜";
            case 5: return "麻醉治疗";
            default: return "未知";
        }
    }

    /**
     * 手术间资源校验(P4b): 若 room_no 命中 his_surgery_room(资源表)则校验其可用/类型匹配/机构归属;
     * 未命中视为存量自由文本手术间(如"手术间1"), 不做资源约束(向后兼容)。
     */
    private void requireRoomResource(String roomNo, Integer moduleType, Long orgId) {
        if (!StringUtils.hasText(roomNo)) {
            return;
        }
        HisSurgeryRoom room = roomMapper.selectOne(new LambdaQueryWrapper<HisSurgeryRoom>()
                .eq(HisSurgeryRoom::getRoomCode, roomNo.trim()).last("LIMIT 1"));
        if (room == null) {
            return;
        }
        if (room.getStatus() != null && room.getStatus() == 0) {
            throw new BizException("手术间 " + roomNo + " 已停用, 不可排程");
        }
        Integer expected = roomTypeForModule(moduleType);
        if (expected != null && room.getRoomType() != null && !expected.equals(room.getRoomType())) {
            throw new BizException("手术间 " + roomNo + "(" + roomTypeName(room.getRoomType())
                    + ") 与手术模块(" + moduleTypeName(moduleType) + ")不匹配, 请选择对应类型的机房/手术间");
        }
        if (orgId != null && room.getOrgId() != null && !orgId.equals(room.getOrgId())) {
            throw new BizException("手术间 " + roomNo + " 不属于本机构, 不可排程");
        }
    }

    /** 一体化专属字段 upsert(P4b): 仅 module_type∈{2,3,4} 且采集到任一专属字段时旁挂写入 his_surgery_module_ext。 */
    private void upsertModuleExt(Long surgeryId, Long orgId, Integer moduleType,
                                 String dsaEquip, String dsaContrast, BigDecimal dsaDose,
                                 String endoScope, Integer endoBiopsy, String obstWeek, Integer obstBirth) {
        if (surgeryId == null || moduleType == null) {
            return;
        }
        if (moduleType != 2 && moduleType != 3 && moduleType != 4) {
            return;
        }
        boolean hasData = StringUtils.hasText(dsaEquip) || StringUtils.hasText(dsaContrast) || dsaDose != null
                || StringUtils.hasText(endoScope) || endoBiopsy != null
                || StringUtils.hasText(obstWeek) || obstBirth != null;
        if (!hasData) {
            return;
        }
        HisSurgeryModuleExt ext = moduleExtMapper.selectOne(new LambdaQueryWrapper<HisSurgeryModuleExt>()
                .eq(HisSurgeryModuleExt::getSurgeryId, surgeryId).last("LIMIT 1"));
        boolean isNew = ext == null;
        if (isNew) {
            ext = new HisSurgeryModuleExt();
        }
        ext.setSurgeryId(surgeryId);
        ext.setOrgId(orgId);
        ext.setModuleType(moduleType);
        if (moduleType == 2) {
            ext.setDsaEquipment(trimOrNull(dsaEquip));
            ext.setDsaContrast(trimOrNull(dsaContrast));
            ext.setDsaRadiationDose(dsaDose);
        } else if (moduleType == 4) {
            ext.setEndoScopeType(trimOrNull(endoScope));
            ext.setEndoBiopsyCnt(endoBiopsy);
        } else {
            ext.setObstGestationalWeek(trimOrNull(obstWeek));
            ext.setObstBirthType(obstBirth);
        }
        if (isNew) {
            moduleExtMapper.insert(ext);
        } else {
            moduleExtMapper.updateById(ext);
        }
    }

    /** 手术间可用资源列表(P4b): 按 module_type 映射资源类型返回可用房 room_code; 手术室/未指定时并入预置8间(向后兼容)。 */
    public List<String> roomList(Integer moduleType) {
        Integer rt = roomTypeForModule(moduleType);
        Long scope = guard.scopeOrgId(null);
        LambdaQueryWrapper<HisSurgeryRoom> w = new LambdaQueryWrapper<HisSurgeryRoom>()
                .eq(HisSurgeryRoom::getStatus, 1);
        if (rt != null) {
            w.eq(HisSurgeryRoom::getRoomType, rt);
        }
        if (scope != null) {
            w.eq(HisSurgeryRoom::getOrgId, scope);
        }
        List<HisSurgeryRoom> rooms = roomMapper.selectList(w.orderByAsc(HisSurgeryRoom::getRoomCode));
        List<String> codes = new ArrayList<>();
        for (HisSurgeryRoom r : rooms) {
            codes.add(r.getRoomCode());
        }
        if (rt == null || rt == 1) {
            LinkedHashSet<String> set = new LinkedHashSet<>(PRESET_ROOMS);
            set.addAll(codes);
            return new ArrayList<>(set);
        }
        return codes;
    }

    /** 排程板房列(P4b): 指定模块时取对应类型资源房, 未指定保留预置8间(与存量一致)。 */
    private List<String> boardRoomColumns(Integer moduleType) {
        return moduleType != null ? roomList(moduleType) : new ArrayList<>(PRESET_ROOMS);
    }

    /** 术者权限校验(申请安排/急诊直排两处钩子): 命中规则白名单或职工级别不足即拒绝 */
    private void requireSurgeonAuth(Long staffId, String code, String name, Integer level) {
        if (staffId == null) {
            return;
        }
        Map<String, Object> chk = authRuleService.check(staffId, code, name, level);
        if (!Boolean.TRUE.equals(chk.get("allowed"))) {
            throw new BizException("主刀医师权限不足: " + chk.get("reason"));
        }
    }

    /** 手术取消/退回时来源申请单回退为待安排(4->2, 非申请来源或状态已变不阻断) */
    private void revertApplyOnCancel(HisSurgery exist) {
        if (exist.getApplyId() != null) {
            try {
                applyService.updateApplyStatus(exist.getApplyId(), 4, 2, null);
            } catch (BizException e) {
                log.warn("手术取消回退申请单失败: applyId={}, {}", exist.getApplyId(), e.getMessage());
            }
        }
    }

    /** 申请单患者信息(姓名/电话) */
    private Map<String, Object> applyPatient(Long applyId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT patient_name, phone FROM his_surgery_apply WHERE id = ? AND deleted = 0", applyId);
        return rows.isEmpty() ? new HashMap<>() : rows.get(0);
    }

    /** 患者联系电话 */
    private Map<String, Object> patientContact(Long patientId) {
        if (patientId == null) {
            return new HashMap<>();
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT name patient_name, phone FROM his_patient WHERE id = ? AND deleted = 0", patientId);
        return rows.isEmpty() ? new HashMap<>() : rows.get(0);
    }

    /** 住院就诊科室ID(急诊直排登记手术科室) */
    private Long visitDeptId(Long inpVisitId) {
        if (inpVisitId == null) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT dept_id FROM his_inp_visit WHERE id = ? AND deleted = 0", inpVisitId);
        return rows.isEmpty() ? null : toLong(rows.get(0).get("dept_id"));
    }

    private static Long nvl(Long v) {
        return v == null ? 0L : v;
    }

    private static String nvlStr(String v) {
        return v == null ? "-" : v;
    }

    private static Long toLong(Object v) {
        return v instanceof Number ? ((Number) v).longValue() : null;
    }

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
