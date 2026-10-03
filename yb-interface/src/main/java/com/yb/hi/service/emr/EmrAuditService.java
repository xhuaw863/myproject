package com.yb.hi.service.emr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.emr.HisEmrAuditLog;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.emr.EmrAuditLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 病历操作审计日志服务(病历P2): 病历级动作留痕(CREATE/UPDATE/VIEW/PRINT/SIGN/DELETE/SUBMIT/AUDIT),
 * 按病历维(scope+record_id)与操作人维(operator_id+create_time)双维追溯。
 * 来源IP取当前请求(RequestContextHolder), 无请求上下文的内部调用/定时任务留空;
 * orgId 取登录机构快照(未登录留空)。tenant_id 由租户插件自动注入, 实体不显式映射。
 * 注意: 写入失败会向上抛出, 不希望审计阻断病历主流程的调用方须自行 try/catch 包裹。
 */
@Slf4j
@Service
public class EmrAuditService {

    /** 适用范围: 1住院 2门诊(与 his_emr_audit_log.scope 口径一致) */
    public static final int SCOPE_INP = 1;
    public static final int SCOPE_OUTP = 2;

    private final EmrAuditLogMapper auditLogMapper;

    public EmrAuditService(EmrAuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * 记录审计事件(完整参数版)。
     *
     * @param scope        1住院 2门诊
     * @param recordId     病历ID(住院 his_inp_medical_record.id / 门诊病历ID), 列表级动作可空
     * @param action       动作: CREATE/UPDATE/VIEW/PRINT/SIGN/DELETE/SUBMIT/AUDIT
     * @param operatorId   操作人ID(his_staff.id)
     * @param operatorName 操作人姓名(冗余留痕, 超50字符截断)
     * @param detail       变更摘要JSON(可空)
     */
    public void log(int scope, Long recordId, String action, Long operatorId, String operatorName, String detail) {
        HisEmrAuditLog logRow = new HisEmrAuditLog();
        logRow.setScope(scope);
        logRow.setRecordId(recordId);
        logRow.setAction(action);
        logRow.setOperatorId(operatorId);
        logRow.setOperatorName(truncate(operatorName, 50));
        logRow.setDetail(detail);
        logRow.setOrgId(currentOrgIdSafe());
        logRow.setIpAddress(currentIpSafe());
        auditLogMapper.insert(logRow);
    }

    /**
     * 记录审计事件(当前用户版): 操作人取 UserContext 当前登录职工(staffId + realName/username),
     * 未登录(系统内部调用)时操作人留空, 审计行仍落库。
     */
    public void log(int scope, Long recordId, String action, String detail) {
        LoginUser cur = UserContext.get();
        Long operatorId = cur == null ? null : cur.getStaffId();
        String operatorName = cur == null ? null
                : (StringUtils.hasText(cur.getRealName()) ? cur.getRealName() : cur.getUsername());
        log(scope, recordId, action, operatorId, operatorName, detail);
    }

    /** 单份病历的审计轨迹(时间倒序, 同秒按ID倒序稳定排序) */
    public List<HisEmrAuditLog> listByRecord(int scope, Long recordId) {
        return auditLogMapper.selectList(new LambdaQueryWrapper<HisEmrAuditLog>()
                .eq(HisEmrAuditLog::getScope, scope)
                .eq(HisEmrAuditLog::getRecordId, recordId)
                .orderByDesc(HisEmrAuditLog::getCreateTime)
                .orderByDesc(HisEmrAuditLog::getId));
    }

    /**
     * 分页检索(管理端): 时间区间/动作/操作人过滤, 时间倒序; 租户隔离由租户插件自动注入。
     */
    public IPage<HisEmrAuditLog> pageQuery(LocalDateTime start, LocalDateTime end, String action,
                                           Long operatorId, int page, int size) {
        LambdaQueryWrapper<HisEmrAuditLog> qw = new LambdaQueryWrapper<>();
        qw.ge(start != null, HisEmrAuditLog::getCreateTime, start);
        qw.le(end != null, HisEmrAuditLog::getCreateTime, end);
        qw.eq(StringUtils.hasText(action), HisEmrAuditLog::getAction, action);
        qw.eq(operatorId != null, HisEmrAuditLog::getOperatorId, operatorId);
        qw.orderByDesc(HisEmrAuditLog::getCreateTime);
        qw.orderByDesc(HisEmrAuditLog::getId);
        return auditLogMapper.selectPage(new Page<>(page, size), qw);
    }

    /* ==================== 内部 ==================== */

    /** 登录机构ID(未登录留空, 不抛异常以免阻断审计落库) */
    private Long currentOrgIdSafe() {
        try {
            LoginUser cur = UserContext.get();
            return cur == null ? null : cur.getOrgId();
        } catch (Exception e) {
            return null;
        }
    }

    /** 来源IP(无请求上下文时留空, 如定时任务/启动期调用) */
    private String currentIpSafe() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attrs == null ? null : attrs.getRequest().getRemoteAddr();
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
