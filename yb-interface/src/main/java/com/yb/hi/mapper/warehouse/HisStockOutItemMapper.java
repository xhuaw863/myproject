package com.yb.hi.mapper.warehouse;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.warehouse.HisStockOutItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 出库明细 Mapper
 */
@Mapper
public interface HisStockOutItemMapper extends BaseMapper<HisStockOutItem> {
}
