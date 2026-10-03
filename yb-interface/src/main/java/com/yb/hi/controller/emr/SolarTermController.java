package com.yb.hi.controller.emr;

import com.yb.hi.framework.common.R;
import com.yb.hi.service.emr.SolarTermService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 二十四节气接口(P8a-2): 体温单日期轴节气标注等场景按窗口拉取节气。
 *  - GET /api/emr/solar-term?start=2026-01-01&end=2026-01-31
 *    → data: [{date: "2026-01-05", name: "小寒"}, ...](按日期升序)
 * 认证由 AuthInterceptor(/api/**)把守; 节气为无状态公历推算, 不涉及租户数据。
 */
@RestController
@RequestMapping("/api/emr/solar-term")
public class SolarTermController {

    private final SolarTermService solarTermService;

    public SolarTermController(SolarTermService solarTermService) {
        this.solarTermService = solarTermService;
    }

    /** 查询日期范围内的节气列表(start/end 缺省为当天; 跨度上限 800 天防御异常入参) */
    @GetMapping
    public R<List<Map<String, Object>>> range(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        LocalDate s = start != null ? start : LocalDate.now();
        LocalDate e = end != null ? end : s;
        List<Map<String, Object>> out = new ArrayList<>();
        solarTermService.getSolarTermsInRange(s, e).forEach((date, name) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", date.toString());
            m.put("name", name);
            out.add(m);
        });
        return R.ok(out);
    }
}
