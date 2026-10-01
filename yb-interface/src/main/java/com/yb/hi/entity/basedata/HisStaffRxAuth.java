package com.yb.hi.entity.basedata;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 医师处方权限·按级授权明细(T2 阶段5-3)。
 * 背景: his_staff 宽表仅有单一 rx_valid_until(整份处方权共用一个有效期), 无法表达
 * "抗菌各分级 / 麻醉 / 精一 / 精二 分别授权、各自有效期独立到期" 的药事法规要求。
 * 本表作为按类别(权限项)拆分的授权明细, 支持多行、逐项有效期与变更留痕; 到期判定优先取本表,
 * 无明细行时回落 his_staff.rx_valid_until(零回归, 兼容历史数据)。
 * auth_kind 权限类别: abx 抗菌分级 / narcotic 麻醉 / psych1 精一 / psych2 精二;
 * auth_code 类别内编码: abx 取 HBCV08.50.029(11非限制/12限制/13特殊使用), 其余专项取 1。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_staff_rx_auth")
public class HisStaffRxAuth extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 职工ID(his_staff.id) */
    private Long staffId;
    /** 权限类别: abx/narcotic/psych1/psych2 */
    private String authKind;
    /** 类别内权限编码(抗菌分级 11/12/13; 专项类填 1) */
    private String authCode;
    /** 权限名称(服务端按字典回填, 如"限制使用级") */
    private String authName;
    /** 生效日期 */
    private LocalDate validFrom;
    /** 有效期至(到期即失效, 需复训再授权) */
    private LocalDate validUntil;
    /** 授权机构(医务科/授权部门) */
    private String authOrg;
    /** 授权文号(授权文件编号, 便于追溯) */
    private String authNo;
    /** 状态: 1有效 0注销 */
    private Integer status;
    /** 机构ID(取职工归属机构) */
    private Long orgId;
    /** 备注 */
    private String memo;
}
