package com.yb.hi.controller.doctor;

import com.yb.hi.framework.common.R;
import com.yb.hi.service.doctor.HisVisitService;
import com.yb.hi.service.doctor.OutpEmrService;
import com.yb.hi.service.inpatient.EmrVersionService;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 门诊结构化病历接口(Phase B): 复用住院侧 EMR 引擎的版本快照/字段定义, 增门诊专用完整性质控与门诊宏解析。
 * 结构化病历正文的保存/回显走既有 /api/his/visit/save-draft 与 /api/his/visit/detail(visit.structure);
 * 字段定义走既有 /api/his/emr/template/{id}/defs; 本控制器负责版本/质控/宏三项门诊扩展能力。
 * 归属判权统一复用医生站就诊级科室口径 HisVisitService.requireVisitScope。
 */
@RestController
@RequestMapping("/api/his/outp/emr")
public class OutpEmrController {

    /** 病历作用域: 2=门诊 */
    private static final int SCOPE_OUTP = 2;

    private final HisVisitService visitService;
    private final OutpEmrService outpEmrService;
    private final EmrVersionService emrVersionService;

    public OutpEmrController(HisVisitService visitService, OutpEmrService outpEmrService,
                             EmrVersionService emrVersionService) {
        this.visitService = visitService;
        this.outpEmrService = outpEmrService;
        this.emrVersionService = emrVersionService;
    }

    /** 门诊结构化病历版本列表(scope=2, refId=visitId) */
    @GetMapping("/versions")
    public R<List<Map<String, Object>>> versions(@RequestParam Long visitId) {
        visitService.requireVisitScope(visitId);
        return R.ok(emrVersionService.listVersionsByScope(SCOPE_OUTP, visitId));
    }

    /** 指定版本详情(含 content/structure 双快照正文), 按版本主键取, 门诊住院通用 */
    @GetMapping("/version/{id}")
    public R<Map<String, Object>> version(@PathVariable Long id) {
        return R.ok(emrVersionService.getVersion(id));
    }

    /** 两版本行级差异对比 */
    @GetMapping("/version-diff")
    public R<Map<String, Object>> versionDiff(@RequestParam Long v1, @RequestParam Long v2) {
        return R.ok(emrVersionService.diffVersions(v1, v2));
    }

    /** 门诊结构化病历完整性质控(即时评分, 不落库) */
    @PostMapping("/quality")
    public R<Map<String, Object>> quality(@RequestParam Long visitId) {
        visitService.requireVisitScope(visitId);
        return R.ok(outpEmrService.evaluateCompleteness(visitId));
    }

    /** 门诊宏解析: body {macroCodes:[...]} 可空则解析全部; 返回 {macroCode: 值} */
    @PostMapping("/macro/resolve")
    public R<Map<String, String>> macro(@RequestParam Long visitId,
                                        @RequestBody(required = false) Map<String, Object> body) {
        visitService.requireVisitScope(visitId);
        List<String> codes = null;
        if (body != null && body.get("macroCodes") instanceof List) {
            codes = new ArrayList<>();
            for (Object o : (List<?>) body.get("macroCodes")) {
                if (o != null) {
                    codes.add(String.valueOf(o));
                }
            }
        }
        return outpEmrService.resolveMacros(visitId, codes);
    }

    /** P3 Tiptap 双轨: 门诊宏解析 Tiptap 文档(body 为明文 Tiptap JSON), emrMacro 节点回写 resolvedValue / {macroCode} 占位符原位替换 */
    @PostMapping("/resolve-tiptap-macros")
    public R<String> resolveTiptapMacros(@RequestParam Long visitId, @RequestBody String tiptapJson) {
        visitService.requireVisitScope(visitId);
        return R.ok(outpEmrService.resolveMacrosInTiptap(tiptapJson, visitId));
    }
}
