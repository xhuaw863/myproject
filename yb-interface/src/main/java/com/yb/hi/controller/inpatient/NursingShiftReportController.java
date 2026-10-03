package com.yb.hi.controller.inpatient;

import com.yb.hi.entity.inpatient.HisNursingShiftReport;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.inpatient.NursingShiftService;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 护理交班报告接口(护士站): 一键生成(汇总固化) / 交班 / 接班 / 详情 / 病区列表 / 最近一份。
 * 生命周期状态机(0草稿→1已交班→2已接班)、班次窗口(day 08:00-16:00 / evening 16:00-24:00 /
 * night 00:00-08:00)与归属机构校验均在 Service 内完成。
 * 日期参数口径: yyyy-MM-dd。
 */
@RestController
@RequestMapping("/api/his/inp/nursing/shift-report")
public class NursingShiftReportController {

    private final NursingShiftService shiftService;

    public NursingShiftReportController(NursingShiftService shiftService) {
        this.shiftService = shiftService;
    }

    /** 一键生成交班报告: 病区×班次×日期唯一, 草稿覆盖重算; shiftDate 缺省当日。 */
    @PostMapping("/generate")
    public R<HisNursingShiftReport> generate(@RequestParam Long wardId,
                                             @RequestParam String shiftType,
                                             @RequestParam(required = false) String shiftDate) {
        return R.ok(shiftService.generate(wardId, shiftType, parseDate(shiftDate)));
    }

    /** 交班: 草稿报告回填交班人(当前登录职工)/接班人, 置 status=1。 */
    @PutMapping("/{id}/handover")
    public R<HisNursingShiftReport> handover(@PathVariable Long id,
                                             @RequestParam(required = false) Long receiverId,
                                             @RequestParam(required = false) String receiverName) {
        return R.ok(shiftService.handover(id, receiverId, receiverName));
    }

    /** 接班: 已交班报告回填实际接班人(当前登录职工), 置 status=2。 */
    @PutMapping("/{id}/receive")
    public R<HisNursingShiftReport> receive(@PathVariable Long id) {
        return R.ok(shiftService.receive(id));
    }

    /** 交班报告详情(content 为汇总 JSON 字符串)。 */
    @GetMapping("/{id}")
    public R<HisNursingShiftReport> getById(@PathVariable Long id) {
        return R.ok(shiftService.getById(id));
    }

    /** 报告列表(按病区+日期范围): 交班日期倒序, start/end 可选(闭区间)。 */
    @GetMapping("/list")
    public R<List<HisNursingShiftReport>> list(@RequestParam Long wardId,
                                               @RequestParam(required = false) String start,
                                               @RequestParam(required = false) String end) {
        return R.ok(shiftService.listByWard(wardId, parseDate(start), parseDate(end)));
    }

    /** 最近一份报告(按病区): 交班日期倒序取第一, 无报告返回 null。 */
    @GetMapping("/latest")
    public R<HisNursingShiftReport> latest(@RequestParam Long wardId) {
        return R.ok(shiftService.getLatest(wardId));
    }

    /** 日期参数解析(yyyy-MM-dd, 空白返回 null)。 */
    private static LocalDate parseDate(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        } catch (Exception e) {
            throw new BizException(400, "日期格式不正确(应为 yyyy-MM-dd)");
        }
    }
}
