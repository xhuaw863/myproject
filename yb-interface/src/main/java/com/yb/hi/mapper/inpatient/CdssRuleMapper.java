package com.yb.hi.mapper.inpatient;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.inpatient.HisCdssRule;
import org.apache.ibatis.annotations.Mapper;

/**
 * CDSS 临床决策支持规则 Mapper
 */
@Mapper
public interface CdssRuleMapper extends BaseMapper<HisCdssRule> {
}
