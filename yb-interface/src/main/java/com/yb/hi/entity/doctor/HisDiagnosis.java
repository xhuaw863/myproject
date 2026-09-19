package com.yb.hi.entity.doctor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 诊断(取自医保疾病目录, 供2203上传)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_diagnosis")
public class HisDiagnosis extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 就诊ID */
    private Long visitId;
    /** 诊断类别: 1-门诊诊断 */
    private String diagType;
    /** 诊断排序号 */
    private Integer diagSrtNo;
    /** 诊断代码(医保疾病目录) */
    private String diagCode;
    /** 诊断名称 */
    private String diagName;
    /** 主诊断标识: 0-否 1-是 */
    private String maindiagFlag;
    /** 诊断科室 */
    private String diagDept;
    /** 诊断医生编码 */
    private String diseDorNo;
    /** 诊断医生姓名 */
    private String diseDorName;
    /** 诊断时间 */
    private LocalDateTime diagTime;
    /** 入院病情(门诊可空) */
    private String admCond;
    /** 有效标志: 1-有效 0-无效 */
    private String valiFlag;
}
