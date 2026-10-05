package com.yb.hi.controller.ris;

import com.yb.hi.dto.ris.RisScheduleDTO;
import com.yb.hi.entity.ris.HisExamSchedule;
import com.yb.hi.entity.ris.HisExamScheduleTpl;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.ris.RisScheduleService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * RIS 检查排程接口(设备号源簿/预约占位/周模板维护):
 * 排程为机构自治业务过程(与挂号排班号源同口径): 读按当前登录机构隔离
 * (scopeOrgId, 非牵头忽略入参; 牵头未指定回落本机构), 写锁当前登录机构。
 */
@RestController
@RequestMapping("/api/ris/schedule")
public class RisScheduleController {

    private final RisScheduleService scheduleService;
    private final OrgAccessGuard guard;

    public RisScheduleController(RisScheduleService scheduleService, OrgAccessGuard guard) {
        this.scheduleService = scheduleService;
        this.guard = guard;
    }

    /** 从周模板生成指定日期的排程号源(幂等: 同设备同日同时段已存在跳过), 返回新生成条数 */
    @PostMapping("/generate")
    public R<Map<String, Object>> generate(@RequestBody Map<String, Object> body) {
        Long deviceId = toLong(body == null ? null : body.get("deviceId"));
        LocalDate date = toLocalDate(body == null ? null : body.get("date"));
        LocalDate startDate = toLocalDate(body == null ? null : body.get("startDate"));
        LocalDate endDate = toLocalDate(body == null ? null : body.get("endDate"));
        if (deviceId == null) {
            throw new BizException(400, "设备ID不能为空");
        }
        int created;
        if (startDate != null) {
            // 区间批量生成(无模板的日期静默跳过)
            created = scheduleService.generateRange(deviceId, startDate, endDate == null ? startDate : endDate);
        } else {
            if (date == null) {
                throw new BizException(400, "生成日期不能为空(单日 date 或区间 startDate/endDate)");
            }
            created = scheduleService.generateFromTemplate(deviceId, date);
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("deviceId", deviceId);
        result.put("created", created);
        return R.ok(result);
    }

    /** 预约排程时段(占位乐观锁, 占满自动置已满; 申请单联动置已预约并回填时间/设备/技师) */
    @PostMapping("/book")
    public R<Map<String, Object>> book(@RequestBody Map<String, Object> body) {
        Long scheduleId = toLong(body == null ? null : body.get("scheduleId"));
        Long requestId = toLong(body == null ? null : body.get("requestId"));
        return R.ok(scheduleService.bookSlot(scheduleId, requestId));
    }

    /** 取消预约(余量回退/已满恢复可约, 申请单回退待预约) */
    @PutMapping("/{id}/cancel-booking")
    public R<Map<String, Object>> cancelBooking(@PathVariable Long id,
                                                @RequestBody(required = false) Map<String, Object> body) {
        Long requestId = toLong(body == null ? null : body.get("requestId"));
        return R.ok(scheduleService.cancelBooking(id, requestId));
    }

    /** 查询设备可用时段(状态可约且有余量) */
    @GetMapping("/slots")
    public R<List<Map<String, Object>>> availableSlots(@RequestParam Long deviceId,
                                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                                       @RequestParam(required = false) Long orgId) {
        Long scope = guard.scopeOrgId(orgId);
        if (scope == null) {
            scope = guard.currentOrgId();
        }
        return R.ok(scheduleService.listAvailableSlots(scope, deviceId, date));
    }

    /** 按设备查询排程区间(JOIN 设备名/技师名, 附剩余可约数) */
    @GetMapping("/by-device")
    public R<List<Map<String, Object>>> listByDevice(@RequestParam Long deviceId,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                                                     @RequestParam(required = false) Long orgId) {
        Long scope = guard.scopeOrgId(orgId);
        if (scope == null) {
            scope = guard.currentOrgId();
        }
        return R.ok(scheduleService.listByDevice(scope, deviceId, startDate, endDate));
    }

    /** 单时段保存(临时加号/手工调整容量与状态) */
    @PostMapping("/slot")
    public R<HisExamSchedule> saveSlot(@RequestBody RisScheduleDTO dto) {
        return R.ok(scheduleService.saveSlot(dto));
    }

    /* ================= 排程周模板 ================= */

    /** 周模板列表(按设备过滤可选) */
    @GetMapping("/template")
    public R<List<HisExamScheduleTpl>> listTemplates(
            @RequestParam(required = false) Long deviceId,
            @RequestParam(required = false) Long orgId) {
        Long scope = guard.scopeOrgId(orgId);
        if (scope == null) {
            scope = guard.currentOrgId();
        }
        return R.ok(scheduleService.listTemplates(scope, deviceId));
    }

    /** 新增/编辑周模板(id 为空新增, 否则更新; dayOfWeek 1-7) */
    @PostMapping("/template")
    public R<HisExamScheduleTpl> saveTemplate(@RequestBody HisExamScheduleTpl tpl) {
        return R.ok(scheduleService.saveTemplate(tpl));
    }

    /** 更新周模板 */
    @PutMapping("/template/{id}")
    public R<HisExamScheduleTpl> updateTemplate(@PathVariable Long id, @RequestBody HisExamScheduleTpl tpl) {
        if (tpl == null) {
            throw new BizException(400, "模板参数不能为空");
        }
        tpl.setId(id);
        return R.ok(scheduleService.saveTemplate(tpl));
    }

    /** 删除周模板(逻辑删除, 不影响已生成的排程) */
    @DeleteMapping("/template/{id}")
    public R<Void> deleteTemplate(@PathVariable Long id) {
        scheduleService.deleteTemplate(id);
        return R.ok(null);
    }

    /* ================= 辅助 ================= */

    private static Long toLong(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        try {
            return Long.valueOf(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 日期参数解析: 兼容 yyyy-MM-dd 字符串与 LocalDate 反序列化形态 */
    private static LocalDate toLocalDate(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof LocalDate) {
            return (LocalDate) o;
        }
        String s = o.toString().trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (RuntimeException e) {
            throw new BizException(400, "日期参数非法(须为 yyyy-MM-dd): " + s);
        }
    }
}
