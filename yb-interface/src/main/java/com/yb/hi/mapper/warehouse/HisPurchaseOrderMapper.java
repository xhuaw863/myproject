package com.yb.hi.mapper.warehouse;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.warehouse.HisPurchaseOrder;
import org.apache.ibatis.annotations.Mapper;

/**
 * 采购订单主表 Mapper
 */
@Mapper
public interface HisPurchaseOrderMapper extends BaseMapper<HisPurchaseOrder> {
}
