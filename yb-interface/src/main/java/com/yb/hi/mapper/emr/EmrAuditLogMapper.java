package com.yb.hi.mapper.emr;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.emr.HisEmrAuditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 病历操作审计日志 Mapper
 */
@Mapper
public interface EmrAuditLogMapper extends BaseMapper<HisEmrAuditLog> {
}
