package com.yb.hi.mapper.inpatient;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.inpatient.HisEmrElement;
import org.apache.ibatis.annotations.Mapper;

/**
 * 病历数据元(要素) Mapper
 */
@Mapper
public interface HisEmrElementMapper extends BaseMapper<HisEmrElement> {
}
