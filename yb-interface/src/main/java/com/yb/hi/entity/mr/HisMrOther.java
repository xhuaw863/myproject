package com.yb.hi.entity.mr;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 病案编目多条扩展记录: 转科 / 过敏药物 / 重症监护出入记录(按 rec_type 归类)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_mr_other")
public class HisMrOther extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 编目主表ID(his_mr_catalog.id) */
    private Long catalogId;
    /** 就诊ID */
    private Long visitId;
    /** 记录类别:transfer转科 allergy过敏药物 icu重症监护 */
    private String recType;
    /** 编码(药物/科室编码等) */
    private String code;
    /** 名称(药物名称/转入转出科室/监护项目) */
    private String name;
    /** 详情/描述 */
    private String detail;
    /** 开始时间 */
    private LocalDateTime beginTime;
    /** 结束时间 */
    private LocalDateTime endTime;
    /** 序号 */
    private Integer sortNo;
}
