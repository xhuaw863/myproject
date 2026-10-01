package com.yb.hi.entity.pharmacy;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 发药窗口定义(药房下细分窗口, P1 智能分窗基座)
 * 唯一键: tenant_id + pharmacy_id + code
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_pharmacy_window")
public class HisPharmacyWindow extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 所属药房ID(his_pharmacy_def.id) */
    private Long pharmacyId;
    /** 窗口编码 */
    private String code;
    /** 窗口名称 */
    private String name;
    /** 窗口类型: WEST西药 / CHINESE_PATENT中成药 / HERB草药 / NARCOTIC精麻 / TOXIC毒性 / DECOCT代煎 / EXPRESS快递 */
    private String windowType;
    /** 分配策略: 1剩余量最小 2平均轮询 3定向 */
    private Integer assignStrategy;
    /** 兜底默认窗口: 1是 0否 */
    private Integer isDefault;
    /** 开窗状态: 1开 0关 */
    private Integer openStatus;
    /** 发药前需患者签到: 1是 0否 */
    private Integer signinRequired;
    /** 窗口级追溯码强制: 1是 0否 */
    private Integer traceRequired;
    /** 排序号 */
    private Integer sortNo;
    /** 状态: 1启用 0停用 */
    private Integer status;
}
