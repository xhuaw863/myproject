package com.yb.hi.mapper.inpatient;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.inpatient.HisEmrNlgTemplate;
import org.apache.ibatis.annotations.Mapper;

/**
 * 病历NLG生成模板 Mapper
 */
@Mapper
public interface EmrNlgTemplateMapper extends BaseMapper<HisEmrNlgTemplate> {
}
