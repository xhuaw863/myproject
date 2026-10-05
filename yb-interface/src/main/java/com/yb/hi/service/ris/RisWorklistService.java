package com.yb.hi.service.ris;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.outpatient.HisPatient;
import com.yb.hi.entity.ris.HisExamExecution;
import com.yb.hi.entity.ris.HisExamRequest;
import com.yb.hi.entity.ris.HisExamWorklist;
import com.yb.hi.entity.ris.HisImagingDevice;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.mapper.outpatient.HisPatientMapper;
import com.yb.hi.mapper.ris.HisExamExecutionMapper;
import com.yb.hi.mapper.ris.HisExamRequestMapper;
import com.yb.hi.mapper.ris.HisExamWorklistMapper;
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
 * RIS DICOM Worklist 服务(申请单 -> Worklist 条目下发 MWL SCP, 设备回填 StudyUID/MPPS)。
 * 口径:
 * 1) Accession Number: AN + yyyyMMdd + 6位序号, synchronized 内存序号 + 跨日DB回读当日最大序号兜底重启防撞号;
 * 2) 患者姓名/性别(M/F/O)/出生日期(yyyyMMdd)转为 DICOM 口径, 设备 AE Title 取自 his_imaging_device 台账;
 * 3) 状态: 0待检查 -> 1检查中 -> 2已完成; 3已取消。到检后由检查执行侧联动, 本层提供显式状态更新;
 * 4) 机构隔离: 按 id 加载一律 scopeOrgId 相等校验(非牵头仅本机构/牵头可跨机构); 列表查询走 scopeOrgId(null) 过滤;
 * 5) 同名申请单已有未取消条目时幂等返回(防重复生成撞 Accession 号)。
 */
@Slf4j
@Service
public class RisWorklistService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");
    /** 检查号前缀(Accession Number) */
    private static final String ACCESSION_PREFIX = "AN";

    private final HisExamWorklistMapper worklistMapper;
    private final HisExamRequestMapper requestMapper;
    private final HisImagingDeviceMapper deviceMapper;
    private final HisExamExecutionMapper executionMapper;
    private final HisPatientMapper patientMapper;
    private final OrgAccessGuard guard;

    /** Accession 内存序号(synchronized 唯一; 跨日重置时从DB回读当日最大序号) */
    private String seqDate;
    private int seqNo = 0;

    public RisWorklistService(HisExamWorklistMapper worklistMapper, HisExamRequestMapper requestMapper,
                              HisImagingDeviceMapper deviceMapper, HisExamExecutionMapper executionMapper,
                              HisPatientMapper patientMapper, OrgAccessGuard guard) {
        this.worklistMapper = worklistMapper;
        this.requestMapper = requestMapper;
        this.deviceMapper = deviceMapper;
        this.executionMapper = executionMapper;
        this.patientMapper = patientMapper;
        this.guard = guard;
    }

    /* ================= Worklist 生成 ================= */

    /**
     * 从检查申请单生成 DICOM Worklist 条目:
     * 校验申请单(存在/未取消) -> 幂等(已有未取消条目直接返回) -> 生成 Accession 号,
     * 患者信息转 DICOM 格式, 设备表取 AE Title/Modality, 预约时间转 yyyyMMdd/HHmmss, status=0待检查。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisExamWorklist generateWorklist(Long requestId) {
        if (requestId == null) {
            throw new BizException(400, "检查申请单ID不能为空");
        }
        HisExamRequest req = requestMapper.selectById(requestId);
        if (req == null) {
            throw new BizException(400, "检查申请单不存在");
        }
        requireOrgScope(req.getOrgId(), "检查申请单");
        if (req.getStatus() != null && req.getStatus() == 7) {
            throw new BizException("该检查申请单已取消, 无法生成 Worklist");
        }

        // 幂等: 同一申请单已有未取消条目(0待检查/1检查中/2已完成)直接返回, 防重复生成撞 Accession 号
        HisExamWorklist existed = worklistMapper.selectOne(Wrappers.<HisExamWorklist>lambdaQuery()
                .eq(HisExamWorklist::getRequestId, requestId)
                .ne(HisExamWorklist::getStatus, 3)
                .orderByDesc(HisExamWorklist::getId)
                .last("LIMIT 1"));
        if (existed != null) {
            log.info("Worklist已存在, 幂等返回: requestId={}, accessionNo={}", requestId, existed.getAccessionNo());
            return existed;
        }

        HisImagingDevice device = req.getDeviceId() == null ? null : deviceMapper.selectById(req.getDeviceId());
        HisPatient patient = req.getPatientId() == null ? null : patientMapper.selectById(req.getPatientId());

        HisExamWorklist w = new HisExamWorklist();
        w.setOrgId(req.getOrgId());
        w.setRequestId(requestId);
        w.setAccessionNo(nextAccessionNo());
        w.setPatientId(req.getPatientId());
        if (patient != null) {
            w.setPatientName(patient.getName());
            w.setPatientIdNo(patient.getPatientNo());
            w.setGender(dicomGender(patient.getGender()));
            w.setBirthDate(patient.getBirthDate() == null ? null : patient.getBirthDate().toLocalDate().format(DAY));
        } else {
            w.setGender("O");
        }
        w.setModality(StringUtils.hasText(req.getModality()) ? req.getModality()
                : (device == null ? null : device.getModality()));
        w.setDeviceAeTitle(device == null ? null : device.getAeTitle());
        w.setScheduledStation(device == null ? null : device.getAeTitle());
        LocalDateTime scheduled = req.getScheduledTime();
        w.setScheduledDate(scheduled == null ? LocalDate.now().format(DAY) : scheduled.format(DAY));
        w.setScheduledTime(scheduled == null ? null : scheduled.format(TIME));
        w.setBodyPart(req.getBodyPart());
        w.setProcedureDesc(firstText(req.getExamItemName(), req.getInhospExamItemName(), req.getChargeItemName()));
        w.setReferringPhysician(req.getApplyDoctorName());
        w.setRequestingDept(req.getApplyDeptName());
        w.setStatus(0);
        worklistMapper.insert(w);
        log.info("Worklist生成: id={}, requestId={}, accessionNo={}, aeTitle={}, scheduledDate={}",
                w.getId(), requestId, w.getAccessionNo(), w.getDeviceAeTitle(), w.getScheduledDate());
        return w;
    }

    /* ================= 查询 ================= */

    /**
     * 按设备 AE Title + 日期查询待检查列表(供 DICOM Worklist SCP / 技师工作站调用)。
     * date 缺省当日, 支持 yyyyMMdd 与 yyyy-MM-dd; aeTitle 为空时不过滤设备; 仅返回 status=0待检查。
     */
    public List<HisExamWorklist> queryWorklist(String aeTitle, String date) {
        String day = normalizeDay(date);
        LambdaQueryWrapper<HisExamWorklist> qw = Wrappers.<HisExamWorklist>lambdaQuery()
                .eq(HisExamWorklist::getStatus, 0)
                .eq(HisExamWorklist::getScheduledDate, day);
        if (StringUtils.hasText(aeTitle)) {
            String ae = aeTitle.trim();
            qw.and(w -> w.eq(HisExamWorklist::getDeviceAeTitle, ae)
                    .or().eq(HisExamWorklist::getScheduledStation, ae));
        }
        Long scope = guard.scopeOrgId(null);
        if (scope != null) {
            qw.eq(HisExamWorklist::getOrgId, scope);
        }
        qw.orderByAsc(HisExamWorklist::getScheduledTime).orderByAsc(HisExamWorklist::getId);
        return worklistMapper.selectList(qw);
    }

    /**
     * 今日该设备的 Worklist 任务板: Worklist 条目 + 申请单摘要 + 最新执行记录(到检/检查进度)。
     * 设备归属校验后按 AE Title 匹配, 不再叠加机构过滤(跨机构患者使用本院设备的检查应出现在本院设备板上)。
     */
    public List<Map<String, Object>> listTodayByDevice(Long deviceId) {
        HisImagingDevice device = requireDevice(deviceId);
        if (!StringUtils.hasText(device.getAeTitle())) {
            log.warn("设备无 AE Title, 无法解析今日 Worklist: deviceId={}", deviceId);
            return new ArrayList<>();
        }
        String today = LocalDate.now().format(DAY);
        List<HisExamWorklist> wls = worklistMapper.selectList(Wrappers.<HisExamWorklist>lambdaQuery()
                .eq(HisExamWorklist::getDeviceAeTitle, device.getAeTitle().trim())
                .eq(HisExamWorklist::getScheduledDate, today)
                .orderByAsc(HisExamWorklist::getScheduledTime)
                .orderByAsc(HisExamWorklist::getId));

        Map<Long, HisExamRequest> requests = new HashMap<>();
        Map<Long, HisExamExecution> executions = new HashMap<>();
        List<Long> requestIds = new ArrayList<>();
        for (HisExamWorklist w : wls) {
            if (w.getRequestId() != null) {
                requestIds.add(w.getRequestId());
            }
        }
        if (!requestIds.isEmpty()) {
            for (HisExamRequest r : requestMapper.selectBatchIds(requestIds)) {
                requests.put(r.getId(), r);
            }
            // 降序取每申请单最新一条执行记录(putIfAbsent 保留首条)
            List<HisExamExecution> es = executionMapper.selectList(Wrappers.<HisExamExecution>lambdaQuery()
                    .in(HisExamExecution::getRequestId, requestIds)
                    .orderByDesc(HisExamExecution::getId));
            for (HisExamExecution e : es) {
                executions.putIfAbsent(e.getRequestId(), e);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (HisExamWorklist w : wls) {
            out.add(toTaskRow(w, requests.get(w.getRequestId()), executions.get(w.getRequestId())));
        }
        return out;
    }

    /* ================= 状态更新 ================= */

    /** 更新 Worklist 状态(0待检查/1检查中/2已完成/3已取消)。 */
    public HisExamWorklist updateStatus(Long id, Integer status) {
        if (id == null) {
            throw new BizException(400, "Worklist条目ID不能为空");
        }
        if (status == null || status < 0 || status > 3) {
            throw new BizException(400, "Worklist状态非法(0待检查/1检查中/2已完成/3已取消)");
        }
        HisExamWorklist w = loadGuarded(id);
        w.setStatus(status);
        worklistMapper.updateById(w);
        log.info("Worklist状态更新: id={}, status={}", id, status);
        return w;
    }

    /** 设备完成后回填 Study Instance UID(DICOM StudyInstanceUID 由设备/PACS 生成)。 */
    public HisExamWorklist updateStudyUid(Long id, String studyUid) {
        if (id == null) {
            throw new BizException(400, "Worklist条目ID不能为空");
        }
        if (!StringUtils.hasText(studyUid)) {
            throw new BizException(400, "Study Instance UID不能为空");
        }
        HisExamWorklist w = loadGuarded(id);
        w.setStudyUid(studyUid.trim());
        worklistMapper.updateById(w);
        log.info("Worklist回填StudyUID: id={}, studyUid={}", id, w.getStudyUid());
        return w;
    }

    /** MPPS 状态回调(设备 Performed Procedure Step: IN PROGRESS/COMPLETED/DISCONTINUED)。 */
    public HisExamWorklist updateMppsStatus(Long id, String mppsStatus) {
        if (id == null) {
            throw new BizException(400, "Worklist条目ID不能为空");
        }
        if (!StringUtils.hasText(mppsStatus)) {
            throw new BizException(400, "MPPS状态不能为空");
        }
        HisExamWorklist w = loadGuarded(id);
        w.setMppsStatus(mppsStatus.trim());
        worklistMapper.updateById(w);
        log.info("Worklist更新MPPS状态: id={}, mppsStatus={}", id, w.getMppsStatus());
        return w;
    }

    /* ================= 内部: 状态联动(供检查执行侧同事务调用) ================= */

    /**
     * 按申请单联动 Worklist(检查执行服务在到检/开始/完成时调用): 取最新未取消条目回写状态与可选 StudyUID。
     * 到检时 status=0(待检查/准备中)维持不变, 到检事实由执行记录 check_in_time 承载。状态/UID 无变化时不写库。
     */
    public void syncByRequest(Long requestId, Integer status, String studyUid) {
        if (requestId == null || status == null) {
            return;
        }
        HisExamWorklist w = worklistMapper.selectOne(Wrappers.<HisExamWorklist>lambdaQuery()
                .eq(HisExamWorklist::getRequestId, requestId)
                .ne(HisExamWorklist::getStatus, 3)
                .orderByDesc(HisExamWorklist::getId)
                .last("LIMIT 1"));
        if (w == null) {
            return;
        }
        boolean statusChanged = !status.equals(w.getStatus());
        boolean uidChanged = StringUtils.hasText(studyUid) && !studyUid.equals(w.getStudyUid());
        if (!statusChanged && !uidChanged) {
            return;
        }
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<HisExamWorklist> upd =
                Wrappers.<HisExamWorklist>lambdaUpdate()
                        .eq(HisExamWorklist::getId, w.getId())
                        .set(HisExamWorklist::getUpdateTime, LocalDateTime.now());
        if (statusChanged) {
            upd.set(HisExamWorklist::getStatus, status);
        }
        if (uidChanged) {
            upd.set(HisExamWorklist::getStudyUid, studyUid);
        }
        worklistMapper.update(null, upd);
        log.info("Worklist联动: id={}, status={}->{}, studyUidSet={}",
                w.getId(), w.getStatus(), status, uidChanged);
    }

    /* ================= 辅助 ================= */

    private HisExamWorklist loadGuarded(Long id) {
        HisExamWorklist w = worklistMapper.selectById(id);
        if (w == null) {
            throw new BizException(400, "Worklist条目不存在");
        }
        requireOrgScope(w.getOrgId(), "Worklist条目");
        return w;
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

    /** Accession 号生成: AN+yyyyMMdd+6位序号, synchronized 唯一, 跨日DB回读 + 占用自增防撞(唯一键 tenant+accession) */
    private synchronized String nextAccessionNo() {
        String today = LocalDate.now().format(DAY);
        if (!today.equals(seqDate)) {
            seqDate = today;
            seqNo = maxSeqFromDb(today);
        }
        seqNo++;
        String no = ACCESSION_PREFIX + today + String.format("%06d", seqNo);
        while (worklistMapper.selectCount(Wrappers.<HisExamWorklist>lambdaQuery()
                .eq(HisExamWorklist::getAccessionNo, no)) > 0) {
            seqNo++;
            no = ACCESSION_PREFIX + today + String.format("%06d", seqNo);
        }
        return no;
    }

    /** 查当日已有 Accession 最大序号(重启后防撞号; Mapper 查询经租户插件自动按当前租户过滤) */
    private int maxSeqFromDb(String today) {
        HisExamWorklist one = worklistMapper.selectOne(Wrappers.<HisExamWorklist>lambdaQuery()
                .likeRight(HisExamWorklist::getAccessionNo, ACCESSION_PREFIX + today)
                .orderByDesc(HisExamWorklist::getAccessionNo)
                .last("LIMIT 1"));
        if (one == null || !StringUtils.hasText(one.getAccessionNo()) || one.getAccessionNo().length() < 8) {
            return 0;
        }
        try {
            return Integer.parseInt(one.getAccessionNo().substring(one.getAccessionNo().length() - 6));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 日期归一: 空=当日; 支持 yyyyMMdd / yyyy-MM-dd / yyyy/MM/dd */
    private static String normalizeDay(String date) {
        if (!StringUtils.hasText(date)) {
            return LocalDate.now().format(DAY);
        }
        String d = date.trim().replace("-", "").replace("/", "");
        boolean ok = d.length() == 8;
        for (int i = 0; ok && i < d.length(); i++) {
            ok = Character.isDigit(d.charAt(i));
        }
        if (!ok) {
            throw new BizException(400, "日期格式应为 yyyyMMdd 或 yyyy-MM-dd: " + date);
        }
        return d;
    }

    /** 医保性别(1男/2女/0未知/9未说明)转 DICOM 性别 M/F/O */
    static String dicomGender(String gender) {
        if ("1".equals(gender) || "男".equals(gender) || "M".equalsIgnoreCase(gender)) {
            return "M";
        }
        if ("2".equals(gender) || "女".equals(gender) || "F".equalsIgnoreCase(gender)) {
            return "F";
        }
        return "O";
    }

    /** 首个非空文本(检查描述取 医保项目名 > 院内项目名 > 收费项目名) */
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

    /** 任务板行: Worklist 字段 + 申请单摘要 + 最新执行进度(字段与 RisExecutionService.listTodayByDevice 对齐) */
    private static Map<String, Object> toTaskRow(HisExamWorklist w, HisExamRequest r, HisExamExecution e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("worklistId", w.getId());
        m.put("requestId", w.getRequestId());
        m.put("accessionNo", w.getAccessionNo());
        m.put("patientId", w.getPatientId());
        m.put("patientName", w.getPatientName());
        m.put("patientIdNo", w.getPatientIdNo());
        m.put("gender", w.getGender());
        m.put("birthDate", w.getBirthDate());
        m.put("modality", w.getModality());
        m.put("deviceAeTitle", w.getDeviceAeTitle());
        m.put("scheduledDate", w.getScheduledDate());
        m.put("scheduledTime", w.getScheduledTime());
        m.put("bodyPart", w.getBodyPart());
        m.put("procedureDesc", w.getProcedureDesc());
        m.put("referringPhysician", w.getReferringPhysician());
        m.put("requestingDept", w.getRequestingDept());
        m.put("status", w.getStatus());
        m.put("mppsStatus", w.getMppsStatus());
        m.put("studyUid", w.getStudyUid());
        m.put("requestNo", r == null ? null : r.getRequestNo());
        m.put("examItemName", r == null ? null : r.getExamItemName());
        m.put("isUrgent", r == null ? null : r.getIsUrgent());
        m.put("priority", r == null ? null : r.getPriority());
        m.put("paidFlag", r == null ? null : r.getPaidFlag());
        m.put("scheduledAt", r == null ? null : r.getScheduledTime());
        m.put("executionId", e == null ? null : e.getId());
        m.put("executionStatus", e == null ? null : e.getStatus());
        m.put("checkInTime", e == null ? null : e.getCheckInTime());
        m.put("examStartTime", e == null ? null : e.getExamStartTime());
        m.put("examEndTime", e == null ? null : e.getExamEndTime());
        m.put("imageCount", e == null ? null : e.getImageCount());
        m.put("seriesCount", e == null ? null : e.getSeriesCount());
        m.put("technicianName", e == null ? null : e.getTechnicianName());
        return m;
    }
}
