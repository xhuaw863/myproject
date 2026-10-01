package com.yb.hi.entity.warehouse;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 供应商主数据(编码租户内唯一): 承载采购订单/入库的供应商档案, 集采标识 jtFlag 用于智能采购与上传筛选。
 * 唯一键: tenant_id + supplier_code
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_supplier")
public class HisSupplier extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 引用全局企业字典(std_supplier.sup_code), 新建时可从字典导入基本信息 */
    private String stdSupCode;
    /** 供应商编码(租户内唯一) */
    private String supplierCode;
    /** 供应商名称 */
    private String supplierName;
    /** 联系人 */
    private String contact;
    /** 联系电话 */
    private String phone;
    /** 地址 */
    private String address;
    /** 结算周期(天) */
    private Integer settleCycle;
    /** 集采供应商标识: 1是 0否 */
    private Integer jtFlag;
    /** 状态: 1启用 0停用 */
    private Integer status;
    /** 备注 */
    private String remark;
}
