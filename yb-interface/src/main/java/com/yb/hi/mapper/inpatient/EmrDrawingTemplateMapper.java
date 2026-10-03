package com.yb.hi.mapper.inpatient;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.inpatient.HisEmrDrawingTemplate;
import org.apache.ibatis.annotations.Mapper;

/**
 * 医学图示模板 Mapper
 */
@Mapper
public interface EmrDrawingTemplateMapper extends BaseMapper<HisEmrDrawingTemplate> {
}
