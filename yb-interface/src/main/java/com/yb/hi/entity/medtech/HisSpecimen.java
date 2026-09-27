package com.yb.hi.entity.medtech;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 检验标本(采集/签收/拒收, 条码为标本唯一标识)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_specimen")
public class HisSpecimen extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 标本条码号 */
    private String barcode;
    /** 医嘱单ID(his_order.id) */
    private Long orderId;
    /** 患者ID */
    private Long patientId;
    /** 标本类型: blood血/urine尿/stool便/sputum痰/other其他 */
    private String specimenType;
    /** 采血管颜色(红/紫/蓝/黑/绿/灰/黄) */
    private String tubeColor;
    /** 采集护士ID(his_staff.id) */
    private Long collectNurseId;
    /** 采集时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime collectTime;
    /** 签收技师ID(his_staff.id) */
    private Long receiveTechId;
    /** 签收时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime receiveTime;
    /** 状态: 0待采集 1已采集 2已签收 3已拒收 4已出报告 */
    private Integer status;
    /** 拒收原因(溶血/量不足等) */
    private String rejectReason;
}
