package com.yb.hi.entity.community;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 医共体值域字典(L2, 牵头机构统一维护): 医疗业务自由值域(性别/险种/剂型/号别/抗菌分级等),
 * 单表按 dict_type 区分, dict_type = 标准源键:分组码(如 cv_code:gend / hbvalue:HBCV08.50.029)。
 * 导入源(std_*): cv_code←std_cv_code(dict_code), wst364←std_wst364_code(cv_code),
 * hbvalue←std_hbvalue_code(dict_code), whvalue←std_whvalue_code(dict_code);
 * cv_code(医保字典)源导入时 yb_code=code, 卫健/湖北/武汉源留空。
 * 业务下拉(HIS.stdValues)统一检索本字典启用项(status=1), 标准字典仅作导入源不再直连。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_val_dict")
public class HisValDict extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 值域类别=标准源键:分组码, 如 cv_code:gend / hbvalue:HBCV08.50.029 */
    private String dictType;
    /** 值域中文名(导入时取标准字典组名, 如"性别代码") */
    private String typeName;
    /** 值编码(租户内同类型唯一, 导入取标准值域码) */
    private String code;
    /** 值名称 */
    private String name;
    /** 医保值域编码(cv_code 源导入时=code; 卫健/湖北/武汉源留空) */
    private String ybCode;
    /** 排序号 */
    private Integer sortNo;
    /** 状态:1启用 0停用 */
    private Integer status;
    /** 备注 */
    private String memo;
    /** 来源标准字典key(cv_code/wst364/hbvalue/whvalue) */
    private String srcType;
    /** 来源文档(标准字典注册 src_doc) */
    private String srcDoc;
    /** 来源编码(标准值域行编码) */
    private String srcCode;

    /** 拼音简码(名称首字母, 保存时自动生成只读) */
    private String pyCode;

    /** 自定义简码(维护页可编辑, 选填) */
    private String abbrCode;
}
