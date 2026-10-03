package com.yb.hi.entity.basedata;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 医疗类别字典(医共体级模板): 医保标准值域 cv_code:med_type 整组导入后的场景/级别启用叠加层。
 * otp_use_flag/ipt_use_flag 为门诊/住院两个独立启停开关; open_levels 为 orgLevel token 逗号集(1县/2乡/3村)。
 * 不改 2201/2203 报送语义(仍存医保码); code 为医保权威锚点不可改。
 * tenant_id 由租户插件注入(不入 IGNORE_TABLES), 本表为医共体级统一配置故不含 org_id。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_med_type_dict")
public class HisMedTypeDict extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 医疗类别码(=医保 med_type 码, 租户内唯一, 权威不可改) */
    private String code;
    /** 名称(普通门诊/急诊/门诊慢特病/普通住院...) */
    private String name;
    /** 拼音简码(自动生成只读) */
    private String pyCode;
    /** 门诊使用:1启用 0停用 */
    private Integer otpUseFlag;
    /** 住院使用:1启用 0停用 */
    private Integer iptUseFlag;
    /** 开放机构级别(orgLevel token 逗号集:1县/2乡/3村) */
    private String openLevels;
    /** 医保码(=code) */
    private String ybCode;
    /** 溯源类型:cv_code */
    private String srcType;
    /** 溯源文档/字典组 */
    private String srcDoc;
    /** 溯源原始码 */
    private String srcCode;
    /** 排序号 */
    private Integer sortNo;
    /** 状态:1启用 0停用 */
    private Integer status;
    /** 备注 */
    private String memo;
}
