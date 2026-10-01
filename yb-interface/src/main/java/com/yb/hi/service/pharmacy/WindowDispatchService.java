package com.yb.hi.service.pharmacy;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.pharmacy.HisPharmacyWindow;
import com.yb.hi.entity.pharmacy.HisWindowDeptRule;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.mapper.pharmacy.HisPharmacyWindowMapper;
import com.yb.hi.mapper.pharmacy.HisWindowDeptRuleMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 智能分窗调度服务(P1 发药窗口子系统核心)。
 * 输入(药房 + 处方特殊标志 + 开单科室 + 中西药类型) → 输出窗口ID, 优先级:
 *   1. 特殊标志命中窗口类型定向(代煎/快递/精麻/毒性; 中药→草药窗)
 *   2. 开单科室规则命中(his_window_dept_rule)
 *   3. 开启态窗口按各自 assign_strategy 取"最空闲"(剩余最小=当日待发计数最小; 平均=当日总发药最小; 定向窗不入普通池)
 *   4. 全部关闭取本药房兜底默认窗口(is_default=1)
 *   5. 均无 → 返回 null(发药回落既有流程, 不写窗口)
 * 说明: his_pharmacy_window/his_window_dept_rule 为租户表, Mapper 走租户插件;
 *       当日待发/总发计数基于 his_dispense 走 JdbcTemplate 原生 SQL(需显式 tenant_id)。
 */
@Slf4j
@Service
public class WindowDispatchService {

    /** 特殊窗口类型(与 his_pharmacy_window.window_type 对齐) */
    public static final String WT_WEST = "WEST";
    public static final String WT_CHINESE_PATENT = "CHINESE_PATENT";
    public static final String WT_HERB = "HERB";
    public static final String WT_NARCOTIC = "NARCOTIC";
    public static final String WT_TOXIC = "TOXIC";
    public static final String WT_DECOCT = "DECOCT";
    public static final String WT_EXPRESS = "EXPRESS";

    /** 定向/特殊投递类型(命中处方特殊标志才投递, 不入普通轮询池) */
    private static final List<String> DIRECTED_TYPES =
            java.util.Arrays.asList(WT_NARCOTIC, WT_TOXIC, WT_DECOCT, WT_EXPRESS);

    private final HisPharmacyWindowMapper windowMapper;
    private final HisWindowDeptRuleMapper deptRuleMapper;
    private final JdbcTemplate jdbcTemplate;

    public WindowDispatchService(HisPharmacyWindowMapper windowMapper, HisWindowDeptRuleMapper deptRuleMapper,
                                 JdbcTemplate jdbcTemplate) {
        this.windowMapper = windowMapper;
        this.deptRuleMapper = deptRuleMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 分配发药窗口。
     *
     * @param pharmacyId   发药药房ID
     * @param deptId       开单科室ID(可空)
     * @param rxType       处方类型(西药/中药, 用于中药→草药窗回落)
     * @param specialTypes 处方特殊标志集合(DECOCT/EXPRESS/NARCOTIC/TOXIC, 可空)
     * @return 命中的窗口ID; 无窗口配置返回 null
     */
    public Long assignWindow(Long pharmacyId, Long deptId, String rxType, Set<String> specialTypes) {
        if (pharmacyId == null) {
            return null;
        }
        List<HisPharmacyWindow> all = windowMapper.selectList(Wrappers.<HisPharmacyWindow>lambdaQuery()
                .eq(HisPharmacyWindow::getPharmacyId, pharmacyId)
                .eq(HisPharmacyWindow::getStatus, 1)
                .orderByAsc(HisPharmacyWindow::getSortNo)
                .orderByAsc(HisPharmacyWindow::getId));
        if (all.isEmpty()) {
            return null;
        }

        // 1. 特殊标志定向(代煎/快递/精麻/毒性); 中药无显式标志时回落草药窗
        java.util.Set<String> wanted = new java.util.LinkedHashSet<>();
        if (specialTypes != null) {
            for (String t : specialTypes) {
                if (t != null && DIRECTED_TYPES.contains(t.trim().toUpperCase())) {
                    wanted.add(t.trim().toUpperCase());
                }
            }
        }
        if (wanted.isEmpty() && rxType != null && rxType.contains("中药")) {
            wanted.add(WT_HERB);
        }
        for (String wt : wanted) {
            Long hit = pickByType(all, wt, true);
            if (hit != null) {
                log.info("分窗命中特殊定向: pharmacyId={}, type={}, windowId={}", pharmacyId, wt, hit);
                return hit;
            }
        }

        // 2. 开单科室规则命中
        if (deptId != null) {
            List<HisWindowDeptRule> rules = deptRuleMapper.selectList(Wrappers.<HisWindowDeptRule>lambdaQuery()
                    .eq(HisWindowDeptRule::getDeptId, deptId));
            for (HisWindowDeptRule rule : rules) {
                HisPharmacyWindow w = findIn(all, rule.getWindowId());
                if (w != null && w.getStatus() != null && w.getStatus() == 1 && isOpen(w)) {
                    log.info("分窗命中科室规则: pharmacyId={}, deptId={}, windowId={}", pharmacyId, deptId, w.getId());
                    return w.getId();
                }
            }
        }

        // 3. 开启态普通窗口按策略取最空闲(排除定向窗)
        List<HisPharmacyWindow> open = new java.util.ArrayList<>();
        for (HisPharmacyWindow w : all) {
            if (isOpen(w) && !isDirected(w)) {
                open.add(w);
            }
        }
        if (!open.isEmpty()) {
            HisPharmacyWindow best = null;
            long bestLoad = Long.MAX_VALUE;
            for (HisPharmacyWindow w : open) {
                long load = loadMetric(w);
                if (best == null || load < bestLoad) {
                    best = w;
                    bestLoad = load;
                }
            }
            if (best != null) {
                return best.getId();
            }
        }

        // 4. 全部关闭 → 兜底默认窗口
        for (HisPharmacyWindow w : all) {
            if (w.getIsDefault() != null && w.getIsDefault() == 1) {
                log.info("分窗回落兜底默认窗口: pharmacyId={}, windowId={}", pharmacyId, w.getId());
                return w.getId();
            }
        }

        // 5. 无兜底: 取启用窗中排序第一(仍有窗口即尽量给一个), 完全不落为 null
        return null;
    }

    /** 窗口是否需发药前签到 */
    public boolean isSigninRequired(Long windowId) {
        if (windowId == null) {
            return false;
        }
        HisPharmacyWindow w = windowMapper.selectById(windowId);
        return w != null && w.getSigninRequired() != null && w.getSigninRequired() == 1;
    }

    /** 窗口级追溯是否强制(P3 依赖, P1 仅暴露读取) */
    public boolean isTraceRequired(Long windowId) {
        if (windowId == null) {
            return false;
        }
        HisPharmacyWindow w = windowMapper.selectById(windowId);
        return w != null && w.getTraceRequired() != null && w.getTraceRequired() == 1;
    }

    /* ================= 内部实现 ================= */

    /** 在候选列表中按类型挑选开启态最空闲窗口(定向投递用); 无匹配返回 null */
    private Long pickByType(List<HisPharmacyWindow> all, String windowType, boolean preferOpen) {
        HisPharmacyWindow best = null;
        long bestLoad = Long.MAX_VALUE;
        for (HisPharmacyWindow w : all) {
            if (!windowType.equalsIgnoreCase(w.getWindowType())) {
                continue;
            }
            if (preferOpen && !isOpen(w)) {
                continue;
            }
            long load = loadMetric(w);
            if (best == null || load < bestLoad) {
                best = w;
                bestLoad = load;
            }
        }
        return best == null ? null : best.getId();
    }

    private HisPharmacyWindow findIn(List<HisPharmacyWindow> all, Long windowId) {
        if (windowId == null) {
            return null;
        }
        for (HisPharmacyWindow w : all) {
            if (windowId.equals(w.getId())) {
                return w;
            }
        }
        return null;
    }

    private boolean isOpen(HisPharmacyWindow w) {
        return w.getOpenStatus() != null && w.getOpenStatus() == 1;
    }

    private boolean isDirected(HisPharmacyWindow w) {
        String t = w.getWindowType();
        boolean typeDirected = t != null && DIRECTED_TYPES.contains(t.toUpperCase());
        boolean strategyDirected = w.getAssignStrategy() != null && w.getAssignStrategy() == 3;
        return typeDirected || strategyDirected;
    }

    /** 空闲度量: 策略1剩余最小=当日待发(status 0/1)计数; 策略2平均=当日总发药计数; 缺省按剩余最小 */
    private long loadMetric(HisPharmacyWindow w) {
        int strategy = w.getAssignStrategy() == null ? 1 : w.getAssignStrategy();
        String sql = strategy == 2
                ? "SELECT COUNT(*) FROM his_dispense WHERE tenant_id = ? AND window_id = ? AND deleted = 0"
                    + " AND create_time >= CURDATE()"
                : "SELECT COUNT(*) FROM his_dispense WHERE tenant_id = ? AND window_id = ? AND deleted = 0"
                    + " AND status IN (0, 1) AND create_time >= CURDATE()";
        Long cnt = jdbcTemplate.queryForObject(sql, Long.class, tenantId(), w.getId());
        return cnt == null ? 0L : cnt;
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }
}
