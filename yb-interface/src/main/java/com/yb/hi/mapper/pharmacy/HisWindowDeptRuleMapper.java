package com.yb.hi.mapper.pharmacy;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.pharmacy.HisWindowDeptRule;
import org.apache.ibatis.annotations.Mapper;

/**
 * 开单科室→窗口定向规则 Mapper
 */
@Mapper
public interface HisWindowDeptRuleMapper extends BaseMapper<HisWindowDeptRule> {
}
