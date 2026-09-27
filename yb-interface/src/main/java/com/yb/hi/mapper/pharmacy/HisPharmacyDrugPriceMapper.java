package com.yb.hi.mapper.pharmacy;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.pharmacy.HisPharmacyDrugPrice;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 药房药品定价(覆盖价) Mapper
 * 注解SQL不写 tenant_id: 租户插件对注解SQL同样自动追加, 显式写会重复绑定参数。
 */
@Mapper
public interface HisPharmacyDrugPriceMapper extends BaseMapper<HisPharmacyDrugPrice> {

    /** 物理删除覆盖价(清空回落目录价): 软删残行会撞唯一键 uk_tenant_org_ph_drug, 故不走逻辑删除 */
    @Delete("DELETE FROM his_pharmacy_drug_price WHERE pharmacy_id = #{pharmacyId} AND drug_catalog_id = #{drugCatalogId}")
    int physicalDelete(@Param("pharmacyId") Long pharmacyId, @Param("drugCatalogId") Long drugCatalogId);
}
