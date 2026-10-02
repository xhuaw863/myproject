package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 手术/机房/内镜室/产房资源(手麻P4b 一体化): 以 room_type 区分资源类别(1手术间 2DSA机房 3内镜室 4产房),
 * 排程时按 module_type→room_type 归集校验占用与冲突, 排程板按 room_type 分列。tenant_id 由 MP 租户插件注入。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_surgery_room")
public class HisSurgeryRoom extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 资源编码(唯一) */
    private String roomCode;
    /** 资源名称 */
    private String roomName;
    /** 资源类型: 1手术间 2DSA机房 3内镜室 4产房 */
    private Integer roomType;
    /** 归属科室ID */
    private Long deptId;
    /** 状态: 1可用 0停用 */
    private Integer status;
    /** 备注 */
    private String remark;
}
