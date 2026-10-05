package com.yb.hi.service.yb;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.yb.HisYbTxnLog;
import com.yb.hi.mapper.yb.HisYbTxnLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 医保接口出站交易日志查询(只读): 分页检索 his_yb_txn_log。
 * 租户隔离由 MyBatis-Plus 租户插件自动注入; 机构可见范围由调用方经 OrgAccessGuard.scopeOrgId 收敛后传入。
 */
@Service
public class YbTxnLogService {

    private final HisYbTxnLogMapper txnLogMapper;

    public YbTxnLogService(HisYbTxnLogMapper txnLogMapper) {
        this.txnLogMapper = txnLogMapper;
    }

    /**
     * 分页检索(管理端): 时间区间 / 机构 / 交易编号 / 状态 / 报文ID / 就诊ID / 人员编号 精确过滤, 时间倒序。
     *
     * @param orgId 机构过滤(null=当前可见范围全部, 已由 scopeOrgId 收敛)
     */
    public IPage<HisYbTxnLog> pageQuery(LocalDateTime start, LocalDateTime end, Long orgId,
                                        String infno, String status, String msgid,
                                        String mdtrtId, String psnNo, int page, int size) {
        LambdaQueryWrapper<HisYbTxnLog> qw = new LambdaQueryWrapper<>();
        qw.ge(start != null, HisYbTxnLog::getCreateTime, start);
        qw.le(end != null, HisYbTxnLog::getCreateTime, end);
        qw.eq(orgId != null, HisYbTxnLog::getOrgId, orgId);
        qw.eq(StringUtils.hasText(infno), HisYbTxnLog::getInfno, infno);
        qw.eq(StringUtils.hasText(status), HisYbTxnLog::getStatus, status);
        qw.eq(StringUtils.hasText(msgid), HisYbTxnLog::getMsgid, msgid);
        qw.eq(StringUtils.hasText(mdtrtId), HisYbTxnLog::getMdtrtId, mdtrtId);
        qw.eq(StringUtils.hasText(psnNo), HisYbTxnLog::getPsnNo, psnNo);
        qw.orderByDesc(HisYbTxnLog::getCreateTime);
        qw.orderByDesc(HisYbTxnLog::getId);
        return txnLogMapper.selectPage(new Page<>(page, size), qw);
    }
}
