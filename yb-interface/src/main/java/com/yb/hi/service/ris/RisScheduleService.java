package com.yb.hi.service.ris;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.dto.ris.RisScheduleDTO;
import com.yb.hi.entity.ris.HisExamRequest;
import com.yb.hi.entity.ris.HisExamSchedule;
import com.yb.hi.entity.ris.HisExamScheduleTpl;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.ris.HisExamRequestMapper;
import com.yb.hi.mapper.ris.HisExamScheduleMapper;
import com.yb.hi.mapper.ris.HisExamScheduleTplMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RIS 检查排程服务(设备x日期x时段号源簿, 周模板批量生成具体日期排程):
 * 口径:
 * 1) 号源生成: 按周模板(day_of_week 匹配)将 [slot_start, slot_end) 按 slot_duration 切分时段批量落库,
 *    幂等(同设备同日同时段已存在跳过, uk_tenant_dev_date_slot);
 * 2) 预约占位: 乐观锁 UPDATE ... WHERE booked_count < max_patients AND status IN (1可预约/4临时加号),
 *    占满自动置 2已满; 申请单联动置 1已预约并回填预约时间/设备/技师(乐观锁 status=0);
 * 3) 取消预约: 余量回退(booked_count-1, 不低于0), 已满恢复 1可预约, 申请单回退 0待预约并清空分配;
 * 4) 机构隔离: 排程按当前登录机构(机构自治业务过程, 与挂号排班同口径, 牵头也不穿透成员机构);
 *    申请单不锁机构(医共体内乡镇开单/牵头检查的跨机构预约为合法场景)。
 */
@Slf4j
@Service
public class RisScheduleService extends ServiceImpl<HisExamScheduleMapper, HisExamSchedule> {

    /** 排程状态: 1可预约/2已满/3停诊/4临时加号 */
    private static final int SLOT_OPEN = 1;
    private static final int SLOT_FULL = 2;
    private static final int SLOT_STOP = 3;
    private static final int SLOT_EXTRA = 4;

    private final HisExamScheduleMapper scheduleMapper;
    private final HisExamScheduleTplMapper tplMapper;
    private final HisExamRequestMapper requestMapper;
    private final JdbcTemplate jdbcTemplate;
    private final OrgAccessGuard guard;

    public RisScheduleService(HisExamScheduleMapper scheduleMapper, HisExamScheduleTplMapper tplMapper,
                              HisExamRequestMapper requestMapper, JdbcTemplate jdbcTemplate,
                              OrgAccessGuard guard) {
        this.scheduleMapper = scheduleMapper;
        this.tplMapper = tplMapper;
        this.requestMapper = requestMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.guard = guard;
    }

    /* ================= 模板 ================= */

    /** 周模板列表(按设备过滤可选) */
    public List<HisExamScheduleTpl> listTemplates(Long orgId, Long deviceId) {
        if (orgId == null) {
            throw new BizException(400, "机构范围不能为空");
        }
        return tplMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.<HisExamScheduleTpl>lambdaQuery()
                .eq(HisExamScheduleTpl::getOrgId, orgId)
                .eq(deviceId != null, HisExamScheduleTpl::getDeviceId, deviceId)
                .orderByAsc(HisExamScheduleTpl::getDeviceId)
                .orderByAsc(HisExamScheduleTpl::getDayOfWeek)
                .orderByAsc(HisExamScheduleTpl::getSlotStart));
    }

    /** 保存周模板(新增/更新): day_of_week 1-7, 校验时段起止与时长 */
    @Transactional(rollbackFor = Exception.class)
    public HisExamScheduleTpl saveTemplate(HisExamScheduleTpl tpl) {
        if (tpl == null || tpl.getDeviceId() == null) {
            throw new BizException(400, "模板设备ID不能为空");
        }
        if (tpl.getDayOfWeek() == null || tpl.getDayOfWeek() < 1 || tpl.getDayOfWeek() > 7) {
            throw new BizException(400, "周几必须为 1-7(周一=1)");
        }
        if (tpl.getSlotStart() == null || tpl.getSlotEnd() == null) {
            throw new BizException(400, "时段起止时间不能为空");
        }
        if (!tpl.getSlotEnd().isAfter(tpl.getSlotStart())) {
            throw new BizException(400, "时段结束时间必须晚于开始时间");
        }
        tpl.setSlotDuration(tpl.getSlotDuration() == null || tpl.getSlotDuration() < 5 ? 15 : tpl.getSlotDuration());
        tpl.setMaxPatients(tpl.getMaxPatients() == null || tpl.getMaxPatients() < 1 ? 1 : tpl.getMaxPatients());
        tpl.setEnabled(tpl.getEnabled() == null ? 1 : tpl.getEnabled());
        if (tpl.getId() == null) {
            tpl.setOrgId(guard.currentOrgId());
            requireDevice(tpl.getDeviceId());
            tplMapper.insert(tpl);
            log.info("排程周模板新增: deviceId={}, dayOfWeek={}, {}-{}",
                    tpl.getDeviceId(), tpl.getDayOfWeek(), tpl.getSlotStart(), tpl.getSlotEnd());
        } else {
            HisExamScheduleTpl old = tplMapper.selectById(tpl.getId());
            if (old == null) {
                throw new BizException(400, "周模板不存在");
            }
            requireSelfOrg(old.getOrgId());
            tpl.setOrgId(old.getOrgId());
            tplMapper.updateById(tpl);
            log.info("排程周模板更新: id={}, deviceId={}, dayOfWeek={}", tpl.getId(), tpl.getDeviceId(), tpl.getDayOfWeek());
        }
        return tpl;
    }

    /** 删除周模板(逻辑删除, 不影响已生成的排程) */
    @Transactional(rollbackFor = Exception.class)
    public void deleteTemplate(Long id) {
        if (id == null) {
            throw new BizException(400, "模板ID不能为空");
        }
        HisExamScheduleTpl tpl = tplMapper.selectById(id);
        if (tpl == null) {
            throw new BizException(400, "周模板不存在");
        }
        requireSelfOrg(tpl.getOrgId());
        tplMapper.deleteById(id);
        log.info("排程周模板删除: id={}, deviceId={}", id, tpl.getDeviceId());
    }

    /* ================= 号源生成 ================= */

    /**
     * 从周模板生成指定日期的排程: 查 day_of_week 匹配的启用模板行,
     * 按时长切分时段批量插入(幂等: 同设备同日同时段已存在跳过), 返回新生成条数。
     */
    @Transactional(rollbackFor = Exception.class)
    public int generateFromTemplate(Long deviceId, LocalDate date) {
        if (deviceId == null || date == null) {
            throw new BizException(400, "设备ID与日期不能为空");
        }
        Long orgId = guard.currentOrgId();
        requireDevice(deviceId);
        int dayOfWeek = date.getDayOfWeek().getValue(); // ISO-8601: 周一=1 ... 周日=7
        List<HisExamScheduleTpl> tpls = tplMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<HisExamScheduleTpl>lambdaQuery()
                        .eq(HisExamScheduleTpl::getDeviceId, deviceId)
                        .eq(HisExamScheduleTpl::getDayOfWeek, dayOfWeek)
                        .eq(HisExamScheduleTpl::getEnabled, 1));
        if (tpls.isEmpty()) {
            throw new BizException("该设备在周" + dayOfWeek + "没有启用的排程模板, 请先维护周模板");
        }
        // 已存在时段(幂等跳过): 一次查询取当日该设备全部时段键
        List<HisExamSchedule> existing = scheduleMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<HisExamSchedule>lambdaQuery()
                        .eq(HisExamSchedule::getDeviceId, deviceId)
                        .eq(HisExamSchedule::getScheduleDate, date));
        java.util.Set<String> existingSlots = new java.util.HashSet<>();
        for (HisExamSchedule s : existing) {
            existingSlots.add(s.getTimeSlot());
        }
        int created = 0;
        for (HisExamScheduleTpl tpl : tpls) {
            int duration = tpl.getSlotDuration() == null || tpl.getSlotDuration() < 5 ? 15 : tpl.getSlotDuration();
            LocalTime t = tpl.getSlotStart();
            while (t.plusMinutes(duration).compareTo(tpl.getSlotEnd()) <= 0) {
                LocalTime end = t.plusMinutes(duration);
                String timeSlot = fmtTime(t) + "-" + fmtTime(end);
                if (existingSlots.add(timeSlot)) {
                    HisExamSchedule s = new HisExamSchedule();
                    s.setOrgId(orgId);
                    s.setDeviceId(deviceId);
                    s.setScheduleDate(date);
                    s.setTimeSlot(timeSlot);
                    s.setSlotStart(t);
                    s.setSlotEnd(end);
                    s.setSlotDuration(duration);
                    s.setMaxPatients(tpl.getMaxPatients() == null ? 1 : tpl.getMaxPatients());
                    s.setBookedCount(0);
                    s.setStatus(SLOT_OPEN);
                    s.setTechnicianId(tpl.getTechnicianId());
                    scheduleMapper.insert(s);
                    created++;
                }
                t = end;
            }
        }
        log.info("排程号源生成: deviceId={}, date={}, 模板{}条, 新生成{}条(已存在跳过{}条)",
                deviceId, date, tpls.size(), created, existingSlots.size() - created);
        return created;
    }

    /**
     * 按周模板批量生成日期区间排程(逐日调用 {@link #generateFromTemplate}, 无模板的日期静默跳过), 返回总生成条数。
     */
    @Transactional(rollbackFor = Exception.class)
    public int generateRange(Long deviceId, LocalDate startDate, LocalDate endDate) {
        if (deviceId == null || startDate == null || endDate == null) {
            throw new BizException(400, "设备ID与起止日期不能为空");
        }
        if (endDate.isBefore(startDate)) {
            throw new BizException(400, "截止日期不能早于起始日期");
        }
        int total = 0;
        for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
            try {
                total += generateFromTemplate(deviceId, d);
            } catch (BizException e) {
                // 该日无启用模板: 静默跳过(区间批量场景不应整体失败)
                log.debug("排程区间生成跳过(无模板): deviceId={}, date={}", deviceId, d);
            }
        }
        log.info("排程区间号源生成: deviceId={}, {} ~ {}, 共{}条", deviceId, startDate, endDate, total);
        return total;
    }

    /* ================= 预约 ================= */

    /**
     * 预约排程时段: 占位乐观锁(booked_count < max_patients 且状态可约), 占满自动置已满;
     * 申请单联动置已预约并回填预约时间/设备/技师(乐观锁 status=0, 占位失败整体回滚)。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> bookSlot(Long scheduleId, Long requestId) {
        if (scheduleId == null || requestId == null) {
            throw new BizException(400, "排程时段ID与申请单ID不能为空");
        }
        long tid = tenantId();
        Long orgId = guard.currentOrgId();
        HisExamSchedule slot = scheduleMapper.selectById(scheduleId);
        if (slot == null) {
            throw new BizException(400, "排程时段不存在");
        }
        if (!orgId.equals(slot.getOrgId())) {
            throw new BizException(403, "无权预约其他机构的排程时段");
        }
        if (slot.getStatus() != null && slot.getStatus() == SLOT_STOP) {
            throw new BizException("该时段已停诊, 不可预约");
        }
        HisExamRequest request = requestMapper.selectById(requestId);
        if (request == null) {
            throw new BizException(400, "检查申请单不存在");
        }
        if (request.getStatus() != null && request.getStatus() != 0) {
            throw new BizException("仅待预约状态的申请单可预约(当前状态: " + request.getStatus() + ")");
        }
        // 占位乐观锁: 余量+1 且 关联申请单
        int affected = jdbcTemplate.update(
                "UPDATE his_exam_schedule SET booked_count = booked_count + 1, request_id = ?,"
                        + " update_by = ?, update_time = NOW()"
                        + " WHERE id = ? AND tenant_id = ? AND deleted = 0 AND status IN (1, 4)"
                        + " AND booked_count < max_patients",
                requestId, currentUserName(), scheduleId, tid);
        if (affected == 0) {
            throw new BizException("该时段已约满或不可预约, 请刷新后重选时段");
        }
        // 占满自动置已满
        jdbcTemplate.update(
                "UPDATE his_exam_schedule SET status = 2 WHERE id = ? AND tenant_id = ? AND deleted = 0"
                        + " AND booked_count >= max_patients AND status = 1",
                scheduleId, tid);
        // 申请单联动: 置已预约并回填预约时间(排程日期+时段开始)/设备/技师(乐观锁 status=0)
        LocalDateTime scheduledTime = LocalDateTime.of(slot.getScheduleDate(), slot.getSlotStart());
        int reqAffected = jdbcTemplate.update(
                "UPDATE his_exam_request SET status = 1, scheduled_time = ?, device_id = ?, technician_id = ?,"
                        + " update_by = ?, update_time = NOW()"
                        + " WHERE id = ? AND status = 0 AND tenant_id = ? AND deleted = 0",
                scheduledTime, slot.getDeviceId(), slot.getTechnicianId(), currentUserName(), requestId, tid);
        if (reqAffected == 0) {
            // 申请单已被并发预约/取消: 整体回滚占位
            throw new BizException("申请单状态已变更, 预约失败请刷新重试");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schedule", scheduleMapper.selectById(scheduleId));
        result.put("request", requestMapper.selectById(requestId));
        log.info("检查预约成功: scheduleId={}, requestId={}, timeSlot={} {}, 申请单号={}",
                scheduleId, requestId, slot.getScheduleDate(), slot.getTimeSlot(), request.getRequestNo());
        return result;
    }

    /**
     * 取消预约(按排程时段): 校验时段当前关联的申请单一致(防串号释放),
     * 余量回退/已满恢复可约, 申请单回退待预约并清空分配。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> cancelBooking(Long scheduleId, Long requestId) {
        if (scheduleId == null) {
            throw new BizException(400, "排程时段ID不能为空");
        }
        HisExamSchedule slot = scheduleMapper.selectById(scheduleId);
        if (slot == null) {
            throw new BizException(400, "排程时段不存在");
        }
        if (requestId != null && slot.getRequestId() != null && !requestId.equals(slot.getRequestId())) {
            throw new BizException("该时段当前关联的申请单与请求不符, 无法取消(防串号释放)");
        }
        Long targetRequestId = requestId != null ? requestId : slot.getRequestId();
        if (targetRequestId == null) {
            throw new BizException(400, "该时段无预约占位, 无需取消");
        }
        releaseSlot(slot, targetRequestId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schedule", scheduleMapper.selectById(scheduleId));
        result.put("request", requestMapper.selectById(targetRequestId));
        log.info("检查预约取消: scheduleId={}, requestId={}", scheduleId, targetRequestId);
        return result;
    }

    /**
     * 按申请单释放全部排程占位(供申请单取消联动, 一个申请单最多占一个时段, 防御式遍历):
     * 余量回退不低于0, 已满恢复可约, 申请单回退待预约。
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancelBookingByRequest(Long requestId) {
        if (requestId == null) {
            return;
        }
        List<HisExamSchedule> slots = scheduleMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<HisExamSchedule>lambdaQuery()
                        .eq(HisExamSchedule::getRequestId, requestId));
        for (HisExamSchedule slot : slots) {
            releaseSlot(slot, requestId);
            log.info("检查预约联动释放: scheduleId={}, requestId={}", slot.getId(), requestId);
        }
    }

    /** 单时段释放: booked_count-1(不低于0)/清关联/已满恢复可约, 申请单回退待预约并清空分配 */
    private void releaseSlot(HisExamSchedule slot, Long requestId) {
        long tid = tenantId();
        int affected = jdbcTemplate.update(
                "UPDATE his_exam_schedule SET booked_count = GREATEST(booked_count - 1, 0), request_id = NULL,"
                        + " status = CASE WHEN status = 2 THEN 1 ELSE status END,"
                        + " update_by = ?, update_time = NOW()"
                        + " WHERE id = ? AND tenant_id = ? AND deleted = 0 AND request_id = ?",
                currentUserName(), slot.getId(), tid, requestId);
        if (affected == 0) {
            throw new BizException("时段占位已变更, 释放失败请刷新重试");
        }
        // 申请单回退待预约(仅已预约态可回退, 幂等)
        jdbcTemplate.update(
                "UPDATE his_exam_request SET status = 0, scheduled_time = NULL, device_id = NULL,"
                        + " technician_id = NULL, update_by = ?, update_time = NOW()"
                        + " WHERE id = ? AND status = 1 AND tenant_id = ? AND deleted = 0",
                currentUserName(), requestId, tid);
    }

    /* ================= 单时段维护 ================= */

    /** 单时段保存(临时加号/手工调整): 已有同时段行则调整容量与状态, 否则新增 */
    @Transactional(rollbackFor = Exception.class)
    public HisExamSchedule saveSlot(RisScheduleDTO dto) {
        if (dto == null || dto.getDeviceId() == null || dto.getScheduleDate() == null
                || dto.getSlotStart() == null || dto.getSlotEnd() == null) {
            throw new BizException(400, "设备/日期/时段起止不能为空");
        }
        if (!dto.getSlotEnd().isAfter(dto.getSlotStart())) {
            throw new BizException(400, "时段结束时间必须晚于开始时间");
        }
        Long orgId = guard.currentOrgId();
        requireDevice(dto.getDeviceId());
        String timeSlot = (StringUtils.hasText(dto.getTimeSlot()) ? dto.getTimeSlot().trim()
                : fmtTime(dto.getSlotStart()) + "-" + fmtTime(dto.getSlotEnd()));
        HisExamSchedule existed = scheduleMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<HisExamSchedule>lambdaQuery()
                        .eq(HisExamSchedule::getDeviceId, dto.getDeviceId())
                        .eq(HisExamSchedule::getScheduleDate, dto.getScheduleDate())
                        .eq(HisExamSchedule::getTimeSlot, timeSlot)
                        .last("LIMIT 1"));
        if (existed != null) {
            existed.setSlotEnd(dto.getSlotEnd());
            existed.setSlotDuration(dto.getSlotDuration() == null || dto.getSlotDuration() < 5
                    ? 15 : dto.getSlotDuration());
            existed.setMaxPatients(dto.getMaxPatients() == null || dto.getMaxPatients() < 1
                    ? existed.getMaxPatients() : dto.getMaxPatients());
            if (dto.getStatus() != null) {
                existed.setStatus(dto.getStatus());
            }
            if (dto.getTechnicianId() != null) {
                existed.setTechnicianId(dto.getTechnicianId());
            }
            scheduleMapper.updateById(existed);
            log.info("排程时段调整: id={}, deviceId={}, {} {}, maxPatients={}",
                    existed.getId(), dto.getDeviceId(), dto.getScheduleDate(), timeSlot, existed.getMaxPatients());
            return existed;
        }
        HisExamSchedule s = new HisExamSchedule();
        s.setOrgId(orgId);
        s.setDeviceId(dto.getDeviceId());
        s.setScheduleDate(dto.getScheduleDate());
        s.setTimeSlot(timeSlot);
        s.setSlotStart(dto.getSlotStart());
        s.setSlotEnd(dto.getSlotEnd());
        s.setSlotDuration(dto.getSlotDuration() == null || dto.getSlotDuration() < 5 ? 15 : dto.getSlotDuration());
        s.setMaxPatients(dto.getMaxPatients() == null || dto.getMaxPatients() < 1 ? 1 : dto.getMaxPatients());
        s.setBookedCount(0);
        s.setStatus(dto.getStatus() == null ? SLOT_OPEN : dto.getStatus());
        s.setTechnicianId(dto.getTechnicianId());
        scheduleMapper.insert(s);
        log.info("排程时段新增(临时加号/手工): deviceId={}, {} {}, maxPatients={}",
                dto.getDeviceId(), dto.getScheduleDate(), timeSlot, s.getMaxPatients());
        return s;
    }

    /* ================= 查询 ================= */

    /** 设备排程区间查询(JOIN 设备名, 按日期+时段排序), 附剩余可约数 */
    public List<Map<String, Object>> listByDevice(Long orgId, Long deviceId, LocalDate startDate, LocalDate endDate) {
        if (orgId == null || deviceId == null) {
            throw new BizException(400, "机构与设备不能为空");
        }
        LocalDate start = startDate == null ? LocalDate.now() : startDate;
        LocalDate end = endDate == null ? start : endDate;
        if (end.isBefore(start)) {
            throw new BizException(400, "截止日期不能早于起始日期");
        }
        return jdbcTemplate.queryForList(
                "SELECT s.id, s.device_id AS deviceId, d.device_name AS deviceName, d.device_type AS deviceType,"
                        + " s.schedule_date AS scheduleDate, s.time_slot AS timeSlot, s.slot_start AS slotStart,"
                        + " s.slot_end AS slotEnd, s.slot_duration AS slotDuration, s.max_patients AS maxPatients,"
                        + " s.booked_count AS bookedCount, (s.max_patients - s.booked_count) AS remaining,"
                        + " s.request_id AS requestId, s.status, s.technician_id AS technicianId,"
                        + " t.staff_name AS technicianName"
                        + " FROM his_exam_schedule s"
                        + " LEFT JOIN his_imaging_device d ON d.id = s.device_id AND d.deleted = 0"
                        + " LEFT JOIN his_staff t ON t.id = s.technician_id AND t.deleted = 0"
                        + " WHERE s.tenant_id = ? AND s.org_id = ? AND s.device_id = ?"
                        + " AND s.schedule_date BETWEEN ? AND ? AND s.deleted = 0"
                        + " ORDER BY s.schedule_date, s.slot_start",
                tenantId(), orgId, deviceId, start, end);
    }

    /** 可预约时段查询: 状态可约(1可预约/4临时加号)且有余量, 附申请单号/患者姓名(已占位部分) */
    public List<Map<String, Object>> listAvailableSlots(Long orgId, Long deviceId, LocalDate date) {
        if (orgId == null || deviceId == null || date == null) {
            throw new BizException(400, "机构/设备/日期不能为空");
        }
        return jdbcTemplate.queryForList(
                "SELECT s.id, s.device_id AS deviceId, d.device_name AS deviceName,"
                        + " s.schedule_date AS scheduleDate, s.time_slot AS timeSlot, s.slot_start AS slotStart,"
                        + " s.slot_end AS slotEnd, s.slot_duration AS slotDuration, s.max_patients AS maxPatients,"
                        + " s.booked_count AS bookedCount, (s.max_patients - s.booked_count) AS remaining,"
                        + " s.status, s.technician_id AS technicianId"
                        + " FROM his_exam_schedule s"
                        + " LEFT JOIN his_imaging_device d ON d.id = s.device_id AND d.deleted = 0"
                        + " WHERE s.tenant_id = ? AND s.org_id = ? AND s.device_id = ?"
                        + " AND s.schedule_date = ? AND s.deleted = 0"
                        + " AND s.status IN (1, 4) AND s.booked_count < s.max_patients"
                        + " ORDER BY s.slot_start",
                tenantId(), orgId, deviceId, date);
    }

    /* ================= 辅助 ================= */

    /** 校验设备存在且未停用(排程机构以当前登录机构为准) */
    private void requireDevice(Long deviceId) {
        Map<String, Object> device = jdbcTemplate.queryForList(
                "SELECT id, org_id, device_name, status FROM his_imaging_device"
                        + " WHERE id = ? AND tenant_id = ? AND deleted = 0", deviceId, tenantId())
                .stream().findFirst().orElse(null);
        if (device == null) {
            throw new BizException(400, "影像设备不存在");
        }
        Long deviceOrg = device.get("org_id") == null ? null : Long.parseLong(device.get("org_id").toString());
        if (!guard.currentOrgId().equals(deviceOrg)) {
            throw new BizException(403, "无权维护其他机构的设备排程");
        }
        Number status = (Number) device.get("status");
        if (status != null && status.intValue() == 3) {
            throw new BizException("该设备已停用, 不可排程");
        }
    }

    /** 排程/模板写守卫: 仅本机构(机构自治, 与挂号排班号源同口径) */
    private void requireSelfOrg(Long orgId) {
        if (orgId != null && !guard.currentOrgId().equals(orgId)) {
            throw new BizException(403, "无权维护其他机构的排程数据");
        }
    }

    private static String fmtTime(LocalTime t) {
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }

    private String currentUserName() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            return null;
        }
        return StringUtils.hasText(lu.getRealName()) ? lu.getRealName() : lu.getUsername();
    }
}

