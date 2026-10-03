package com.yb.hi.controller.emr;

import com.yb.hi.framework.common.R;
import com.yb.hi.service.emr.TcmDiagAssembleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 中医诊断拼装接口(P8a):
 *  - GET  /api/emr/tcm/search-diag?keyword=       搜索中医诊断(dict_type=tcm, 名称/拼音简码/编码, LIMIT 50);
 *  - GET  /api/emr/tcm/search-syndrome?keyword=   搜索中医证候(dict_type=symp, LIMIT 50);
 *  - GET  /api/emr/tcm/suggest-syndrome?diagCode= 按中医诊断推荐常见证候(标准对照表→院内字典→兜底前20);
 *  - POST /api/emr/tcm/assemble                   拼装"诊断+证候"并按 targetType 写入:
 *         body: {tcmDiagCode, syndromeCode, targetType: "casePage"|"admission"(可空仅拼装返回), targetId, tcmDiags?}
 * 认证由 AuthInterceptor(/api/**)把守; 租户隔离由 TcmDiagAssembleService 显式携带 tenant_id 完成。
 */
@RestController
@RequestMapping("/api/emr/tcm")
public class TcmDiagAssembleController {

    private final TcmDiagAssembleService tcmService;

    public TcmDiagAssembleController(TcmDiagAssembleService tcmService) {
        this.tcmService = tcmService;
    }

    /** 搜索中医诊断 */
    @GetMapping("/search-diag")
    public R<List<Map<String, Object>>> searchDiag(@RequestParam String keyword) {
        return R.ok(tcmService.searchTcmDiag(keyword));
    }

    /** 搜索中医证候 */
    @GetMapping("/search-syndrome")
    public R<List<Map<String, Object>>> searchSyndrome(@RequestParam String keyword) {
        return R.ok(tcmService.searchSyndrome(keyword));
    }

    /** 按中医诊断推荐常见证候 */
    @GetMapping("/suggest-syndrome")
    public R<List<Map<String, Object>>> suggestSyndrome(@RequestParam String diagCode) {
        return R.ok(tcmService.suggestSyndromes(diagCode));
    }

    /**
     * 拼装"诊断+证候"文本; targetType=casePage 写入病案首页(his_case_front_page.tcm_diag),
     * targetType=admission 写入住院入院诊断(his_inp_diagnosis), 缺省仅返回拼装结果不落库。
     * tcmDiags 可传多条组合一次落库; 缺省用本次单条拼装结果。
     */
    @PostMapping("/assemble")
    public R<Map<String, Object>> assemble(@RequestBody Map<String, Object> body) {
        String tcmDiagCode = str(body.get("tcmDiagCode"));
        String syndromeCode = str(body.get("syndromeCode"));
        String targetType = str(body.get("targetType"));
        Long targetId = toLong(body.get("targetId"));
        Map<String, Object> assembled = tcmService.assembleDiagnosis(tcmDiagCode, syndromeCode);
        if ("casePage".equals(targetType) || "admission".equals(targetType)) {
            List<Map<String, Object>> items = items(body.get("tcmDiags"));
            if (items.isEmpty()) {
                items.add(assembled);
            }
            if ("casePage".equals(targetType)) {
                tcmService.assembleToCasePage(targetId, items);
            } else {
                tcmService.assembleToAdmission(targetId, items);
            }
        }
        return R.ok(assembled);
    }

    /** 请求体 tcmDiags 数组归一(非数组/空则返回空表, 由调用方回退单条拼装结果) */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Object o) {
        List<Map<String, Object>> ret = new ArrayList<>();
        if (o instanceof List) {
            for (Object x : (List<Object>) o) {
                if (x instanceof Map) {
                    ret.add((Map<String, Object>) x);
                }
            }
        }
        return ret;
    }

    private static Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : Long.valueOf(s);
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }
}
