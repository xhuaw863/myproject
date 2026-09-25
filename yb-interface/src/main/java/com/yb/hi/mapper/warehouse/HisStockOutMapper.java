package com.yb.hi.mapper.warehouse;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.warehouse.HisStockOut;
import org.apache.ibatis.annotations.Mapper;

/**
 * 出库单 Mapper
 */
@Mapper
public interface HisStockOutMapper extends BaseMapper<HisStockOut> {
}
