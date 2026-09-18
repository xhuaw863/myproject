package com.yb.hi.entity.basedata;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 职工(医师/护士/药师/技师)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_staff")
public class HisStaff extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 职工工号(院内) */
    private String staffNo;
    /** 姓名 */
    private String staffName;
    /** 职工类别: 医师/护士/药师/技师/管理 */
    private String staffType;
    /** 性别 */
    private String gender;
    /** 职称编码 */
    private String titleCode;
    /** 职称名称 */
    private String titleName;
    /** 所属科室ID */
    private Long deptId;
    /** 主治医师医保编码(2201/2203) */
    private String atddrNo;
    /** 诊断医师医保编码 */
    private String diseDorNo;
    /** 身份证号 */
    private String idCard;
    /** 联系电话 */
    private String phone;
    /** 是否可挂号: 1-是 0-否 */
    private Integer canRegister;
    /** 默认挂号费(诊查费) */
    private BigDecimal regFee;
    /** 排序号 */
    private Integer sortNo;
    /** 状态: 1-在职 0-停用 */
    private Integer status;
    /** 备注 */
    private String memo;
}
