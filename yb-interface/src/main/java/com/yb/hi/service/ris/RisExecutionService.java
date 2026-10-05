package com.yb.hi.service.ris;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.dto.ris.RisExecutionDTO;
import com.yb.hi.entity.basedata.HisStaff;
import com.yb.hi.entity.outpatient.HisPatient;
import com.yb.hi.entity.ris.HisExamExecution;
import com.yb.hi.entity.ris.HisExamRequest;
import com.yb.hi.entity.ris.HisExamWorklist;
import com.yb.hi.entity.ris.HisImagingDevice;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.basedata.HisStaffMapper;
import com.yb.hi.mapper.outpatient.HisPatientMapper;
import com.yb.hi.mapper.ris.HisExamExecutionMapper;
import com.yb.hi.mapper.ris.HisExamRequestMapper;
import com.yb.hi.mapper.ris.HisImagingDeviceMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RIS 检查执行服务(技师工作站到检/开始/完成三段流转, 落 his_exam_execution)。
 * 口径:
 * 1) 执行状态: 0未开始(已到检) -> 1检查中 -> 2已完成; 3中断(允许重新到检生成新记录);
 *    申请单状态联动: 2已登记(到检) -> 3检查中(开始) -> 4已完成(完成, 乐观锁前值钉 2/3);
 * 2) Worklist 联动(同事务, 经 RisWorklistService.syncByRequest): 到检维持 0待检查(准备中,
 *    到检事实由 check_in_time 承载); 开始 -> 1检查中; 完成 -> 2已完成 + 回填 StudyInstanceUID;
 * 3) 到检幂等: 同申请单已有未完成执行记录(0/1)直接返回; 已完成(2)拒绝重复到检;
 * 4) 完成时填充造影剂(contrast_agent/dose/route)与剂量(DLP/CTDIvol/DAP)及图像/序列数,
 *    未点"开始"直接完成时以完成时间兜底回填 exam_start_time;
 * 5) 机构隔离: 按 id 加载一律 scopeOrgId 相等校验; 列表查询走 scopeOrgId 过滤。
 */
@Slf4j
@Service
public class RisExecutionService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final HisExamExecutionMapper executionMapper;
    private final HisExamRequestMapper requestMapper;
    private final HisImagingDeviceMapper deviceMapper;
    private final HisStaffMapper staffMapper;
    private final HisPatientMapper patientMapper;
    private final OrgAccessGuard guard;
    /** Worklist 联动与 Accession 号统一出自 Worklist 域服务 */
    private final RisWorklistService worklistService;

    public RisExecutionService(HisExamExecutionMapper executionMapper, HisExamRequestMapper requestMapper,
                               HisImagingDeviceMapper deviceMapper, HisStaffMapper staffMapper,
                               HisPatientMapper patientMapper, OrgAccessGuard guard,
                               RisWorklistService worklistService) {
        this.executionMapper = executionMapper;
        this.requestMapper = requestMapper;
        this.deviceMapper = deviceMapper;
        this.staffMapper = staffMapper;
        this.patientMapper = patientMapper;
        this.guard = guard;
        this.worklistService = worklistService;
    }

    /* ================= 到检登记 ================= */

    /**
     * 到检登记: 校验申请单(未取消/未完成) -> 幂等(已有未完成执行记录直接返回) ->
     * 确保 Worklist 存在(不存在则生成, 统一 Accession 命名) -> 创建执行记录(check_in_time=now, status=0)
     * -> 申请单 0/1 -> 2已登记(乐观锁); Worklist 维持 0待检查(准备中, 到检事实由 check_in_time 承载)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisExamExecution checkIn(Long requestId) {
        if (requestId == null) {
            throw new BizException(400, "检查申请单ID不能为空");
        }
        HisExamRequest req = requestMapper.selectById(requestId);
        if (req == null) {
            throw new BizException(400, "检查申请单不存在");
        }
        requireOrgScope(req.getOrgId(), "检查申请单");
        Integer st = req.getStatus();
        if (st != null && st == 7) {
            throw new BizException("该检查申请单已取消, 无法到检");
        }
        if (st != null && st >= 4) {
            throw new BizException("该检查申请单已完成检查, 无法重复到检");
        }

        // 幂等: 已有未完成执行记录(0未开始/1检查中)直接返回; 已完成(2)拒绝
        HisExamExecution existed = latestExecution(requestId);
        if (existed != null && existed.getStatus() != null) {
            if (existed.getStatus() <= 1) {
                log.info("到检幂等返回: requestId={}, executionId={}", requestId, existed.getId());
                return existed;
            }
            if (existed.getStatus() == 2) {
                throw new BizException("该检查申请单已完成, 无法重复到检");
            }
            // status=3中断: 继续新建执行记录(重扫)
        }

        // Worklist: 不存在则先生成(Accession 号统一命名: AN+yyyyMMdd+6位序号)
        HisExamWorklist wl = worklistService.generateWorklist(requestId);

        HisExamExecution exec = new HisExamExecution();
        exec.setOrgId(req.getOrgId());
        exec.setRequestId(requestId);
        exec.setWorklistId(wl.getId());
        exec.setAccessionNo(wl.getAccessionNo());
        exec.setDeviceId(req.getDeviceId());
        exec.setCheckInTime(LocalDateTime.now());
        exec.setStatus(0);
        executionMapper.insert(exec);

        // 申请单 0待预约/1已预约 -> 2已登记(乐观锁: 前值钉)
        int n = requestMapper.update(null, Wrappers.<HisExamRequest>lambdaUpdate()
                .eq(HisExamRequest::getId, requestId)
                .in(HisExamRequest::getStatus, 0, 1)
                .set(HisExamRequest::getStatus, 2)
                .set(HisExamRequest::getUpdateTime, LocalDateTime.now())
                .set(HisExamRequest::getUpdateBy, currentUserName()));
        if (n == 0) {
            HisExamRequest now = requestMapper.selectById(requestId);
            if (now == null || now.getStatus() == null || now.getStatus() != 2) {
                throw new BizException("申请单状态已变化, 请刷新后重试");
            }
            // 并发同操作已推进到 2已登记: 幂等继续
        }
        // Worklist 维持 0待检查(准备中): 患者已到检但检查未开始, 设备端 MWL 仍可查询本条目
        worklistService.syncByRequest(requestId, 0, null);

        log.info("到检登记: requestId={}, executionId={}, accessionNo={}", requestId, exec.getId(), exec.getAccessionNo());
        return exec;
    }

    /* ================= 开始检查 ================= */

    /**
     * 开始检查: 0/3 -> 1检查中; 申请单 2/3 -> 3检查中; Worklist -> 1检查中;
     * 技师缺省取当前登录关联职工, 设备可覆盖到检时默认值; 已完成(2)拒绝, 检查中(1)幂等返回。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisExamExecution startExam(Long executionId, Long deviceId, Long technicianId) {
        if (executionId == null) {
            throw new BizException(400, "执行记录ID不能为空");
        }
        HisExamExecution exec = loadGuarded(executionId);
        Integer cur = exec.getStatus();
        if (cur != null && cur == 2) {
            throw new BizException("该检查已完成, 无法再次开始");
        }
        if (cur != null && cur == 1) {
            log.info("开始检查幂等返回: executionId={}", executionId);
            return exec;
        }
        Long staffId = technicianId != null ? technicianId : currentStaffId();

        HisExamExecution upd = new HisExamExecution();
        upd.setId(executionId);
        upd.setExamStartTime(LocalDateTime.now());
        upd.setStatus(1);
        if (deviceId != null) {
            upd.setDeviceId(deviceId);
        }
        if (staffId != null) {
            upd.setTechnicianId(staffId);
            upd.setTechnicianName(staffName(staffId));
        }
        executionMapper.updateById(upd);

        // 申请单 2已登记/3检查中 -> 3检查中(乐观锁: 前值钉)
        int n = requestMapper.update(null, Wrappers.<HisExamRequest>lambdaUpdate()
                .eq(HisExamRequest::getId, exec.getRequestId())
                .in(HisExamRequest::getStatus, 2, 3)
                .set(HisExamRequest::getStatus, 3)
                .set(HisExamRequest::getUpdateTime, LocalDateTime.now())
                .set(HisExamRequest::getUpdateBy, currentUserName()));
        if (n == 0) {
            HisExamRequest now = exec.getRequestId() == null ? null : requestMapper.selectById(exec.getRequestId());
            if (now == null || now.getStatus() == null || now.getStatus() != 3) {
                throw new BizException("申请单状态已变化, 请刷新后重试");
            }
        }
        // Worklist -> 1检查中
        worklistService.syncByRequest(exec.getRequestId(), 1, null);

        log.info("开始检查: executionId={}, requestId={}, deviceId={}, technicianId={}",
                executionId, exec.getRequestId(), deviceId, staffId);
        return executionMapper.selectById(executionId);
    }

    /* ================= 完成检查 ================= */

    /**
     * 完成检查: 填充结束时间/图像数/序列数/StudyUID/曝光次数/造影剂/剂量/体位等 -> status=2;
     * 申请单 2/3 -> 4已完成; Worklist -> 2已完成 + 回填 StudyUID; 已完成(2)幂等返回。
     * 未点"开始"直接完成时以完成时间兜底回填 exam_start_time, 保证时长统计不空。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisExamExecution completeExam(Long executionId, RisExecutionDTO dto) {
        if (executionId == null) {
            throw new BizException(400, "执行记录ID不能为空");
        }
        HisExamExecution exec = loadGuarded(executionId);
        if (exec.getStatus() != null && exec.getStatus() == 2) {
            log.info("完成检查幂等返回: executionId={}", executionId);
            return exec;
        }
        RisExecutionDTO in = dto == null ? new RisExecutionDTO() : dto;
        LocalDateTime end = in.getExamEndTime() != null ? in.getExamEndTime() : LocalDateTime.now();

        HisExamExecution upd = new HisExamExecution();
        upd.setId(executionId);
        upd.setStatus(2);
        upd.setExamEndTime(end);
        upd.setImageCount(in.getImageCount());
        upd.setSeriesCount(in.getSeriesCount());
        upd.setExposureCount(in.getExposureCount());
        upd.setStudyUid(StringUtils.hasText(in.getStudyUid()) ? in.getStudyUid().trim() : null);
        upd.setContrastAgent(in.getContrastAgent());
        upd.setContrastDose(in.getContrastDose());
        upd.setContrastRoute(in.getContrastRoute());
        upd.setDoseDlp(in.getDoseDlp());
        upd.setDoseCtdi(in.getDoseCtdi());
        upd.setDoseDap(in.getDoseDap());
        upd.setKvp(in.getKvp());
        upd.setMas(in.getMas());
        upd.setPatientPosition(in.getPatientPosition());
        upd.setNotes(in.getNotes());
        Long staffId = in.getTechnicianId() != null ? in.getTechnicianId() : currentStaffId();
        if (exec.getTechnicianId() == null && staffId != null) {
            upd.setTechnicianId(staffId);
        }
        if (StringUtils.hasText(in.getTechnicianName())) {
            upd.setTechnicianName(in.getTechnicianName().trim());
        } else if (!StringUtils.hasText(exec.getTechnicianName()) && staffId != null) {
            upd.setTechnicianName(staffName(staffId));
        }
        if (exec.getExamStartTime() == null) {
            upd.setExamStartTime(in.getExamStartTime() != null ? in.getExamStartTime() : end);
        }
        executionMapper.updateById(upd);

        // 申请单 2已登记/3检查中 -> 4已完成(乐观锁; 已推进到 4/5/6 视为幂等继续, 7已取消拒绝)
        int n = requestMapper.update(null, Wrappers.<HisExamRequest>lambdaUpdate()
                .eq(HisExamRequest::getId, exec.getRequestId())
                .in(HisExamRequest::getStatus, 2, 3)
                .set(HisExamRequest::getStatus, 4)
                .set(HisExamRequest::getUpdateTime, LocalDateTime.now())
                .set(HisExamRequest::getUpdateBy, currentUserName()));
        if (n == 0) {
            HisExamRequest now = exec.getRequestId() == null ? null : requestMapper.selectById(exec.getRequestId());
            Integer cur = now == null ? null : now.getStatus();
            if (cur == null || cur == 7 || cur < 4) {
                throw new BizException("申请单状态已变化, 请刷新后重试");
            }
        }
        // Worklist -> 2已完成 + 回填 StudyUID(优先本次录入, 其次执行记录已有值)
        String uid = StringUtils.hasText(in.getStudyUid()) ? in.getStudyUid().trim() : exec.getStudyUid();
        worklistService.syncByRequest(exec.getRequestId(), 2, uid);

        log.info("完成检查: executionId={}, requestId={}, imageCount={}, seriesCount={}, studyUid={}",
                executionId, exec.getRequestId(), in.getImageCount(), in.getSeriesCount(), uid);
        return executionMapper.selectById(executionId);
    }

    /** 完成检查(按 DTO 内 requestId 定位最新执行记录, 供无执行ID路径调用)。 */
    @Transactional(rollbackFor = Exception.class)
    public HisExamExecution completeExam(RisExecutionDTO dto) {
        if (dto == null || dto.getRequestId() == null) {
            throw new BizException(400, "检查申请单ID不能为空");
        }
        HisExamExecution exec = latestExecution(dto.getRequestId());
        if (exec == null) {
            throw new BizException(400, "该申请单尚无执行记录, 请先到检");
        }
        return completeExam(exec.getId(), dto);
    }

    /* ================= 查询 ================= */

    /** 按申请单查执行记录(倒序, 最新在前)。 */
    public List<HisExamExecution> getByRequest(Long requestId) {
        if (requestId == null) {
            throw new BizException(400, "检查申请单ID不能为空");
        }
        HisExamRequest req = requestMapper.selectById(requestId);
        if (req == null) {
            throw new BizException(400, "检查申请单不存在");
        }
        requireOrgScope(req.getOrgId(), "检查申请单");
        return executionMapper.selectList(Wrappers.<HisExamExecution>lambdaQuery()
                .eq(HisExamExecution::getRequestId, requestId)
                .orderByDesc(HisExamExecution::getId));
    }

    /**
     * 今日该设备的执行记录(今日到检 或 今日开始; 设备归属校验后按 device_id 收口):
     * 拼装申请单摘要与患者姓名/ID号, 字段与 Worklist 任务板行对齐, 供今日任务合并展示。
     */
    public List<Map<String, Object>> listTodayByDevice(Long deviceId) {
        HisImagingDevice device = requireDevice(deviceId);
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);
        List<HisExamExecution> execs = executionMapper.selectList(Wrappers.<HisExamExecution>lambdaQuery()
                .eq(HisExamExecution::getDeviceId, device.getId())
                .and(w -> w.and(x -> x.ge(HisExamExecution::getCheckInTime, dayStart)
                                .lt(HisExamExecution::getCheckInTime, dayEnd))
                        .or(x -> x.ge(HisExamExecution::getExamStartTime, dayStart)
                                .lt(HisExamExecution::getExamStartTime, dayEnd)))
                .orderByAsc(HisExamExecution::getCheckInTime)
                .orderByAsc(HisExamExecution::getId));

        Map<Long, HisExamRequest> requests = new HashMap<>();
        Map<Long, HisPatient> patients = new HashMap<>();
        List<Long> requestIds = new ArrayList<>();
        for (HisExamExecution e : execs) {
            if (e.getRequestId() != null) {
                requestIds.add(e.getRequestId());
            }
        }
        if (!requestIds.isEmpty()) {
            List<Long> patientIds = new ArrayList<>();
            for (HisExamRequest r : requestMapper.selectBatchIds(requestIds)) {
                requests.put(r.getId(), r);
                if (r.getPatientId() != null) {
                    patientIds.add(r.getPatientId());
                }
            }
            if (!patientIds.isEmpty()) {
                for (HisPatient p : patientMapper.selectBatchIds(patientIds)) {
                    patients.put(p.getId(), p);
                }
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (HisExamExecution e : execs) {
            HisExamRequest r = requests.get(e.getRequestId());
            HisPatient p = r == null ? null : patients.get(r.getPatientId());
            out.add(toTaskRow(e, r, p));
        }
        return out;
    }

    /* ================= 辅助 ================= */

    private HisExamExecution loadGuarded(Long executionId) {
        HisExamExecution e = executionMapper.selectById(executionId);
        if (e == null) {
            throw new BizException(400, "检查执行记录不存在");
        }
        requireOrgScope(e.getOrgId(), "检查执行记录");
        return e;
    }

    private HisImagingDevice requireDevice(Long deviceId) {
        if (deviceId == null) {
            throw new BizException(400, "设备ID不能为空");
        }
        HisImagingDevice d = deviceMapper.selectById(deviceId);
        if (d == null) {
            throw new BizException(400, "影像设备不存在");
        }
        requireOrgScope(d.getOrgId(), "影像设备");
        return d;
    }

    /** 机构归属校验: 非牵头仅可访问本机构, 牵头可跨机构(scopedOrgId 相等语义)。 */
    private void requireOrgScope(Long orgId, String what) {
        if (orgId == null) {
            throw new BizException(403, "当前" + what + "未归属机构, 无法操作");
        }
        Long scope = guard.scopeOrgId(orgId);
        if (scope == null || !scope.equals(orgId)) {
            throw new BizException(403, "无权访问其他机构的" + what);
        }
    }

    /** 同申请单最新一条执行记录(含中断/已完成; 无则 null)。 */
    private HisExamExecution latestExecution(Long requestId) {
        return executionMapper.selectOne(Wrappers.<HisExamExecution>lambdaQuery()
                .eq(HisExamExecution::getRequestId, requestId)
                .orderByDesc(HisExamExecution::getId)
                .last("LIMIT 1"));
    }

    private String staffName(Long staffId) {
        if (staffId == null) {
            return null;
        }
        HisStaff s = staffMapper.selectById(staffId);
        return s == null ? null : s.getStaffName();
    }

    private static Long currentStaffId() {
        LoginUser u = UserContext.get();
        return u == null ? null : u.getStaffId();
    }

    private static String currentUserName() {
        LoginUser u = UserContext.get();
        if (u == null) {
            return null;
        }
        return StringUtils.hasText(u.getRealName()) ? u.getRealName() : u.getUsername();
    }

    /** 任务板行(执行记录 + 申请单摘要 + 患者信息; 字段与 RisWorklistService.listTodayByDevice 对齐) */
    private static Map<String, Object> toTaskRow(HisExamExecution e, HisExamRequest r, HisPatient p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("worklistId", e.getWorklistId());
        m.put("requestId", e.getRequestId());
        m.put("accessionNo", e.getAccessionNo());
        m.put("patientId", r == null ? null : r.getPatientId());
        m.put("patientName", p == null ? null : p.getName());
        m.put("patientIdNo", p == null ? null : p.getPatientNo());
        m.put("gender", p == null ? null : RisWorklistService.dicomGender(p.getGender()));
        m.put("birthDate", p == null || p.getBirthDate() == null
                ? null : p.getBirthDate().toLocalDate().format(DAY));
        m.put("modality", r == null ? null : r.getModality());
        m.put("deviceAeTitle", null);
        m.put("scheduledDate", null);
        m.put("scheduledTime", null);
        m.put("bodyPart", r == null ? null : r.getBodyPart());
        m.put("procedureDesc", r == null ? null : firstText(r.getExamItemName(), r.getInhospExamItemName(), r.getChargeItemName()));
        m.put("referringPhysician", r == null ? null : r.getApplyDoctorName());
        m.put("requestingDept", r == null ? null : r.getApplyDeptName());
        m.put("status", e.getStatus());
        m.put("mppsStatus", null);
        m.put("studyUid", e.getStudyUid());
        m.put("requestNo", r == null ? null : r.getRequestNo());
        m.put("examItemName", r == null ? null : r.getExamItemName());
        m.put("isUrgent", r == null ? null : r.getIsUrgent());
        m.put("priority", r == null ? null : r.getPriority());
        m.put("paidFlag", r == null ? null : r.getPaidFlag());
        m.put("scheduledAt", r == null ? null : r.getScheduledTime());
        m.put("executionId", e.getId());
        m.put("executionStatus", e.getStatus());
        m.put("checkInTime", e.getCheckInTime());
        m.put("examStartTime", e.getExamStartTime());
        m.put("examEndTime", e.getExamEndTime());
        m.put("imageCount", e.getImageCount());
        m.put("seriesCount", e.getSeriesCount());
        m.put("technicianName", e.getTechnicianName());
        return m;
    }

    private static String firstText(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (StringUtils.hasText(v)) {
                return v;
            }
        }
        return null;
    }
}
