package com.yb.hi.mapper.warehouse;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.warehouse.HisStockInItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

/**
 * 入库明细 Mapper(含出入库流水 UNION 查询)
 */
@Mapper
public interface HisStockInItemMapper extends BaseMapper<HisStockInItem> {

    /**
     * 出入库流水: 已确认单据的入库明细 UNION ALL 出库明细, 按单据确认时间倒序。
     * 外层包一层派生表保证 UNION + ORDER BY 解析无歧义; tenant_id 条件由租户插件对各子查询自动追加。
     */
    @Select("<script>"
            + "SELECT * FROM ("
            + " SELECT i.id AS itemId, m.id AS billId, m.in_no AS billNo, 'IN' AS flowType, '入库' AS flowTypeName, m.in_type AS billType,"
            + "   i.drug_catalog_id AS drugCatalogId, i.drug_code AS drugCode, i.drug_name AS drugName, i.spec AS spec,"
            + "   i.batch_no AS batchNo, i.qty AS qty, i.cost_price AS costPrice, i.retail_price AS retailPrice, i.amount AS amount,"
            + "   m.confirm_time AS opTime"
            + " FROM his_stock_in_item i JOIN his_stock_in m ON i.stock_in_id = m.id"
            + " WHERE m.status = 1"
            + " <if test='orgId != null'> AND m.org_id = #{orgId} </if>"
            + " <if test='warehouseId != null'> AND m.warehouse_id = #{warehouseId} </if>"
            + " <if test='drugCatalogId != null'> AND i.drug_catalog_id = #{drugCatalogId} </if>"
            + " <if test='startDate != null and startDate != \"\"'> AND DATE(m.confirm_time) &gt;= #{startDate} </if>"
            + " <if test='endDate != null and endDate != \"\"'> AND DATE(m.confirm_time) &lt;= #{endDate} </if>"
            + " UNION ALL"
            + " SELECT o.id, m2.id, m2.out_no, 'OUT', '出库', m2.out_type,"
            + "   o.drug_catalog_id, o.drug_code, o.drug_name, o.spec,"
            + "   o.batch_no, o.qty, o.cost_price, o.retail_price, o.amount,"
            + "   m2.confirm_time"
            + " FROM his_stock_out_item o JOIN his_stock_out m2 ON o.stock_out_id = m2.id"
            + " WHERE m2.status = 1"
            + " <if test='orgId != null'> AND m2.org_id = #{orgId} </if>"
            + " <if test='warehouseId != null'> AND m2.warehouse_id = #{warehouseId} </if>"
            + " <if test='drugCatalogId != null'> AND o.drug_catalog_id = #{drugCatalogId} </if>"
            + " <if test='startDate != null and startDate != \"\"'> AND DATE(m2.confirm_time) &gt;= #{startDate} </if>"
            + " <if test='endDate != null and endDate != \"\"'> AND DATE(m2.confirm_time) &lt;= #{endDate} </if>"
            + ") t ORDER BY t.opTime DESC, t.itemId DESC"
            + "</script>")
    IPage<Map<String, Object>> selectFlowPage(Page<Map<String, Object>> page,
                                              @Param("orgId") Long orgId,
                                              @Param("warehouseId") Long warehouseId,
                                              @Param("drugCatalogId") Long drugCatalogId,
                                              @Param("startDate") String startDate,
                                              @Param("endDate") String endDate);
}
