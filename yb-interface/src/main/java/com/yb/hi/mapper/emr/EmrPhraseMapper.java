package com.yb.hi.mapper.emr;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.emr.HisEmrPhrase;
import org.apache.ibatis.annotations.Mapper;

/**
 * 病历常用语 Mapper
 */
@Mapper
public interface EmrPhraseMapper extends BaseMapper<HisEmrPhrase> {
}
