package com.yb.hi.entity.outpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 患者档案修改记录(字段级留痕)。
 * 档案为长期使用的关键数据, 每次建档/修改按字段记录"旧值 -> 新值", 同一次保存共享 batch_no, 便于追溯。
 * 修改人账号 = create_by(自动填充), 修改时间 = create_time(自动填充); change_by_name 存修改人姓名。
 * tenant_id 由租户插件自动注入/过滤。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_patient_change_log")
public class HisPatientChangeLog extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 患者ID */
    private Long patientId;
    /** 患者姓名(冗余, 便于检索展示) */
    private String patientName;
    /** 同一次保存批次号 */
    private String batchNo;
    /** 变更字段属性名 */
    private String fieldName;
    /** 变更字段中文名 */
    private String fieldLabel;
    /** 修改前值 */
    private String oldValue;
    /** 修改后值 */
    private String newValue;
    /** 变更来源: 建档/手动修改/医保读卡 */
    private String source;
    /** 修改人姓名(create_by 为登录账号) */
    private String changeByName;
    /** 备注 */
    private String memo;
}
