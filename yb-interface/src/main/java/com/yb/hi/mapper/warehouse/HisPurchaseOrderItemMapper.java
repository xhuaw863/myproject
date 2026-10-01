package com.yb.hi.mapper.warehouse;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.warehouse.HisPurchaseOrderItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 采购订单明细 Mapper
 */
@Mapper
public interface HisPurchaseOrderItemMapper extends BaseMapper<HisPurchaseOrderItem> {
}
