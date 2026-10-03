package com.yb.hi.platform;

import com.yb.hi.framework.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 全量导出/大查询防护闸(限流+并发+行数上限三合一), 防导出不受控拖垮系统。
 *
 * 参数(sys_param 全局定义行 scope_level=0, 60s 缓存动态生效; 见 ExportGuardParamSeeder):
 *  - system.export_max_rows        单次导出行数上限(超过直接拒绝, 提示收窄条件)
 *  - system.export_rate_per_min    每用户每分钟导出次数(滑动窗口)
 *  - system.export_concurrency     全库并发导出闸(信号量, 变更需重启生效)
 * 查询超时不设在此层: 由 JDBC URL sessionVariables=max_execution_time 会话级钉死(仅 SELECT)。
 *
 * 用法: HTTP 导出端点由 ExportGuardInterceptor 按 URL 自动 enter/exit(限流+并发);
 *       行数闸由 Controller 组装 rows 后调用静态 {@link #checkRows} (无 rows 上下文则不拦)。
 */
@Slf4j
@Component
public class ExportGuard {

    public static final String P_MAX_ROWS = "system.export_max_rows";
    public static final String P_RATE_PER_MIN = "system.export_rate_per_min";
    public static final String P_CONCURRENCY = "system.export_concurrency";

    private static ExportGuard instance;

    private final JdbcTemplate jdbcTemplate;

    /** 参数缓存(60s 惰性刷新; 读取失败沿用旧值, 保底默认) */
    private volatile long cacheAt = 0L;
    private volatile int maxRows = 100000;
    private volatile int ratePerMin = 6;
    /** 并发闸: 信号量在启动时按参数一次性固化(调参重启生效, 字段仅展示当前配置值) */
    private final Semaphore concurrencyGate;
    private volatile int concurrency = 5;

    /** 每用户滑动窗口: 用户名 -> 最近命中时间戳(毫秒, 双端队列) */
    private final ConcurrentHashMap<String, Deque<Long>> userWindows = new ConcurrentHashMap<>();

    public ExportGuard(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        refreshIfNeeded();
        this.concurrencyGate = new Semaphore(this.concurrency);
        instance = this;
    }

    /** 拦截器入口: 限流(每用户每分钟) + 占用并发导出处; 失败抛 BizException(code=429)。 */
    public void enter(String username) {
        refreshIfNeeded();
        String user = username == null ? "anonymous" : username;
        long now = System.currentTimeMillis();
        Deque<Long> win = userWindows.computeIfAbsent(user, k -> new ArrayDeque<>());
        int rate;
        synchronized (win) {
            while (!win.isEmpty() && now - win.peekFirst() > TimeUnit.MINUTES.toMillis(1)) {
                win.pollFirst();
            }
            rate = ratePerMin;
            if (win.size() >= rate) {
                throw new BizException(429, "导出操作过于频繁(每用户每分钟上限 " + rate + " 次), 请稍后再试");
            }
            win.addLast(now);
        }
        if (!concurrencyGate.tryAcquire()) {
            synchronized (win) {
                win.removeLast();
            }
            throw new BizException(429, "当前导出任务已达并发上限(" + concurrency + "), 请等待既有导出完成后再试");
        }
    }

    /** 拦截器出口: 释放并发位(与 enter 严格配对, 由 afterCompletion 保证)。 */
    public void exit() {
        concurrencyGate.release();
    }

    /** 行数闸: 导出组装完成后校验, 超上限拒绝(HTTP 200 + body code=400, 与全系统 R 口径一致)。 */
    public static void checkRows(int n, String scope) {
        ExportGuard g = instance;
        if (g == null) {
            return;
        }
        g.refreshIfNeeded();
        int limit = g.maxRows;
        if (n > limit) {
            throw new BizException(400, "「" + scope + "」导出共 " + n + " 行, 超过单次上限 " + limit
                    + " 行; 请收窄查询条件(时间/机构/关键字)或联系管理员在系统参数 system.export_max_rows 调整");
        }
    }

    /** 集合便捷重载。 */
    public static void checkRows(java.util.Collection<?> rows, String scope) {
        checkRows(rows == null ? 0 : rows.size(), scope);
    }

    /** 当前生效的行数上限(供 Service 组装带 LIMIT 的导出 SQL 时取用)。 */
    public static int maxRows() {
        ExportGuard g = instance;
        return g == null ? 100000 : g.maxRows;
    }

    private void refreshIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - cacheAt < TimeUnit.SECONDS.toMillis(60)) {
            return;
        }
        cacheAt = now;
        try {
            maxRows = readInt(P_MAX_ROWS, maxRows);
            ratePerMin = readInt(P_RATE_PER_MIN, ratePerMin);
            concurrency = readInt(P_CONCURRENCY, concurrency);
        } catch (Exception e) {
            log.debug("导出防护参数刷新跳过(沿用旧值): {}", e.getMessage());
        }
    }

    private int readInt(String key, int fallback) {
        List<Map<String, Object>> hit = jdbcTemplate.queryForList(
                "SELECT param_value, default_value FROM sys_param WHERE param_key = ? AND scope_level = 0"
                        + " AND scope_id = 0 AND deleted = 0 LIMIT 1", key);
        if (hit.isEmpty()) {
            return fallback;
        }
        Object v = hit.get(0).get("param_value");
        if (v == null || String.valueOf(v).trim().isEmpty()) {
            v = hit.get(0).get("default_value");
        }
        try {
            int parsed = Integer.parseInt(String.valueOf(v).trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
