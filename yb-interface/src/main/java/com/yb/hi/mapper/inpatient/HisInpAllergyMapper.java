package com.yb.hi.mapper.inpatient;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.inpatient.HisInpAllergy;
import org.apache.ibatis.annotations.Mapper;

/**
 * 住院患者过敏记录 Mapper
 */
@Mapper
public interface HisInpAllergyMapper extends BaseMapper<HisInpAllergy> {
}
