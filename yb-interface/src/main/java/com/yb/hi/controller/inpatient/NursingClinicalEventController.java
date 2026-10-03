package com.yb.hi.controller.inpatient;

import com.yb.hi.dto.inpatient.NursingClinicalEventDTO;
import com.yb.hi.entity.inpatient.HisNursingClinicalEvent;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.inpatient.NursingClinicalEventService;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 护理临床事件接口(护士站): 手动登记 / 事件时间轴(按就诊) / 时段事件(临时体温单/交班数据源)。
 * 自动触发通道(autoRecord)由就诊状态/医嘱执行侧服务内部调用, 不暴露 HTTP 端点。
 * 时间参数口径: yyyy-MM-dd 或 yyyy-MM-dd HH:mm[:ss](兼容 ISO 的 T 分隔)。
 */
@RestController
@RequestMapping("/api/his/inp/nursing/clinical-event")
public class NursingClinicalEventController {

    private static final DateTimeFormatter MINUTE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter SECOND_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final NursingClinicalEventService eventService;

    public NursingClinicalEventController(NursingClinicalEventService eventService) {
        this.eventService = eventService;
    }

    /** 手动登记事件: eventType 限固定枚举集, 落库 auto_generated=0。 */
    @PostMapping({"", "/"})
    public R<HisNursingClinicalEvent> record(@RequestBody NursingClinicalEventDTO dto) {
        return R.ok(eventService.record(dto));
    }

    /** 事件时间轴(按就诊): 按事件时间升序。 */
    @GetMapping("/list")
    public R<List<HisNursingClinicalEvent>> list(@RequestParam Long inpVisitId) {
        return R.ok(eventService.listByVisit(inpVisitId));
    }

    /** 时段事件(按就诊+闭区间, 临时体温单/交班报告数据源): start/end 可选。 */
    @GetMapping("/list-range")
    public R<List<HisNursingClinicalEvent>> listRange(@RequestParam Long inpVisitId,
                                                      @RequestParam(required = false) String start,
                                                      @RequestParam(required = false) String end) {
        return R.ok(eventService.listByVisitAndRange(inpVisitId,
                parseTime(start, true), parseTime(end, false)));
    }

    /**
     * 时间参数解析: yyyy-MM-dd(起=当日 00:00:00, 止=当日 23:59:59) / yyyy-MM-dd HH:mm / yyyy-MM-dd HH:mm:ss,
     * 兼容 ISO 的 "T" 分隔; 空白返回 null(不参与过滤)。
     */
    private static LocalDateTime parseTime(String raw, boolean isStart) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String s = raw.trim().replace('T', ' ');
        int cut = s.indexOf('.');
        if (cut > 0) {
            s = s.substring(0, cut); // 容忍 ISO 毫秒尾缀
        }
        try {
            switch (s.length()) {
                case 10:
                    LocalDate d = LocalDate.parse(s);
                    return isStart ? d.atStartOfDay() : d.atTime(23, 59, 59);
                case 16:
                    return LocalDateTime.parse(s, MINUTE_FMT);
                default:
                    return LocalDateTime.parse(s.length() > 19 ? s.substring(0, 19) : s, SECOND_FMT);
            }
        } catch (Exception e) {
            throw new BizException(400, "时间格式不正确(应为 yyyy-MM-dd 或 yyyy-MM-dd HH:mm[:ss])");
        }
    }
}
