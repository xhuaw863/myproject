package com.yb.hi.mapper.inpatient;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.inpatient.HisEmrFragment;
import org.apache.ibatis.annotations.Mapper;

/**
 * 病历片段 Mapper
 */
@Mapper
public interface EmrFragmentMapper extends BaseMapper<HisEmrFragment> {
}
