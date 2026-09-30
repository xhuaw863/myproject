package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 住院床位(占用状态由入院/出院/转床链路回写)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_bed")
public class HisBed extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 床位号 */
    private String bedNo;
    /** 病区ID(his_ward.id) */
    private Long wardId;
    /** 房间号 */
    private String roomNo;
    /** 床位类型: 1普通 2抢救 3监护 4隔离 */
    private Integer bedType;
    /** 状态: 0空床 1占用 2停用 */
    private Integer status;
    /** 当前患者ID(his_patient.id) */
    private Long patientId;
    /** 当前住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 床位日费用 */
    private BigDecimal dailyPrice;
    /* ---------- 模型增强扩展列(DictSchemaMigration 幂等补列) ---------- */
    /** 床位等级: 1普通 2单间 3监护 4特需 */
    private Integer bedLevel;
    /** 是否加床: 1是 0否 */
    private Integer isExtraBed;
    /** 性别限制: 0无 1男 2女 */
    private Integer genderLimit;
}
