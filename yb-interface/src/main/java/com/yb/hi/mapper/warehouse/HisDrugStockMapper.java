package com.yb.hi.mapper.warehouse;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.warehouse.HisDrugStock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * 药品库存 Mapper
 * 自定义SQL不加 tenant_id 条件: 租户插件对注解SQL同样自动追加, 显式写会重复绑定参数。
 */
@Mapper
public interface HisDrugStockMapper extends BaseMapper<HisDrugStock> {

    /**
     * 原子加库存(入库确认upsert / 退药回库):
     * 定位键与唯一键一致, 按 org+药库+药品+批次 定位批次行加量; deleted=0 过滤逻辑删除行, affected=0 表示批次行不存在。
     * warehouseId 传非空时只在目标药库的行上加量(防止同批次多库并存时误加到兄弟库行)。
     */
    @Update("UPDATE his_drug_stock SET qty = qty + #{qty}, update_time = NOW() "
            + "WHERE org_id = #{orgId} AND drug_catalog_id = #{drugCatalogId} "
            + "AND batch_no = #{batchNo} AND deleted = 0 "
            + "AND (#{warehouseId} IS NULL OR warehouse_id = #{warehouseId})")
    int addQty(@Param("orgId") Long orgId,
               @Param("warehouseId") Long warehouseId,
               @Param("drugCatalogId") Long drugCatalogId,
               @Param("batchNo") String batchNo,
               @Param("qty") BigDecimal qty);

    /**
     * 乐观锁扣减: affected=0 表示该批次当前数量不足(并发下被抢先扣减)。
     */
    @Update("UPDATE his_drug_stock SET qty = qty - #{qty}, update_time = NOW() "
            + "WHERE id = #{id} AND qty >= #{qty}")
    int deductQty(@Param("id") Long id, @Param("qty") BigDecimal qty);
}
