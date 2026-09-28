package com.yb.hi.platform;

import com.alibaba.fastjson2.JSONObject;
import com.yb.hi.common.MockYbServer;
import com.yb.hi.config.TenantYbConfigResolver;
import com.yb.hi.entity.yb.HisCompTask;
import com.yb.hi.service.cashier.CashierService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 医保补偿任务扫描器(批次4 M1):
 * 周期扫描 his_comp_task(PENDING/RUNNING 且 next_run 到期), 驱动 RESOLVE_UNKNOWN 决策树,
 * 收敛医保交易 UNKNOWN(超时/网络异常)遗留的本地中间态, 保证平台侧与院内数据最终一致。
 * mock 模式可本地核对(模拟结算流水按原交易 msgid 定位), 真实模式无法本地确认 -> 任务置 DEAD 并告警人工介入。
 * 原生 SQL 显式 tenant_id(租户插件不作用于 jdbcTemplate; 调度线程无请求上下文)。
 */
@Slf4j
@Component
public class CompTaskSweeper {

    private final JdbcTemplate jdbcTemplate;
    private final MockYbServer mockYbServer;
    private final TenantYbConfigResolver ybConfigResolver;
    private final CashierService cashierService;

    public CompTaskSweeper(JdbcTemplate jdbcTemplate, MockYbServer mockYbServer,
                           TenantYbConfigResolver ybConfigResolver, CashierService cashierService) {
        this.jdbcTemplate = jdbcTemplate;
        this.mockYbServer = mockYbServer;
        this.ybConfigResolver = ybConfigResolver;
        this.cashierService = cashierService;
    }

    @Scheduled(fixedDelay = 30000)
    public void sweep() {
        List<Map<String, Object>> tasks = jdbcTemplate.queryForList(
                "SELECT id, tenant_id, biz_type, ref_id, action, txn_log_id"
                        + " FROM his_comp_task"
                        + " WHERE deleted = 0 AND status IN ('PENDING','RUNNING') AND next_run <= NOW()"
                        + " ORDER BY id LIMIT 50");
        for (Map<String, Object> t : tasks) {
            try {
                handleTask(t);
            } catch (Exception e) {
                log.error("补偿任务执行异常: taskId={}, 原因: {}", t.get("id"), e.getMessage(), e);
                jdbcTemplate.update("UPDATE his_comp_task SET status='PENDING', attempts=attempts+1,"
                                + " next_run=DATE_ADD(NOW(), INTERVAL 5 MINUTE), memo=?"
                                + " WHERE id=? AND tenant_id=? AND deleted=0",
                        "执行异常, 指数退避: " + e.getMessage(), toLong(t.get("id")), toLong(t.get("tenant_id")));
            }
        }
    }

    private void handleTask(Map<String, Object> t) {
        Long taskId = toLong(t.get("id"));
        Long tenantId = toLong(t.get("tenant_id"));
        // 认领(RUNNING 期间崩溃的任务可被再次认领自愈)
        int claimed = jdbcTemplate.update(
                "UPDATE his_comp_task SET status='RUNNING'"
                        + " WHERE id=? AND tenant_id=? AND deleted=0"
                        + " AND status IN ('PENDING','RUNNING') AND next_run <= NOW()",
                taskId, tenantId);
        if (claimed != 1) {
            return;
        }
        String action = str(t.get("action"));
        if (!HisCompTask.ACT_RESOLVE_UNKNOWN.equals(action)) {
            done(taskId, tenantId, "未知动作, 任务关闭: " + action);
            return;
        }
        String bizType = str(t.get("biz_type"));
        if (HisCompTask.BIZ_REFUND.equals(bizType)) {
            resolveRefund(taskId, tenantId, t);
        } else if (HisCompTask.BIZ_PARTIAL_REFUND.equals(bizType)) {
            resolvePartial(taskId, tenantId, t);
        } else {
            resolveUnknown(taskId, tenantId, t);
        }
    }

    /** REFUND: 2208 UNKNOWN 退费 —— 按平台侧撤销受理状态回填终态或复位中间态 */
    private void resolveRefund(Long taskId, Long tenantId, Map<String, Object> t) {
        Long billId = toLong(t.get("ref_id"));
        boolean mock = ybConfigResolver.resolve().isMockEnabled();
        if (!mock) {
            dead(taskId, tenantId, "真实模式 2208 UNKNOWN 需人工核对平台撤销状态后处理");
            log.error("【医保补偿告警】真实模式结算撤销结果未知, 需人工核对: taskId={}, billId={}", taskId, billId);
            return;
        }
        String setlId = null;
        List<String> ids = jdbcTemplate.queryForList(
                "SELECT setl_id FROM his_charge_bill WHERE id=? AND tenant_id=? AND deleted=0",
                String.class, billId, tenantId);
        if (!ids.isEmpty()) {
            setlId = ids.get(0);
        }
        boolean cancelled = mockYbServer.isSetlCancelled(setlId);
        String memo = cashierService.resolveUnknownRefund(tenantId, billId, cancelled);
        done(taskId, tenantId, memo);
    }

    /** PARTIAL_REFUND: 全撤重结链 UNKNOWN/②-⑤失败 —— 2208已受理则从②重放或按2207流水回填, 未受理则复位 */
    private void resolvePartial(Long taskId, Long tenantId, Map<String, Object> t) {
        Long billId = toLong(t.get("ref_id"));
        Long txnLogId = toLong(t.get("txn_log_id"));
        boolean mock = ybConfigResolver.resolve().isMockEnabled();
        if (!mock) {
            dead(taskId, tenantId, "真实模式全撤重结需人工按 3202 核对后处理");
            log.error("【医保补偿告警】真实模式部分退费全撤重结结果未知, 需人工核对: taskId={}, billId={}", taskId, billId);
            return;
        }
        // 原单 setl_id + 2208 受理状态
        String setlId = null;
        List<String> ids = jdbcTemplate.queryForList(
                "SELECT setl_id FROM his_charge_bill WHERE id=? AND tenant_id=? AND deleted=0",
                String.class, billId, tenantId);
        if (!ids.isEmpty()) {
            setlId = ids.get(0);
        }
        boolean cancelLanded = mockYbServer.isSetlCancelled(setlId);
        // 触发任务的交易若是 2207(其 msgid 即结算单 medins_setl_id), 查平台侧是否已落新结算流水
        String landedSetlId = null;
        if (txnLogId != null) {
            List<String> msgs = jdbcTemplate.queryForList(
                    "SELECT msgid FROM his_yb_txn_log WHERE id=? AND tenant_id=? AND deleted=0",
                    String.class, txnLogId, tenantId);
            if (!msgs.isEmpty()) {
                JSONObject s = mockYbServer.findSetlByMsgid(msgs.get(0));
                landedSetlId = s == null ? null : s.getString("setl_id");
            }
        }
        String memo = cashierService.resolveUnknownPartial(tenantId, billId, cancelLanded, landedSetlId);
        done(taskId, tenantId, memo);
    }

    /** RESOLVE_UNKNOWN: 核对 UNKNOWN 交易在平台侧是否实际受理, 决定本地终态回填还是中间态复位 */
    private void resolveUnknown(Long taskId, Long tenantId, Map<String, Object> t) {
        Long billId = toLong(t.get("ref_id"));
        Long txnLogId = toLong(t.get("txn_log_id"));
        String msgid = null;
        if (txnLogId != null) {
            List<String> msgs = jdbcTemplate.queryForList(
                    "SELECT msgid FROM his_yb_txn_log WHERE id=? AND tenant_id=? AND deleted=0",
                    String.class, txnLogId, tenantId);
            if (!msgs.isEmpty()) {
                msgid = msgs.get(0);
            }
        }
        boolean mock = ybConfigResolver.resolve().isMockEnabled();
        if (!mock) {
            // 真实模式: 平台侧是否受理无法本地确认, 置 DEAD 并告警, 由人工按 3202 对明细账复核后处理
            dead(taskId, tenantId, "真实模式 UNKNOWN 需人工核对平台结算状态后处理(msgid=" + msgid + ")");
            log.error("【医保补偿告警】真实模式结算结果未知, 需人工核对: taskId={}, billId={}, msgid={}", taskId, billId, msgid);
            return;
        }
        JSONObject setlinfo = msgid == null ? null : mockYbServer.findSetlByMsgid(msgid);
        boolean accepted = setlinfo != null;
        String setlId = accepted ? setlinfo.getString("setl_id") : null;
        String memo = cashierService.resolveUnknownCharge(tenantId, billId, accepted, setlId);
        done(taskId, tenantId, memo);
    }

    private void done(Long taskId, Long tenantId, String memo) {
        jdbcTemplate.update("UPDATE his_comp_task SET status='DONE', memo=?"
                + " WHERE id=? AND tenant_id=? AND deleted=0", memo, taskId, tenantId);
    }

    private void dead(Long taskId, Long tenantId, String memo) {
        jdbcTemplate.update("UPDATE his_comp_task SET status='DEAD', memo=?"
                + " WHERE id=? AND tenant_id=? AND deleted=0", memo, taskId, tenantId);
    }

    private static Long toLong(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
