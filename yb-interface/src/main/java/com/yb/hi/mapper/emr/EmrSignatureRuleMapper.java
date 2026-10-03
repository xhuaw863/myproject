package com.yb.hi.mapper.emr;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.emr.HisEmrSignatureRule;
import org.apache.ibatis.annotations.Mapper;

/**
 * 病历签名规则链 Mapper
 */
@Mapper
public interface EmrSignatureRuleMapper extends BaseMapper<HisEmrSignatureRule> {
}
