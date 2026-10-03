package com.yb.hi.controller.emr;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.entity.emr.HisEmrAuditLog;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.emr.EmrAuditService;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 病历操作审计接口(病历P2):
 *  - GET /api/his/emr/audit/record/{scope}/{recordId}  单份病历审计轨迹(时间倒序);
 *  - GET /api/his/emr/audit/page                       全量审计分页检索(仅牵头机构管理员)。
 * 认证由 AuthInterceptor(/api/**)把守, 租户隔离由 MyBatis-Plus 租户插件自动注入。
 */
@RestController
@RequestMapping("/api/his/emr/audit")
public class EmrAuditController {

    private final EmrAuditService auditService;
    private final OrgAccessGuard guard;

    public EmrAuditController(EmrAuditService auditService, OrgAccessGuard guard) {
        this.auditService = auditService;
        this.guard = guard;
    }

    /**
     * 单份病历审计轨迹。
     *
     * @param scope    1住院 2门诊
     * @param recordId 病历ID
     */
    @GetMapping("/record/{scope}/{recordId}")
    public R<List<HisEmrAuditLog>> listByRecord(@PathVariable Integer scope,
                                                @PathVariable Long recordId) {
        return R.ok(auditService.listByRecord(scope, recordId));
    }

    /**
     * 全量审计分页检索(管理端, 仅牵头机构管理员):
     * startTime/endTime 支持 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss; action 动作精确过滤; operatorId 操作人过滤。
     */
    @GetMapping("/page")
    public R<IPage<HisEmrAuditLog>> page(@RequestParam(required = false) String startTime,
                                         @RequestParam(required = false) String endTime,
                                         @RequestParam(required = false) String action,
                                         @RequestParam(required = false) Long operatorId,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        guard.requireLeadOrg("仅牵头机构管理员可查询全量审计日志");
        return R.ok(auditService.pageQuery(parseTime(startTime, "startTime"), parseTime(endTime, "endTime"),
                action, operatorId, page, size));
    }

    /* ==================== 内部 ==================== */

    /** 时间解析: yyyy-MM-dd 视为当日 00:00:00; 完整格式 yyyy-MM-dd HH:mm:ss */
    private LocalDateTime parseTime(String s, String at) {
        if (!StringUtils.hasText(s)) {
            return null;
        }
        try {
            String v = s.trim();
            if (v.length() <= 10) {
                return LocalDate.parse(v).atStartOfDay();
            }
            return LocalDateTime.parse(v, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            throw new BizException(400, "时间格式应为 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss: " + at);
        }
    }
}
