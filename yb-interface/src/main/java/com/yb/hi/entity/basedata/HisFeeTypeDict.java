package com.yb.hi.entity.basedata;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 患者费别字典(机构级自定义, 门诊住院统一维护): 含结算通道/控费规则/院内自付比例/优惠方式/支付方式白名单。
 * scope 列区分适用场景(OTP门诊/IPT住院/BOTH通用, 多选逗号分隔); 内置项 auto_flag=1 禁删且编码锁定。
 * tenant_id 由租户插件注入(不入 IGNORE_TABLES), org_id 由服务层按登录机构显式过滤。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_fee_type_dict")
public class HisFeeTypeDict extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 费别编码(机构内唯一; 内置项沿用历史值 self/insurance 免存量回迁) */
    private String code;
    /** 费别名称 */
    private String name;
    /** 拼音简码(自动生成只读) */
    private String pyCode;
    /** 适用场景:OTP门诊 IPT住院 BOTH通用(多选逗号分隔) */
    private String scope;
    /** 结算通道:INSURANCE/SELF/GOV/UNIT/HOSP/HELP/OTHER */
    private String channel;
    /** 默认医保险种(仅INSURANCE通道) */
    private String insutype;
    /** 系统内置:1不可删/编码锁定 */
    private Integer autoFlag;
    /** 控费开关:1启用 */
    private Integer ctlFlag;
    /** 控费强度:0超阈提示 1强阻断 */
    private Integer ctlHard;
    /** 控费适用场景:OTP/IPT/BOTH */
    private String ctlScene;
    /** 门诊次均限额(元) */
    private BigDecimal ctlAmount;
    /** 住院次均限额(元) */
    private BigDecimal ctlIptAmount;
    /** 住院日均限额(元) */
    private BigDecimal ctlDayAmount;
    /** 目录自付比例上叠加的院内比例(0~100) */
    private BigDecimal selfpayRate;
    /** 住院预交金测算比例(%) */
    private BigDecimal prepayRate;
    /** 优惠方式:NONE/RATE/AMOUNT/FULL */
    private String discountMode;
    /** 优惠比例(mode=RATE 生效) */
    private BigDecimal discountRate;
    /** 固定减免金额(mode=AMOUNT 生效) */
    private BigDecimal discountAmount;
    /** 优惠细规则JSON(预留) */
    private String discountJson;
    /** 支付方式白名单 {"otp":[...],"ipt":[...]}, 空=不限 */
    private String payLimitJson;
    /** 排序号 */
    private Integer sortNo;
    /** 状态:1启用 0停用 */
    private Integer status;
    /** 备注 */
    private String memo;
}
