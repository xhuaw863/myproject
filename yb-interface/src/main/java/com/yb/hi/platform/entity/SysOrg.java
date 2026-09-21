package com.yb.hi.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 医共体机构(县/乡/村三级树), 按租户隔离。
 * tenant_id 由租户插件自动注入/过滤, 实体不显式映射。
 * org_id 在业务表中仅作"归属机构"标注, 不参与数据隔离。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_org")
public class SysOrg extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构编码(医共体内唯一) */
    private String orgCode;
    /** 机构名称 */
    private String orgName;
    /** 机构级别: 1-县级(牵头) 2-乡镇 3-村 */
    private Integer orgLevel;
    /** 上级机构ID(县级为0) */
    private Long parentId;
    /** 机构类型编码(医保字典 MEDINS_TYPE, 如 A100综合医院/C220乡卫生院/D600村卫生室/G100妇幼保健院) */
    private String orgType;
    /** 机构类型名称(服务端按 orgType 回填, 供列表/导出免联查) */
    private String orgTypeName;
    /** 机构类型字典来源标识(如 cv_code:MEDINS_TYPE) */
    private String orgTypeSrc;
    /** 定点医药机构编号(机构级) */
    private String fixmedinsCode;
    /** 定点医药机构名称(医保登记名, 医保1201) */
    private String fixmedinsName;
    /** 统一社会信用代码(医保1201必填) */
    private String uscc;
    /** 定点医疗服务机构类型编码(医保字典 cv_code:fixmedins_type) */
    private String fixmedinsType;
    /** 定点医疗服务机构类型名称(服务端回填) */
    private String fixmedinsTypeName;
    /** 定点机构类型字典来源标识(cv_code:fixmedins_type) */
    private String fixmedinsTypeSrc;
    /** 医院等级编码(医保字典 cv_code:hosp_lv; 决定起付线/报销比例) */
    private String hospLv;
    /** 医院等级名称(服务端回填) */
    private String hospLvName;
    /** 医院等级字典来源标识(cv_code:hosp_lv) */
    private String hospLvSrc;
    /** 医疗机构执业许可证号(卫健委执业登记) */
    private String pdLicenseNo;
    /** 编制床位数(卫统/评审/绩效) */
    private Integer bedCnt;
    /** 收费价格档次: 1/2/3(医共体分级价格执行档, 决定收费项目取 price_l1/l2/l3) */
    private Integer priceLv;
    /** 行政区划代码(关联 area_code_2021) */
    private String admvsCode;
    /** 负责人 */
    private String leader;
    /** 联系电话 */
    private String phone;
    /** 机构地址 */
    private String address;

    /* ================= 机构级医保接口配置 =================
     * 医共体内各定点机构可各自维护接口连接配置; 空值表示继承租户(sys_tenant)/全局(yml)。
     * 生效优先级: 全局 < 租户 < 机构, 由 TenantYbConfigResolver 按当前登录用户 orgId 合并。
     */
    /** 就医地区划(医保接口 mdtrtarea_admvs) */
    private String mdtrtareaAdmvs;
    /** 参保地区划(医保接口 insuplc_admdvs, 存储备用) */
    private String insuplcAdmdvs;
    /** 医保接口地址 */
    private String apiUrl;
    /** 医保文件下载地址 */
    private String fileDownloadUrl;
    /** 接收系统编码 */
    private String recerSysCode;
    /** 接口版本号 */
    private String infver;
    /** 经办人类别 */
    private String opterType;
    /** 经办人编号 */
    private String opter;
    /** 经办人姓名 */
    private String opterName;
    /** 签名号 */
    private String signNo;
    /** SM2私钥(敏感: 只写不读, 永不随响应序列化输出) */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String sm2PrivateKey;
    /** SM2公钥 */
    private String sm2PublicKey;
    /** 加密方式 */
    private String encType;
    /** 模拟平台模式: 1-模拟 0-真实(空=继承租户/全局) */
    private Integer mockEnabled;

    /** 排序号 */
    private Integer sortNo;
    /** 状态: 1-启用 0-停用 */
    private Integer status;
}
