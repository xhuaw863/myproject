package com.yb.hi.service.pharmacy;

import com.yb.hi.framework.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 合理用药自动审查(P2, Mock 起步): 对接真实合理用药系统前的本地规则预检。
 * 审查维度: 单次剂量上限 / 妊娠禁用(X级) / 需皮试药品 / 高警示药品 / 抗菌药物分级提示 /
 *           重复用药(同通用名多行) / 常见配伍相互作用(Mock 内置药名对)。
 * 结论口径: level = block(存在硬性阻断项) > warn(仅提示项) > pass(无异常)。
 * 真实对接: 后续替换 review() 内部为外部合理用药接口调用, 出参结构保持不变(留接口位)。
 */
@Slf4j
@Service
public class RationalMedicationService {

    /** 剂量文本首个数值提取 */
    private static final Pattern NUM = Pattern.compile("(\\d+(?:\\.\\d+)?)");

    /** Mock 内置常见配伍相互作用(通用名关键字两两命中即提示) */
    private static final String[][] INTERACTION_PAIRS = {
            {"华法林", "阿司匹林"}, {"华法林", "布洛芬"}, {"地高辛", "氨氯地平"},
            {"氯吡格雷", "奥美拉唑"}, {"卡托普利", "螺内酯"}, {"辛伐他汀", "氨氯地平"},
    };

    /** 高警示药品关键字(管理类别名称或通用名命中) */
    private static final String[] HIGH_ALERT_KEYS = {"麻醉", "精神", "毒性", "高危", "胰岛素", "肝素"};

    private final JdbcTemplate jdbcTemplate;

    public RationalMedicationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }

    /**
     * 审查处方合理用药: 返回 {level: pass/warn/block, blocks:[...], warns:[...], items:[...逐药]}。
     * 不落库, 供审核工作台展示与自动审核决策。
     */
    public Map<String, Object> review(Long prescriptionId) {
        if (prescriptionId == null) {
            throw new com.yb.hi.framework.common.BizException(400, "处方ID不能为空");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT pi.drug_id AS drugId, pi.item_name AS itemName, pi.spec, pi.quantity, pi.dosage, pi.dosage_unit AS dosageUnit,"
                        + " pi.frequency, pi.days, d.generic_name AS genericName, d.max_qty_once AS maxQtyOnce,"
                        + " d.skin_test_flag AS skinTestFlag, d.drug_class_name AS drugClassName, d.abx_grade_name AS abxGradeName,"
                        + " d.preg_class AS pregClass"
                        + " FROM his_prescription_item pi LEFT JOIN his_drug_catalog d ON pi.drug_id = d.id AND d.deleted = 0"
                        + " WHERE pi.prescription_id = ? AND pi.tenant_id = ? AND pi.deleted = 0 ORDER BY pi.id",
                prescriptionId, tenantId());

        List<String> blocks = new ArrayList<>();
        List<String> warns = new ArrayList<>();
        List<Map<String, Object>> lines = new ArrayList<>();
        List<String> genericNames = new ArrayList<>();
        Set<String> dupGuard = new HashSet<>();

        for (Map<String, Object> r : rows) {
            String dispName = firstNonBlank(str(r.get("genericName")), str(r.get("itemName")));
            String name = dispName == null ? "未知药品" : dispName;
            if (dispName != null) {
                genericNames.add(dispName);
            }
            List<String> lineIssues = new ArrayList<>();

            // 1. 单次剂量上限(管制药品 max_qty_once, 超出为硬阻断)
            BigDecimal maxOnce = toBd(r.get("maxQtyOnce"));
            BigDecimal dose = parseNumber(str(r.get("dosage")));
            if (maxOnce != null && maxOnce.signum() > 0 && dose != null && dose.compareTo(maxOnce) > 0) {
                String msg = "剂量超限: " + name + " 单次剂量" + dose.toPlainString() + " 超过上限" + maxOnce.toPlainString();
                blocks.add(msg);
                lineIssues.add(msg);
            }

            // 2. 妊娠禁用 X 级(硬阻断)
            if ("X".equalsIgnoreCase(str(r.get("pregClass")))) {
                String msg = "妊娠禁用(X级): " + name + " 妊娠期禁忌";
                blocks.add(msg);
                lineIssues.add(msg);
            }

            // 3. 需皮试药品(提示, 由发药前皮试校验兑底)
            if (isOne(r.get("skinTestFlag"))) {
                String msg = "需皮试: " + name + " 应确认皮试阴性后方可发药";
                warns.add(msg);
                lineIssues.add(msg);
            }

            // 4. 高警示药品(提示双人核对)
            if (isHighAlert(name, str(r.get("drugClassName")))) {
                String msg = "高警示药品: " + name + " 建议执行双人核对";
                warns.add(msg);
                lineIssues.add(msg);
            }

            // 5. 抗菌药物分级(级别含"特殊使用/限制使用"提示权限)
            String abx = str(r.get("abxGradeName"));
            if (abx != null && (abx.contains("特殊") || abx.contains("限制"))) {
                String msg = "抗菌药物分级: " + name + "(" + abx + ") 须对应处方权限";
                warns.add(msg);
                lineIssues.add(msg);
            }

            // 6. 重复用药(同通用名多行)
            if (dispName != null && !dupGuard.add(dispName)) {
                String msg = "重复用药: " + name + " 处方内存在多行相同通用名";
                warns.add(msg);
                lineIssues.add(msg);
            }

            Map<String, Object> line = new LinkedHashMap<>();
            line.put("drugId", r.get("drugId"));
            line.put("drugName", name);
            line.put("spec", r.get("spec"));
            line.put("quantity", r.get("quantity"));
            line.put("issues", lineIssues);
            lines.add(line);
        }

        // 7. 配伍相互作用(Mock 关键字命中)
        for (String[] pair : INTERACTION_PAIRS) {
            if (containsAny(genericNames, pair[0]) && containsAny(genericNames, pair[1])) {
                warns.add("配伍相互作用: " + pair[0] + " 与 " + pair[1] + " 联用需评估风险(Mock 规则)");
            }
        }

        String level = !blocks.isEmpty() ? "block" : (!warns.isEmpty() ? "warn" : "pass");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("prescriptionId", prescriptionId);
        out.put("level", level);
        out.put("passed", blocks.isEmpty());
        out.put("blocks", blocks);
        out.put("warns", warns);
        out.put("items", lines);
        return out;
    }

    /* ================= 内部工具 ================= */

    private static boolean isHighAlert(String name, String className) {
        String s = (name == null ? "" : name) + (className == null ? "" : className);
        for (String k : HIGH_ALERT_KEYS) {
            if (s.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(List<String> names, String key) {
        for (String n : names) {
            if (n != null && n.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private static BigDecimal parseNumber(String s) {
        if (s == null) {
            return null;
        }
        Matcher m = NUM.matcher(s);
        if (m.find()) {
            try {
                return new BigDecimal(m.group(1));
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    private static boolean isOne(Object v) {
        return v instanceof Number && ((Number) v).intValue() == 1;
    }

    private static BigDecimal toBd(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        if (v instanceof Number) {
            return BigDecimal.valueOf(((Number) v).doubleValue());
        }
        try {
            return new BigDecimal(v.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.trim().isEmpty()) {
            return a;
        }
        return b != null && !b.trim().isEmpty() ? b : null;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
