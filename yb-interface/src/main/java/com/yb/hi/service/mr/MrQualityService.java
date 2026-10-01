package com.yb.hi.service.mr;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yb.hi.entity.mr.HisMrCatalog;
import com.yb.hi.entity.mr.HisMrDiag;
import com.yb.hi.entity.mr.HisMrOper;
import com.yb.hi.mapper.mr.HisMrDiagMapper;
import com.yb.hi.mapper.mr.HisMrOperMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 病案首页编目质控校验(强制类/非强制类规则 + 字段定位)。
 * 与病案室编目/审核解耦于临床端病历质控(EmrQualityService): 此处专攻首页数据完整/逻辑/合理性。
 * 错误项携带 fieldKey 供前端"双击错误项→定位左侧字段"。强制类未清, 编目不允许定稿。
 */
@Service
public class MrQualityService {

    private final HisMrDiagMapper diagMapper;
    private final HisMrOperMapper operMapper;

    public MrQualityService(HisMrDiagMapper diagMapper, HisMrOperMapper operMapper) {
        this.diagMapper = diagMapper;
        this.operMapper = operMapper;
    }

    /** 单份病案首页校验, 返回错误项列表(空表示通过)。 */
    public List<Map<String, Object>> validate(HisMrCatalog catalog) {
        List<Map<String, Object>> errs = new ArrayList<>();
        Long cid = catalog.getId();
        List<HisMrDiag> diags = diagMapper.selectList(new QueryWrapper<HisMrDiag>()
                .eq("catalog_id", cid).orderByAsc("sort_no").orderByAsc("id"));
        List<HisMrOper> opers = operMapper.selectList(new QueryWrapper<HisMrOper>()
                .eq("catalog_id", cid).orderByAsc("sort_no").orderByAsc("id"));

        HisMrDiag mainDiag = null;
        List<HisMrDiag> injuries = new ArrayList<>();
        List<HisMrDiag> paths = new ArrayList<>();
        for (HisMrDiag d : diags) {
            if ("dmain".equals(d.getDiagType())) {
                mainDiag = d;
            } else if ("injure".equals(d.getDiagType())) {
                injuries.add(d);
            } else if ("path".equals(d.getDiagType())) {
                paths.add(d);
            }
        }

        // 强制类
        if (catalog.getDischargeDeptId() == null) {
            errs.add(err("强制", "MR_DEPT_REQUIRED", "catalog.dischargeDeptId", "出院科室不能为空"));
        }
        if (catalog.getAdmissionDate() == null || catalog.getDischargeDate() == null) {
            errs.add(err("强制", "MR_DATE_REQUIRED", "catalog.dischargeDate", "入院/出院日期不能为空"));
        }
        if (mainDiag == null || !StringUtils.hasText(mainDiag.getClinicalCode())) {
            errs.add(err("强制", "MR_MAIN_DIAG_REQUIRED", "diag.dmain", "缺少出院主要诊断(需填写国临版编码)"));
        }
        // 主要诊断 ICD 首字母 S/T 需损伤中毒外因
        if (mainDiag != null && startsWithAny(mainDiag.getClinicalCode(), "S", "T") && injuries.isEmpty()) {
            errs.add(err("强制", "MR_INJURY_REQUIRED", "diag.injure", "主要诊断为损伤中毒(S/T), 需录入损伤中毒外部原因"));
        }

        // 非强制类
        if (mainDiag != null && startsWithAny(mainDiag.getClinicalCode(), "C") && paths.isEmpty()) {
            errs.add(err("非强制", "MR_PATH_SUGGEST", "diag.path", "主要诊断为肿瘤(C), 建议补录病理诊断"));
        }
        if (mainDiag != null && StringUtils.hasText(mainDiag.getYbCode()) && !StringUtils.hasText(mainDiag.getClinicalCode())) {
            errs.add(err("非强制", "MR_YB_ORPHAN", "diag.dmain", "存在医保版编码但缺国临版编码, 请核对对照"));
        }
        if (!opers.isEmpty()) {
            boolean anyCode = false;
            for (HisMrOper o : opers) {
                if (StringUtils.hasText(o.getClinicalCode())) { anyCode = true; break; }
            }
            if (!anyCode) {
                errs.add(err("非强制", "MR_OPER_CODE", "oper", "存在手术记录但均未填写手术操作编码(ICD-9-CM-3)"));
            }
        }
        return errs;
    }

    private boolean startsWithAny(String code, String... prefixes) {
        if (!StringUtils.hasText(code)) {
            return false;
        }
        String c = code.trim().toUpperCase();
        for (String p : prefixes) {
            if (c.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> err(String category, String ruleCode, String fieldKey, String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ruleCategory", category);
        m.put("ruleCode", ruleCode);
        m.put("fieldKey", fieldKey);
        m.put("errorMsg", msg);
        m.put("resolved", 0);
        return m;
    }
}
