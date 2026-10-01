package com.yb.hi.entity.doctor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 医生站用户显示偏好(个人级, 按场景存配置 JSON)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_user_display_pref")
public class HisUserDisplayPref extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户ID(sys_user.id) */
    private Long userId;
    /** 场景: dw_banner 患者信息栏 / dw_layout 布局偏好 */
    private String scene;
    /** 显示配置 JSON(字段开关与顺序) */
    private String configJson;
}
