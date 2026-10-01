package com.yb.hi.entity.warehouse;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 药品养护单主表(单号 YH+yyyyMMdd+4位, 租户内唯一): 对药库在库药品做质量养护检查。
 * 建单方式 mnt_type: 1手动(逐行录入) 2自动(按在库库存筛选生行) 3模板(引入养护模板筛选条件+默认措施)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_drug_maintenance")
public class HisDrugMaintenance extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 药库ID(his_warehouse_def.id) */
    private Long warehouseId;
    /** 养护单号(YH+yyyyMMdd+4位序号) */
    private String mntNo;
    /** 养护日期 */
    private LocalDate mntDate;
    /** 建单方式: 1手动 2自动 3模板 */
    private Integer mntType;
    /** 养护品种行数 */
    private Integer drugCount;
    /** 异常品行数 */
    private Integer abnormalCount;
    /** 整体结论 */
    private String conclusion;
    /** 养护人 */
    private String mntBy;
    /** 养护完成时间 */
    private LocalDateTime mntTime;
    /** 状态: 0草稿 1已完成 */
    private Integer status;
    /** 来源模板ID(mnt_type=3) */
    private Long templateId;
    /** 备注 */
    private String remark;
}
