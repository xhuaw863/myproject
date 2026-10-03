package com.yb.hi.mapper.inpatient;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.inpatient.HisEmrDataset;
import org.apache.ibatis.annotations.Mapper;

/**
 * 病历数据集 Mapper
 */
@Mapper
public interface EmrDatasetMapper extends BaseMapper<HisEmrDataset> {
}
