package com.yb.hi.dto.inpatient;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 新生儿建档/补录请求(P2d 产科分娩一体化)。
 * register 必填 motherInpVisitId; baby_* 为新生儿基础信息(姓名/性别/出生时间/体重/身长/Apgar/分娩方式)。
 */
@Data
public class NewbornDTO {

    /** 母亲住院就诊ID(his_inp_visit.id, register 必填) */
    private Long motherInpVisitId;
    /** 分娩手术ID(his_surgery.id, 剖宫产可关联, 可空) */
    private Long surgeryId;
    /** 新生儿姓名 */
    private String babyName;
    /** 性别: 1男 2女 */
    private Integer babySex;
    /** 出生时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime birthTime;
    /** Apgar 1分钟评分 */
    private Integer apgar1;
    /** Apgar 5分钟评分 */
    private Integer apgar5;
    /** Apgar 10分钟评分 */
    private Integer apgar10;
    /** 出生体重(克) */
    private Integer weightG;
    /** 身长(厘米) */
    private BigDecimal heightCm;
    /** 分娩方式: 1顺产 2剖宫产 3产钳 4臀助 5其他 */
    private Integer birthType;
    /** 备注 */
    private String remark;
    /** 可选: 指定建卡科室/病区(缺省随母亲所在科室/病区) */
    private Long deptId;
    private Long wardId;
}
