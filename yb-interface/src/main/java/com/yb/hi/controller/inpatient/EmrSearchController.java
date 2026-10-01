package com.yb.hi.controller.inpatient;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.entity.inpatient.HisEmrElement;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.emr.EmrSearchService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 病历数据元检索/透视接口(Phase C): 字段级检索 + 单份病历病案首页/上报数据集透视 + 病历维度去重列表。
 * 数据由 EmrElementService 在病历保存/提交时抽取落 his_emr_element。
 */
@RestController
@RequestMapping("/api/his/emr/element")
public class EmrSearchController {

    private final EmrSearchService searchService;

    public EmrSearchController(EmrSearchService searchService) {
        this.searchService = searchService;
    }

    /** 要素扁平分页检索(字段/术语/文本/数值区间/日期区间 + 患者/就诊/记录/科室/医生维度) */
    @GetMapping("/search")
    public R<IPage<HisEmrElement>> search(
            @RequestParam(required = false) Integer scope,
            @RequestParam(required = false) Long patientId,
            @RequestParam(required = false) Long visitId,
            @RequestParam(required = false) Long recordId,
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) Long doctorId,
            @RequestParam(required = false) Long orgId,
            @RequestParam(required = false) String fieldKey,
            @RequestParam(required = false) String termCode,
            @RequestParam(required = false) String valueText,
            @RequestParam(required = false) BigDecimal valueNumMin,
            @RequestParam(required = false) BigDecimal valueNumMax,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate valueDateStart,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate valueDateEnd,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return searchService.searchElements(scope, patientId, visitId, recordId, deptId, doctorId, orgId,
                fieldKey, termCode, valueText, valueNumMin, valueNumMax, valueDateStart, valueDateEnd, page, size);
    }

    /** 单份病历要素透视: scope=2 传 visitId, scope=1 传 recordId */
    @GetMapping("/pivot")
    public R<Map<String, Object>> pivot(
            @RequestParam(required = false) Integer scope,
            @RequestParam(required = false) Long visitId,
            @RequestParam(required = false) Long recordId) {
        return searchService.pivot(scope, visitId, recordId);
    }

    /** 病历维度去重列表(供上报/批量透视选择) */
    @GetMapping("/visits")
    public R<List<Map<String, Object>>> visits(
            @RequestParam(required = false) Integer scope,
            @RequestParam(required = false) Long orgId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate since) {
        return searchService.distinctVisits(scope, orgId, since);
    }
}
