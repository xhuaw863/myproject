package com.yb.hi.entity.basedata;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 支付方式字典(机构级自定义, 门诊住院统一维护): 收费工作站混合支付/挂号支付/住院预交金统一取数源。
 * code 为规范大写码(新数据统一落此码); legacy_codes 存历史旧值映射(如 "cash,1"), 存量数据不回迁。
 * scope 列区分适用场景(OTP/IPT/BOTH); 内置项 auto_flag=1 禁删且编码锁定。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_pay_method_dict")
public class HisPayMethodDict extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 规范码(机构内唯一, 大写: CASH/WECHAT/...) */
    private String code;
    /** 名称 */
    private String name;
    /** 拼音简码(自动生成只读) */
    private String pyCode;
    /** 适用场景:OTP门诊 IPT住院 BOTH通用(多选逗号分隔) */
    private String scope;
    /** 历史旧值映射(逗号分隔, 如 cash,1) */
    private String legacyCodes;
    /** 分类:CASH/ELECTRONIC/CREDIT/FREE/DEPOSIT/INSURANCE */
    private String payKind;
    /** 需找零:1是 */
    private Integer changeFlag;
    /** 可充住院预交金:1是 */
    private Integer depositFlag;
    /** 纳入日结:1是 */
    private Integer dayendFlag;
    /** 退费方式:ORIGIN原路 CASH现金退 ACCOUNT退预交金 */
    private String refundWay;
    /** 系统内置:1不可删/编码锁定 */
    private Integer autoFlag;
    /** 排序号 */
    private Integer sortNo;
    /** 状态:1启用 0停用 */
    private Integer status;
    /** 备注 */
    private String memo;
}
