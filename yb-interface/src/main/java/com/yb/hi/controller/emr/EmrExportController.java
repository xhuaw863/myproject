package com.yb.hi.controller.emr;

import com.yb.hi.service.emr.EmrExportService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 病历互操作导出接口(Phase D): 结构化病历导出为 FHIR R4 Document Bundle 或 WS/T 500 CDA/XML。
 * scope 1住院(定位病历ID) / 2门诊(定位就诊ID); format=fhir|cda(默认 fhir)。
 * 返回原始文档体(非统一 R 包装), 便于前端 blob 下载与外部系统直接消费。
 */
@RestController
@RequestMapping("/api/emr/export")
public class EmrExportController {

    private final EmrExportService exportService;

    public EmrExportController(EmrExportService exportService) {
        this.exportService = exportService;
    }

    /** GET /api/emr/export/{scope}/{recordId}?format=fhir|cda */
    @GetMapping("/{scope}/{recordId}")
    public ResponseEntity<String> export(@PathVariable Integer scope,
                                         @PathVariable Long recordId,
                                         @RequestParam(defaultValue = "fhir") String format) {
        if ("cda".equalsIgnoreCase(format)) {
            String xml = exportService.exportCda(scope, recordId);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/xml;charset=UTF-8"))
                    .header("Content-Disposition", disposition("cda", scope, recordId))
                    .body(xml);
        }
        String json = exportService.exportFhir(scope, recordId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/fhir+json;charset=UTF-8"))
                .header("Content-Disposition", disposition("fhir", scope, recordId))
                .body(json);
    }

    private String disposition(String fmt, int scope, Long id) {
        String kind = scope == 2 ? "opd" : "ipd";
        return "attachment; filename=emr_" + kind + "_" + id + "." + fmt;
    }
}
