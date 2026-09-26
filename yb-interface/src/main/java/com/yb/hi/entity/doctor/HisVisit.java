package com.yb.hi.entity.doctor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 就诊记录(医生站接诊主表, 与挂号1:1)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_visit")
public class HisVisit extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 挂号记录ID */
    private Long registrationId;
    /** 挂号单号 */
    private String regNo;
    /** 医保就诊ID */
    private String mdtrtId;
    /** 院内就诊流水号 */
    private String iptOtpNo;
    /** 患者ID */
    private Long patientId;
    /** 院内患者号 */
    private String patientNo;
    /** 患者姓名 */
    private String patientName;
    /** 性别 */
    private String gender;
    /** 年龄 */
    private Integer age;
    /** 医保人员编号 */
    private String psnNo;
    /** 险种类型 */
    private String insutype;
    /** 科室ID */
    private Long deptId;
    /** 科室编码 */
    private String deptCode;
    /** 科室名称 */
    private String deptName;
    /** 医师ID */
    private Long staffId;
    /** 医师医保编码 */
    private String atddrNo;
    /** 医师姓名 */
    private String drName;
    /** 就诊日期 */
    private LocalDate workDate;
    /** 就诊状态: 1-候诊 2-接诊中 3-已完成 4-已取消 */
    private Integer visitStatus;
    /** 候诊序号(自挂号记录同步, 科室简码+4位流水号) */
    private String queueNo;
    /** 主诉 */
    private String chiefComplaint;
    /** 现病史 */
    private String presentIllness;
    /** 既往史 */
    private String pastHistory;
    /** 体格检查 */
    private String physicalExam;
    /** 处理意见 */
    private String treatmentOpinion;
    /** 接诊时间 */
    private LocalDateTime visitTime;
    /** 完成时间 */
    private LocalDateTime finishTime;
}
