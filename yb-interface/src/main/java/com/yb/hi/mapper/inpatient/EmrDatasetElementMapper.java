package com.yb.hi.mapper.inpatient;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.inpatient.HisEmrDatasetElement;
import org.apache.ibatis.annotations.Mapper;

/**
 * 病历数据集数据元 Mapper
 */
@Mapper
public interface EmrDatasetElementMapper extends BaseMapper<HisEmrDatasetElement> {
}
