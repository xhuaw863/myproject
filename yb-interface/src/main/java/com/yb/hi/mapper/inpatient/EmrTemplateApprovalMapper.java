package com.yb.hi.mapper.inpatient;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.inpatient.HisEmrTemplateApproval;
import org.apache.ibatis.annotations.Mapper;

/**
 * 病历模板发布审批流水 Mapper
 */
@Mapper
public interface EmrTemplateApprovalMapper extends BaseMapper<HisEmrTemplateApproval> {
}
