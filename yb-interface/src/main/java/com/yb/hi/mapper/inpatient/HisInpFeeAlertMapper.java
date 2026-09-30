package com.yb.hi.mapper.inpatient;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.inpatient.HisInpFeeAlert;
import org.apache.ibatis.annotations.Mapper;

/**
 * 住院费用预警记录 Mapper
 */
@Mapper
public interface HisInpFeeAlertMapper extends BaseMapper<HisInpFeeAlert> {
}
