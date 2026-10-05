package com.yb.hi.mapper.inpatient;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.inpatient.HisEmrRefIndex;
import org.apache.ibatis.annotations.Mapper;

/**
 * 病历模板引用反查索引 Mapper
 */
@Mapper
public interface EmrRefIndexMapper extends BaseMapper<HisEmrRefIndex> {
}
