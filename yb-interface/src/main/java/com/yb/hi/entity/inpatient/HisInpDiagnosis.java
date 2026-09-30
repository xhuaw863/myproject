package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 住院诊断(入院/补充/术后/出院四类, 主诊断标志驱动病案首页与医保上报)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_inp_diagnosis")
public class HisInpDiagnosis extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 诊断类型: 1入院诊断 2补充诊断 3术后诊断 4出院诊断 */
    private Integer diagType;
    /** 诊断编码(ICD-10) */
    private String diagCode;
    /** 诊断名称 */
    private String diagName;
    /** 是否主诊断: 1是 0否 */
    private Integer isMain;
    /** 诊断科室ID(his_dept.id) */
    private Long diagDeptId;
    /** 诊断医生ID(his_staff.id) */
    private Long diagDoctorId;
    /** 诊断时间 */
    private LocalDateTime diagTime;
    /** 排序号 */
    private Integer sortNo;
    /* ---------- 模型增强扩展列(DictSchemaMigration 幂等补列) ---------- */
    /** 入院病情: 1危急 2严重 3一般 4不适用 */
    private Integer admitCondition;
    /** 并发症标志: 1是 0否 */
    private Integer complicationFlag;
}
