package com.yb.hi.controller.inpatient;

import com.yb.hi.dto.inpatient.NursingVitalSignDTO;
import com.yb.hi.entity.inpatient.HisNursingVitalSign;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.inpatient.NursingVitalSignService;
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
import java.util.Map;

/**
 * 生命体征接口(护士站): 单次录入(自动 MEWS 评分+异常预警) / 批量录入 / 列表 / 体温单绘图数据 / 当日待测统计。
 * 归属机构校验与预警 SSE 推送(NURSING_VITAL_ALERT)均在 Service 内完成。
 * 时间参数口径: yyyy-MM-dd 或 yyyy-MM-dd HH:mm[:ss](兼容 ISO 的 T 分隔)。
 */
@RestController
@RequestMapping("/api/his/inp/nursing/vital-sign")
public class NursingVitalSignController {

    private static final DateTimeFormatter MINUTE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter SECOND_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final NursingVitalSignService vitalSignService;

    public NursingVitalSignController(NursingVitalSignService vitalSignService) {
        this.vitalSignService = vitalSignService;
    }

    /** 录入单次体征: 校验上下限, 自动计算 MEWS, 命中预警时广播 SSE。 */
    @PostMapping({"", "/"})
    public R<HisNursingVitalSign> record(@RequestBody NursingVitalSignDTO dto) {
        return R.ok(vitalSignService.record(dto));
    }

    /** 批量录入(多床巡回): 逐条全量校验, 单条失败整体回滚(错误标注序号)。 */
    @PostMapping("/batch")
    public R<List<HisNursingVitalSign>> batchRecord(@RequestBody List<NursingVitalSignDTO> dtos) {
        return R.ok(vitalSignService.batchRecord(dtos));
    }

    /** 体征列表(按就诊): start/end 可选(闭区间), 测量时间倒序。 */
    @GetMapping("/list")
    public R<List<HisNursingVitalSign>> list(@RequestParam Long inpVisitId,
                                             @RequestParam(required = false) String start,
                                             @RequestParam(required = false) String end) {
        return R.ok(vitalSignService.listByVisit(inpVisitId,
                parseTime(start, true), parseTime(end, false)));
    }

    /** 体温单绘图数据(默认 7 天): vitals(日×时段) + events(入院/手术/转科) + 窗口端点/手术日/在院天数。 */
    @GetMapping("/chart-data")
    public R<Map<String, Object>> chartData(@RequestParam Long inpVisitId,
                                            @RequestParam(required = false, defaultValue = "7") Integer days) {
        return R.ok(vitalSignService.chartData(inpVisitId, days));
    }

    /** 当日待测统计: 在院且今日未录入体征的患者数, wardId 可选过滤病区。 */
    @GetMapping("/pending-count")
    public R<Integer> pendingCount(@RequestParam(required = false) Long wardId) {
        return R.ok(vitalSignService.countPendingToday(wardId));
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
