package com.yb.hi.controller.inpatient;

import com.alibaba.fastjson2.JSON;
import com.yb.hi.dto.inpatient.SurgeryReportQueryDTO;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.inpatient.SurgeryReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * 手麻统计报表接口(P2a): 手术量/麻醉/时长/费用/质量/工作台KPI 六类 + 报表快照落盘。
 * 机构隔离: 查询走 scopeOrgId(牵头可跨机构汇总, 非牵头锁定本机构)。
 */
@RestController
@RequestMapping("/api/his/surgery-report")
public class SurgeryReportController {

    private final SurgeryReportService reportService;
    private final OrgAccessGuard guard;

    public SurgeryReportController(SurgeryReportService reportService, OrgAccessGuard guard) {
        this.reportService = reportService;
        this.guard = guard;
    }

    /** 手术量(总量+级别/科室/术者/日期分布+申请漏斗) */
    @GetMapping("/volume")
    public R<Map<String, Object>> volume(SurgeryReportQueryDTO q,
                                         @RequestParam(required = false) Long orgId) {
        return reportService.volume(q, guard.scopeOrgId(orgId));
    }

    /** 麻醉分布(麻醉类型/ASA + 平均麻醉时长) */
    @GetMapping("/anesthesia")
    public R<Map<String, Object>> anesthesia(SurgeryReportQueryDTO q,
                                             @RequestParam(required = false) Long orgId) {
        return reportService.anesthesia(q, guard.scopeOrgId(orgId));
    }

    /** 手术时长(台内/切口平均分钟 + 按级别分档) */
    @GetMapping("/duration")
    public R<Map<String, Object>> duration(SurgeryReportQueryDTO q,
                                           @RequestParam(required = false) Long orgId) {
        return reportService.duration(q, guard.scopeOrgId(orgId));
    }

    /** 费用统计(按费用分类汇总 + 例均费 + 术式TOP) */
    @GetMapping("/fee")
    public R<Map<String, Object>> fee(SurgeryReportQueryDTO q,
                                      @RequestParam(required = false) Long orgId) {
        return reportService.fee(q, guard.scopeOrgId(orgId));
    }

    /** 质量指标(切口分布 + 取消率 + 非计划二次手术) */
    @GetMapping("/quality")
    public R<Map<String, Object>> quality(SurgeryReportQueryDTO q,
                                          @RequestParam(required = false) Long orgId) {
        return reportService.quality(q, guard.scopeOrgId(orgId));
    }

    /** 工作台KPI(今日台次/在术/待安排/完成率/取消率/三四级占比) */
    @GetMapping("/dashboard")
    public R<Map<String, Object>> dashboard(SurgeryReportQueryDTO q,
                                            @RequestParam(required = false) Long orgId) {
        return reportService.dashboard(q, guard.scopeOrgId(orgId));
    }

    /** 设备/机房利用率(P4b): 按手术间/资源类型统计开机台次/占用时长/利用率(moduleType 可选限定一体化模块) */
    @GetMapping("/device-utilization")
    public R<Map<String, Object>> deviceUtilization(SurgeryReportQueryDTO q,
                                                    @RequestParam(required = false) Long orgId) {
        return reportService.deviceUtilization(q, guard.scopeOrgId(orgId));
    }

    /** 报表快照落盘(按类型重算后存 his_inp_report_snapshot, report_type 6-11; date 缺省今天)。 */
    @PostMapping("/{type}/snapshot")
    public R<Void> snapshot(@PathVariable String type, SurgeryReportQueryDTO q,
                            @RequestParam(required = false) Long orgId,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        String t = type == null ? "" : type.trim().toLowerCase();
        Long scoped = guard.scopeOrgId(orgId);
        R<Map<String, Object>> data;
        int rt;
        switch (t) {
            case "volume":
                data = reportService.volume(q, scoped);
                rt = SurgeryReportService.RT_VOLUME;
                break;
            case "anesthesia":
                data = reportService.anesthesia(q, scoped);
                rt = SurgeryReportService.RT_ANESTHESIA;
                break;
            case "duration":
                data = reportService.duration(q, scoped);
                rt = SurgeryReportService.RT_DURATION;
                break;
            case "fee":
                data = reportService.fee(q, scoped);
                rt = SurgeryReportService.RT_FEE;
                break;
            case "quality":
                data = reportService.quality(q, scoped);
                rt = SurgeryReportService.RT_QUALITY;
                break;
            case "dashboard":
                data = reportService.dashboard(q, scoped);
                rt = SurgeryReportService.RT_DASHBOARD;
                break;
            case "device":
            case "device-utilization":
                data = reportService.deviceUtilization(q, scoped);
                rt = SurgeryReportService.RT_DEVICE;
                break;
            default:
                throw new BizException(400, "未知报表类型: " + type);
        }
        LocalDate snapDate = date != null ? date : LocalDate.now();
        return reportService.saveSnapshot(scoped, rt, snapDate, q == null ? null : q.getDeptId(),
                JSON.toJSONString(data.getData()));
    }
}
