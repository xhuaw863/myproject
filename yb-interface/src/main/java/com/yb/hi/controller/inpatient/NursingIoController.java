package com.yb.hi.controller.inpatient;

import com.yb.hi.dto.inpatient.NursingIoRecordDTO;
import com.yb.hi.entity.inpatient.HisNursingIoRecord;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.inpatient.NursingIoService;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
 * 护理出入量接口(护士站): 单条录入 / 批量录入 / 列表 / 24小时汇总 / 自定义时段统计 / 删除。
 * 归属机构校验在 Service 内完成; 时间参数口径: yyyy-MM-dd 或 yyyy-MM-dd HH:mm[:ss](兼容 ISO 的 T 分隔)。
 */
@RestController
@RequestMapping("/api/his/inp/nursing/io")
public class NursingIoController {

    private static final DateTimeFormatter MINUTE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter SECOND_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final NursingIoService ioService;

    public NursingIoController(NursingIoService ioService) {
        this.ioService = ioService;
    }

    /** 录入单条出入量(1入量 2出量; 项目分类 infusion/oral/urine/drain/gastric/vomit/stool/blood/other)。 */
    @PostMapping({"", "/"})
    public R<HisNursingIoRecord> record(@RequestBody NursingIoRecordDTO dto) {
        return R.ok(ioService.record(dto));
    }

    /** 批量录入(逐项补录/多床巡回): 逐条全量校验, 单条失败整体回滚(错误标注序号)。 */
    @PostMapping("/batch")
    public R<List<HisNursingIoRecord>> batchRecord(@RequestBody List<NursingIoRecordDTO> dtos) {
        return R.ok(ioService.batchRecord(dtos));
    }

    /** 出入量列表(按就诊): start/end 可选(闭区间), 记录时间倒序。 */
    @GetMapping("/list")
    public R<List<HisNursingIoRecord>> list(@RequestParam Long inpVisitId,
                                            @RequestParam(required = false) String start,
                                            @RequestParam(required = false) String end) {
        return R.ok(ioService.listByVisit(inpVisitId,
                parseTime(start, true), parseTime(end, false)));
    }

    /** 24小时出入量汇总(按自然日, date 缺省当日): 总入量/总出量/差额 + 按项目分类分组明细。 */
    @GetMapping("/summary")
    public R<Map<String, Object>> summary(@RequestParam Long inpVisitId,
                                          @RequestParam(required = false) String date) {
        return R.ok(ioService.summary24h(inpVisitId, parseDate(date)));
    }

    /** 自定义时段出入量统计: start/end 必填(闭区间), 返回结构同 24 小时汇总。 */
    @GetMapping("/summary-range")
    public R<Map<String, Object>> summaryRange(@RequestParam Long inpVisitId,
                                               @RequestParam String start,
                                               @RequestParam String end) {
        return R.ok(ioService.summaryRange(inpVisitId,
                parseTime(start, true), parseTime(end, false)));
    }

    /** 删除单条出入量记录(逻辑删除, 误录/补录场景)。 */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        ioService.delete(id);
        return R.ok();
    }

    /** 日期参数解析(yyyy-MM-dd, 空白返回当日)。 */
    private static LocalDate parseDate(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (Exception e) {
            throw new BizException(400, "日期格式不正确(应为 yyyy-MM-dd)");
        }
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
