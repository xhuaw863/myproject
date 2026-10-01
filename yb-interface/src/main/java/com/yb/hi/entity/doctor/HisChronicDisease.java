package com.yb.hi.entity.doctor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 患者门诊慢特病备案(需求2.2.2.3.14.3/14.4): 长期慢病患者的门特/门慢病种备案与有效期。
 * 数据源: 本表(患者维度); 供开方按病种自动拆方、处方笺备注带病种/处方用途/有效次数。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_chronic_disease")
public class HisChronicDisease extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID(空=租户通用) */
    private Long orgId;
    /** 患者ID(his_patient.id) */
    private Long patientId;
    /** 患者姓名(冗余) */
    private String patientName;
    /** 病种编码(门特/门慢病种目录) */
    private String diseCode;
    /** 病种名称 */
    private String diseName;
    /** 备案类型: 1门特 2门慢 */
    private String diseType;
    /** 备案编号 */
    private String registerNo;
    /** 备案有效期起 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate validFrom;
    /** 备案有效期止 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate validTo;
    /** 状态: 1有效 0失效 */
    private Integer status;
    /** 来源: manual手工 / import导入 / insutype医保结算回流 */
    private String source;
}
