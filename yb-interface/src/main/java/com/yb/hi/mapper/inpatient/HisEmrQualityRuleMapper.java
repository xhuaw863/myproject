package com.yb.hi.mapper.inpatient;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.inpatient.HisEmrQualityRule;
import org.apache.ibatis.annotations.Mapper;

/**
 * 病历质控规则 Mapper
 */
@Mapper
public interface HisEmrQualityRuleMapper extends BaseMapper<HisEmrQualityRule> {
}
