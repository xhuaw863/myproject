package com.yb.hi.controller.outpatient;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.entity.outpatient.HisRegistration;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.outpatient.HisRegistrationService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * 门诊挂号/退号接口(院内编排 + 医保2201/2202)
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

    /** 挂号(调用医保2201) */
    @PostMapping("/register")
    public R<HisRegistration> register(@RequestParam Long patientId,
                                       @RequestParam Long scheduleId,
                                       @RequestParam(required = false) String medType) {
        return R.ok(service.register(patientId, scheduleId, medType));
    }

    /** 退号(调用医保2202) */
    @PostMapping("/cancel")
    public R<Void> cancel(@RequestParam Long id,
                          @RequestParam(required = false) String reason) {
        service.cancel(id, reason);
        return R.ok();
    }
}
