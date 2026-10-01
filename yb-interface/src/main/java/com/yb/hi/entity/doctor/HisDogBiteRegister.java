package com.yb.hi.entity.doctor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 犬伤暴露登记(咬伤/抓伤分级处置与免疫程序随访)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_dog_bite_register")
public class HisDogBiteRegister extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 门诊就诊ID */
    private Long visitId;
    /** 患者ID */
    private Long patientId;
    /** 患者姓名 */
    private String patientName;
    /** 暴露(咬伤/抓伤)时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime exposeTime;
    /** 致伤动物: 犬/猫/其他 */
    private String animalType;
    /** 动物来源与免疫/观察情况 */
    private String dogInfo;
    /** 伤口分级: 1-Ⅰ级 2-Ⅱ级 3-Ⅲ级 */
    private Integer woundGrade;
    /** 暴露部位 */
    private String woundParts;
    /** 伤口数量 */
    private Integer woundCount;
    /** 伤口处置(冲洗/消毒等) */
    private String woundHandling;
    /** 免疫程序: 五针法/四针法(2-1-1) */
    private String vaccinePlan;
    /** 首针时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime vaccineFirstTime;
    /** 下次接种日期 */
    private LocalDate vaccineNextDate;
    /** 被动免疫制剂: 1-已注射 0-未注射 */
    private Integer immunoglobulin;
    /** 登记医师ID */
    private Long doctorId;
    /** 登记医师姓名 */
    private String doctorName;
    /** 状态: 1-已登记 0-作废 */
    private Integer status;
    /** 机构ID */
    private Long orgId;
}
