package com.yb.hi.mapper.inpatient;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.inpatient.HisEmrComponentShare;
import org.apache.ibatis.annotations.Mapper;

/**
 * 病历组件与模板市场 Mapper
 */
@Mapper
public interface EmrComponentShareMapper extends BaseMapper<HisEmrComponentShare> {
}
