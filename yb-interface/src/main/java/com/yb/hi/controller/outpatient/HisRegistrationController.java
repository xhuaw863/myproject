package com.yb.hi.controller.outpatient;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.entity.outpatient.HisRegistration;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.outpatient.HisRegistrationService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * 门诊挂号/退号接口(院内编排 + 医保2201/2202) + 今日概览/多维统计/重新挂号/重复预检
 */
@RestController
@RequestMapping("/api/his/registration")
public class HisRegistrationController {

    private final HisRegistrationService service;

    public HisRegistrationController(HisRegistrationService service) {
        this.service = service;
    }

    @GetMapping("/page")
    public R<IPage<HisRegistration>> page(@RequestParam(defaultValue = "1") long page,
                                          @RequestParam(defaultValue = "15") long size,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                          @RequestParam(required = false) Integer status,
                                          @RequestParam(required = false) String keyword) {
        return R.ok(service.pageQuery(page, size, from, to, status, keyword));
    }

    /** 挂号(调用医保2201; 支持减免/支付方式/费别; 返回 sameDeptWarning 首日同科室提示) */
    @PostMapping("/register")
    public R<HisRegistration> register(@RequestParam Long patientId,
                                       @RequestParam Long scheduleId,
                                       @RequestParam(required = false) String medType,
                                       @RequestParam(required = false) String discountType,
                                       @RequestParam(required = false) String discountReason,
                                       @RequestParam(required = false) BigDecimal discountAmount,
                                       @RequestParam(required = false) String payMethod,
                                       @RequestParam(required = false) String payDetail,
                                       @RequestParam(required = false) String feeType) {
        return R.ok(service.register(patientId, scheduleId, medType, discountType, discountReason,
                discountAmount, payMethod, payDetail, feeType));
    }

    /** 退号(调用医保2202; needRefund=true 表示已支付非free需退费) */
    @PostMapping("/cancel")
    public R<Map<String, Object>> cancel(@RequestParam Long id,
                                         @RequestParam(required = false) String reason) {
        return R.ok(service.cancel(id, reason));
    }

    /** 重新挂号(仅已退号记录, 复用原挂号信息重走2201链路) */
    @PostMapping("/reRegister")
    public R<HisRegistration> reRegister(@RequestParam Long id) {
        return R.ok(service.reRegister(id));
    }

    /** 换号: 有效挂号迁移到目标号源(同一事务内先退原号2202再挂新号2201), 返回 {needRefund, refundAmount, newReg} */
    @PostMapping("/changeSlot")
    public R<Map<String, Object>> changeSlot(@RequestParam Long id,
                                             @RequestParam Long scheduleId,
                                             @RequestParam(required = false) String reason) {
        return R.ok(service.changeSlot(id, scheduleId, reason));
    }

    /** 今日挂号概览(挂号/退号/候诊/就诊/减免计数与金额) */
    @GetMapping("/todaySummary")
    public R<Map<String, Object>> todaySummary() {
        return R.ok(service.todaySummary());
    }

    /** 挂号多维统计(日期区间+科室/医师筛选; from/to 缺省当天) */
    @GetMapping("/stats")
    public R<Map<String, Object>> stats(@RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to,
                                        @RequestParam(required = false) Long deptId,
                                        @RequestParam(required = false) Long staffId) {
        return R.ok(service.stats(from, to, deptId, staffId));
    }

    /** 统计明细(分页; 支持日期/科室/医师/状态/关键字筛选) */
    @GetMapping("/statDetail")
    public R<IPage<Map<String, Object>>> statDetail(@RequestParam(required = false) String from,
                                                    @RequestParam(required = false) String to,
                                                    @RequestParam(required = false) Long deptId,
                                                    @RequestParam(required = false) Long staffId,
                                                    @RequestParam(required = false) Integer status,
                                                    @RequestParam(required = false) String keyword,
                                                    @RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return R.ok(service.statDetail(from, to, deptId, staffId, status, keyword, page, size));
    }

    /** 重复挂号预检: {duplicate, sameDept, sameDeptInfo} */
    @GetMapping("/checkDuplicate")
    public R<Map<String, Object>> checkDuplicate(@RequestParam Long patientId,
                                                 @RequestParam Long scheduleId) {
        return R.ok(service.checkDuplicate(patientId, scheduleId));
    }
}
