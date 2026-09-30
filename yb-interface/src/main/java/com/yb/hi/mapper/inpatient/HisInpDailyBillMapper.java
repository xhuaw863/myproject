package com.yb.hi.mapper.inpatient;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.inpatient.HisInpDailyBill;
import org.apache.ibatis.annotations.Mapper;

/**
 * 住院每日费用清单 Mapper
 */
@Mapper
public interface HisInpDailyBillMapper extends BaseMapper<HisInpDailyBill> {
}
