package com.yb.hi.mapper.inpatient;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.inpatient.HisEmrAnnotation;
import org.apache.ibatis.annotations.Mapper;

/**
 * 病历批注与修订线程 Mapper
 */
@Mapper
public interface EmrAnnotationMapper extends BaseMapper<HisEmrAnnotation> {
}
