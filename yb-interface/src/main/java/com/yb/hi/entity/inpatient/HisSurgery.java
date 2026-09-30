package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 手术记录主表(申请→排程→术中→术后→完成状态机, 手术团队八角色 + 切皮/缝合时间轴)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_surgery")
public class HisSurgery extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 手术编码ICD-9-CM-3 */
    private String surgeryCode;
    /** 手术名称 */
    private String surgeryName;
    /** 手术级别: 1一级 2二级 3三级 4四级 */
    private Integer surgeryLevel;
    /** 主刀医师ID(his_staff.id) */
    private Long surgeonId;
    /** 一助ID(his_staff.id) */
    private Long firstAssistantId;
    /** 二助ID(his_staff.id) */
    private Long secondAssistantId;
    /** 麻醉医师ID(his_staff.id) */
    private Long anesthesiologistId;
    /** 麻醉护士ID(his_staff.id) */
    private Long anesthesiaNurseId;
    /** 器械护士ID(his_staff.id) */
    private Long instrumentNurseId;
    /** 巡回护士ID(his_staff.id) */
    private Long circulatingNurseId;
    /** 手术间号 */
    private String roomNo;
    /** 手术日期 */
    private LocalDate scheduleDate;
    /** 手术时间段 */
    private String scheduleTime;
    /** 实际开始时间 */
    private LocalDateTime startTime;
    /** 实际结束时间 */
    private LocalDateTime endTime;
    /** 切皮时间 */
    private LocalDateTime incisionTime;
    /** 缝合时间 */
    private LocalDateTime sutureTime;
    /** ASA分级: 1-5 */
    private Integer asaGrade;
    /** 切口类型: 1清洁 2清洁污染 3污染 4感染 */
    private Integer incisionType;
    /** 状态: 1申请 2排程 3术中 4术后 5完成 6取消 */
    private Integer status;
    /** 手术科室ID(his_dept.id) */
    private Long deptId;
    /* ---------- 模型增强扩展列(DictSchemaMigration 幂等补列) ---------- */
    /** 审批状态: 0无需 1待审 2通过 3拒绝 */
    private Integer approvalStatus;
    /** 审批医师ID(his_staff.id) */
    private Long approvalDoctorId;
    /** WHO手术安全核查JSON */
    private String safetyChecklist;
}
