package com.yb.hi.mapper.nurse;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.nurse.HisPatientAllergy;
import org.apache.ibatis.annotations.Mapper;

/**
 * 患者过敏记录 Mapper
 */
@Mapper
public interface HisPatientAllergyMapper extends BaseMapper<HisPatientAllergy> {
}
