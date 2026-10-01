package com.yb.hi.mapper.warehouse;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.warehouse.HisSupplierPayment;
import org.apache.ibatis.annotations.Mapper;

/**
 * 供应商付款单主表 Mapper
 */
@Mapper
public interface HisSupplierPaymentMapper extends BaseMapper<HisSupplierPayment> {
}
