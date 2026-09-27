package com.yb.hi.entity.basedata;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 科室
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_dept")
public class HisDept extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属机构ID(sys_org); 医共体内科室按机构归属维护 */
    private Long orgId;

    /** 上级科室ID(科室层级树); 0/null=顶级大类节点(门诊/住院/病区护理/医技/行政后勤) */
    private Long parentId;
    /** 科室大类(本地受控枚举: 门诊科室/住院科室/病区护理/医技科室/行政后勤); 子节点冗余继承便于筛选 */
    private String deptCategory;
    /** 层级: 1-大类 2-科室 3-窗口/诊室(药房窗口、门诊诊室) */
    private Integer deptLevel;

    /** 科室编码(院内) */
    private String deptCode;
    /** 科室名称 */
    private String deptName;
    /** 科室类型: 临床/医技/行政 */
    private String deptType;
    /** 医保科别编码(医保字典 cv_code:caty; 2201/2203必填, 亦作标准诊疗科目分类) */
    private String deptCaty;
    /** 医保科别名称(服务端按 deptCaty 回填) */
    private String deptCatyName;
    /** 医保科别字典来源标识(cv_code:caty) */
    private String deptCatySrc;
    /** 医保科室编码 */
    private String ybDeptCode;
    /** 联系电话 */
    private String phone;
    /** 位置描述 */
    private String locDesc;
    /** 排序号 */
    private Integer sortNo;
    /** 状态: 1-启用 0-停用 */
    private Integer status;
    /** 门诊开诊: 1-开诊 0-未开诊(仅对 dept_category=门诊科室 有意义; 排班/挂号科室下拉只列开诊科室) */
    private Integer openClinic;
    /** 默认发药药房-西药渠道(his_pharmacy_def.id; 开方未手选时按 rxType 回落, 空=不预绑) */
    private Long defPharmacyWest;
    /** 默认发药药房-中药渠道(his_pharmacy_def.id; rxType含"中药"时适用) */
    private Long defPharmacyTcm;
    /** 备注 */
    private String memo;

    /** 子科室(层级树形结构, 非持久化) */
    @TableField(exist = false)
    private List<HisDept> children;

    /** 拼音简码(名称首字母, 保存时自动生成只读) */
    private String pyCode;

    /** 自定义简码(维护页可编辑, 选填) */
    private String abbrCode;
}
