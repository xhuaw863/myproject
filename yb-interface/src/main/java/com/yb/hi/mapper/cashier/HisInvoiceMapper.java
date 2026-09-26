package com.yb.hi.mapper.cashier;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.cashier.HisInvoice;
import org.apache.ibatis.annotations.Mapper;

/**
 * 发票 Mapper
 */
@Mapper
public interface HisInvoiceMapper extends BaseMapper<HisInvoice> {
}
