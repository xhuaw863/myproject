package com.yb.hi.controller.inpatient;

import com.yb.hi.dto.inpatient.EmrMacroDTO;
import com.yb.hi.entity.inpatient.HisEmrMacro;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.inpatient.EmrMacroService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 病历宏变量接口: 宏维护(仅牵头机构管理员) + 就诊宏批量解析 / 文本占位符替换。
 * 30 个标准宏由 EmrMacroService 启动时按租户幂等播种(@Order(9))。
 */
@RestController
@RequestMapping("/api/his/emr/macro")
public class EmrMacroController {

    private final EmrMacroService macroService;
    private final OrgAccessGuard guard;

    public EmrMacroController(EmrMacroService macroService, OrgAccessGuard guard) {
        this.macroService = macroService;
        this.guard = guard;
    }

    /** 宏列表(dataSource 可选筛选: 1患者 2就诊 3诊断 4医嘱 5检验 6体征) */
    @GetMapping("/list")
    public R<List<HisEmrMacro>> list(@RequestParam(required = false) Integer dataSource) {
        return macroService.listMacros(dataSource);
    }

    /** 新建宏变量(仅牵头机构管理员) */
    @PostMapping
    public R<HisEmrMacro> create(@RequestBody EmrMacroDTO dto) {
        guard.requireLeadOrg("仅牵头机构管理员可维护病历宏变量");
        return macroService.createMacro(dto);
    }

    /** 更新宏变量(仅牵头机构管理员) */
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody EmrMacroDTO dto) {
        guard.requireLeadOrg("仅牵头机构管理员可维护病历宏变量");
        return macroService.updateMacro(id, dto);
    }

    /** 删除宏变量(逻辑删除; 仅牵头机构管理员) */
    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable Long id) {
        guard.requireLeadOrg("仅牵头机构管理员可维护病历宏变量");
        return macroService.removeMacro(id);
    }

    /** 批量解析就诊宏变量: body={visitId, macroCodes(空则全部)} */
    @PostMapping("/resolve")
    public R<Map<String, String>> resolve(@RequestBody Map<String, Object> body) {
        Long visitId = toLong(body.get("visitId"));
        List<String> codes = toStringList(body.get("macroCodes"));
        return macroService.resolveMacros(visitId, codes);
    }

    /** 解析文本中的 {macroCode} 占位符(未知宏保留原样): body={visitId, text} */
    @PostMapping("/resolve-text")
    public R<String> resolveText(@RequestBody Map<String, Object> body) {
        Long visitId = toLong(body.get("visitId"));
        Object text = body.get("text");
        return macroService.resolveText(visitId, text == null ? null : String.valueOf(text));
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

    @SuppressWarnings("unchecked")
    private static List<String> toStringList(Object v) {
        if (!(v instanceof List)) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (Object o : (List<Object>) v) {
            if (o != null && !String.valueOf(o).trim().isEmpty()) {
                out.add(String.valueOf(o).trim());
            }
        }
        return out;
    }
}
