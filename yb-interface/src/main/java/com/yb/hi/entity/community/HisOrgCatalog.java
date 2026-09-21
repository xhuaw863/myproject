package com.yb.hi.entity.community;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 机构开展目录(L3): 机构从医共体目录勾选能开展的项目, 只能启停选用, 不能改目录内容与价格。
 * 牵头机构默认全量开展(不落记录, 隐式启用)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_org_catalog")
public class HisOrgCatalog extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID(sys_org) */
    private Long orgId;
    /** 机构名称(冗余) */
    private String orgName;
    /** 目录类型: charge/drug/cons */
    private String catalogType;
    /** 医共体目录记录ID */
    private Long catalogId;
    /** 目录名称(冗余) */
    private String catalogName;
    /** 是否开展:1启用 0停用 */
    private Integer enabled;
    /** 开展生效日期 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate effDate;
    /** 备注 */
    private String memo;
}
