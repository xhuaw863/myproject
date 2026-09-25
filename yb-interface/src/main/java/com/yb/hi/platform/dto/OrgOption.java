package com.yb.hi.platform.dto;

import lombok.Data;

/**
 * 可登录机构选项(登录/切换机构时下发前端)
 */
@Data
public class OrgOption {
    /** 机构ID */
    private Long orgId;
    /** 机构名称 */
    private String orgName;
    /** 机构层级: 1-牵头 2-乡 3-村 */
    private Integer orgLevel;
    /** 是否为该用户归属机构(默认可登录机构) */
    private Boolean home;
}
