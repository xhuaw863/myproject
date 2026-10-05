package com.yb.hi.controller.yb;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.entity.yb.HisYbTxnLog;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.yb.YbTxnLogService;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 医保接口日志查询(只读): 分页检索出站交易日志 his_yb_txn_log, 供机构管理员按交易编号/状态/患者/报文ID
 * 回溯每笔医保交易的请求与响应原文。
 * 认证由 AuthInterceptor(/api/**)把守; 租户隔离由 MyBatis-Plus 租户插件自动注入;
 * 机构可见范围经 OrgAccessGuard.scopeOrgId 收敛(牵头机构可按机构过滤或看全部, 成员机构强制本机构)。
 */
@RestController
@RequestMapping("/api/yb/txn-log")
public class YbTxnLogController {

    private final YbTxnLogService txnLogService;
    private final OrgAccessGuard guard;

    public YbTxnLogController(YbTxnLogService txnLogService, OrgAccessGuard guard) {
        this.txnLogService = txnLogService;
        this.guard = guard;
    }

    /**
     * 分页检索。startTime/endTime 支持 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss;
     * infno/status/msgid/mdtrtId/psnNo 精确过滤; orgId 仅牵头机构可指定(null=可见范围全部)。
     */
    @GetMapping("/page")
    public R<IPage<HisYbTxnLog>> page(@RequestParam(required = false) String startTime,
                                      @RequestParam(required = false) String endTime,
                                      @RequestParam(required = false) Long orgId,
                                      @RequestParam(required = false) String infno,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) String msgid,
                                      @RequestParam(required = false) String mdtrtId,
                                      @RequestParam(required = false) String psnNo,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        requireOrgAdmin();
        Long effOrg = guard.scopeOrgId(orgId);
        return R.ok(txnLogService.pageQuery(parseTime(startTime, false), parseTime(endTime, true),
                effOrg, infno, status, msgid, mdtrtId, psnNo,
                Math.max(1, page), Math.max(1, Math.min(size, 200))));
    }

    /* ==================== 内部 ==================== */

    /** 仅机构管理员(牵头 ADMIN / 非牵头 ORG_ADMIN / 超管)可查询医保接口日志 */
    private void requireOrgAdmin() {
        LoginUser lu = UserContext.get();
        if (lu == null || !lu.hasAnyRole(Roles.ADMIN, Roles.ORG_ADMIN, Roles.SUPER_ADMIN)) {
            throw new BizException(403, "仅机构管理员可查询医保接口日志");
        }
    }

    /** 时间解析: yyyy-MM-dd 起=00:00:00/止=23:59:59; 完整格式 yyyy-MM-dd HH:mm:ss */
    private LocalDateTime parseTime(String s, boolean endOfDay) {
        if (!StringUtils.hasText(s)) {
            return null;
        }
        try {
            String v = s.trim();
            if (v.length() <= 10) {
                LocalDate d = LocalDate.parse(v);
                return endOfDay ? d.atTime(23, 59, 59) : d.atStartOfDay();
            }
            return LocalDateTime.parse(v, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            throw new BizException(400, "时间格式应为 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss");
        }
    }
}
