package com.yb.hi.platform.dto;

import lombok.Data;

/**
 * 系统参数保存请求(POST /api/sys/param/save)。
 * scopeLevel: 0全局 1租户 2机构 3科室; scopeId 为空时服务端按层级从登录上下文兜底
 * (租户级=当前租户, 机构级=当前机构, 科室级=当前科室)。
 */
@Data
public class ParamSaveReq {

    /** 参数键 */
    private String paramKey;

    /** 参数值(字符串形式, 按 data_type 解析校验; 空=清除该作用域覆盖恢复继承) */
    private String value;

    /** 作用域层级: 0全局 1租户 2机构 3科室 */
    private Integer scopeLevel;

    /** 作用域对象ID(租户级=tenantId, 机构级=orgId, 科室级=deptId) */
    private Long scopeId;
}
