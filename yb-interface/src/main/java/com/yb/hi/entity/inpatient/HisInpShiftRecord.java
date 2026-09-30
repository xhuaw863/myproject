package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 病区交接班记录(病区×日期×班次唯一, 交班人→接班人双签闭环)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_inp_shift_record")
public class HisInpShiftRecord extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 病区ID(his_ward.id) */
    private Long wardId;
    /** 交班日期 */
    private LocalDate shiftDate;
    /** 班次: 1白班 2小夜 3大夜 */
    private Integer shiftType;
    /** 交班护士ID(his_staff.id) */
    private Long handoverNurseId;
    /** 接班护士ID(his_staff.id) */
    private Long takeoverNurseId;
    /** 在院总数 */
    private Integer totalPatients;
    /** 新入院 */
    private Integer newAdmit;
    /** 出院 */
    private Integer discharged;
    /** 危重人数 */
    private Integer criticalCount;
    /** 交班内容(JSON) */
    private String content;
    /** 状态: 1待接班 2已交接 */
    private Integer status;
    /* ---------- 模型增强扩展列(DictSchemaMigration 幂等补列) ---------- */
    /** SBAR-情景 */
    private String sbarSituation;
    /** SBAR-背景 */
    private String sbarBackground;
    /** SBAR-评估 */
    private String sbarAssessment;
    /** SBAR-建议 */
    private String sbarRecommendation;
}
