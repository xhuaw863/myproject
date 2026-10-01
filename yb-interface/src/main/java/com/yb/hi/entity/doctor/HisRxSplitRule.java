package com.yb.hi.entity.doctor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 处方自动拆方规则(需求2.2.2.3.14.3): 按使用场景/险种/门慢病种/特药/药房等维度配置拆方优先级。
 * 数据源: 本表(org/dept 级配置); 实际开方时按 priority 匹配命中维度自动拆分处方组(C2 接线 HisPrescriptionService)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_rx_split_rule")
public class HisRxSplitRule extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID(his_org.id, 空=租户通用) */
    private Long orgId;
    /** 科室ID(his_dept.id, 空=全院通用) */
    private Long deptId;
    /** 规则名称 */
    private String ruleName;
    /** 拆方维度: usage使用场景/insutype险种/chronic_dise门慢病种/special_drug特药/pharmacy药房 */
    private String splitDim;
    /** 维度匹配值(如 insutype=310 / usage=慢病 / special_drug=1) */
    private String dimValue;
    /** 拆分优先级(数值小者优先) */
    private Integer priority;
    /** 状态: 1启用 0停用 */
    private Integer status;
}
