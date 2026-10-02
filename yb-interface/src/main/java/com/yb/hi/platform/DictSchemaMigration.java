package com.yb.hi.platform;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 字典化字段建列迁移(@Order(0), 早于所有演示/RBAC 初始化, 保证 MyBatis-Plus 查询/写入前列已存在)。
 * 为 his_patient / his_staff / sys_org 的字典编码字段补充"名称 + 来源标识"列(幂等: 列已存在则跳过)。
 * 数据回填见 {@link DictDataBackfill}(@Order(6), 在演示数据插入之后执行)。
 */
@Slf4j
@Order(0)
@Component
public class DictSchemaMigration implements ApplicationRunner {

    private final DataSource dataSource;

    @Value("${his.dict-migration.enabled:true}")
    private boolean enabled;

    public DictSchemaMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        // 表名, 列名, 列定义
        String[][] cols = {
                {"his_patient", "gender_name", "VARCHAR(50) NULL COMMENT '性别名称(字典回填)'"},
                {"his_patient", "gender_src", "VARCHAR(50) NULL COMMENT '性别字典来源标识'"},
                {"his_patient", "insutype_name", "VARCHAR(100) NULL COMMENT '险种名称(字典回填)'"},
                {"his_patient", "insutype_src", "VARCHAR(50) NULL COMMENT '险种字典来源标识'"},
                {"his_patient", "mdtrt_cert_type_name", "VARCHAR(100) NULL COMMENT '就诊凭证类型名称(字典回填)'"},
                {"his_patient", "mdtrt_cert_type_src", "VARCHAR(50) NULL COMMENT '就诊凭证类型字典来源标识'"},
                {"his_patient", "insuplc_admdvs_name", "VARCHAR(100) NULL COMMENT '参保地区划名称(字典回填)'"},
                {"his_patient", "insuplc_admdvs_src", "VARCHAR(50) NULL COMMENT '参保地区划来源标识'"},
                /* ---------- 患者档案: A 身份人口学(采集规范 BASE_PERSON_YLFW; 三件套) ---------- */
                {"his_patient", "cert_type", "VARCHAR(10) NULL COMMENT '身份证件类别编码(cv_code:psn_cert_type)'"},
                {"his_patient", "cert_type_name", "VARCHAR(100) NULL COMMENT '身份证件类别名称(字典回填)'"},
                {"his_patient", "cert_type_src", "VARCHAR(50) NULL COMMENT '身份证件类别来源标识'"},
                {"his_patient", "nation", "VARCHAR(10) NULL COMMENT '民族编码(cv_code:naty)'"},
                {"his_patient", "nation_name", "VARCHAR(50) NULL COMMENT '民族名称(字典回填)'"},
                {"his_patient", "nation_src", "VARCHAR(50) NULL COMMENT '民族来源标识'"},
                {"his_patient", "nationality", "VARCHAR(20) NULL COMMENT '国籍编码(hbvalue:GB/T 2659.1-2022)'"},
                {"his_patient", "nationality_name", "VARCHAR(100) NULL COMMENT '国籍名称(字典回填)'"},
                {"his_patient", "nationality_src", "VARCHAR(50) NULL COMMENT '国籍来源标识'"},
                {"his_patient", "marital_status", "VARCHAR(10) NULL COMMENT '婚姻状况编码(hbvalue:GB/T 2261.2-2003)'"},
                {"his_patient", "marital_status_name", "VARCHAR(50) NULL COMMENT '婚姻状况名称(字典回填)'"},
                {"his_patient", "marital_status_src", "VARCHAR(50) NULL COMMENT '婚姻状况来源标识'"},
                {"his_patient", "edu_level", "VARCHAR(20) NULL COMMENT '文化程度编码(hbvalue:GB/T 4658-2006)'"},
                {"his_patient", "edu_level_name", "VARCHAR(50) NULL COMMENT '文化程度名称(字典回填)'"},
                {"his_patient", "edu_level_src", "VARCHAR(50) NULL COMMENT '文化程度来源标识'"},
                {"his_patient", "occupation", "VARCHAR(20) NULL COMMENT '职业类别编码(hbvalue:CV02.01.202)'"},
                {"his_patient", "occupation_name", "VARCHAR(50) NULL COMMENT '职业类别名称(字典回填)'"},
                {"his_patient", "occupation_src", "VARCHAR(50) NULL COMMENT '职业类别来源标识'"},
                {"his_patient", "occupation_other", "VARCHAR(100) NULL COMMENT '职业类别其他(ZYLBQT)'"},
                /* ---------- 患者档案: B 现住址五级级联(编码+名称) + 户籍/工作单位地址 ---------- */
                {"his_patient", "present_prov", "VARCHAR(20) NULL COMMENT '现住址-省编码(area_code_2021)'"},
                {"his_patient", "present_prov_name", "VARCHAR(100) NULL COMMENT '现住址-省名称(字典回填)'"},
                {"his_patient", "present_city", "VARCHAR(20) NULL COMMENT '现住址-市编码(area_code_2021)'"},
                {"his_patient", "present_city_name", "VARCHAR(100) NULL COMMENT '现住址-市名称(字典回填)'"},
                {"his_patient", "present_county", "VARCHAR(20) NULL COMMENT '现住址-区县编码(area_code_2021)'"},
                {"his_patient", "present_county_name", "VARCHAR(100) NULL COMMENT '现住址-区县名称(字典回填)'"},
                {"his_patient", "present_town", "VARCHAR(20) NULL COMMENT '现住址-乡镇/街道编码(area_code_2021)'"},
                {"his_patient", "present_town_name", "VARCHAR(100) NULL COMMENT '现住址-乡镇/街道名称(字典回填)'"},
                {"his_patient", "present_src", "VARCHAR(50) NULL COMMENT '现住址来源标识(area_code_2021)'"},
                {"his_patient", "present_detail", "VARCHAR(255) NULL COMMENT '现住址-详细地址(村/街/路/门牌)'"},
                {"his_patient", "household_addr", "VARCHAR(255) NULL COMMENT '户籍地址(文本)'"},
                {"his_patient", "employer", "VARCHAR(200) NULL COMMENT '工作单位名称'"},
                {"his_patient", "employer_phone", "VARCHAR(30) NULL COMMENT '工作单位电话'"},
                {"his_patient", "employer_addr", "VARCHAR(255) NULL COMMENT '工作单位地址(文本)'"},
                /* ---------- 患者档案: C 联系人与患者关系(三件套) + 联系人证件/地址 ---------- */
                {"his_patient", "contact_relation", "VARCHAR(20) NULL COMMENT '联系人与患者关系编码(hbvalue:GB/T 4761-2008)'"},
                {"his_patient", "contact_relation_name", "VARCHAR(50) NULL COMMENT '联系人与患者关系名称(字典回填)'"},
                {"his_patient", "contact_relation_src", "VARCHAR(50) NULL COMMENT '联系人与患者关系来源标识'"},
                {"his_patient", "contact_id_card", "VARCHAR(30) NULL COMMENT '联系人身份证件号码'"},
                {"his_patient", "contact_addr", "VARCHAR(255) NULL COMMENT '联系人地址(文本)'"},
                /* ---------- 患者档案: B2 出生地/户籍/通讯/单位/联系人 地址四级级联(编码+名称, 来源 area_code_2021) ---------- */
                {"his_patient", "birth_prov", "VARCHAR(20) NULL COMMENT '出生地-省编码(area_code_2021)'"},
                {"his_patient", "birth_prov_name", "VARCHAR(100) NULL COMMENT '出生地-省名称(字典回填)'"},
                {"his_patient", "birth_city", "VARCHAR(20) NULL COMMENT '出生地-市编码(area_code_2021)'"},
                {"his_patient", "birth_city_name", "VARCHAR(100) NULL COMMENT '出生地-市名称(字典回填)'"},
                {"his_patient", "birth_county", "VARCHAR(20) NULL COMMENT '出生地-区县编码(area_code_2021)'"},
                {"his_patient", "birth_county_name", "VARCHAR(100) NULL COMMENT '出生地-区县名称(字典回填)'"},
                {"his_patient", "birth_town", "VARCHAR(20) NULL COMMENT '出生地-乡镇/街道编码(area_code_2021)'"},
                {"his_patient", "birth_town_name", "VARCHAR(100) NULL COMMENT '出生地-乡镇/街道名称(字典回填)'"},
                {"his_patient", "birth_src", "VARCHAR(50) NULL COMMENT '出生地来源标识(area_code_2021)'"},
                {"his_patient", "birth_detail", "VARCHAR(255) NULL COMMENT '出生地-详细地址'"},
                {"his_patient", "household_prov", "VARCHAR(20) NULL COMMENT '户籍地址-省编码(area_code_2021)'"},
                {"his_patient", "household_prov_name", "VARCHAR(100) NULL COMMENT '户籍地址-省名称(字典回填)'"},
                {"his_patient", "household_city", "VARCHAR(20) NULL COMMENT '户籍地址-市编码(area_code_2021)'"},
                {"his_patient", "household_city_name", "VARCHAR(100) NULL COMMENT '户籍地址-市名称(字典回填)'"},
                {"his_patient", "household_county", "VARCHAR(20) NULL COMMENT '户籍地址-区县编码(area_code_2021)'"},
                {"his_patient", "household_county_name", "VARCHAR(100) NULL COMMENT '户籍地址-区县名称(字典回填)'"},
                {"his_patient", "household_town", "VARCHAR(20) NULL COMMENT '户籍地址-乡镇/街道编码(area_code_2021)'"},
                {"his_patient", "household_town_name", "VARCHAR(100) NULL COMMENT '户籍地址-乡镇/街道名称(字典回填)'"},
                {"his_patient", "household_src", "VARCHAR(50) NULL COMMENT '户籍地址来源标识(area_code_2021)'"},
                {"his_patient", "mail_prov", "VARCHAR(20) NULL COMMENT '通讯地址-省编码(area_code_2021)'"},
                {"his_patient", "mail_prov_name", "VARCHAR(100) NULL COMMENT '通讯地址-省名称(字典回填)'"},
                {"his_patient", "mail_city", "VARCHAR(20) NULL COMMENT '通讯地址-市编码(area_code_2021)'"},
                {"his_patient", "mail_city_name", "VARCHAR(100) NULL COMMENT '通讯地址-市名称(字典回填)'"},
                {"his_patient", "mail_county", "VARCHAR(20) NULL COMMENT '通讯地址-区县编码(area_code_2021)'"},
                {"his_patient", "mail_county_name", "VARCHAR(100) NULL COMMENT '通讯地址-区县名称(字典回填)'"},
                {"his_patient", "mail_town", "VARCHAR(20) NULL COMMENT '通讯地址-乡镇/街道编码(area_code_2021)'"},
                {"his_patient", "mail_town_name", "VARCHAR(100) NULL COMMENT '通讯地址-乡镇/街道名称(字典回填)'"},
                {"his_patient", "mail_src", "VARCHAR(50) NULL COMMENT '通讯地址来源标识(area_code_2021)'"},
                {"his_patient", "emp_prov", "VARCHAR(20) NULL COMMENT '单位地址-省编码(area_code_2021)'"},
                {"his_patient", "emp_prov_name", "VARCHAR(100) NULL COMMENT '单位地址-省名称(字典回填)'"},
                {"his_patient", "emp_city", "VARCHAR(20) NULL COMMENT '单位地址-市编码(area_code_2021)'"},
                {"his_patient", "emp_city_name", "VARCHAR(100) NULL COMMENT '单位地址-市名称(字典回填)'"},
                {"his_patient", "emp_county", "VARCHAR(20) NULL COMMENT '单位地址-区县编码(area_code_2021)'"},
                {"his_patient", "emp_county_name", "VARCHAR(100) NULL COMMENT '单位地址-区县名称(字典回填)'"},
                {"his_patient", "emp_town", "VARCHAR(20) NULL COMMENT '单位地址-乡镇/街道编码(area_code_2021)'"},
                {"his_patient", "emp_town_name", "VARCHAR(100) NULL COMMENT '单位地址-乡镇/街道名称(字典回填)'"},
                {"his_patient", "emp_src", "VARCHAR(50) NULL COMMENT '单位地址来源标识(area_code_2021)'"},
                {"his_patient", "contact_prov", "VARCHAR(20) NULL COMMENT '联系人地址-省编码(area_code_2021)'"},
                {"his_patient", "contact_prov_name", "VARCHAR(100) NULL COMMENT '联系人地址-省名称(字典回填)'"},
                {"his_patient", "contact_city", "VARCHAR(20) NULL COMMENT '联系人地址-市编码(area_code_2021)'"},
                {"his_patient", "contact_city_name", "VARCHAR(100) NULL COMMENT '联系人地址-市名称(字典回填)'"},
                {"his_patient", "contact_county", "VARCHAR(20) NULL COMMENT '联系人地址-区县编码(area_code_2021)'"},
                {"his_patient", "contact_county_name", "VARCHAR(100) NULL COMMENT '联系人地址-区县名称(字典回填)'"},
                {"his_patient", "contact_town", "VARCHAR(20) NULL COMMENT '联系人地址-乡镇/街道编码(area_code_2021)'"},
                {"his_patient", "contact_town_name", "VARCHAR(100) NULL COMMENT '联系人地址-乡镇/街道名称(字典回填)'"},
                {"his_patient", "contact_src", "VARCHAR(50) NULL COMMENT '联系人地址来源标识(area_code_2021)'"},
                {"his_staff", "gender_name", "VARCHAR(50) NULL COMMENT '性别名称(字典回填)'"},
                {"his_staff", "gender_src", "VARCHAR(50) NULL COMMENT '性别字典来源标识'"},
                {"his_staff", "staff_type_name", "VARCHAR(50) NULL COMMENT '职工类别名称'"},
                {"his_staff", "staff_type_src", "VARCHAR(50) NULL COMMENT '职工类别来源标识'"},
                {"his_staff", "title_src", "VARCHAR(50) NULL COMMENT '职称字典来源标识'"},
                {"sys_org", "org_type_name", "VARCHAR(100) NULL COMMENT '机构类型名称(字典回填)'"},
                {"sys_org", "org_type_src", "VARCHAR(50) NULL COMMENT '机构类型字典来源标识'"},
                /* ---------- P0: 机构医保/执业/统计字段(医保1201对齐) ---------- */
                {"sys_org", "fixmedins_name", "VARCHAR(200) NULL COMMENT '定点医药机构名称(医保登记名)'"},
                {"sys_org", "uscc", "VARCHAR(50) NULL COMMENT '统一社会信用代码'"},
                {"sys_org", "fixmedins_type", "VARCHAR(6) NULL COMMENT '定点医疗服务机构类型编码(cv_code:fixmedins_type)'"},
                {"sys_org", "fixmedins_type_name", "VARCHAR(100) NULL COMMENT '定点医疗服务机构类型名称(字典回填)'"},
                {"sys_org", "fixmedins_type_src", "VARCHAR(50) NULL COMMENT '定点机构类型来源标识'"},
                {"sys_org", "hosp_lv", "VARCHAR(6) NULL COMMENT '医院等级编码(cv_code:hosp_lv)'"},
                {"sys_org", "hosp_lv_name", "VARCHAR(100) NULL COMMENT '医院等级名称(字典回填)'"},
                {"sys_org", "hosp_lv_src", "VARCHAR(50) NULL COMMENT '医院等级来源标识'"},
                {"sys_org", "pd_license_no", "VARCHAR(50) NULL COMMENT '医疗机构执业许可证号'"},
                {"sys_org", "bed_cnt", "INT NULL COMMENT '编制床位数'"},
                /* ---------- 机构级医保接口配置(医共体内各定点机构独立; 覆盖租户级, 由 TenantYbConfigResolver 生效) ---------- */
                {"sys_org", "mdtrtarea_admvs", "VARCHAR(20) NULL COMMENT '医保接口-就医地区划(机构级)'"},
                {"sys_org", "insuplc_admdvs", "VARCHAR(20) NULL COMMENT '医保接口-参保地区划(机构级, 存储备用)'"},
                {"sys_org", "api_url", "VARCHAR(255) NULL COMMENT '医保接口地址(机构级)'"},
                {"sys_org", "file_download_url", "VARCHAR(255) NULL COMMENT '医保文件下载地址(机构级)'"},
                {"sys_org", "recer_sys_code", "VARCHAR(50) NULL COMMENT '接收系统编码(机构级)'"},
                {"sys_org", "infver", "VARCHAR(20) NULL COMMENT '接口版本号(机构级)'"},
                {"sys_org", "opter_type", "VARCHAR(10) NULL COMMENT '经办人类别(机构级)'"},
                {"sys_org", "opter", "VARCHAR(50) NULL COMMENT '经办人编号(机构级)'"},
                {"sys_org", "opter_name", "VARCHAR(100) NULL COMMENT '经办人姓名(机构级)'"},
                {"sys_org", "sign_no", "VARCHAR(100) NULL COMMENT '签名号(机构级)'"},
                {"sys_org", "sm2_private_key", "TEXT NULL COMMENT 'SM2私钥(机构级)'"},
                {"sys_org", "sm2_public_key", "TEXT NULL COMMENT 'SM2公钥(机构级)'"},
                {"sys_org", "enc_type", "VARCHAR(20) NULL COMMENT '加密方式(机构级)'"},
                {"sys_org", "mock_enabled", "TINYINT NULL COMMENT '模拟平台模式:1-模拟 0-真实(空=继承租户/全局)'"},
                /* ---------- P0: 科室医保科别三件套(2201/2203必填caty) ---------- */
                {"his_dept", "dept_caty_name", "VARCHAR(100) NULL COMMENT '医保科别名称(cv_code:caty回填)'"},
                {"his_dept", "dept_caty_src", "VARCHAR(50) NULL COMMENT '医保科别来源标识'"},
                /* ---------- 科室层级树(大类/科室/窗口诊室三级) ---------- */
                {"his_dept", "parent_id", "BIGINT NULL DEFAULT 0 COMMENT '上级科室ID(0/null=顶级大类)'"},
                {"his_dept", "dept_category", "VARCHAR(20) NULL COMMENT '科室大类(门诊科室/住院科室/病区护理/医技科室/行政后勤)'"},
                {"his_dept", "dept_level", "TINYINT NULL DEFAULT 2 COMMENT '层级:1-大类 2-科室 3-窗口/诊室'"},
                /* ---------- 科室门诊开诊标志(排班/挂号只列本机构开诊的门诊科室) ---------- */
                {"his_dept", "open_clinic", "TINYINT NULL DEFAULT 1 COMMENT '门诊开诊:1-开诊 0-未开诊(仅门诊科室大类生效)'"},
                /* ---------- P0: 人员执业资质/医保编码/出生日期 ---------- */
                {"his_staff", "med_insur_code", "VARCHAR(30) NULL COMMENT '国家医保业务编码(医师/药师/护士)'"},
                {"his_staff", "prac_cate", "VARCHAR(4) NULL COMMENT '执业类别编码(whvalue:CT98.00.024 临床/口腔/公共卫生/中医)'"},
                {"his_staff", "prac_cate_name", "VARCHAR(50) NULL COMMENT '执业类别名称(字典回填)'"},
                {"his_staff", "prac_cate_src", "VARCHAR(50) NULL COMMENT '执业类别来源标识'"},
                {"his_staff", "dr_qual_cert_no", "VARCHAR(50) NULL COMMENT '医师资格证号'"},
                {"his_staff", "prac_cert_no", "VARCHAR(50) NULL COMMENT '医师执业证书编码'"},
                {"his_staff", "birth_date", "DATE NULL COMMENT '出生日期'"},
                /* ---------- 人员头像/签名图片(本地上传URL) ---------- */
                {"his_staff", "avatar_url", "VARCHAR(255) NULL COMMENT '头像图片URL(/uploads/...)'"},
                {"his_staff", "sign_img_url", "VARCHAR(255) NULL COMMENT '签名图片URL(电子处方/文档签名)'"},
                /* ---------- 医师处方权限(药事管理强管控: 处方权/精麻/抗菌分级/授权留痕) ---------- */
                {"his_staff", "rx_right", "TINYINT NULL DEFAULT 0 COMMENT '处方权(总):1-具备 0-无'"},
                {"his_staff", "narcotic_right", "TINYINT NULL DEFAULT 0 COMMENT '麻醉药品处方权:1-有 0-无'"},
                {"his_staff", "psych1_right", "TINYINT NULL DEFAULT 0 COMMENT '第一类精神药品处方权:1-有 0-无'"},
                {"his_staff", "psych2_right", "TINYINT NULL DEFAULT 0 COMMENT '第二类精神药品处方权:1-有 0-无'"},
                {"his_staff", "antibiotic_level", "VARCHAR(6) NULL COMMENT '抗菌药物处方权级别(hbvalue:HBCV08.50.029 11/12/13)'"},
                {"his_staff", "antibiotic_level_name", "VARCHAR(50) NULL COMMENT '抗菌药物处方权级别名称(字典回填)'"},
                {"his_staff", "antibiotic_level_src", "VARCHAR(50) NULL COMMENT '抗菌药物处方权级别来源标识'"},
                {"his_staff", "surgery_level", "VARCHAR(6) NULL COMMENT '手术级别权限(cv_code:oprn_lv_code 1-4级/5其他)'"},
                {"his_staff", "surgery_level_name", "VARCHAR(50) NULL COMMENT '手术级别权限名称(字典回填)'"},
                {"his_staff", "surgery_level_src", "VARCHAR(50) NULL COMMENT '手术级别权限来源标识'"},
                {"his_staff", "rx_auth_org", "VARCHAR(100) NULL COMMENT '处方权授权机构(医务科/授权部门)'"},
                {"his_staff", "rx_auth_no", "VARCHAR(50) NULL COMMENT '处方权授权文号'"},
                {"his_staff", "rx_auth_date", "DATE NULL COMMENT '处方权授权日期'"},
                {"his_staff", "rx_valid_until", "DATE NULL COMMENT '处方权有效期至(到期需复训)'"},
                /* ---------- 用户账号: 授权科室范围(数据权限, 空=仅主属科室) ---------- */
                {"sys_user", "dept_scope", "VARCHAR(500) NULL COMMENT '授权科室范围(逗号分隔dept_id, 空=仅主属科室)'"},
                /* ---------- 医共体统一目录: 收费项目升级(牵头机构统一定义一二三级分级价格 + 标准溯源) ---------- */
                {"his_charge_item", "price_l1", "DECIMAL(12,4) NULL COMMENT '一级机构价格(牵头机构统一定义)'"},
                {"his_charge_item", "price_l2", "DECIMAL(12,4) NULL COMMENT '二级机构价格(牵头机构统一定义)'"},
                {"his_charge_item", "price_l3", "DECIMAL(12,4) NULL COMMENT '三级机构价格(牵头机构统一定义)'"},
                {"his_charge_item", "nat_item_code", "VARCHAR(32) NULL COMMENT '全国医疗服务项目编码(std_msi_nat/msi_hb.item_code)'"},
                {"his_charge_item", "loc_item_code", "VARCHAR(32) NULL COMMENT '湖北地方项目编码(std_med_service.loc_item_code)'"},
                {"his_charge_item", "item_content", "VARCHAR(2000) NULL COMMENT '项目内涵'"},
                {"his_charge_item", "item_excluded", "VARCHAR(1000) NULL COMMENT '除外内容'"},
                {"his_charge_item", "invoice_class", "VARCHAR(100) NULL COMMENT '收费票据分类'"},
                {"his_charge_item", "acct_class", "VARCHAR(100) NULL COMMENT '会计科目分类'"},
                {"his_charge_item", "mr_cost_class", "VARCHAR(200) NULL COMMENT '病案首页费用分类(归并), 值域std_mr_cost_class.raw_value'"},
                {"his_charge_item", "cat_code", "VARCHAR(20) NULL COMMENT '物价分类码(std_msi_cat.cat_code, 导入自msi_nat.cat_code)'"},
                {"his_charge_item", "dept_caty", "VARCHAR(100) NULL COMMENT '医疗科室类别'"},
                {"his_charge_item", "src_type", "VARCHAR(30) NULL COMMENT '来源标准字典key(med_service/msi_hb/msi_nat/院内自定义)'"},
                {"his_charge_item", "src_doc", "VARCHAR(200) NULL COMMENT '来源文档'"},
                {"his_charge_item", "src_code", "VARCHAR(50) NULL COMMENT '来源编码(标准字典行编码)'"},
                {"his_charge_item", "eff_date", "DATE NULL COMMENT '生效日期'"},
                {"his_charge_item", "end_date", "DATE NULL COMMENT '作废日期'"},
                /* ---------- 机构收费价格档次(分级价格执行档位, 空=按org_level默认 县→2 乡/村→1) ---------- */
                {"sys_org", "price_lv", "TINYINT NULL COMMENT '收费价格档次:1/2/3(医共体分级价格执行档)'"},
                /* ---------- 处方明细: 医共体药品目录关联与包装/剂量换算参数快照 ---------- */
                {"his_prescription_item", "drug_id", "BIGINT NULL COMMENT '医共体药品目录ID(his_drug_catalog.id)'"},
                {"his_prescription_item", "unit_dose", "DECIMAL(12,4) NULL COMMENT '每最小包装单位含药量(换算快照)'"},
                {"his_prescription_item", "pack_ratio", "INT NULL COMMENT '包装换算比(大包装→最小单位, 快照)'"},
                {"his_prescription_item", "round_rule", "TINYINT NULL COMMENT '发药取整规则快照:1向上 2向下 3四舍五入'"},
                {"his_prescription_item", "group_no", "VARCHAR(20) NULL COMMENT '用药组号'"},
                /* ---------- 医保对照变更留痕: 生效时间 + 变更前医保码(三目录统一) ---------- */
                {"his_drug_catalog", "prev_yb_code", "VARCHAR(64) NULL COMMENT '变更前医保码(上一次对照的医保编码)'"},
                {"his_drug_catalog", "yb_map_eff_time", "DATETIME NULL COMMENT '医保对照生效时间(当前医保码开始生效时刻)'"},
                {"his_cons_catalog", "prev_yb_code", "VARCHAR(64) NULL COMMENT '变更前医保码(上一次对照的医保编码)'"},
                {"his_cons_catalog", "yb_map_eff_time", "DATETIME NULL COMMENT '医保对照生效时间(当前医保码开始生效时刻)'"},
                {"his_charge_item", "prev_yb_code", "VARCHAR(64) NULL COMMENT '变更前医保码(上一次对照的医保编码)'"},
                {"his_charge_item", "yb_map_eff_time", "DATETIME NULL COMMENT '医保对照生效时间(当前医保码开始生效时刻)'"},
                /* ---------- 药库/药房/收费业务闭环: 处方发药状态 + 就诊收费状态 ---------- */
                {"his_prescription", "dispense_status", "TINYINT DEFAULT 0 COMMENT '发药状态:0未发药 1已发药 2已退药'"},
                {"his_visit", "charge_status", "TINYINT DEFAULT 0 COMMENT '收费状态:0未收费 1已收费 2已退费'"},
                /* ---------- 排班重构: 诊室/来源模板/停诊原因 ---------- */
                {"his_schedule", "room", "VARCHAR(50) DEFAULT NULL COMMENT '诊室'"},
                {"his_schedule", "template_id", "BIGINT DEFAULT NULL COMMENT '来源模板ID'"},
                {"his_schedule", "stop_reason", "VARCHAR(200) DEFAULT NULL COMMENT '停诊原因'"},
                /* ---------- 字典简码(拼音首字母 py_code + 自定义码 abbr_code): 院内可维护字典两列, 机构/患者/区划/医保目录仅 py_code ---------- */
                {"his_staff", "py_code", "VARCHAR(64) NULL COMMENT '拼音简码(姓名首字母, 自动生成只读)'"},
                {"his_staff", "abbr_code", "VARCHAR(64) NULL COMMENT '自定义简码(人工维护, 选填)'"},
                {"his_dept", "py_code", "VARCHAR(64) NULL COMMENT '拼音简码(名称首字母, 自动生成只读)'"},
                {"his_dept", "abbr_code", "VARCHAR(64) NULL COMMENT '自定义简码(人工维护, 选填)'"},
                {"his_drug_catalog", "py_code", "VARCHAR(64) NULL COMMENT '拼音简码(通用名首字母, 自动生成只读)'"},
                {"his_drug_catalog", "abbr_code", "VARCHAR(64) NULL COMMENT '自定义简码(人工维护, 选填)'"},
                {"his_charge_item", "py_code", "VARCHAR(64) NULL COMMENT '拼音简码(项目名称首字母, 自动生成只读)'"},
                {"his_charge_item", "abbr_code", "VARCHAR(64) NULL COMMENT '自定义简码(人工维护, 选填)'"},
                {"his_cons_catalog", "py_code", "VARCHAR(64) NULL COMMENT '拼音简码(耗材名称首字母, 自动生成只读)'"},
                {"his_cons_catalog", "abbr_code", "VARCHAR(64) NULL COMMENT '自定义简码(人工维护, 选填)'"},
                /* ---------- 企业字典化: 药品/耗材目录生产企业与上市许可持有人改为 std_supplier 编码引用(名称仍冗余回填) ---------- */
                {"his_drug_catalog", "manufacturer_code", "VARCHAR(40) NULL COMMENT '生产企业编码(std_supplier.sup_code)'"},
                {"his_drug_catalog", "mkt_holder_code", "VARCHAR(40) NULL COMMENT '上市许可持有人编码(std_supplier.sup_code)'"},
                {"his_cons_catalog", "manufacturer_code", "VARCHAR(40) NULL COMMENT '生产企业编码(std_supplier.sup_code)'"},
                {"his_med_dict", "py_code", "VARCHAR(64) NULL COMMENT '拼音简码(名称首字母, 自动生成只读)'"},
                {"his_med_dict", "abbr_code", "VARCHAR(64) NULL COMMENT '自定义简码(人工维护, 选填)'"},
                {"sys_org", "py_code", "VARCHAR(64) NULL COMMENT '拼音简码(机构名称首字母, 自动生成只读)'"},
                {"his_patient", "py_code", "VARCHAR(64) NULL COMMENT '拼音简码(患者姓名首字母, 自动生成只读)'"},
                {"area_code_2021", "py_code", "VARCHAR(64) NULL COMMENT '拼音简码(区划名称首字母, 自动生成只读)'"},
                {"med_service_catalog", "py_code", "VARCHAR(64) NULL COMMENT '拼音简码(项目名称首字母, 自动生成只读)'"},
                {"consumable_catalog", "py_code", "VARCHAR(64) NULL COMMENT '拼音简码(耗材名称首字母, 自动生成只读)'"},
                {"disease_catalog", "py_code", "VARCHAR(64) NULL COMMENT '拼音简码(诊断名称首字母, 自动生成只读)'"},
                /* ---------- 挂号增强: 费别/减免/实收/支付方式/候诊序号 ---------- */
                {"his_registration", "fee_type", "VARCHAR(10) NULL COMMENT '费别编码'"},
                {"his_registration", "discount_type", "VARCHAR(20) NULL COMMENT '减免类型编码(none/age70free等)'"},
                {"his_registration", "discount_reason", "VARCHAR(200) NULL COMMENT '减免原因说明'"},
                {"his_registration", "discount_amount", "DECIMAL(10,2) DEFAULT 0 COMMENT '减免金额'"},
                {"his_registration", "actual_fee", "DECIMAL(10,2) NULL COMMENT '实收金额(挂号费-减免金额)'"},
                {"his_registration", "pay_method", "VARCHAR(20) NULL COMMENT '支付方式(free=免费)'"},
                {"his_registration", "pay_detail", "VARCHAR(500) NULL COMMENT '混合支付明细JSON'"},
                {"his_registration", "queue_no", "VARCHAR(20) NULL COMMENT '候诊序号(科室简码+4位流水号)'"},
                /* ---------- 就诊增强: 候诊序号(自挂号记录同步) ---------- */
                {"his_visit", "queue_no", "VARCHAR(20) NULL COMMENT '候诊序号(自挂号记录同步)'"},
                /* ---------- 医生站增强: 医疗类别自挂号同步 + 2203病种/计划生育字段 + 病历补充(过敏史/辅助检查) + 随访 ---------- */
                {"his_visit", "med_type", "VARCHAR(10) NULL COMMENT '医疗类别(2203 medType, 自挂号同步: 11普通门诊 14急诊)'"},
                {"his_visit", "allergy_history", "VARCHAR(500) NULL COMMENT '过敏史'"},
                {"his_visit", "aux_exam", "VARCHAR(1000) NULL COMMENT '辅助检查'"},
                {"his_visit", "dise_type_code", "VARCHAR(20) NULL COMMENT '病种类型代码(2203 mdtrtinfo.dise_type_code)'"},
                {"his_visit", "birctrl_type", "VARCHAR(10) NULL COMMENT '计划生育手术类别(2203 mdtrtinfo.birctrl_type)'"},
                {"his_visit", "birctrl_matn_date", "DATE NULL COMMENT '计划生育手术或生育日期(2203 mdtrtinfo.birctrl_matn_date)'"},
                {"his_visit", "followup_date", "DATE NULL COMMENT '随访日期'"},
                {"his_visit", "followup_note", "VARCHAR(500) NULL COMMENT '随访备注'"},
                {"his_medical_record", "allergy_history", "VARCHAR(500) NULL COMMENT '过敏史'"},
                {"his_medical_record", "aux_exam", "VARCHAR(1000) NULL COMMENT '辅助检查'"},
                /* ---------- 护士站/治疗/医技三模块基座: 医嘱单执行状态/执行科室/收费标志(实体已映射, alterExistingTables 双保险) ---------- */
                {"his_order", "exec_status", "TINYINT DEFAULT 0 COMMENT '执行状态:0未执行 1已执行'"},
                {"his_order", "exec_dept_id", "BIGINT DEFAULT NULL COMMENT '执行科室ID(his_dept.id)'"},
                {"his_order", "paid_flag", "TINYINT DEFAULT 0 COMMENT '收费标志:0未收费 1已收费'"},
                /* ---------- 医共体诊断字典: 就诊诊断落库带类别(west/tcm/symp/oper/tumor, 源自his_diag_dict.dict_type) ---------- */
                {"his_diagnosis", "diag_class", "VARCHAR(20) NULL COMMENT '诊断类别: west/tcm/symp/oper/tumor(源自医共体诊断字典dict_type)'"},
                /* ---------- 批次4: 收费单医保结算状态(两阶段化中间态, 存量库补列; 新库由 ensureCashierTables 建列) ---------- */
                {"his_charge_bill", "yb_status", "TINYINT NOT NULL DEFAULT 0 COMMENT '医保结算状态:0未结算 1结算中 2已结算 3撤销中 4已撤销 9冲正中'"},
                /* ---------- 批次4 M3: 日结口径补挂号费与全渠道分项(存量库补列; 新库由 ensureCashierTables 建列) ---------- */
                {"his_daily_settle", "reg_count", "INT DEFAULT 0 COMMENT '挂号笔数(净额: 挂号-退号)'"},
                {"his_daily_settle", "reg_amount", "DECIMAL(12,2) DEFAULT 0 COMMENT '挂号费净额(挂号-退号)'"},
                {"his_daily_settle", "wechat_total", "DECIMAL(12,2) DEFAULT 0 COMMENT '微信合计(收费-退费净额)'"},
                {"his_daily_settle", "alipay_total", "DECIMAL(12,2) DEFAULT 0 COMMENT '支付宝合计(收费-退费净额)'"},
                {"his_daily_settle", "card_total", "DECIMAL(12,2) DEFAULT 0 COMMENT '银行卡合计(收费-退费净额)'"},
                {"his_daily_settle", "free_total", "DECIMAL(12,2) DEFAULT 0 COMMENT '减免合计(收费-退费净额)'"},
                /* ---------- 供货商(企业)字典扩充: 医院药品采购/首营企业审核必备字段(GSP第六十二条+集采两票制+主流HIS供应商档案), 全局共享 ---------- */
                /* 基础信息 */
                {"std_supplier", "sup_short_name", "VARCHAR(100) NULL COMMENT '企业简称(列表检索/显示)'"},
                {"std_supplier", "uscc", "VARCHAR(32) NULL COMMENT '统一社会信用代码(三证合一)'"},
                {"std_supplier", "legal_person", "VARCHAR(60) NULL COMMENT '法定代表人'"},
                {"std_supplier", "reg_capital", "VARCHAR(40) NULL COMMENT '注册资本(万元)'"},
                {"std_supplier", "estab_date", "VARCHAR(20) NULL COMMENT '成立日期'"},
                {"std_supplier", "reg_address", "VARCHAR(200) NULL COMMENT '注册地址'"},
                {"std_supplier", "business_scope", "VARCHAR(500) NULL COMMENT '生产/经营范围'"},
                /* 资质证照 */
                {"std_supplier", "license_no", "VARCHAR(64) NULL COMMENT '药品生产/经营许可证号'"},
                {"std_supplier", "license_expiry", "VARCHAR(20) NULL COMMENT '许可证有效期'"},
                {"std_supplier", "license_authority", "VARCHAR(120) NULL COMMENT '许可证发证机关'"},
                {"std_supplier", "gmp_gsp_no", "VARCHAR(64) NULL COMMENT 'GMP/GSP证书号'"},
                {"std_supplier", "gmp_gsp_expiry", "VARCHAR(20) NULL COMMENT 'GMP/GSP证书有效期'"},
                /* 联系与开票 */
                {"std_supplier", "contact_person", "VARCHAR(60) NULL COMMENT '联系人'"},
                {"std_supplier", "contact_phone", "VARCHAR(60) NULL COMMENT '联系电话'"},
                {"std_supplier", "fax", "VARCHAR(40) NULL COMMENT '传真'"},
                {"std_supplier", "email", "VARCHAR(120) NULL COMMENT '电子邮箱'"},
                {"std_supplier", "bank_name", "VARCHAR(160) NULL COMMENT '开户银行'"},
                {"std_supplier", "bank_account", "VARCHAR(64) NULL COMMENT '银行账号'"},
                {"std_supplier", "tax_no", "VARCHAR(32) NULL COMMENT '纳税人识别号'"},
                {"std_supplier", "invoice_title", "VARCHAR(160) NULL COMMENT '发票抬头'"},
                /* 招采与合规 */
                {"std_supplier", "two_ticket_flag", "VARCHAR(2) NULL COMMENT '是否两票制:1是 0否'"},
                {"std_supplier", "platform_code", "VARCHAR(64) NULL COMMENT '招采/挂网平台编码'"},
                {"std_supplier", "delivery_area", "VARCHAR(200) NULL COMMENT '配送区域'"},
                {"std_supplier", "blacklist_flag", "VARCHAR(2) NULL COMMENT '失信/黑名单标志:1是 0否'"},
        };
        int added = 0;
        try (Connection conn = dataSource.getConnection()) {
            ensureChangeLogTable(conn);
            ensurePatientInsuTable(conn);
            ensurePsnInsuStasDict(conn);
            ensureCommunityDictTables(conn);
            ensureWarehouseTables(conn);
            ensurePharmacyTables(conn);
            ensureCashierTables(conn);
            // 批次4: 医保出站交易日志 + 补偿任务
            ensureYbTxnTables(conn);
            ensureYbMapLogTable(conn);
            ensureDictEditLogTable(conn);
            ensureScheduleTemplateTables(conn);
            ensureDoctorDocTables(conn);
            ensureMedicalTemplateTable(conn);
            // 系统参数基座: 分组 + 参数两表(Task #7)
            ensureSystemParamTables(conn);
            ensureCommunityDictSeeds(conn);
            ensureDrugMajorClassDict(conn);
            seedRegLevelDict(conn);
            // 供货商(企业)字典基表: 全局共享(不分租户), 含元数据/生命周期列, 供目录企业引用与专用维护屏
            ensureStdSupplierTable(conn);
            ensureSupplierFileTable(conn);
            for (String[] c : cols) {
                if (!columnExists(conn, c[0], c[1])) {
                    try (Statement st = conn.createStatement()) {
                        st.executeUpdate("ALTER TABLE " + c[0] + " ADD COLUMN " + c[1] + " " + c[2]);
                        added++;
                    }
                }
            }
            // price_lv 默认档回填依赖上面新增的 sys_org.price_lv 列, 必须在建列循环之后执行
            ensureOrgPriceLv(conn);
            // open_clinic 新增列对存量行会落 NULL, 统一按开诊回填(保持既有科室可见行为不变)
            normalizeDeptOpenClinic(conn);
            // 标准字典大表(16 张 std_*)幂等补 py_code 列(drug_catalog 已有源 pinyin 列, 不加)
            ensureStdPyCodeColumns(conn);
            // 患者出生日期精度升级: DATE -> DATETIME(新生儿需精确到时分秒; 幂等, 仅当前类型为 date 时 MODIFY)
            if ("date".equalsIgnoreCase(columnDataType(conn, "his_patient", "birth_date"))) {
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate("ALTER TABLE his_patient MODIFY COLUMN birth_date DATETIME NULL COMMENT '出生日期时间(精确到秒)'");
                    added++;
                }
            }
            // 收费项目细分类别加宽: msi_hb.cat_name 是含说明的分类路径(最长54字符), 原 VARCHAR(50) 批量导入溢出 -> 加宽到 255
            // (幂等, 仅当前容量<255时 MODIFY; 加宽不截断存量数据)
            Integer itemCatLen = columnCharLen(conn, "his_charge_item", "item_cat");
            if (itemCatLen != null && itemCatLen < 255) {
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate("ALTER TABLE his_charge_item MODIFY COLUMN item_cat VARCHAR(255) DEFAULT NULL COMMENT '细分类别(msi_hb/cat_name 含分类路径)'" );
                    added++;
                }
            }
            /* ---------- 多库房/多药房/发票/混合支付/盘点: 幂等建表(表已存在则跳过) ---------- */
            ensureWarehouseDefTable(conn);
            ensurePharmacyDefTable(conn);
            ensureInvoicePoolTable(conn);
            ensureInvoiceTable(conn);
            ensurePaymentDetailTable(conn);
            ensureStockCheckTables(conn);
            // 二级库存专业化(P1-P5): 请领/调拨/调价/追溯码 新表(幂等, 新模块非启动关键路径)
            ensureStockChainTables(conn);
            // 药库管理系统升级批次A: 供应商/采购规则/采购计划(含明细)/采购订单(含明细) 新表(幂等, 非启动关键路径)
            ensureWarehousePurchaseTables(conn);
            // 药库管理系统升级批次B: 财务验收单(含明细)/平账记录 新表 + his_stock_in/_item 采购入库增强补列(幂等)
            ensureWarehouseAcceptTables(conn);
            // 药库管理系统升级批次C: 供应商付款单(含明细) 新表 + his_stock_in 已付回写补列(幂等)
            ensureWarehousePaymentTables(conn);
                        // 药库管理系统升级批次D: 药品养护(单+明细+模板) + 库房月结 新表(幂等)
                        ensureWarehouseOpsTables(conn);
            // 三期: 药房维度定价覆盖表(新发药/定价链路直接依赖, 与关键段双保险幂等)
            ensurePharmacyPriceTable(conn);
            // P1 发药窗口子系统: 窗口/工作站/科室定向/跨药房配置/签到 5 表(幂等, 新模块非启动关键路径)
            ensurePharmacyWindowTables(conn);
            // 护士站/治疗管理/医技管理三模块基座: 12 张新表(幂等, 新模块非启动关键路径)
            ensureNurseTables(conn);
            ensureTreatmentTables(conn);
            ensureMedtechTables(conn);
            // 住院模块基座: 12 张新表(幂等, 新模块非启动关键路径)
            ensureInpatientTables(conn);
            // 临床路径 + 手术麻醉两模块基座: 9 张新表 + 住院医嘱/费用明细挂路径与手术补列(幂等, 新模块非启动关键路径)
            ensurePathwayAndSurgeryTables(conn);
            // 手麻P0升级: 手术申请单/权限规则/费用模板/通知记录 5 新表 + his_surgery(费)门诊化改造与补列(幂等, 新模块非启动关键路径)
            ensureSurgeryApplyTables(conn);
            // 住院模型增强: 15 张新表(过敏/知情同意/会诊/转科转床/费用预警/医嘱模板/护理量表/病历模板/质控/宏变量/日清单/打印模板/报表快照)
            // + 10 张存量表扩展列(幂等, 新模块非启动关键路径)
            ensureInpatientEnhancementTables(conn);
            // 手麻P2: 医嘱模板手术场景扩展列 + 通知渠道下发展开列 + 新生儿建档新表(依赖 his_order_template 已建, 故置于增强表之后; 幂等)
            ensureSurgeryP2Tables(conn);
            // UI升级(住院看板/通知角标): his_inp_notification 住院通知表(幂等, 新模块非启动关键路径)
            ensureUIEnhancementTables(conn);
            // P4 住院发药增强: 住院发药记录/病区暂存冲抵台账/出院带药取药二次核发 3 表 + his_drug_return.keep_ward_flag + his_inp_order.dispense_status 补列(幂等, 新模块非启动关键路径)
            ensureInpDispenseTables(conn);
            // P0 安全基座(T34): 危急值规则/危急值处理记录/病案首页/电子签名日志 4 表 + his_inp_order 药审四列 + 危急值规则种子(幂等)
            ensureP0SafetyTables(conn);
            // P1/P2 住院深化(T41): 病历版本快照/出入量记录 2 表 + 医嘱续开/病历三级签名/SBAR评估建议/费用结算关联/视频会诊/预入院 12 列(幂等)
            ensureP1P2Tables(conn);
            // 门诊医生站对标优化 OP-A(接诊增强): 展示偏好/发热登记/诊前预问诊/生命体征 4 表 + his_visit 诊后去向补列(幂等, 新模块非启动关键路径)
            ensureOutpWsEnhancementTables(conn);
            // 门诊医生站对标优化 OP-B(诊断进阶): 诊断高频/疾病报卡/诊断→模板链接 3 表 + his_diagnosis牙位/his_medical_cert审核/his_dept审核开关补列(幂等)
            ensureOutpWsDiagnosisTables(conn);
            // 门诊医生站对标优化 OP-C(医嘱处方专业化): 拆方规则/自动计费规则/医嘱高频/慢特病备案 4 表 + his_prescription_item草药5列/his_drug_catalog适应症补列(幂等)
            ensureOutpRxOrderTables(conn);
            // 门诊医生站对标优化 OP-D(诊间业务与集成): 门诊知情同意/代办登记/转诊/绿通信用/犬伤登记 5 表 + 模板收藏/住院证预开卡补列(幂等)
            ensureOutpWsIntegrationTables(conn);
            // 病案统计科管理系统(P0 核心编目闭环): 编目主表/诊断明细/手术明细/多条扩展/分配/审核确认锁定/质控错误项/修改留痕 8 表(幂等, 新模块非启动关键路径)
            ensureMedicalRecordTables(conn);
            // 住院医生站顺延项 T2 阶段0: 特殊药品分级/管制示例数据幂等回填(激活抗菌/精麻处方权限前端拦截的触发材料, 演示数据, 仅空列回填)
            ensureInpDoctorT2Tables(conn);
            // 存量表补列: 药库/药房归属 + 混合支付/发票号/退费关联/部分退费已退数量(幂等, 列已存在则跳过)
            alterExistingTables(conn);
        } catch (Exception e) {
            log.warn("字典化字段建列迁移跳过: {}", e.getMessage());
            return;
        }
        try (Connection conn = dataSource.getConnection()) {
            /* ---------- 关键结构迁移: 新表 + 存量表补列 + 库存唯一键重建 ----------
             * 新增列被收费/发药/统计 SQL 直接引用, 一旦像上面那样静默跳过, 应用会"带病启动"、
             * 运行期全线 Unknown column 报错。因此独立成段, 失败直接阻断启动。 */
            ensureWarehouseDefTable(conn);
            ensurePharmacyDefTable(conn);
            ensureInvoicePoolTable(conn);
            ensureInvoiceTable(conn);
            ensurePaymentDetailTable(conn);
            ensureStockCheckTables(conn);
            ensurePharmacyPriceTable(conn);
            ensureRegPaymentTable(conn);
            alterExistingTables(conn);
            // 四期: 药房/药库归属科室与科室一一对应的唯一约束(补列之后才建, NULL 不参与唯一碰撞)
            ensureDeptUniqueIndex(conn);
            // 库存唯一键重建依赖 his_warehouse_def 已存在(回填默认库), 因此必须排在补列之后
            ensureStockWarehouseIsolation(conn);
            // 两级库存基座: 为存量药房幂等创建 PHARMACY 库存位并回填 stock_location_id(需在库存隔离之后)
            ensurePharmacyStockLocations(conn);
        } catch (Exception e) {
            throw new IllegalStateException("HIS 门诊业务关键结构迁移失败, 拒绝启动(避免运行期缺列全线报错)", e);
        }
        if (added > 0) {
            log.info("字典化字段建列迁移完成, 新增/变更 {} 列", added);
        }
    }

    /**
     * 病案统计科管理系统基座(P0 核心编目闭环, 幂等 CREATE TABLE IF NOT EXISTS):
     * 与住院医生站临床端 his_case_front_page 解耦——本子系统读取临床首页生成"编目态快照"(不回写临床首页),
     * 编目员在其上按国标修订诊断/手术并对照医保版, 经质控审核→确认锁定。
     * 1) his_mr_catalog 编目主表(就诊唯一);
     * 2) his_mr_diag 编目诊断明细(多条, 含医保版编码/位序/上报勾选/灰码);
     * 3) his_mr_oper 编目手术操作明细(多条, 含医保版);
     * 4) his_mr_other 多条扩展记录(转科/过敏/重症);
     * 5) his_mr_assign 病案分配;
     * 6) his_mr_review 审核确认与锁定;
     * 7) his_mr_quality_err 质控/审核错误项(批量审核落库, 供错误归类);
     * 8) his_mr_change_log 编目/审核/锁定修改留痕。
     * 全程幂等, 新模块非启动关键路径(建表失败仅告警不阻断启动)。
     */
    private void ensureMedicalRecordTables(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_mr_catalog ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "visit_id BIGINT NOT NULL COMMENT '就诊ID(his_inp_visit.id)',"
                    + "patient_id BIGINT DEFAULT NULL COMMENT '患者ID',"
                    + "org_id BIGINT DEFAULT NULL COMMENT '机构ID(sys_org.id)',"
                    + "catalog_no VARCHAR(50) DEFAULT NULL COMMENT '病案号',"
                    + "source_case_page_id BIGINT DEFAULT NULL COMMENT '临床首页ID(his_case_front_page.id)',"
                    + "admission_date DATETIME DEFAULT NULL COMMENT '入院时间',"
                    + "discharge_date DATETIME DEFAULT NULL COMMENT '出院时间',"
                    + "los_days INT DEFAULT NULL COMMENT '住院天数',"
                    + "admission_dept_id BIGINT DEFAULT NULL COMMENT '入院科室ID(his_dept.id)',"
                    + "discharge_dept_id BIGINT DEFAULT NULL COMMENT '出院科室ID(his_dept.id)',"
                    + "main_diag_code VARCHAR(50) DEFAULT NULL COMMENT '出院主要诊断编码(编目修订后)',"
                    + "main_diag_name VARCHAR(200) DEFAULT NULL COMMENT '出院主要诊断名称',"
                    + "is_tcm TINYINT DEFAULT 0 COMMENT '中医标志:0西医 1中医',"
                    + "catalog_status TINYINT DEFAULT 1 COMMENT '编目状态:1待编目 2编目中 3已编目',"
                    + "audit_status TINYINT DEFAULT 1 COMMENT '审核状态:1未审核 2已审核 3已确认',"
                    + "lock_status TINYINT DEFAULT 0 COMMENT '锁定状态:0未锁 1已锁',"
                    + "cataloger_id BIGINT DEFAULT NULL COMMENT '责任编目员(his_staff.id)',"
                    + "cataloger_name VARCHAR(100) DEFAULT NULL COMMENT '责任编目员姓名',"
                    + "quality_score INT DEFAULT NULL COMMENT '病案首页质量评分',"
                    + "summary TEXT DEFAULT NULL COMMENT '首页快照JSON(基础/费用字段)',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "deleted TINYINT DEFAULT 0,"
                    + "create_by VARCHAR(50),"
                    + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + "update_by VARCHAR(50),"
                    + "update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_mr_visit (visit_id, tenant_id, deleted),"
                    + "KEY idx_mr_catalog_status (catalog_status, deleted),"
                    + "KEY idx_mr_discharge_dept (discharge_dept_id, deleted)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='病案编目主表'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_mr_diag ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "catalog_id BIGINT NOT NULL COMMENT '编目主表ID(his_mr_catalog.id)',"
                    + "visit_id BIGINT NOT NULL COMMENT '就诊ID',"
                    + "diag_type VARCHAR(20) NOT NULL COMMENT '诊断类别:outp门急诊 adm入院 dmain出院主 dother出院次 path病理 injure损伤中毒外因 infect院内感染',"
                    + "clinical_code VARCHAR(50) DEFAULT NULL COMMENT '国临版诊断编码',"
                    + "clinical_name VARCHAR(200) DEFAULT NULL COMMENT '国临版诊断名称',"
                    + "yb_code VARCHAR(50) DEFAULT NULL COMMENT '医保版诊断编码',"
                    + "yb_name VARCHAR(200) DEFAULT NULL COMMENT '医保版诊断名称',"
                    + "yb_sort_no INT DEFAULT NULL COMMENT '医保位序',"
                    + "report_flag TINYINT DEFAULT 0 COMMENT '是否医保上报:0否 1是',"
                    + "gray_flag TINYINT DEFAULT 0 COMMENT '医保灰码:0否 1是',"
                    + "main_flag TINYINT DEFAULT 0 COMMENT '主诊断标志:0否 1是',"
                    + "doctor_desc VARCHAR(500) DEFAULT NULL COMMENT '医师诊断描述',"
                    + "sort_no INT DEFAULT 0 COMMENT '同类别内序号',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "deleted TINYINT DEFAULT 0,"
                    + "create_by VARCHAR(50),"
                    + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + "update_by VARCHAR(50),"
                    + "update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_mr_diag_catalog (catalog_id, diag_type, deleted)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='病案编目诊断明细'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_mr_oper ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "catalog_id BIGINT NOT NULL COMMENT '编目主表ID(his_mr_catalog.id)',"
                    + "visit_id BIGINT NOT NULL COMMENT '就诊ID',"
                    + "clinical_code VARCHAR(50) DEFAULT NULL COMMENT '国临版手术操作编码(ICD-9-CM-3)',"
                    + "clinical_name VARCHAR(200) DEFAULT NULL COMMENT '国临版手术操作名称',"
                    + "yb_code VARCHAR(50) DEFAULT NULL COMMENT '医保版手术编码',"
                    + "yb_name VARCHAR(200) DEFAULT NULL COMMENT '医保版手术名称',"
                    + "yb_sort_no INT DEFAULT NULL COMMENT '医保位序',"
                    + "report_flag TINYINT DEFAULT 0 COMMENT '是否医保上报:0否 1是',"
                    + "main_flag TINYINT DEFAULT 0 COMMENT '主手术标志:0否 1是',"
                    + "gray_flag TINYINT DEFAULT 0 COMMENT '医保灰码:0否 1是',"
                    + "oper_date DATETIME DEFAULT NULL COMMENT '手术日期',"
                    + "surgeon_name VARCHAR(100) DEFAULT NULL COMMENT '手术医师',"
                    + "anesthesia VARCHAR(100) DEFAULT NULL COMMENT '麻醉方式',"
                    + "incision_type VARCHAR(50) DEFAULT NULL COMMENT '切口类型',"
                    + "heal_level VARCHAR(50) DEFAULT NULL COMMENT '愈合等级',"
                    + "sort_no INT DEFAULT 0 COMMENT '序号',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "deleted TINYINT DEFAULT 0,"
                    + "create_by VARCHAR(50),"
                    + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + "update_by VARCHAR(50),"
                    + "update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_mr_oper_catalog (catalog_id, deleted)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='病案编目手术操作明细'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_mr_other ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "catalog_id BIGINT NOT NULL COMMENT '编目主表ID(his_mr_catalog.id)',"
                    + "visit_id BIGINT NOT NULL COMMENT '就诊ID',"
                    + "rec_type VARCHAR(20) NOT NULL COMMENT '记录类别:transfer转科 allergy过敏药物 icu重症监护',"
                    + "code VARCHAR(50) DEFAULT NULL COMMENT '编码(药物/科室编码等)',"
                    + "name VARCHAR(200) DEFAULT NULL COMMENT '名称(药物名称/转入转出科室/监护项目)',"
                    + "detail VARCHAR(500) DEFAULT NULL COMMENT '详情/描述',"
                    + "begin_time DATETIME DEFAULT NULL COMMENT '开始时间(入科/入监护/过敏发现)',"
                    + "end_time DATETIME DEFAULT NULL COMMENT '结束时间(出科/出监护)',"
                    + "sort_no INT DEFAULT 0 COMMENT '序号',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "deleted TINYINT DEFAULT 0,"
                    + "create_by VARCHAR(50),"
                    + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + "update_by VARCHAR(50),"
                    + "update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_mr_other_catalog (catalog_id, rec_type, deleted)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='病案编目多条扩展记录'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_mr_assign ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "catalog_id BIGINT NOT NULL COMMENT '编目主表ID(his_mr_catalog.id)',"
                    + "visit_id BIGINT NOT NULL COMMENT '就诊ID',"
                    + "assign_type TINYINT DEFAULT 1 COMMENT '分配方式:1偏好科室 2随机均分',"
                    + "cataloger_id BIGINT DEFAULT NULL COMMENT '分配编目员(his_staff.id)',"
                    + "cataloger_name VARCHAR(100) DEFAULT NULL COMMENT '编目员姓名',"
                    + "priority INT DEFAULT 0 COMMENT '优先级',"
                    + "assign_status TINYINT DEFAULT 1 COMMENT '分配状态:1已分配 2已释放 3已编目',"
                    + "assign_by VARCHAR(50) DEFAULT NULL COMMENT '分配操作人',"
                    + "assign_time DATETIME DEFAULT NULL COMMENT '分配时间',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "deleted TINYINT DEFAULT 0,"
                    + "create_by VARCHAR(50),"
                    + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + "update_by VARCHAR(50),"
                    + "update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_mr_assign_catalog (catalog_id, deleted),"
                    + "KEY idx_mr_assign_cataloger (cataloger_id, assign_status, deleted)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='病案分配'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_mr_review ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "catalog_id BIGINT NOT NULL COMMENT '编目主表ID(his_mr_catalog.id)',"
                    + "visit_id BIGINT NOT NULL COMMENT '就诊ID',"
                    + "reviewer_id BIGINT DEFAULT NULL COMMENT '审核人(sys_user.id)',"
                    + "reviewer_name VARCHAR(100) DEFAULT NULL COMMENT '审核人姓名',"
                    + "audit_opinion VARCHAR(500) DEFAULT NULL COMMENT '审核意见',"
                    + "confirm_time DATETIME DEFAULT NULL COMMENT '审核确认时间',"
                    + "lock_status TINYINT DEFAULT 0 COMMENT '锁定状态:0未锁 1已锁',"
                    + "lock_time DATETIME DEFAULT NULL COMMENT '锁定时间',"
                    + "unlock_reason VARCHAR(500) DEFAULT NULL COMMENT '解锁原因',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "deleted TINYINT DEFAULT 0,"
                    + "create_by VARCHAR(50),"
                    + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + "update_by VARCHAR(50),"
                    + "update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_mr_review_catalog (catalog_id, deleted)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='病案审核确认与锁定'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_mr_quality_err ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "catalog_id BIGINT NOT NULL COMMENT '编目主表ID(his_mr_catalog.id)',"
                    + "visit_id BIGINT NOT NULL COMMENT '就诊ID',"
                    + "batch_no VARCHAR(50) DEFAULT NULL COMMENT '审核批次号',"
                    + "rule_category VARCHAR(20) DEFAULT NULL COMMENT '规则类别:强制 非强制',"
                    + "rule_code VARCHAR(50) DEFAULT NULL COMMENT '规则编码',"
                    + "field_key VARCHAR(100) DEFAULT NULL COMMENT '定位字段键',"
                    + "error_msg VARCHAR(500) DEFAULT NULL COMMENT '错误描述',"
                    + "resolved TINYINT DEFAULT 0 COMMENT '是否已修复:0否 1是',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "deleted TINYINT DEFAULT 0,"
                    + "create_by VARCHAR(50),"
                    + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + "update_by VARCHAR(50),"
                    + "update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_mr_qe_catalog (catalog_id, resolved, deleted),"
                    + "KEY idx_mr_qe_batch (batch_no, deleted)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='病案质控审核错误项'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_mr_change_log ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "catalog_id BIGINT NOT NULL COMMENT '编目主表ID(his_mr_catalog.id)',"
                    + "visit_id BIGINT NOT NULL COMMENT '就诊ID',"
                    + "op_type VARCHAR(20) DEFAULT NULL COMMENT '操作类型:catalog编目 assign分配 audit审核 confirm确认 lock锁定 unlock解锁',"
                    + "field_key VARCHAR(100) DEFAULT NULL COMMENT '变更字段键',"
                    + "old_val VARCHAR(500) DEFAULT NULL COMMENT '变更前值',"
                    + "new_val VARCHAR(500) DEFAULT NULL COMMENT '变更后值',"
                    + "op_user VARCHAR(50) DEFAULT NULL COMMENT '操作人',"
                    + "op_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "deleted TINYINT DEFAULT 0,"
                    + "create_by VARCHAR(50),"
                    + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + "update_by VARCHAR(50),"
                    + "update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_mr_log_catalog (catalog_id, deleted),"
                    + "KEY idx_mr_log_visit (visit_id, deleted)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='病案修改留痕'");
            /* ==================== P1 病案室日常作业与检索(幂等新建) ==================== */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_mr_recall ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "visit_id BIGINT NOT NULL COMMENT '就诊ID(his_inp_visit.id)',"
                    + "catalog_id BIGINT DEFAULT NULL COMMENT '编目主表ID(his_mr_catalog.id)',"
                    + "patient_id BIGINT DEFAULT NULL COMMENT '患者ID',"
                    + "org_id BIGINT DEFAULT NULL COMMENT '机构ID(sys_org.id)',"
                    + "barcode VARCHAR(64) DEFAULT NULL COMMENT '病案条码(扫描录入)',"
                    + "recall_status TINYINT DEFAULT 1 COMMENT '收回状态:1待收回 2已收回 3逾期',"
                    + "due_date DATETIME DEFAULT NULL COMMENT '应回收日期(出院后按规则推算)',"
                    + "recall_time DATETIME DEFAULT NULL COMMENT '实际回收时间',"
                    + "recall_user_id BIGINT DEFAULT NULL COMMENT '回收人(his_staff.id)',"
                    + "recall_user_name VARCHAR(100) DEFAULT NULL COMMENT '回收人姓名',"
                    + "shelf_flag TINYINT DEFAULT 0 COMMENT '是否已上架:0否 1是',"
                    + "shelf_location VARCHAR(100) DEFAULT NULL COMMENT '上架库位/架号',"
                    + "remark VARCHAR(500) DEFAULT NULL COMMENT '备注',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "deleted TINYINT DEFAULT 0,"
                    + "create_by VARCHAR(50),"
                    + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + "update_by VARCHAR(50),"
                    + "update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_mr_recall_visit (visit_id, deleted),"
                    + "KEY idx_mr_recall_status (recall_status, due_date, deleted),"
                    + "KEY idx_mr_recall_barcode (barcode, deleted)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='病案收回/回收登记'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_mr_borrow ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "visit_id BIGINT NOT NULL COMMENT '就诊ID(his_inp_visit.id)',"
                    + "catalog_id BIGINT DEFAULT NULL COMMENT '编目主表ID(his_mr_catalog.id)',"
                    + "patient_id BIGINT DEFAULT NULL COMMENT '患者ID',"
                    + "org_id BIGINT DEFAULT NULL COMMENT '机构ID(sys_org.id)',"
                    + "borrow_no VARCHAR(50) DEFAULT NULL COMMENT '借阅单号',"
                    + "borrower_id BIGINT DEFAULT NULL COMMENT '借阅人(his_staff.id)',"
                    + "borrower_name VARCHAR(100) DEFAULT NULL COMMENT '借阅人姓名',"
                    + "borrower_dept_id BIGINT DEFAULT NULL COMMENT '借阅人科室ID(his_dept.id)',"
                    + "borrower_dept_name VARCHAR(100) DEFAULT NULL COMMENT '借阅人科室名称',"
                    + "purpose VARCHAR(500) DEFAULT NULL COMMENT '借阅事由',"
                    + "borrow_time DATETIME DEFAULT NULL COMMENT '借出时间',"
                    + "expect_return_date DATETIME DEFAULT NULL COMMENT '应归还日期',"
                    + "actual_return_time DATETIME DEFAULT NULL COMMENT '实际归还时间',"
                    + "borrow_status TINYINT DEFAULT 1 COMMENT '借阅状态:1借出 2已归还 3逾期',"
                    + "operator_name VARCHAR(50) DEFAULT NULL COMMENT '经办人姓名',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "deleted TINYINT DEFAULT 0,"
                    + "create_by VARCHAR(50),"
                    + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + "update_by VARCHAR(50),"
                    + "update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_mr_borrow_visit (visit_id, deleted),"
                    + "KEY idx_mr_borrow_status (borrow_status, expect_return_date, deleted),"
                    + "KEY idx_mr_borrow_no (borrow_no, deleted)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='病案借阅'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_mr_annotation ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "visit_id BIGINT NOT NULL COMMENT '就诊ID(his_inp_visit.id)',"
                    + "catalog_id BIGINT DEFAULT NULL COMMENT '编目主表ID(his_mr_catalog.id)',"
                    + "org_id BIGINT DEFAULT NULL COMMENT '机构ID(sys_org.id)',"
                    + "parent_id BIGINT DEFAULT NULL COMMENT '父批注ID(回复线程,NULL=顶层)',"
                    + "from_staff_id BIGINT DEFAULT NULL COMMENT '批注人(his_staff.id)',"
                    + "from_staff_name VARCHAR(100) DEFAULT NULL COMMENT '批注人姓名',"
                    + "to_staff_id BIGINT DEFAULT NULL COMMENT '接收人(his_staff.id,可空)',"
                    + "to_staff_name VARCHAR(100) DEFAULT NULL COMMENT '接收人姓名',"
                    + "ann_type VARCHAR(20) DEFAULT NULL COMMENT '类型:feedback反馈 ask询问 reply答复 other其他',"
                    + "target_field VARCHAR(100) DEFAULT NULL COMMENT '关联/定位字段键',"
                    + "content VARCHAR(1000) DEFAULT NULL COMMENT '批注内容',"
                    + "resolved TINYINT DEFAULT 0 COMMENT '是否已处理:0未处理 1已处理',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "deleted TINYINT DEFAULT 0,"
                    + "create_by VARCHAR(50),"
                    + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + "update_by VARCHAR(50),"
                    + "update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_mr_ann_catalog (catalog_id, deleted),"
                    + "KEY idx_mr_ann_visit (visit_id, deleted),"
                   + "KEY idx_mr_ann_resolved (resolved, deleted)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='病案批注与反馈'");
            /* ==================== P2 工作量/维护字典/统计报表/上报闭环(幂等新建) ==================== */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_mr_workload ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "org_id BIGINT DEFAULT NULL COMMENT '机构ID(sys_org.id)',"
                    + "period VARCHAR(7) NOT NULL COMMENT '统计期(yyyy-MM)',"
                    + "dept_id BIGINT DEFAULT NULL COMMENT '科室ID(his_dept.id)',"
                    + "dept_name VARCHAR(100) DEFAULT NULL COMMENT '科室名称',"
                    + "staff_id BIGINT DEFAULT NULL COMMENT '责任人(his_staff.id)',"
                    + "staff_name VARCHAR(100) DEFAULT NULL COMMENT '责任人姓名',"
                    + "category VARCHAR(20) NOT NULL COMMENT '工作量类别:outp门诊 inp住院病区 tech医技 other其他项',"
                    + "item_code VARCHAR(50) DEFAULT NULL COMMENT '项目编码',"
                    + "item_name VARCHAR(200) DEFAULT NULL COMMENT '项目名称',"
                    + "qty DECIMAL(14,2) DEFAULT 0 COMMENT '数量',"
                    + "amount DECIMAL(14,2) DEFAULT NULL COMMENT '金额(可空)',"
                    + "audit_status TINYINT DEFAULT 1 COMMENT '逻辑审核状态:1待审 2通过 3驳回',"
                    + "audit_user_name VARCHAR(50) DEFAULT NULL COMMENT '审核人姓名',"
                    + "audit_time DATETIME DEFAULT NULL COMMENT '审核时间',"
                    + "audit_opinion VARCHAR(500) DEFAULT NULL COMMENT '审核意见',"
                    + "remark VARCHAR(500) DEFAULT NULL COMMENT '备注',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "deleted TINYINT DEFAULT 0,"
                    + "create_by VARCHAR(50),"
                    + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + "update_by VARCHAR(50),"
                    + "update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_mr_wl_period (period, deleted),"
                    + "KEY idx_mr_wl_dept (dept_id, category, deleted),"
                    + "KEY idx_mr_wl_audit (audit_status, deleted)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='病案工作量统计录入'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_mr_base_dict ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "org_id BIGINT DEFAULT NULL COMMENT '机构ID(NULL=全局可复用)',"
                    + "dict_type VARCHAR(20) NOT NULL COMMENT '字典类别:case_base病案基础 wt_base卫统基础 ward病区 med_team医疗小组 holiday节假日',"
                    + "code VARCHAR(50) NOT NULL COMMENT '编码(节假日为yyyy-MM-dd)',"
                    + "name VARCHAR(200) NOT NULL COMMENT '名称',"
                    + "parent_code VARCHAR(50) DEFAULT NULL COMMENT '上级编码(层级,可空)',"
                    + "ext1 VARCHAR(200) DEFAULT NULL COMMENT '附加1(病区所属科室/医疗小组组长等)',"
                    + "ext2 VARCHAR(200) DEFAULT NULL COMMENT '附加2(医疗小组所属科室/节假日类型等)',"
                    + "valid_flag TINYINT DEFAULT 1 COMMENT '有效标志:1启用 0停用',"
                    + "sort_no INT DEFAULT 0 COMMENT '排序',"
                    + "remark VARCHAR(500) DEFAULT NULL COMMENT '备注',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "deleted TINYINT DEFAULT 0,"
                    + "create_by VARCHAR(50),"
                    + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + "update_by VARCHAR(50),"
                    + "update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_mr_bd_type (dict_type, valid_flag, deleted),"
                    + "KEY idx_mr_bd_code (dict_type, code, deleted)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='病案系统维护字典'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_mr_report_batch ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "org_id BIGINT DEFAULT NULL COMMENT '机构ID(sys_org.id)',"
                    + "report_type VARCHAR(20) NOT NULL COMMENT '上报类型:wt卫统4表 hqms HQMS绩效 med_list医保结算清单',"
                    + "batch_no VARCHAR(50) DEFAULT NULL COMMENT '上报批次号',"
                    + "period_from DATE DEFAULT NULL COMMENT '统计起',"
                    + "period_to DATE DEFAULT NULL COMMENT '统计止',"
                    + "status TINYINT DEFAULT 1 COMMENT '闭环状态:1待审核 2已审核待转换 3已转换待上报 4已上报 5失败',"
                    + "total_count INT DEFAULT 0 COMMENT '病案总数',"
                    + "ok_count INT DEFAULT 0 COMMENT '审核通过数',"
                    + "err_count INT DEFAULT 0 COMMENT '审核拦截数',"
                    + "reviewer_name VARCHAR(50) DEFAULT NULL COMMENT '审核人姓名',"
                    + "review_time DATETIME DEFAULT NULL COMMENT '审核时间',"
                    + "converter_name VARCHAR(50) DEFAULT NULL COMMENT '转换人姓名',"
                    + "convert_time DATETIME DEFAULT NULL COMMENT '转换时间',"
                    + "submitter_name VARCHAR(50) DEFAULT NULL COMMENT '上报人姓名',"
                    + "submit_time DATETIME DEFAULT NULL COMMENT '上报时间',"
                    + "file_ref VARCHAR(200) DEFAULT NULL COMMENT '上报文件/数据集引用',"
                    + "fail_reason VARCHAR(500) DEFAULT NULL COMMENT '失败/拦截原因',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "deleted TINYINT DEFAULT 0,"
                    + "create_by VARCHAR(50),"
                    + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + "update_by VARCHAR(50),"
                    + "update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_mr_rb_type (report_type, status, deleted),"
                    + "KEY idx_mr_rb_no (batch_no, deleted)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='病案上报批次'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_mr_report_item ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "batch_id BIGINT NOT NULL COMMENT '批次ID(his_mr_report_batch.id)',"
                    + "visit_id BIGINT NOT NULL COMMENT '就诊ID(his_inp_visit.id)',"
                    + "catalog_id BIGINT DEFAULT NULL COMMENT '编目主表ID(his_mr_catalog.id)',"
                    + "patient_name VARCHAR(100) DEFAULT NULL COMMENT '患者姓名',"
                    + "inp_no VARCHAR(50) DEFAULT NULL COMMENT '住院号',"
                    + "main_diag_code VARCHAR(50) DEFAULT NULL COMMENT '主要诊断编码',"
                    + "check_status TINYINT DEFAULT 1 COMMENT '审核结果:1通过 2拦截',"
                    + "check_msg VARCHAR(500) DEFAULT NULL COMMENT '审核意见/拦截原因',"
                    + "converted TINYINT DEFAULT 0 COMMENT '是否已转换:0否 1是',"
                    + "reported TINYINT DEFAULT 0 COMMENT '是否已上报:0否 1是',"
                    + "report_msg VARCHAR(500) DEFAULT NULL COMMENT '上报回执/失败原因',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "deleted TINYINT DEFAULT 0,"
                    + "create_by VARCHAR(50),"
                    + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + "update_by VARCHAR(50),"
                    + "update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_mr_ri_batch (batch_id, check_status, deleted),"
                    + "KEY idx_mr_ri_visit (visit_id, deleted)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='病案上报批次明细'");
        }
    }

    /**
     * 库存按库房隔离的关键迁移: his_drug_stock 唯一键从 (tenant, org, 药品, 批次) 重建为
     * (tenant, org, warehouse_id, 药品, 批次)。旧键不含药库会导致同一批次跨库合并到同一行,
     * 第二个药库的入库量落到第一个药库名下, 分库库存/盘点全部失真。
     * 步骤: 补列 -> 未归属行回填机构默认库 -> 合并同键重复行 -> 换索引; 全程幂等。
     */
    private void ensureStockWarehouseIsolation(Connection conn) throws Exception {
        if (!tableExists(conn, "his_drug_stock")) {
            return;
        }
        addColumnIfNotExists(conn, "his_drug_stock", "warehouse_id", "BIGINT DEFAULT NULL COMMENT '药库ID(his_warehouse_def.id)'");
        if (!columnExists(conn, "his_warehouse_def", "is_default") && !tableExists(conn, "his_warehouse_def")) {
            // 无药库主数据可回填(首次建表前), 本轮只补列, 唯一键待有默认库后再重建
            return;
        }
        // 1. 存量未归属库存回填到该机构排序最早的药库(通常为 DEFAULT 默认药库)
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("UPDATE his_drug_stock s JOIN (" + "SELECT org_id, MIN(id) AS wid FROM his_warehouse_def WHERE deleted = 0 GROUP BY org_id" + ") d ON d.org_id = s.org_id SET s.warehouse_id = d.wid WHERE s.warehouse_id IS NULL");
        }
        // 2. 换唯一键前先合并"同租户+机构+药库+药品+批次"的重复行(旧键按库合并遗留), 否则 ALTER 直接失败
        mergeDuplicateStockRows(conn);
        // 3. 唯一键重建(幂等: 新键已存在则跳过)
        if (!indexExists(conn, "his_drug_stock", "uk_tenant_org_wh_drug_batch")) {
            try (Statement st = conn.createStatement()) {
                if (indexExists(conn, "his_drug_stock", "uk_tenant_org_drug_batch")) {
                    st.executeUpdate("ALTER TABLE his_drug_stock DROP INDEX uk_tenant_org_drug_batch");
                }
                st.executeUpdate("ALTER TABLE his_drug_stock ADD UNIQUE KEY uk_tenant_org_wh_drug_batch "
                        + "(tenant_id, org_id, warehouse_id, drug_catalog_id, batch_no)");
            }
            log.info("his_drug_stock 唯一键已重建为含药库维度(warehouse_id)");
        }
    }

    /**
     * 四期: 幂等建唯一索引——药房/药库的归属科室与科室一一对应(tenant_id+dept_id 唯一)。
     * dept_id 为 NULL(历史未绑定)在 MySQL 唯一索引中互不碰撞, 故存量未绑定行不受影响; 首次建立时 dept_id 全 NULL 必无冲突。
     */
    private void ensureDeptUniqueIndex(Connection conn) throws Exception {
        String[] tabs = {"his_pharmacy_def", "his_warehouse_def"};
        for (String t : tabs) {
            if (!tableExists(conn, t) || !columnExists(conn, t, "dept_id")) {
                continue;
            }
            if (!indexExists(conn, t, "uk_dept")) {
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate("ALTER TABLE " + t + " ADD UNIQUE KEY uk_dept (tenant_id, dept_id)");
                }
                log.info("{} 归属科室唯一索引 uk_dept 已建立", t);
            }
        }
    }

    /**
     * 两级库存基座(P0): 为每个存量药房幂等创建 PHARMACY 型库存位并回填 stock_location_id。
     * 步骤: 先给两张存量表补列(kind/ref_pharmacy_id/stock_location_id) -> 将已有 PHARMACY 库存位
     * 回填到对应药房 -> 对尚无库存位的药房创建(kind=PHARMACY, ref_pharmacy_id, code=PHLOC-药房编码)。
     * 全程幂等: 仅处理 stock_location_id IS NULL 的药房; 建位撞唯一键(code 已存在)则回查已有位复用。
     */
    private void ensurePharmacyStockLocations(Connection conn) throws Exception {
        if (!tableExists(conn, "his_warehouse_def") || !tableExists(conn, "his_pharmacy_def")) {
            return;
        }
        addColumnIfNotExists(conn, "his_warehouse_def", "kind", "VARCHAR(20) NOT NULL DEFAULT 'WAREHOUSE' COMMENT '库存位类型:WAREHOUSE药库/PHARMACY药房库位'");
        addColumnIfNotExists(conn, "his_warehouse_def", "ref_pharmacy_id", "BIGINT DEFAULT NULL COMMENT 'PHARMACY型回指药房ID(his_pharmacy_def.id)'");
        addColumnIfNotExists(conn, "his_pharmacy_def", "stock_location_id", "BIGINT DEFAULT NULL COMMENT '本药房库存位ID(PHARMACY型 his_warehouse_def.id)'");
        // 1. 存量 PHARMACY 库存位(ref_pharmacy_id 已存在)回填到药房(幂等, 仅命中未回填行)
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("UPDATE his_pharmacy_def p JOIN his_warehouse_def w" + " ON w.kind = 'PHARMACY' AND w.ref_pharmacy_id = p.id AND w.deleted = 0" + " SET p.stock_location_id = w.id WHERE p.stock_location_id IS NULL AND p.deleted = 0");
        }
        // 2. 仍无库存位的药房: 逐个创建 PHARMACY 库存位并回填
        List<Object[]> todo = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, tenant_id, org_id, code, name, pharmacy_type FROM his_pharmacy_def" + " WHERE stock_location_id IS NULL AND deleted = 0")) {
            while (rs.next()) {
                todo.add(new Object[]{rs.getLong("id"), rs.getLong("tenant_id"), rs.getLong("org_id"),
                        rs.getString("code"), rs.getString("name"), rs.getString("pharmacy_type")});
            }
        }
        for (Object[] p : todo) {
            Long pharmacyId = (Long) p[0];
            long tenantId = ((Number) p[1]).longValue();
            long orgId = ((Number) p[2]).longValue();
            String phCode = (String) p[3];
            String phName = (String) p[4];
            String phType = (String) p[5];
            String whType = "TCM".equals(phType) ? "TCM" : "MIXED";
            String locCode = "PHLOC-" + phCode;
            long locId;
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO his_warehouse_def" + " (tenant_id, org_id, code, name, warehouse_type, kind, ref_pharmacy_id, status, sort_no, create_time, deleted)" + " VALUES (?, ?, ?, ?, ?, 'PHARMACY', ?, 1, 0, NOW(), 0)", new String[]{"id"})) {
                ps.setLong(1, tenantId);
                ps.setLong(2, orgId);
                ps.setString(3, locCode);
                ps.setString(4, (phName == null ? phCode : phName) + "-库存位");
                ps.setString(5, whType);
                ps.setLong(6, pharmacyId);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    locId = keys.next() ? keys.getLong(1) : 0L;
                }
            } catch (Exception e) {
                // 撞唯一键(同 code 库存位已存在): 回查复用
                Long existing = null;
                try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM his_warehouse_def WHERE tenant_id = ? AND org_id = ? AND code = ? AND deleted = 0 LIMIT 1")) {
                    ps.setLong(1, tenantId);
                    ps.setLong(2, orgId);
                    ps.setString(3, locCode);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            existing = rs.getLong(1);
                        }
                    }
                }
                if (existing == null) {
                    log.warn("药房库存位创建/回查失败, 跳过: pharmacyId={}, err={}", pharmacyId, e.getMessage());
                    continue;
                }
                locId = existing;
            }
            if (locId > 0) {
                try (PreparedStatement ps = conn.prepareStatement("UPDATE his_pharmacy_def SET stock_location_id = ? WHERE id = ? AND stock_location_id IS NULL")) {
                    ps.setLong(1, locId);
                    ps.setLong(2, pharmacyId);
                    ps.executeUpdate();
                }
                log.info("两级库存: 为药房创建 PHARMACY 库存位: pharmacyId={}, stockLocationId={}", pharmacyId, locId);
            }
        }
    }

    /**
     * 合并同 (tenant, org, warehouse, 药品, 批次) 的多条库存行: 数量并入最小 id 行, 其余物理删除。
     * 物理删除而非逻辑删除, 因 MySQL 唯一索引不忽略 deleted=0 之外的行, 保留会与新建的库存行撞键。
     */
    private void mergeDuplicateStockRows(Connection conn) throws Exception {
        String groupSql = "SELECT tenant_id, org_id, warehouse_id, drug_catalog_id, batch_no, COUNT(*) AS c" + " FROM his_drug_stock WHERE warehouse_id IS NOT NULL" + " GROUP BY tenant_id, org_id, warehouse_id, drug_catalog_id, batch_no HAVING COUNT(*) > 1";
        List<String[]> groups = new ArrayList<String[]>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(groupSql)) {
            while (rs.next()) {
                groups.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)});
            }
        }
        for (String[] g : groups) {
            String cond = "tenant_id = " + g[0] + " AND org_id = " + g[1] + " AND warehouse_id = " + g[2]
                    + " AND drug_catalog_id = " + g[3] + " AND batch_no = '" + g[4].replace("'", "''") + "'";
            long keepId;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT MIN(id) FROM his_drug_stock WHERE " + cond)) {
                if (!rs.next() || (keepId = rs.getLong(1)) == 0) {
                    continue;
                }
            }
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("UPDATE his_drug_stock SET qty = (SELECT SUM(x.qty) FROM (" + "SELECT qty FROM his_drug_stock WHERE " + cond + ") x) WHERE id = " + keepId);
                int removed = st.executeUpdate("DELETE FROM his_drug_stock WHERE " + cond + " AND id <> " + keepId);
                log.info("合并重复库存批次行: keepId={}, removed={}, key=[{}/{}]", keepId, removed, g[3], g[4]);
            }
        }
    }

    /** 索引是否存在(information_schema.statistics)。 */
    private boolean indexExists(Connection conn, String table, String index) throws Exception {
        String sql = "SELECT COUNT(*) FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, index);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    /**
     * 幂等回填 his_dept.open_clinic: 新增列(或历史行)为 NULL 时一律视为开诊(1),
     * 不改变存量科室在排班/挂号下拉的可见性; 需关闭开诊由科室管理显式勾选。
     */
    private void normalizeDeptOpenClinic(Connection conn) {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("UPDATE his_dept SET open_clinic = 1 WHERE open_clinic IS NULL");
        } catch (Exception e) {
            log.warn("his_dept.open_clinic 回填跳过: {}", e.getMessage());
        }
    }

    /**
     * 标准字典大表幂等补 py_code 列: 遍历 StdDictRegistry 去重表名(drug_catalog 已有源 pinyin 列, 跳过),
     * 表存在且未建列时 ALTER ADD py_code。回填见 PyCodeBackfillService。
     */
    private void ensureStdPyCodeColumns(Connection conn) {
        java.util.Set<String> done = new java.util.LinkedHashSet<>();
        for (com.yb.hi.stddict.StdDict d : com.yb.hi.stddict.StdDictRegistry.build().values()) {
            String table = d.getTable();
            if (table == null || !done.add(table)) {
                continue;
            }
            if ("drug_catalog".equals(table)) {
                continue;
            }
            try {
                if (tableExists(conn, table) && !columnExists(conn, table, "py_code")) {
                    try (Statement st = conn.createStatement()) {
                        st.executeUpdate("ALTER TABLE " + table
                                + " ADD COLUMN py_code VARCHAR(64) NULL COMMENT '拼音简码(名称首字母, 自动生成只读)'");
                    }
                }
            } catch (Exception e) {
                log.warn("std 表 {} py_code 建列跳过: {}", table, e.getMessage());
            }
        }
    }

    private boolean tableExists(Connection conn, String table) throws Exception {
        String sql = "SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    /** 幂等建表: 医生工作站医疗模板(个人/科室/全院三级)。 */
    private void ensureMedicalTemplateTable(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_medical_template ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT,"
                    + "tenant_id BIGINT NOT NULL,"
                    + "template_type VARCHAR(20) NOT NULL COMMENT 'soap/rx_set/order_set/fragment',"
                    + "name VARCHAR(100) NOT NULL,"
                    + "dept_id BIGINT DEFAULT NULL COMMENT '科室级(null=全院)',"
                    + "staff_id BIGINT DEFAULT NULL COMMENT '个人级(null=科室/全院)',"
                    + "content TEXT NOT NULL COMMENT 'JSON',"
                    + "sort_order INT DEFAULT 0,"
                    + "status TINYINT DEFAULT 1,"
                    + "create_by VARCHAR(50), update_by VARCHAR(50),"
                    + "create_time DATETIME, update_time DATETIME,"
                    + "deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_template_scope (tenant_id, template_type, staff_id, dept_id, status)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='医生工作站医疗模板'");
        }
    }

    /**
     * 幂等建表: 系统参数分组 + 系统参数两张表(表已存在则跳过)。
     * sys_param_group: 全局共享分组(无 tenant_id 列)。
     * sys_param: tenant_id 为显式列(全局行 tenant_id=0 需跨租户可见), 两表均已入 MybatisPlusConfig.IGNORE_TABLES,
     * 租户隔离由 Service 层在 Wrapper 中手动处理。
     */
    private void ensureSystemParamTables(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS sys_param_group ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "group_code VARCHAR(50) NOT NULL COMMENT '分组编码(唯一)',"
                    + "group_name VARCHAR(100) NULL COMMENT '分组名称',"
                    + "sort_no INT DEFAULT 0 COMMENT '排序号',"
                    + "remark VARCHAR(500) NULL COMMENT '备注',"
                    + "create_by VARCHAR(50) NULL, create_time DATETIME NULL,"
                    + "update_by VARCHAR(50) NULL, update_time DATETIME NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_group_code (group_code)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统参数分组(全局共享, 无 tenant_id)'");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS sys_param ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT DEFAULT 0 COMMENT '租户ID(0=全局行, 跨租户可见)',"
                    + "param_key VARCHAR(100) NOT NULL COMMENT '参数键',"
                    + "param_value TEXT NULL COMMENT '参数值(按 data_type 解析)',"
                    + "scope_level TINYINT DEFAULT 0 COMMENT '作用域层级:0全局 1租户 2机构 3科室',"
                    + "scope_id BIGINT DEFAULT 0 COMMENT '作用域对象ID(配合 scope_level, 0=无)',"
                    + "group_code VARCHAR(50) NULL COMMENT '分组编码(sys_param_group.group_code)',"
                    + "param_name VARCHAR(200) NULL COMMENT '参数名称',"
                    + "data_type VARCHAR(20) DEFAULT 'string' COMMENT '数据类型:string/int/decimal/bool/enum',"
                    + "default_value TEXT NULL COMMENT '默认值',"
                    + "enum_options VARCHAR(500) NULL COMMENT '枚举选项(逗号分隔, data_type=enum 时有效)',"
                    + "min_value DECIMAL(12,4) NULL COMMENT '最小值约束(数值型)',"
                    + "max_value DECIMAL(12,4) NULL COMMENT '最大值约束(数值型)',"
                    + "required TINYINT DEFAULT 0 COMMENT '是否必填:1是 0否',"
                    + "allow_scope VARCHAR(20) DEFAULT '0,1,2,3' COMMENT '允许的作用域层级列表(逗号分隔)',"
                    + "remark VARCHAR(500) NULL COMMENT '备注',"
                    + "create_by VARCHAR(50) NULL, create_time DATETIME NULL,"
                    + "update_by VARCHAR(50) NULL, update_time DATETIME NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_param_scope (param_key, scope_level, scope_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统参数(tenant_id=0 为全局行, 已入 IGNORE_TABLES 手动隔离)'");
        }
    }

    /** 幂等建表: 医保对照变更留痕表(三目录统一)。每次新增/变更/清除对照写一行, 支持按时间点回溯生效医保码。 */
    private void ensureYbMapLogTable(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_yb_map_log ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户(医共体)ID',"
                    + "catalog_type VARCHAR(20) NOT NULL COMMENT '目录类型:charge/drug/cons',"
                    + "catalog_id BIGINT NOT NULL COMMENT '院内条目ID',"
                    + "item_code VARCHAR(64) NULL COMMENT '院内编码(冗余)',"
                    + "item_name VARCHAR(200) NULL COMMENT '院内名称(冗余)',"
                    + "old_code VARCHAR(64) NULL COMMENT '变更前医保码(空=首次对照)',"
                    + "new_code VARCHAR(64) NULL COMMENT '变更后医保码(空=清除对照)',"
                    + "change_type VARCHAR(20) NOT NULL COMMENT '变更类型:MAP新增对照/CHANGE变更对照/CLEAR清除对照',"
                    + "score DOUBLE NULL COMMENT '匹配置信度(自动对照)',"
                    + "src VARCHAR(20) NULL COMMENT '对照方式:manual人工/auto自动',"
                    + "operator VARCHAR(50) NULL COMMENT '操作人(登录账号)',"
                    + "operator_name VARCHAR(50) NULL COMMENT '操作人姓名',"
                    + "org_id BIGINT NULL COMMENT '操作人归属机构',"
                    + "change_time DATETIME NOT NULL COMMENT '变更发生时间(即新对照生效时间)',"
                    + "memo VARCHAR(200) NULL COMMENT '备注',"
                    + "create_by VARCHAR(50) NULL, create_time DATETIME NULL,"
                    + "update_by VARCHAR(50) NULL, update_time DATETIME NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id), KEY idx_ymlog_tenant (tenant_id), KEY idx_ymlog_item (catalog_type, catalog_id), KEY idx_ymlog_time (change_time)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='医保对照变更留痕表(生效时间+变更前医保码可回溯)'");
        }
    }

    /**
     * 幂等建表(批次4 M1):
     * 1) his_yb_txn_log 医保出站交易日志——每笔医保交易出站即落 PENDING, 回执后置 SUCCESS/FAIL/UNKNOWN(结果三分),
     *    msgid 为 2601 冲正(omsgid)与补偿核对的唯一凭据; 2) his_comp_task 补偿任务——UNKNOWN 交易收敛的驱动队列。
     */
    private void ensureYbTxnTables(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_yb_txn_log ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NULL COMMENT '租户(医共体)ID',"
                    + "org_id BIGINT NULL COMMENT '发起机构ID',"
                    + "infno VARCHAR(10) NOT NULL COMMENT '医保交易编号(2204/2206/2207/2208/2601/3201/3202/9101等)',"
                    + "msgid VARCHAR(50) NOT NULL COMMENT '发送方报文ID(机构编号12+时间14+顺序号4)',"
                    + "mdtrt_id VARCHAR(50) NULL COMMENT '医保就诊ID(自input抽取)',"
                    + "psn_no VARCHAR(50) NULL COMMENT '人员编号(自input抽取)',"
                    + "setl_id VARCHAR(50) NULL COMMENT '结算ID(2207/2208响应回填)',"
                    + "chrg_bchno VARCHAR(50) NULL COMMENT '收费批次号(自input抽取)',"
                    + "bill_id BIGINT NULL COMMENT '关联院内收费单ID',"
                    + "status VARCHAR(10) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待回执/SUCCESS成功/FAIL明确失败/UNKNOWN结果未知',"
                    + "err_msg VARCHAR(500) NULL COMMENT '平台错误信息',"
                    + "input_json MEDIUMTEXT NULL COMMENT '请求报文(含input节点)',"
                    + "output_json MEDIUMTEXT NULL COMMENT '响应报文',"
                    + "create_by VARCHAR(50) NULL, create_time DATETIME NULL,"
                    + "update_by VARCHAR(50) NULL, update_time DATETIME NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_ybtxn_tenant (tenant_id),"
                    + "KEY idx_ybtxn_msgid (msgid),"
                    + "KEY idx_ybtxn_setl (setl_id),"
                    + "KEY idx_ybtxn_mdtrt (mdtrt_id),"
                    + "KEY idx_ybtxn_infno (infno)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='医保出站交易日志(批次4: 结果三分与2601冲正凭据)'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_comp_task ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户(医共体)ID',"
                    + "biz_type VARCHAR(20) NOT NULL COMMENT '业务类型:CHARGE/REFUND/PARTIAL_REFUND',"
                    + "ref_id BIGINT NULL COMMENT '关联业务主键(收费单ID等)',"
                    + "action VARCHAR(30) NOT NULL COMMENT '动作:RESOLVE_UNKNOWN等',"
                    + "txn_log_id BIGINT NULL COMMENT '触发任务的原交易日志ID(his_yb_txn_log.id)',"
                    + "status VARCHAR(10) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/DONE/DEAD',"
                    + "attempts INT NOT NULL DEFAULT 0 COMMENT '已尝试次数',"
                    + "next_run DATETIME NOT NULL COMMENT '下次执行时间(指数退避)',"
                    + "memo VARCHAR(500) NULL COMMENT '备注/最近一次执行结果',"
                    + "create_by VARCHAR(50) NULL, create_time DATETIME NULL,"
                    + "update_by VARCHAR(50) NULL, update_time DATETIME NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_comptask_tenant (tenant_id),"
                    + "KEY idx_comptask_next (next_run),"
                    + "KEY idx_comptask_status (status)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='医保补偿任务(批次4: UNKNOWN交易收敛)'");
            /* 批次4 M3: 对账任务(3201总账/3202明细账)与差异明细留痕 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_recon_task ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户(医共体)ID',"
                    + "org_id BIGINT DEFAULT NULL COMMENT '机构ID(租户级对账为空)',"
                    + "stmt_date DATE NOT NULL COMMENT '对账日期(T-1结算日)',"
                    + "insutype VARCHAR(6) DEFAULT NULL COMMENT '险种类型(3201分组维度)',"
                    + "recon_type VARCHAR(10) NOT NULL COMMENT '对账类型:TOTAL(3201)/DETAIL(3202)',"
                    + "result VARCHAR(2) NOT NULL COMMENT '结果:1平 2不平 9失败',"
                    + "medfee_local DECIMAL(16,2) DEFAULT 0 COMMENT '医疗费总额-院内口径',"
                    + "medfee_remote DECIMAL(16,2) DEFAULT 0 COMMENT '医疗费总额-平台回执口径',"
                    + "fund_local DECIMAL(16,2) DEFAULT 0 COMMENT '基金支付总额-院内口径',"
                    + "fund_remote DECIMAL(16,2) DEFAULT 0 COMMENT '基金支付总额-平台回执口径',"
                    + "acct_local DECIMAL(16,2) DEFAULT 0 COMMENT '个账支付-院内口径(3202为现金)',"
                    + "acct_remote DECIMAL(16,2) DEFAULT 0 COMMENT '个账支付-平台回执口径',"
                    + "cnt_local INT DEFAULT 0 COMMENT '结算笔数-院内口径',"
                    + "cnt_remote INT DEFAULT 0 COMMENT '结算笔数-平台回执口径',"
                    + "file_qury_no VARCHAR(30) DEFAULT NULL COMMENT '3202明细文件查询号(9101返回)',"
                    + "stmt_rslt VARCHAR(500) DEFAULT NULL COMMENT '平台回执(表197stmt_rslt/stmt_rslt_dscr或差异说明)',"
                    + "recon_time DATETIME DEFAULT NULL COMMENT '对账执行时间',"
                    + "memo VARCHAR(500) DEFAULT NULL COMMENT '备注',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_recontask_tenant (tenant_id),"
                    + "KEY idx_recontask_date (tenant_id, org_id, stmt_date),"
                    + "KEY idx_recontask_type (recon_type)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='医保对账任务(批次4 M3: 3201/3202)'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_recon_diff ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户(医共体)ID',"
                    + "org_id BIGINT DEFAULT NULL COMMENT '机构ID(租户级对账为空)',"
                    + "recon_task_id BIGINT DEFAULT NULL COMMENT '对账任务ID(his_recon_task.id)',"
                    + "stmt_date DATE DEFAULT NULL COMMENT '对账日期',"
                    + "setl_id VARCHAR(30) DEFAULT NULL COMMENT '结算ID(中心端多条时为空, 规范表201说明7)',"
                    + "mdtrt_id VARCHAR(30) DEFAULT NULL COMMENT '就诊ID(中心端多条时为空)',"
                    + "psn_no VARCHAR(30) DEFAULT NULL COMMENT '人员编号',"
                    + "msgid VARCHAR(50) DEFAULT NULL COMMENT '原交易报文ID',"
                    + "stmt_rslt VARCHAR(6) DEFAULT NULL COMMENT '核对结果(表201stmt_rslt)',"
                    + "refd_setl_flag VARCHAR(3) DEFAULT NULL COMMENT '退费结算标志(3位)',"
                    + "memo VARCHAR(500) DEFAULT NULL COMMENT '说明(表201memo)',"
                    + "medfee_sumamt DECIMAL(16,2) DEFAULT 0 COMMENT '医疗费总额-平台',"
                    + "fund_pay_sumamt DECIMAL(16,2) DEFAULT 0 COMMENT '基金支付总额-平台',"
                    + "acct_pay DECIMAL(16,2) DEFAULT 0 COMMENT '个账支付-平台',"
                    + "status TINYINT DEFAULT 0 COMMENT '处理状态:0待处理 1已核对 2已平账',"
                    + "handle_memo VARCHAR(500) DEFAULT NULL COMMENT '处理备注',"
                    + "handle_time DATETIME DEFAULT NULL COMMENT '处理时间',"
                    + "handle_by VARCHAR(50) DEFAULT NULL COMMENT '处理人',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_recondiff_tenant (tenant_id),"
                    + "KEY idx_recondiff_task (recon_task_id),"
                    + "KEY idx_recondiff_status (tenant_id, org_id, status)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='医保对账差异明细(批次4 M3)'");
            /* 结算留存表(2207/2208): 存量库由 his_migration.sql 建, 新库幂等补建(含 tenant_id), 对账/补偿依赖 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS setl_record ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',"
                    + "setl_id VARCHAR(30) DEFAULT NULL COMMENT '结算ID',"
                    + "mdtrt_id VARCHAR(30) DEFAULT NULL COMMENT '就诊ID',"
                    + "psn_no VARCHAR(30) DEFAULT NULL COMMENT '人员编号',"
                    + "psn_name VARCHAR(50) DEFAULT NULL COMMENT '人员姓名',"
                    + "insutype VARCHAR(6) DEFAULT NULL COMMENT '险种类型',"
                    + "med_type VARCHAR(6) DEFAULT NULL COMMENT '医疗类别',"
                    + "biz_type VARCHAR(20) DEFAULT NULL COMMENT '业务类型 outpatient/inpatient',"
                    + "infno VARCHAR(10) DEFAULT NULL COMMENT '交易编号',"
                    + "setl_time VARCHAR(30) DEFAULT NULL COMMENT '结算时间',"
                    + "medfee_sumamt DECIMAL(16,2) DEFAULT NULL COMMENT '医疗费总额',"
                    + "fund_pay_sumamt DECIMAL(16,2) DEFAULT NULL COMMENT '基金支付总额',"
                    + "psn_part_amt DECIMAL(16,2) DEFAULT NULL COMMENT '个人负担总金额',"
                    + "acct_pay DECIMAL(16,2) DEFAULT NULL COMMENT '个人账户支出',"
                    + "psn_cash_pay DECIMAL(16,2) DEFAULT NULL COMMENT '个人现金支出',"
                    + "status VARCHAR(3) DEFAULT '1' COMMENT '状态 1-已结算 0-已撤销',"
                    + "setlinfo_json LONGTEXT DEFAULT NULL COMMENT '结算信息原始JSON',"
                    + "crte_time DATETIME DEFAULT NULL COMMENT '创建时间',"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_setl_id (setl_id),"
                    + "KEY idx_setl_mdtrt (mdtrt_id),"
                    + "KEY idx_setl_tenant_time (tenant_id, setl_time)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='结算记录表'");
            /* 3301/3302 对照上传队列(M4, 事件源: his_yb_map_log; MAP->3301, CLEAR->3302, CHANGE->先3302后3301) */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_yb_upload_queue ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户(医共体)ID',"
                    + "catalog_type VARCHAR(10) DEFAULT NULL COMMENT '目录类型: charge/drug/cons',"
                    + "catalog_id BIGINT DEFAULT NULL COMMENT '院内条目ID',"
                    + "item_code VARCHAR(100) DEFAULT NULL COMMENT '院内编码=fixmedins_hilist_id',"
                    + "item_name VARCHAR(200) DEFAULT NULL COMMENT '院内名称=fixmedins_hilist_name',"
                    + "list_type VARCHAR(30) DEFAULT NULL COMMENT '目录类别(3301/3302 必填, 与平台确认后配置)',"
                    + "old_code VARCHAR(50) DEFAULT NULL COMMENT '变更前医保码(撤销用)',"
                    + "new_code VARCHAR(50) DEFAULT NULL COMMENT '变更后医保码(上传用)',"
                    + "action VARCHAR(10) DEFAULT NULL COMMENT '动作: MAP/CHANGE/CLEAR',"
                    + "status TINYINT DEFAULT 0 COMMENT '状态: 0待传 1已传 2失败',"
                    + "batch_no VARCHAR(40) DEFAULT NULL COMMENT '上传批次号(平台回执报文ID)',"
                    + "upload_time DATETIME DEFAULT NULL COMMENT '上传时间',"
                    + "last_err VARCHAR(500) DEFAULT NULL COMMENT '失败原因',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_upload_status (tenant_id, status),"
                    + "KEY idx_upload_catalog (tenant_id, catalog_type, status)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='医保对照上传队列(批次4 M4: 3301/3302)'");
            /* 上传管线状态机(M5, 设计 §6.1/6.2): 2203 等逐单状态; 0待传 1已传 2失败待补 3已撤销;
               retry_count/next_retry 指数退避 1/5/15/60min, max 6 次转人工; uk_biz 一单一状态 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_upload_status ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户(医共体)ID',"
                    + "org_id BIGINT DEFAULT NULL COMMENT '机构ID',"
                    + "biz_type VARCHAR(10) DEFAULT NULL COMMENT '业务类型: REG/VISIT/RX/FEE/SETL/CANCEL/TRACE(批次5 M1 追溯码2404)',"
                    + "biz_id BIGINT DEFAULT NULL COMMENT '业务主键(如就诊ID)',"
                    + "mdtrt_id VARCHAR(30) DEFAULT NULL COMMENT '医保就诊ID',"
                    + "status TINYINT DEFAULT 0 COMMENT '状态: 0待传 1已传 2失败待补 3已撤销',"
                    + "retry_count INT DEFAULT 0 COMMENT '已重试次数(max 6 次转人工)',"
                    + "next_retry DATETIME DEFAULT NULL COMMENT '下次重试时间(指数退避)',"
                    + "last_err VARCHAR(500) DEFAULT NULL COMMENT '最近失败原因',"
                    + "msgid VARCHAR(40) DEFAULT NULL COMMENT '成功报文ID(回执)',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_biz (tenant_id, biz_type, biz_id),"
                    + "KEY idx_retry (status, retry_count, next_retry)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='医保上传状态机(批次4 M5: 上传管线逐单状态)'");
        }
    }

    /** 幂等建表: 统一字典字段级修改留痕表(三目录统一)。编辑保存逐字段 diff, 每个变化字段写一行; 医保码变更另走 his_yb_map_log。 */
    private void ensureDictEditLogTable(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_dict_edit_log ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户(医共体)ID',"
                    + "catalog_type VARCHAR(20) NOT NULL COMMENT '目录类型:charge/drug/cons',"
                    + "catalog_id BIGINT NOT NULL COMMENT '院内条目ID',"
                    + "item_code VARCHAR(64) NULL COMMENT '院内编码(冗余)',"
                    + "item_name VARCHAR(200) NULL COMMENT '院内名称(冗余)',"
                    + "field_name VARCHAR(60) NULL COMMENT '变更字段属性名',"
                    + "field_label VARCHAR(60) NULL COMMENT '变更字段中文名',"
                    + "old_value VARCHAR(500) NULL COMMENT '修改前值(展示值)',"
                    + "new_value VARCHAR(500) NULL COMMENT '修改后值(展示值)',"
                    + "source VARCHAR(20) NULL COMMENT '来源:编辑/新增',"
                    + "operator VARCHAR(50) NULL COMMENT '操作人(登录账号)',"
                    + "operator_name VARCHAR(50) NULL COMMENT '操作人姓名',"
                    + "org_id BIGINT NULL COMMENT '操作人归属机构',"
                    + "change_time DATETIME NOT NULL COMMENT '变更发生时间',"
                    + "create_by VARCHAR(50) NULL, create_time DATETIME NULL,"
                    + "update_by VARCHAR(50) NULL, update_time DATETIME NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id), KEY idx_delog_tenant (tenant_id), KEY idx_delog_item (catalog_type, catalog_id), KEY idx_delog_time (change_time)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一字典字段级修改留痕表'");
        }
    }

    /**
     * 幂等建表: 周排班模板(按星期+时段固化科室/医师/号别/号源, 一键生成周期排班)。
     */
    private void ensureScheduleTemplateTables(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_schedule_template ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "template_name VARCHAR(100) NOT NULL COMMENT '模板名称',"
                    + "dept_id BIGINT NOT NULL COMMENT '科室ID',"
                    + "staff_id BIGINT NOT NULL COMMENT '医师ID',"
                    + "staff_name VARCHAR(50) DEFAULT NULL COMMENT '医师姓名',"
                    + "dept_name VARCHAR(100) DEFAULT NULL COMMENT '科室名称',"
                    + "weekday TINYINT NOT NULL COMMENT '星期几:1周一~7周日',"
                    + "time_type VARCHAR(10) NOT NULL DEFAULT 'am' COMMENT '时段:am/pm/night',"
                    + "reg_level_code VARCHAR(30) DEFAULT '01' COMMENT '号别编码',"
                    + "reg_level_name VARCHAR(50) DEFAULT '普通号' COMMENT '号别名称',"
                    + "reg_fee DECIMAL(10,2) DEFAULT 0 COMMENT '挂号费',"
                    + "total_num INT DEFAULT 30 COMMENT '号源数',"
                    + "room VARCHAR(50) DEFAULT NULL COMMENT '诊室',"
                    + "status TINYINT DEFAULT 1 COMMENT '1启用 0停用',"
                    + "create_by VARCHAR(50) DEFAULT NULL,"
                    + "create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL,"
                    + "update_time DATETIME DEFAULT NULL,"
                    + "deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_dept_staff (tenant_id, dept_id, staff_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='周排班模板'");
        }
    }

    /**
     * 幂等建表: 医生站业务单据三表(住院证/会诊申请/诊断证明, 门诊医生开具)。
     * DDL 与 sql/his_doctor.sql 末尾追加段保持同步, 保证存量库启动时自动获得新表(表已存在则跳过)。
     */
    private void ensureDoctorDocTables(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_admission_cert ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '住院证ID',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "visit_id BIGINT NOT NULL COMMENT '就诊ID',"
                    + "patient_id BIGINT DEFAULT NULL COMMENT '患者ID',"
                    + "patient_name VARCHAR(50) DEFAULT NULL COMMENT '患者姓名',"
                    + "admit_dept_id BIGINT DEFAULT NULL COMMENT '拟收治科室ID',"
                    + "admit_dept_name VARCHAR(100) DEFAULT NULL COMMENT '拟收治科室名称',"
                    + "admit_diagnosis VARCHAR(500) DEFAULT NULL COMMENT '入院诊断',"
                    + "condition_summary VARCHAR(1000) DEFAULT NULL COMMENT '病情摘要',"
                    + "admit_purpose VARCHAR(500) DEFAULT NULL COMMENT '入院目的',"
                    + "urgency TINYINT DEFAULT 1 COMMENT '紧急程度:1-普通 2-急 3-危急',"
                    + "status TINYINT DEFAULT 1 COMMENT '状态:1-已开具 2-已入院 3-已作废',"
                    + "apply_dr_id BIGINT DEFAULT NULL COMMENT '开具医师ID',"
                    + "apply_dr_name VARCHAR(50) DEFAULT NULL COMMENT '开具医师姓名',"
                    + "apply_time DATETIME DEFAULT NULL COMMENT '开具时间',"
                    + "org_id BIGINT DEFAULT NULL COMMENT '机构ID(开具时就诊科室归属机构)',"
                    + "create_by VARCHAR(50) DEFAULT NULL,"
                    + "create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL,"
                    + "update_time DATETIME DEFAULT NULL,"
                    + "deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_visit (visit_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='住院证'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_consult_request ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '会诊申请ID',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "visit_id BIGINT NOT NULL COMMENT '就诊ID',"
                    + "patient_id BIGINT DEFAULT NULL COMMENT '患者ID',"
                    + "patient_name VARCHAR(50) DEFAULT NULL COMMENT '患者姓名',"
                    + "apply_dept_id BIGINT DEFAULT NULL COMMENT '申请科室ID',"
                    + "apply_dept_name VARCHAR(100) DEFAULT NULL COMMENT '申请科室名称',"
                    + "apply_dr_id BIGINT DEFAULT NULL COMMENT '申请医师ID',"
                    + "apply_dr_name VARCHAR(50) DEFAULT NULL COMMENT '申请医师姓名',"
                    + "consult_dept_id BIGINT DEFAULT NULL COMMENT '受邀会诊科室ID',"
                    + "consult_dept_name VARCHAR(100) DEFAULT NULL COMMENT '受邀会诊科室名称',"
                    + "consult_purpose VARCHAR(500) DEFAULT NULL COMMENT '会诊目的',"
                    + "condition_summary VARCHAR(1000) DEFAULT NULL COMMENT '病情摘要',"
                    + "urgency TINYINT DEFAULT 1 COMMENT '紧急程度:1-普通 2-急 3-紧急',"
                    + "expected_time DATETIME DEFAULT NULL COMMENT '期望会诊时间',"
                    + "status TINYINT DEFAULT 1 COMMENT '状态:1-已申请 2-已接受 3-已完成 4-已拒绝',"
                    + "consult_opinion VARCHAR(1000) DEFAULT NULL COMMENT '会诊意见(受邀科室反馈)',"
                    + "org_id BIGINT DEFAULT NULL COMMENT '机构ID(申请科室归属机构)',"
                    + "create_by VARCHAR(50) DEFAULT NULL,"
                    + "create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL,"
                    + "update_time DATETIME DEFAULT NULL,"
                    + "deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_visit (visit_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会诊申请'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_medical_cert ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '证明ID',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "visit_id BIGINT NOT NULL COMMENT '就诊ID',"
                    + "patient_id BIGINT DEFAULT NULL COMMENT '患者ID',"
                    + "patient_name VARCHAR(50) DEFAULT NULL COMMENT '患者姓名',"
                    + "cert_type TINYINT DEFAULT 1 COMMENT '证明类型:1-诊断证明 2-病假条 3-转诊证明',"
                    + "diagnosis VARCHAR(500) DEFAULT NULL COMMENT '诊断',"
                    + "cert_content VARCHAR(2000) DEFAULT NULL COMMENT '证明内容',"
                    + "sick_leave_days INT DEFAULT NULL COMMENT '建议病假天数',"
                    + "remark VARCHAR(500) DEFAULT NULL COMMENT '备注',"
                    + "issue_dr_id BIGINT DEFAULT NULL COMMENT '开具医师ID',"
                    + "issue_dr_name VARCHAR(50) DEFAULT NULL COMMENT '开具医师姓名',"
                    + "issue_time DATETIME DEFAULT NULL COMMENT '开具时间',"
                    + "org_id BIGINT DEFAULT NULL COMMENT '机构ID(开具科室归属机构)',"
                    + "create_by VARCHAR(50) DEFAULT NULL,"
                    + "create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL,"
                    + "update_time DATETIME DEFAULT NULL,"
                    + "deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_visit (visit_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='诊断证明'");
        }
    }

    /** 幂等补种子: 挂号号别值域(reg_level, 排班模板/挂号使用, 院内补充) */
    private void seedRegLevelDict(Connection conn) throws Exception {
        String sql = "INSERT INTO std_cv_code (dict_code, dict_name, val_code, val_name, std_type, src_doc, vali_flag) "
                + "SELECT 'reg_level', '号别', ?, ?, '医保字典', '挂号号别值域(排班模板/挂号, 院内补充种子)', '1' "
                + "FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM std_cv_code c WHERE c.dict_code = 'reg_level' AND c.val_code = ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "01"); ps.setString(2, "普通号"); ps.setString(3, "01"); ps.executeUpdate();
            ps.setString(1, "02"); ps.setString(2, "副主任医师号"); ps.setString(3, "02"); ps.executeUpdate();
            ps.setString(1, "03"); ps.setString(2, "主任医师号"); ps.setString(3, "03"); ps.executeUpdate();
            ps.setString(1, "04"); ps.setString(2, "专家号"); ps.setString(3, "04"); ps.executeUpdate();
            ps.setString(1, "05"); ps.setString(2, "特需号"); ps.setString(3, "05"); ps.executeUpdate();
            ps.setString(1, "06"); ps.setString(2, "急诊号"); ps.setString(3, "06"); ps.executeUpdate();
        }
    }

    /** 幂等建表: 患者档案修改记录表(字段级留痕)。已存在则跳过。 */
    private void ensureChangeLogTable(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_patient_change_log ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "patient_id BIGINT NOT NULL COMMENT '患者ID',"
                    + "patient_name VARCHAR(50) NULL COMMENT '患者姓名(冗余)',"
                    + "batch_no VARCHAR(40) NULL COMMENT '同一次保存批次号',"
                    + "field_name VARCHAR(60) NULL COMMENT '变更字段属性名',"
                    + "field_label VARCHAR(60) NULL COMMENT '变更字段中文名',"
                    + "old_value VARCHAR(500) NULL COMMENT '修改前',"
                    + "new_value VARCHAR(500) NULL COMMENT '修改后',"
                    + "source VARCHAR(20) NULL COMMENT '变更来源:建档/手动修改/医保读卡',"
                    + "change_by_name VARCHAR(50) NULL COMMENT '修改人姓名',"
                    + "memo VARCHAR(200) NULL COMMENT '备注',"
                    + "create_by VARCHAR(50) NULL, create_time DATETIME NULL,"
                    + "update_by VARCHAR(50) NULL, update_time DATETIME NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id), KEY idx_tenant (tenant_id), KEY idx_patient (patient_id), KEY idx_batch (batch_no)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='患者档案修改记录表'");
        }
    }

    /**
     * 幂等建表: 患者医保参保信息表(【1101】输出节点 insuinfo 完整记录, 一人可多条)。
     * 检测到旧版表结构(无 psn_insu_stas 列)时丢弃重建: 该表为读卡同步缓存, 重新读卡即可重建。
     */
    private void ensurePatientInsuTable(Connection conn) throws Exception {
        if (columnDataType(conn, "his_patient_insu", "id") != null
                && columnDataType(conn, "his_patient_insu", "psn_insu_stas") == null) {
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("DROP TABLE his_patient_insu");
            }
        }
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_patient_insu ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "patient_id BIGINT NOT NULL COMMENT '患者ID(his_patient.id)',"
                    + "psn_no VARCHAR(30) NULL COMMENT '人员编号(1101 baseinfo.psn_no)',"
                    + "balc DECIMAL(16,2) NULL COMMENT '余额(1101 insuinfo.balc)',"
                    + "insutype VARCHAR(6) NULL COMMENT '险种类型编码(1101 insuinfo.insutype, cv_code:insutype)',"
                    + "insutype_name VARCHAR(100) NULL COMMENT '险种名称(字典回填)',"
                    + "insutype_src VARCHAR(50) NULL COMMENT '险种来源标识',"
                    + "psn_type VARCHAR(6) NULL COMMENT '人员类别编码(1101 insuinfo.psn_type, cv_code:psn_type)',"
                    + "psn_type_name VARCHAR(100) NULL COMMENT '人员类别名称(字典回填)',"
                    + "psn_type_src VARCHAR(50) NULL COMMENT '人员类别来源标识',"
                    + "psn_insu_stas VARCHAR(6) NULL COMMENT '人员参保状态编码(1101 insuinfo.psn_insu_stas, cv_code:psn_insu_stas)',"
                    + "psn_insu_stas_name VARCHAR(100) NULL COMMENT '人员参保状态名称(字典回填)',"
                    + "psn_insu_stas_src VARCHAR(50) NULL COMMENT '人员参保状态来源标识',"
                    + "psn_insu_date DATE NULL COMMENT '个人参保日期(1101 insuinfo.psn_insu_date)',"
                    + "paus_insu_date DATE NULL COMMENT '暂停参保日期(1101 insuinfo.paus_insu_date, null=当前在保)',"
                    + "cvlserv_flag VARCHAR(3) NULL COMMENT '公务员标志编码(1101 insuinfo.cvlserv_flag, cv_code:cvlserv_flag)',"
                    + "cvlserv_flag_name VARCHAR(100) NULL COMMENT '公务员标志名称(字典回填)',"
                    + "cvlserv_flag_src VARCHAR(50) NULL COMMENT '公务员标志来源标识',"
                    + "insuplc_admdvs VARCHAR(20) NULL COMMENT '参保地医保区划编码(1101 insuinfo.insuplc_admdvs, area_code_2021)',"
                    + "insuplc_admdvs_name VARCHAR(100) NULL COMMENT '参保地医保区划名称(字典回填)',"
                    + "insuplc_admdvs_src VARCHAR(50) NULL COMMENT '参保地医保区划来源标识',"
                    + "emp_name VARCHAR(200) NULL COMMENT '单位名称(1101 insuinfo.emp_name, 参保单位)',"
                    + "src VARCHAR(30) NULL COMMENT '记录来源: 医保读卡(1101)/手工',"
                    + "create_by VARCHAR(50) NULL, create_time DATETIME NULL,"
                    + "update_by VARCHAR(50) NULL, update_time DATETIME NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id), KEY idx_tenant (tenant_id), KEY idx_patient (patient_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='患者医保参保信息表(【1101】输出节点insuinfo完整记录, 一人可多条)'");
        }
    }

    /** 幂等补种子: 人员参保状态值域(1101 insuinfo.psn_insu_stas 代码标识; 湖北规范第6章字典表未含, 按国家平台码表补 1-参保 2-停保) */
    private void ensurePsnInsuStasDict(Connection conn) throws Exception {
        String sql = "INSERT INTO std_cv_code (dict_code, dict_name, val_code, val_name, std_type, src_doc, vali_flag) "
                + "SELECT 'psn_insu_stas', '人员参保状态', ?, ?, '医保字典', '国家医保信息平台码表psn_insu_stas(补充种子, 1101 insuinfo代码标识)', '1' "
                + "FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM std_cv_code c WHERE c.dict_code = 'psn_insu_stas' AND c.val_code = ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "1"); ps.setString(2, "参保"); ps.setString(3, "1"); ps.executeUpdate();
            ps.setString(1, "2"); ps.setString(2, "停保"); ps.setString(3, "2"); ps.executeUpdate();
        }
    }

    /**
     * 幂等建表: 供货商(企业)字典 std_supplier —— 全局共享(不分租户), 无 tenant_id, 走 raw JDBC 导入/维护。
     * 数据由 tools/extract_supplier.py 从医保各目录(drug/consumable/ivd/preparation)生产企业名去重汇总,
     * 经 classpath 种子文件 seed/std_supplier.tsv 导入。医共体药品/耗材目录的企业字段以此字典编码引用。
     * 列集对齐 std_* 惯例: 表头列(sup_code/sup_name/sup_type/src_catalog) + 注入常量(ver/std_type/src_doc)
     * + 生命周期(vali_flag/begn_time/end_time) + py_code + 审计列; 生命周期/审计列靠 MySQL DEFAULT 自动填充。
     */
    private void ensureStdSupplierTable(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS std_supplier ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "sup_code VARCHAR(40) NOT NULL COMMENT '供货商编码(企业去重后生成, 全局唯一)',"
                    + "sup_name VARCHAR(300) NOT NULL COMMENT '企业名称(供货商名称)',"
                    + "sup_type VARCHAR(60) DEFAULT NULL COMMENT '企业类型(生产企业/上市许可持有人/生产兼持有人/经营企业等)',"
                    + "src_catalog VARCHAR(120) DEFAULT NULL COMMENT '来源目录(drug/consumable/ivd/preparation, 可多值逗号分隔)',"
                    + "ver VARCHAR(30) DEFAULT NULL COMMENT '数据版本',"
                    + "std_type VARCHAR(30) DEFAULT '医保字典' COMMENT '字典标准类型',"
                    + "src_doc VARCHAR(200) DEFAULT NULL COMMENT '来源文档',"
                    + "py_code VARCHAR(64) DEFAULT NULL COMMENT '拼音简码(企业名称首字母, 自动生成只读)',"
                    + "vali_flag CHAR(1) DEFAULT '1' COMMENT '有效标志:1有效 0作废',"
                    + "begn_time DATETIME DEFAULT NULL COMMENT '生效时间',"
                    + "end_time DATETIME DEFAULT NULL COMMENT '作废时间',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_sup_code (sup_code),"
                    + "KEY idx_sup_name (sup_name(100)),"
                    + "KEY idx_sup_py (py_code)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供货商(企业)字典(医保各目录企业去重汇总, 全局共享)'");
        }
    }

    /**
     * 幂等建表: 企业资质证照附件表(全局共享), 存储上传的PDF/图片等资质文件元数据。
     * 物理文件落在 his.upload.path 下 supplier-doc/ 子目录。
     */
    private void ensureSupplierFileTable(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS std_supplier_file ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "sup_code VARCHAR(40) NOT NULL COMMENT '关联企业编码(std_supplier.sup_code)',"
                    + "doc_type VARCHAR(40) DEFAULT NULL COMMENT '资料类型:许可证/GMP/GSP/营业执照/其他',"
                    + "file_name VARCHAR(300) NOT NULL COMMENT '原始文件名',"
                    + "file_path VARCHAR(500) NOT NULL COMMENT '存储相对路径',"
                    + "file_size BIGINT DEFAULT 0 COMMENT '文件大小(字节)',"
                    + "mime_type VARCHAR(100) DEFAULT NULL COMMENT 'MIME类型',"
                    + "remark VARCHAR(500) DEFAULT NULL COMMENT '备注',"
                    + "upload_by VARCHAR(50) DEFAULT NULL COMMENT '上传人',"
                    + "upload_time DATETIME DEFAULT NULL COMMENT '上传时间',"
                    + "deleted TINYINT DEFAULT 0 COMMENT '软删:1已删 0正常',"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_sup_code (sup_code)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='企业资质证照附件(全局共享, 关联std_supplier)'");
        }
    }

    /** 幂等建表: 医共体统一目录四表(药品/耗材/调价留痕/机构开展目录), 牵头机构维护。 */
    private void ensureCommunityDictTables(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            /* 医共体药品目录: 标准字典溯源 + 三级单位包装/剂量换算 + 价格 + 药事管理分类 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_drug_catalog ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户(医共体)ID',"
                    + "drug_code VARCHAR(40) NOT NULL COMMENT '院内药品编码(租户内唯一)',"
                    + "yb_drug_code VARCHAR(50) NULL COMMENT '医保药品代码(std_drug.drug_code)',"
                    + "drug_std_code VARCHAR(50) NULL COMMENT '药品本位码',"
                    + "approval_no VARCHAR(100) NULL COMMENT '批准文号',"
                    + "generic_name VARCHAR(300) NOT NULL COMMENT '通用名(std_drug.reg_name)',"
                    + "trade_name VARCHAR(300) NULL COMMENT '商品名',"
                    + "major_class VARCHAR(50) NULL COMMENT '大类(西药/中成药等)',"
                    + "dosform VARCHAR(50) NULL COMMENT '剂型编码(cv_code:dosform, 允手工文本)',"
                    + "dosform_name VARCHAR(100) NULL COMMENT '剂型名称(字典回填)',"
                    + "dosform_src VARCHAR(50) NULL COMMENT '剂型来源标识',"
                    + "spec VARCHAR(255) NULL COMMENT '规格(如0.25g*24粒)',"
                    + "manufacturer VARCHAR(200) NULL COMMENT '生产企业',"
                    + "mkt_holder VARCHAR(200) NULL COMMENT '上市许可持有人',"
                    + "chrgitm_lv VARCHAR(20) NULL COMMENT '甲乙丙类编码(cv_code:chrgitm_lv)',"
                    + "chrgitm_lv_name VARCHAR(50) NULL COMMENT '甲乙丙类名称(字典回填)',"
                    + "chrgitm_lv_src VARCHAR(50) NULL COMMENT '甲乙丙类来源标识',"
                    + "selfpay_prop DECIMAL(5,4) NULL COMMENT '自付比例(0-1)',"
                    + "pay_std_prep VARCHAR(30) NULL COMMENT '医保支付标准(最小制剂单位)',"
                    + "nego_flag VARCHAR(20) NULL COMMENT '谈判药品标识',"
                    + "msd_flag VARCHAR(20) NULL COMMENT '门诊特殊疾病对应标识',"
                    + "ltd_self_flag VARCHAR(20) NULL COMMENT '限定支付范围自费标识',"
                    + "limit_scope VARCHAR(500) NULL COMMENT '限定支付范围说明',"
                    + "dose_unit VARCHAR(20) NULL COMMENT '剂量单位(cv_code:dose_unit g/mg/IU/mL等)',"
                    + "unit_dose DECIMAL(12,4) NULL COMMENT '每最小包装单位含药量(如0.25g/粒)',"
                    + "min_unit VARCHAR(20) NULL COMMENT '最小包装/发药单位(片/粒/支, 药房计量基准)',"
                    + "pack_unit VARCHAR(20) NULL COMMENT '采购/大包装单位(盒/瓶/箱, 药库记账单位)',"
                    + "pack_ratio INT NULL COMMENT '包装换算比(大包装→最小单位, 如24粒/盒)',"
                    + "round_rule TINYINT NULL DEFAULT 1 COMMENT '发药取整规则:1向上 2向下 3四舍五入',"
                    + "purchase_price DECIMAL(12,4) NULL COMMENT '进货价(最小单位)',"
                    + "retail_price DECIMAL(12,4) NULL COMMENT '零售价(最小单位)',"
                    + "zero_margin TINYINT NULL DEFAULT 1 COMMENT '零差率标志:1是 0否',"
                    + "drug_class VARCHAR(30) NULL COMMENT '药品管理类别编码(cv_code:drug_class 一般/麻醉/精一/精二/毒性/放射/易制毒)',"
                    + "drug_class_name VARCHAR(50) NULL COMMENT '药品管理类别名称(字典回填)',"
                    + "drug_class_src VARCHAR(50) NULL COMMENT '药品管理类别来源标识',"
                    + "abx_grade VARCHAR(6) NULL COMMENT '抗菌药物分级(hbvalue:HBCV08.50.029 11非限制/12限制/13特殊使用)',"
                    + "abx_grade_name VARCHAR(50) NULL COMMENT '抗菌药物分级名称(字典回填)',"
                    + "abx_grade_src VARCHAR(50) NULL COMMENT '抗菌药物分级来源标识',"
                    + "otc_flag TINYINT NULL DEFAULT 0 COMMENT 'OTC标志:1是 0否',"
                    + "essential_flag TINYINT NULL DEFAULT 0 COMMENT '基本药物标志:1是 0否',"
                    + "preg_class VARCHAR(10) NULL COMMENT '妊娠用药分级(A/B/C/D/X)',"
                    + "skin_test_flag TINYINT NULL DEFAULT 0 COMMENT '皮试标志:1需皮试 0否',"
                    + "storage_cond VARCHAR(30) NULL COMMENT '储存条件编码(cv_code:storage_cond)',"
                    + "storage_cond_name VARCHAR(50) NULL COMMENT '储存条件名称(字典回填)',"
                    + "storage_cond_src VARCHAR(50) NULL COMMENT '储存条件来源标识',"
                    + "max_qty_once DECIMAL(12,2) NULL COMMENT '单次处方最大量(最小单位, 管制药品)',"
                    + "status TINYINT NULL DEFAULT 1 COMMENT '状态:1启用 0停用',"
                    + "eff_date DATE NULL COMMENT '生效日期',"
                    + "end_date DATE NULL COMMENT '作废日期',"
                    + "memo VARCHAR(500) NULL COMMENT '备注',"
                    + "src_type VARCHAR(30) NULL COMMENT '来源标准字典key(drug/tcm/preparation/院内自定义)',"
                    + "src_doc VARCHAR(200) NULL COMMENT '来源文档',"
                    + "src_code VARCHAR(50) NULL COMMENT '来源编码(标准字典行编码)',"
                    + "create_by VARCHAR(50) NULL, create_time DATETIME NULL,"
                    + "update_by VARCHAR(50) NULL, update_time DATETIME NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id), UNIQUE KEY uk_tenant_drug_code (tenant_id, drug_code),"
                    + "KEY idx_yb_drug_code (yb_drug_code), KEY idx_generic_name (generic_name(80)), KEY idx_tenant (tenant_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='医共体药品目录(牵头机构维护, 含三级单位包装/剂量换算)'");
            /* 医共体耗材目录 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_cons_catalog ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户(医共体)ID',"
                    + "cons_code VARCHAR(40) NOT NULL COMMENT '院内耗材编码(租户内唯一)',"
                    + "yb_cons_code VARCHAR(50) NULL COMMENT '医保耗材代码(20位, std_consumable.cons_code)',"
                    + "reg_cert_no VARCHAR(500) NULL COMMENT '注册证号',"
                    + "name VARCHAR(300) NOT NULL COMMENT '耗材通用名(std_consumable.hi_genname)',"
                    + "cat1 VARCHAR(100) NULL COMMENT '医保一级分类',"
                    + "cat2 VARCHAR(100) NULL COMMENT '医保二级分类',"
                    + "cat3 VARCHAR(100) NULL COMMENT '医保三级分类',"
                    + "spec_model VARCHAR(255) NULL COMMENT '规格型号(补充录入)',"
                    + "material VARCHAR(100) NULL COMMENT '材质',"
                    + "feature VARCHAR(100) NULL COMMENT '特征',"
                    + "manufacturer VARCHAR(200) NULL COMMENT '生产企业',"
                    + "min_unit VARCHAR(20) NULL COMMENT '最小计价单位(个/套)',"
                    + "pack_unit VARCHAR(20) NULL COMMENT '采购单位(盒/包)',"
                    + "pack_ratio INT NULL COMMENT '包装换算比(采购单位→最小单位)',"
                    + "purchase_price DECIMAL(12,4) NULL COMMENT '进货价(最小单位)',"
                    + "charge_price DECIMAL(12,4) NULL COMMENT '收费价(单独收费项)',"
                    + "charge_flag TINYINT NULL DEFAULT 1 COMMENT '收费方式:1单独收费 0包含性(不单独收费)',"
                    + "chrgitm_lv VARCHAR(20) NULL COMMENT '甲乙丙类编码(cv_code:chrgitm_lv)',"
                    + "chrgitm_lv_name VARCHAR(50) NULL COMMENT '甲乙丙类名称(字典回填)',"
                    + "chrgitm_lv_src VARCHAR(50) NULL COMMENT '甲乙丙类来源标识',"
                    + "selfpay_prop DECIMAL(5,4) NULL COMMENT '自付比例(0-1)',"
                    + "pay_std VARCHAR(50) NULL COMMENT '医保支付标准',"
                    + "high_value_flag TINYINT NULL DEFAULT 0 COMMENT '高值耗材标志:1是 0否',"
                    + "implant_flag TINYINT NULL DEFAULT 0 COMMENT '植入类标志:1是 0否',"
                    + "sterile_flag TINYINT NULL DEFAULT 0 COMMENT '无菌标志:1是 0否',"
                    + "status TINYINT NULL DEFAULT 1 COMMENT '状态:1启用 0停用',"
                    + "eff_date DATE NULL COMMENT '生效日期',"
                    + "end_date DATE NULL COMMENT '作废日期',"
                    + "memo VARCHAR(500) NULL COMMENT '备注',"
                    + "src_type VARCHAR(30) NULL COMMENT '来源标准字典key(consumable/院内自定义)',"
                    + "src_doc VARCHAR(200) NULL COMMENT '来源文档',"
                    + "src_code VARCHAR(50) NULL COMMENT '来源编码(标准字典行编码)',"
                    + "create_by VARCHAR(50) NULL, create_time DATETIME NULL,"
                    + "update_by VARCHAR(50) NULL, update_time DATETIME NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id), UNIQUE KEY uk_tenant_cons_code (tenant_id, cons_code),"
                    + "KEY idx_yb_cons_code (yb_cons_code), KEY idx_cons_name (name(80)), KEY idx_cons_tenant (tenant_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='医共体耗材目录(牵头机构维护)'");
            /* 调价留痕: 三目录统一, 必录调价文号+生效日期 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_price_adjust ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户(医共体)ID',"
                    + "catalog_type VARCHAR(20) NOT NULL COMMENT '目录类型:charge/drug/cons',"
                    + "catalog_id BIGINT NOT NULL COMMENT '目录记录ID',"
                    + "catalog_name VARCHAR(300) NULL COMMENT '目录名称(冗余)',"
                    + "price_field VARCHAR(30) NULL COMMENT '调价字段:price_l1/l2/l3或purchase_price/retail_price/charge_price',"
                    + "price_label VARCHAR(50) NULL COMMENT '调价字段中文名',"
                    + "org_level TINYINT NULL COMMENT '收费项目价格档次:1/2/3(药耗为空)',"
                    + "old_price DECIMAL(12,4) NULL COMMENT '原价',"
                    + "new_price DECIMAL(12,4) NULL COMMENT '新价',"
                    + "adjust_doc_no VARCHAR(100) NULL COMMENT '调价文号',"
                    + "eff_date DATE NULL COMMENT '生效日期',"
                    + "reason VARCHAR(500) NULL COMMENT '调价原因',"
                    + "operator_name VARCHAR(50) NULL COMMENT '操作人姓名',"
                    + "create_by VARCHAR(50) NULL, create_time DATETIME NULL,"
                    + "update_by VARCHAR(50) NULL, update_time DATETIME NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id), KEY idx_pa_tenant (tenant_id), KEY idx_pa_catalog (catalog_type, catalog_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='医共体目录调价留痕表(调价文号+生效日期必录)'");
            /* 机构开展目录: L3 选用子集, 只能启停不能改价 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_org_catalog ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户(医共体)ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID(sys_org)',"
                    + "org_name VARCHAR(200) NULL COMMENT '机构名称(冗余)',"
                    + "catalog_type VARCHAR(20) NOT NULL COMMENT '目录类型:charge/drug/cons',"
                    + "catalog_id BIGINT NOT NULL COMMENT '医共体目录记录ID',"
                    + "catalog_name VARCHAR(300) NULL COMMENT '目录名称(冗余)',"
                    + "enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否开展:1启用 0停用',"
                    + "eff_date DATE NULL COMMENT '开展生效日期',"
                    + "memo VARCHAR(200) NULL COMMENT '备注',"
                    + "create_by VARCHAR(50) NULL, create_time DATETIME NULL,"
                    + "update_by VARCHAR(50) NULL, update_time DATETIME NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id), UNIQUE KEY uk_org_catalog (org_id, catalog_type, catalog_id), KEY idx_oc_tenant (tenant_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='机构开展目录(从医共体目录勾选, 价格不可改)'");
            /* 医共体用药字典: 用法(给药途径)/用药频次, 单表按 dict_type 区分, 牵头机构统一维护 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_med_dict ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户(医共体)ID',"
                    + "dict_type VARCHAR(10) NOT NULL COMMENT '字典类型:usage-用法(给药途径) freq-用药频次',"
                    + "code VARCHAR(40) NOT NULL COMMENT '院内编码(租户内同类型唯一)',"
                    + "name VARCHAR(100) NOT NULL COMMENT '名称(如口服/静脉注射; 每天三次tid)',"
                    + "yb_code VARCHAR(20) NULL COMMENT '医保值域编码(用法:drug_medc_way_code/CV06.00.102; 频次:used_frqu/CV06.00.228)',"
                    + "daily_times DECIMAL(8,4) NULL COMMENT '每日次数(仅频次; 驱动发药量换算, 支持小数如0.5=隔日)',"
                    + "sort_no INT NULL DEFAULT 0 COMMENT '排序号',"
                    + "status TINYINT NULL DEFAULT 1 COMMENT '状态:1启用 0停用',"
                    + "memo VARCHAR(500) NULL COMMENT '备注',"
                    + "src_type VARCHAR(30) NULL COMMENT '来源标准字典key(cv_code/hbvalue/院内自定义)',"
                    + "src_doc VARCHAR(200) NULL COMMENT '来源文档',"
                    + "src_code VARCHAR(50) NULL COMMENT '来源编码(标准值域行编码)',"
                    + "create_by VARCHAR(50) NULL, create_time DATETIME NULL,"
                    + "update_by VARCHAR(50) NULL, update_time DATETIME NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id), UNIQUE KEY uk_tenant_type_code (tenant_id, dict_type, code),"
                    + "KEY idx_md_type (dict_type), KEY idx_md_tenant (tenant_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='医共体用药字典(用法/用药频次, 牵头机构维护)'");
            /* 医共体诊断字典: 西医诊断/中医诊断/症候/手术/肿瘤, 单表按 dict_type 区分, 牵头机构从标准字典(ICD-10/ICD-9/形态学/中医病证)导入 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_diag_dict ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户(医共体)ID',"
                    + "dict_type VARCHAR(10) NOT NULL COMMENT '字典类型:west-西医诊断(ICD-10) tcm-中医诊断 symp-中医症候 oper-手术操作(ICD-9) tumor-肿瘤形态学',"
                    + "code VARCHAR(40) NOT NULL COMMENT '院内编码(租户内同类型唯一, 导入时取标准字典编码)',"
                    + "name VARCHAR(200) NOT NULL COMMENT '名称(诊断/术式/症候名)',"
                    + "yb_code VARCHAR(40) NULL COMMENT '医保编码(国标版源导入时=code, 如E11.900; 仅国标来源时留空待补)',"
                    + "category VARCHAR(200) NULL COMMENT '类目(标准字典附加列: 章节/系统类目/亚目等)',"
                    + "sort_no INT NULL DEFAULT 0 COMMENT '排序号',"
                    + "status TINYINT NULL DEFAULT 1 COMMENT '状态:1启用 0停用',"
                    + "memo VARCHAR(500) NULL COMMENT '备注',"
                    + "src_type VARCHAR(30) NULL COMMENT '来源标准字典key(icd10/icd10_nat/icd9/icd9_nat/morphology/tcm_disease_new/tcm_disease/tcm_syndrome_new/tcm_syndrome)',"
                    + "src_doc VARCHAR(200) NULL COMMENT '来源文档(标准字典行src_doc, 逐行不同)',"
                    + "src_code VARCHAR(50) NULL COMMENT '来源编码(标准字典行编码)',"
                    + "py_code VARCHAR(64) NULL COMMENT '拼音简码(名称首字母, 自动生成只读)',"
                    + "abbr_code VARCHAR(64) NULL COMMENT '自定义简码(人工维护, 选填)',"
                    + "create_by VARCHAR(50) NULL, create_time DATETIME NULL,"
                    + "update_by VARCHAR(50) NULL, update_time DATETIME NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id), UNIQUE KEY uk_tenant_diag_type_code (tenant_id, dict_type, code),"
                    + "KEY idx_dd_type (dict_type), KEY idx_dd_tenant (tenant_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='医共体诊断字典(五类: 西医/中医/症候/手术/肿瘤, 牵头机构维护)'");
            /* 医共体值域字典: 医疗业务自由值域(性别/险种/剂型/号别等), dict_type=标准源键:分组码(如 cv_code:gend),
               牵头机构从基本字典(std_cv_code/std_wst364_code/std_hbvalue_code/std_whvalue_code)整组导入并补充机构字段, 业务下拉统一取值源 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_val_dict ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户(医共体)ID',"
                    + "dict_type VARCHAR(64) NOT NULL COMMENT '值域类别=标准源键:分组码, 如 cv_code:gend / hbvalue:HBCV08.50.029',"
                    + "type_name VARCHAR(200) NULL COMMENT '值域中文名(导入时取标准字典组名)',"
                    + "code VARCHAR(64) NOT NULL COMMENT '值编码(租户内同类唯一, 导入取标准值域码)',"
                    + "name VARCHAR(200) NOT NULL COMMENT '值名称',"
                    + "yb_code VARCHAR(64) NULL COMMENT '医保值域编码(cv_code 源导入时=code; 卫健/湖北源留空)',"
                    + "sort_no INT NULL DEFAULT 0 COMMENT '排序号',"
                    + "status TINYINT NULL DEFAULT 1 COMMENT '状态:1启用 0停用',"
                    + "memo VARCHAR(500) NULL COMMENT '备注',"
                    + "src_type VARCHAR(30) NULL COMMENT '来源标准字典key(cv_code/wst364/hbvalue/whvalue)',"
                    + "src_doc VARCHAR(200) NULL COMMENT '来源文档',"
                    + "src_code VARCHAR(64) NULL COMMENT '来源编码(标准值域行编码)',"
                    + "py_code VARCHAR(64) NULL COMMENT '拼音简码(名称首字母, 自动生成只读)',"
                    + "abbr_code VARCHAR(64) NULL COMMENT '自定义简码(人工维护, 选填)',"
                    + "create_by VARCHAR(50) NULL, create_time DATETIME NULL,"
                    + "update_by VARCHAR(50) NULL, update_time DATETIME NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id), UNIQUE KEY uk_tenant_val_type_code (tenant_id, dict_type, code),"
                    + "KEY idx_vd_type (dict_type), KEY idx_vd_tenant (tenant_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='医共体值域字典(业务自由值域统一取数源, 牵头机构维护)'");
        }
    }

    /** 幂等建表: 药库五表(批次级库存 + 入库单主/明细 + 出库单主/明细)。 */
    private void ensureWarehouseTables(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            /* 药品库存: 批次级独立记账, 有效期预警 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_drug_stock ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "drug_catalog_id BIGINT NOT NULL COMMENT '药品目录ID',"
                    + "drug_code VARCHAR(50) NOT NULL COMMENT '药品编码',"
                    + "drug_name VARCHAR(200) NOT NULL COMMENT '药品名称',"
                    + "spec VARCHAR(200) DEFAULT NULL COMMENT '规格',"
                    + "dosform VARCHAR(50) DEFAULT NULL COMMENT '剂型',"
                    + "batch_no VARCHAR(50) NOT NULL COMMENT '批次号',"
                    + "manufacturer VARCHAR(200) DEFAULT NULL COMMENT '生产厂家',"
                    + "qty DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '库存数量',"
                    + "cost_price DECIMAL(12,4) DEFAULT NULL COMMENT '进价',"
                    + "retail_price DECIMAL(12,4) DEFAULT NULL COMMENT '零售价',"
                    + "prod_date DATE DEFAULT NULL COMMENT '生产日期',"
                    + "exp_date DATE DEFAULT NULL COMMENT '有效期',"
                    + "warn_qty DECIMAL(12,2) DEFAULT 10 COMMENT '预警量',"
                    + "status TINYINT DEFAULT 1 COMMENT '状态:1正常 0停用',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_org_drug_batch (tenant_id, org_id, drug_catalog_id, batch_no),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_org (tenant_id, org_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='药品库存(批次级)'");
            /* 入库单: 主表(单号租户内唯一) */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_stock_in ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "in_no VARCHAR(30) NOT NULL COMMENT '入库单号',"
                    + "in_type TINYINT NOT NULL COMMENT '入库类型:1采购 2退药回库 3盘盈 4调拨入',"
                    + "supplier VARCHAR(200) DEFAULT NULL COMMENT '供应商',"
                    + "supplier_contact VARCHAR(50) DEFAULT NULL COMMENT '供应商联系方式',"
                    + "total_amount DECIMAL(12,2) DEFAULT 0 COMMENT '总金额',"
                    + "status TINYINT DEFAULT 0 COMMENT '状态:0草稿 1已确认 2已作废',"
                    + "confirm_by VARCHAR(50) DEFAULT NULL COMMENT '确认人',"
                    + "confirm_time DATETIME DEFAULT NULL COMMENT '确认时间',"
                    + "remark VARCHAR(500) DEFAULT NULL COMMENT '备注',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_in_no (tenant_id, in_no),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_org_status (tenant_id, org_id, status)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='入库单'");
            /* 入库明细: 确认入库时按批次写入 his_drug_stock */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_stock_in_item ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "stock_in_id BIGINT NOT NULL COMMENT '入库单ID',"
                    + "drug_catalog_id BIGINT NOT NULL COMMENT '药品目录ID',"
                    + "drug_code VARCHAR(50) NOT NULL COMMENT '药品编码',"
                    + "drug_name VARCHAR(200) NOT NULL COMMENT '药品名称',"
                    + "spec VARCHAR(200) DEFAULT NULL COMMENT '规格',"
                    + "batch_no VARCHAR(50) NOT NULL COMMENT '批次号',"
                    + "manufacturer VARCHAR(200) DEFAULT NULL COMMENT '生产厂家',"
                    + "qty DECIMAL(12,2) NOT NULL COMMENT '数量',"
                    + "cost_price DECIMAL(12,4) DEFAULT NULL COMMENT '进价',"
                    + "retail_price DECIMAL(12,4) DEFAULT NULL COMMENT '零售价',"
                    + "prod_date DATE DEFAULT NULL COMMENT '生产日期',"
                    + "exp_date DATE DEFAULT NULL COMMENT '有效期',"
                    + "amount DECIMAL(12,2) DEFAULT NULL COMMENT '小计金额',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_stock_in (stock_in_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='入库明细'");
            /* 出库单: 主表(处方发药/报损/盘亏/调拨出) */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_stock_out ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "out_no VARCHAR(30) NOT NULL COMMENT '出库单号',"
                    + "out_type TINYINT NOT NULL COMMENT '出库类型:1处方发药 2报损 3盘亏 4调拨出',"
                    + "ref_id BIGINT DEFAULT NULL COMMENT '关联单据ID',"
                    + "ref_no VARCHAR(50) DEFAULT NULL COMMENT '关联单据号',"
                    + "total_amount DECIMAL(12,2) DEFAULT 0 COMMENT '总金额',"
                    + "status TINYINT DEFAULT 0 COMMENT '状态:0草稿 1已确认 2已作废',"
                    + "confirm_by VARCHAR(50) DEFAULT NULL COMMENT '确认人',"
                    + "confirm_time DATETIME DEFAULT NULL COMMENT '确认时间',"
                    + "remark VARCHAR(500) DEFAULT NULL COMMENT '备注',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_out_no (tenant_id, out_no),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_org_status (tenant_id, org_id, status)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出库单'");
            /* 出库明细: 记录扣减的库存批次(先进先出可追溯) */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_stock_out_item ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "stock_out_id BIGINT NOT NULL COMMENT '出库单ID',"
                    + "drug_stock_id BIGINT DEFAULT NULL COMMENT '库存批次ID',"
                    + "drug_catalog_id BIGINT NOT NULL COMMENT '药品目录ID',"
                    + "drug_code VARCHAR(50) NOT NULL COMMENT '药品编码',"
                    + "drug_name VARCHAR(200) NOT NULL COMMENT '药品名称',"
                    + "spec VARCHAR(200) DEFAULT NULL COMMENT '规格',"
                    + "batch_no VARCHAR(50) DEFAULT NULL COMMENT '批次号',"
                    + "qty DECIMAL(12,2) NOT NULL COMMENT '数量',"
                    + "cost_price DECIMAL(12,4) DEFAULT NULL COMMENT '进价',"
                    + "retail_price DECIMAL(12,4) DEFAULT NULL COMMENT '零售价',"
                    + "amount DECIMAL(12,2) DEFAULT NULL COMMENT '小计金额',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_stock_out (stock_out_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出库明细'");
        }
    }

    /** 幂等建表: 药房两表(发药记录 + 退药记录)。 */
    private void ensurePharmacyTables(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            /* 发药记录: 处方收费后药房调配/发药/核对(双签), 与 his_prescription.dispense_status 联动 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_dispense ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "dispense_no VARCHAR(30) NOT NULL COMMENT '发药单号',"
                    + "visit_id BIGINT DEFAULT NULL COMMENT '就诊ID',"
                    + "prescription_id BIGINT NOT NULL COMMENT '处方ID',"
                    + "patient_id BIGINT DEFAULT NULL COMMENT '患者ID',"
                    + "patient_name VARCHAR(50) DEFAULT NULL COMMENT '患者姓名',"
                    + "doctor_name VARCHAR(50) DEFAULT NULL COMMENT '医生姓名',"
                    + "dept_name VARCHAR(100) DEFAULT NULL COMMENT '科室名称',"
                    + "status TINYINT DEFAULT 0 COMMENT '状态:0待发药 1已调配 2已发药 3已退药',"
                    + "dispense_by VARCHAR(50) DEFAULT NULL COMMENT '发药人',"
                    + "dispense_time DATETIME DEFAULT NULL COMMENT '发药时间',"
                    + "check_by VARCHAR(50) DEFAULT NULL COMMENT '核对人',"
                    + "check_time DATETIME DEFAULT NULL COMMENT '核对时间',"
                    + "total_amount DECIMAL(12,2) DEFAULT 0 COMMENT '总金额',"
                    + "remark VARCHAR(500) DEFAULT NULL COMMENT '备注',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_dispense_no (tenant_id, dispense_no),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_org_status (tenant_id, org_id, status, create_time),"
                    + "KEY idx_prescription (prescription_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发药记录'");
            /* 退药记录: 已发药处方退回, 审核通过后回补库存 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_drug_return ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "return_no VARCHAR(30) NOT NULL COMMENT '退药单号',"
                    + "dispense_id BIGINT NOT NULL COMMENT '发药记录ID',"
                    + "visit_id BIGINT DEFAULT NULL COMMENT '就诊ID',"
                    + "patient_id BIGINT DEFAULT NULL COMMENT '患者ID',"
                    + "patient_name VARCHAR(50) DEFAULT NULL COMMENT '患者姓名',"
                    + "reason VARCHAR(500) DEFAULT NULL COMMENT '退药原因',"
                    + "status TINYINT DEFAULT 0 COMMENT '状态:0待审核 1已退药 2已驳回',"
                    + "return_amount DECIMAL(12,2) DEFAULT 0 COMMENT '退药金额',"
                    + "approve_by VARCHAR(50) DEFAULT NULL COMMENT '审批人',"
                    + "approve_time DATETIME DEFAULT NULL COMMENT '审批时间',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_return_no (tenant_id, return_no),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_org_status (tenant_id, org_id, status),"
                    + "KEY idx_dispense (dispense_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退药记录'");
        }
    }

    /** 幂等建表: 收费三表(收费单主/明细 + 门诊日结)。 */
    private void ensureCashierTables(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            /* 收费单: 处方/项目合并收费, 医保结算四分(自付/基金/现金/个账) */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_charge_bill ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "bill_no VARCHAR(30) NOT NULL COMMENT '收费单号',"
                    + "visit_id BIGINT DEFAULT NULL COMMENT '就诊ID',"
                    + "registration_id BIGINT DEFAULT NULL COMMENT '挂号ID',"
                    + "patient_id BIGINT DEFAULT NULL COMMENT '患者ID',"
                    + "patient_name VARCHAR(50) DEFAULT NULL COMMENT '患者姓名',"
                    + "bill_type TINYINT DEFAULT 1 COMMENT '单据类型:1门诊收费 2门诊退费',"
                    + "total_amount DECIMAL(12,2) DEFAULT 0 COMMENT '总金额',"
                    + "self_pay DECIMAL(12,2) DEFAULT 0 COMMENT '自付金额',"
                    + "fund_pay DECIMAL(12,2) DEFAULT 0 COMMENT '基金支付',"
                    + "cash_pay DECIMAL(12,2) DEFAULT 0 COMMENT '现金支付',"
                    + "acct_pay DECIMAL(12,2) DEFAULT 0 COMMENT '个账支付',"
                    + "setl_id VARCHAR(50) DEFAULT NULL COMMENT '医保结算ID',"
                    + "status TINYINT DEFAULT 0 COMMENT '状态:0待收费 1已收费 2已退费(-1作废)',"
                    + "yb_status TINYINT NOT NULL DEFAULT 0 COMMENT '医保结算状态:0未结算 1结算中 2已结算 3撤销中 4已撤销 9冲正中',"
                    + "charge_by VARCHAR(50) DEFAULT NULL COMMENT '收费员',"
                    + "charge_time DATETIME DEFAULT NULL COMMENT '收费时间',"
                    + "remark VARCHAR(500) DEFAULT NULL COMMENT '备注',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_bill_no (tenant_id, bill_no),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_org_status (tenant_id, org_id, status, charge_time),"
                    + "KEY idx_visit (visit_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收费单'");
            /* 收费明细: 来源处方明细/医嘱项目, 冗余医保编码/名称/自付比例快照 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_charge_bill_item ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "bill_id BIGINT NOT NULL COMMENT '收费单ID',"
                    + "item_type TINYINT NOT NULL COMMENT '项目类型:1药品 2检查 3治疗 4材料',"
                    + "ref_type VARCHAR(30) DEFAULT NULL COMMENT '来源类型:prescription_item/order_item',"
                    + "ref_id BIGINT DEFAULT NULL COMMENT '来源ID',"
                    + "item_code VARCHAR(50) DEFAULT NULL COMMENT '项目编码',"
                    + "item_name VARCHAR(200) NOT NULL COMMENT '项目名称',"
                    + "spec VARCHAR(200) DEFAULT NULL COMMENT '规格',"
                    + "qty DECIMAL(12,2) NOT NULL COMMENT '数量',"
                    + "price DECIMAL(12,4) NOT NULL COMMENT '单价',"
                    + "amount DECIMAL(12,2) NOT NULL COMMENT '金额',"
                    + "med_list_codg VARCHAR(50) DEFAULT NULL COMMENT '医保编码',"
                    + "med_list_name VARCHAR(200) DEFAULT NULL COMMENT '医保名称',"
                    + "ratio DECIMAL(5,4) DEFAULT 0 COMMENT '自付比例',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_bill (bill_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收费明细'");
            /* 门诊日结: 收费员按日汇总(收费/退费笔数金额 + 现金/基金/个账合计), 机构+日期唯一 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_daily_settle ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "settle_date DATE NOT NULL COMMENT '结算日期',"
                    + "operator VARCHAR(50) DEFAULT NULL COMMENT '操作人',"
                    + "total_count INT DEFAULT 0 COMMENT '收费笔数',"
                    + "total_amount DECIMAL(12,2) DEFAULT 0 COMMENT '总金额',"
                    + "refund_count INT DEFAULT 0 COMMENT '退费笔数',"
                    + "refund_amount DECIMAL(12,2) DEFAULT 0 COMMENT '退费金额',"
                    + "cash_total DECIMAL(12,2) DEFAULT 0 COMMENT '现金合计',"
                    + "fund_total DECIMAL(12,2) DEFAULT 0 COMMENT '基金合计',"
                    + "acct_total DECIMAL(12,2) DEFAULT 0 COMMENT '个账合计',"
                    + "reg_count INT DEFAULT 0 COMMENT '挂号笔数(净额: 挂号-退号)',"
                    + "reg_amount DECIMAL(12,2) DEFAULT 0 COMMENT '挂号费净额(挂号-退号)',"
                    + "wechat_total DECIMAL(12,2) DEFAULT 0 COMMENT '微信合计(收费-退费净额)',"
                    + "alipay_total DECIMAL(12,2) DEFAULT 0 COMMENT '支付宝合计(收费-退费净额)',"
                    + "card_total DECIMAL(12,2) DEFAULT 0 COMMENT '银行卡合计(收费-退费净额)',"
                    + "free_total DECIMAL(12,2) DEFAULT 0 COMMENT '减免合计(收费-退费净额)',"
                    + "status TINYINT DEFAULT 0 COMMENT '状态:0未日结 1已日结',"
                    + "settle_time DATETIME DEFAULT NULL COMMENT '日结时间',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_org_date (tenant_id, org_id, settle_date),"
                    + "KEY idx_tenant (tenant_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='门诊日结'");
            /* 批次4 M3(P1-18): 挂号收费流水(挂号/退号逐笔正负流水, 日结挂号费与全渠道支付分项记账依据) */
            ensureRegPaymentTable(conn);
        }
    }

    /** 幂等建表: 挂号收费流水(P1-18, 挂号/退号逐笔正负流水)。 */
    private void ensureRegPaymentTable(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
        st.executeUpdate("CREATE TABLE IF NOT EXISTS his_reg_payment ("
                + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                + "org_id BIGINT DEFAULT NULL COMMENT '机构ID(科室未归属机构的历史数据为空)',"
                + "registration_id BIGINT NOT NULL COMMENT '挂号记录ID(his_registration.id)',"
                + "reg_no VARCHAR(30) DEFAULT NULL COMMENT '挂号单号',"
                + "patient_id BIGINT DEFAULT NULL COMMENT '患者ID',"
                + "patient_name VARCHAR(50) DEFAULT NULL COMMENT '患者姓名',"
                + "direction TINYINT NOT NULL COMMENT '流水方向:1挂号收款 -1退号退款',"
                + "amount DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '金额(正数, 方向由direction表达)',"
                + "pay_method VARCHAR(20) DEFAULT NULL COMMENT '支付方式全渠道:CASH/WECHAT/ALIPAY/CARD/INSURANCE/FREE',"
                + "mdtrt_id VARCHAR(50) DEFAULT NULL COMMENT '医保就诊ID(挂号医保结算时落mdtrt_id)',"
                + "biz_time DATETIME DEFAULT NULL COMMENT '业务时间(挂号/退号时间)',"
                + "operator VARCHAR(50) DEFAULT NULL COMMENT '操作人',"
                + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                + "PRIMARY KEY (id),"
                + "KEY idx_regpay_tenant (tenant_id),"
                + "KEY idx_regpay_org_time (tenant_id, org_id, biz_time),"
                + "KEY idx_regpay_reg (registration_id),"
                + "KEY idx_regpay_method (tenant_id, org_id, pay_method)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='挂号收费流水(P1-18)'");
        }
    }

    /** 幂等建表: 药库定义表(机构级多药库, 药库库存/入出库按库房独立记账; UNIQUE tenant_id+org_id+code)。 */
    private void ensureWarehouseDefTable(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_warehouse_def ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "code VARCHAR(40) NOT NULL COMMENT '仓库编码(如WH-WEST-01)',"
                    + "name VARCHAR(100) NOT NULL COMMENT '仓库名称(如西药库)',"
                    + "warehouse_type VARCHAR(20) DEFAULT NULL COMMENT '仓库类型:WESTERN/TCM/MIXED',"
                    + "kind VARCHAR(20) NOT NULL DEFAULT 'WAREHOUSE' COMMENT '库存位类型:WAREHOUSE药库/PHARMACY药房库位',"
                    + "ref_pharmacy_id BIGINT DEFAULT NULL COMMENT 'PHARMACY型回指药房ID(his_pharmacy_def.id)',"
                    + "dept_id BIGINT DEFAULT NULL COMMENT '归属科室(his_dept.id, 仅kind=WAREHOUSE; 一一对应; 空=历史未绑定)',"
                    + "location VARCHAR(200) DEFAULT NULL COMMENT '库房位置',"
                    + "manager VARCHAR(50) DEFAULT NULL COMMENT '负责人',"
                    + "status TINYINT DEFAULT 1 COMMENT '状态:1启用 0停用',"
                    + "sort_no INT DEFAULT 0 COMMENT '排序号',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_org_code (tenant_id, org_id, code),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_org (tenant_id, org_id),"
                    + "KEY idx_kind (tenant_id, org_id, kind)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='药库定义(机构级多药库)'");
        }
    }

    /** 幂等建表: 药房定义表(机构级多药房, 发药记录按药房归属; UNIQUE tenant_id+org_id+code)。 */
    private void ensurePharmacyDefTable(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_pharmacy_def ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "code VARCHAR(40) NOT NULL COMMENT '药房编码',"
                    + "name VARCHAR(100) NOT NULL COMMENT '药房名称(门诊药房/住院药房/中药房)',"
                    + "pharmacy_type VARCHAR(20) DEFAULT NULL COMMENT '药房类型:OUTPATIENT/INPATIENT/TCM',"
                    + "warehouse_id BIGINT DEFAULT NULL COMMENT '关联药库ID(his_warehouse_def.id), 请领来源药库',"
                    + "stock_location_id BIGINT DEFAULT NULL COMMENT '本药房库存位ID(PHARMACY型 his_warehouse_def.id), 发药/退药按此记账',"
                    + "dept_id BIGINT DEFAULT NULL COMMENT '归属科室(his_dept.id, 一一对应; 空=历史未绑定)',"
                    + "location VARCHAR(200) DEFAULT NULL COMMENT '药房位置',"
                    + "status TINYINT DEFAULT 1 COMMENT '状态:1启用 0停用',"
                    + "sort_no INT DEFAULT 0 COMMENT '排序号',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_org_code (tenant_id, org_id, code),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_org (tenant_id, org_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='药房定义(机构级多药房)'");
        }
    }

    /**
     * 幂等建表: 发药窗口子系统(P1) 5 张新表。
     * his_pharmacy_window   - 窗口定义(类型/分配策略/兜底/开关/签到/追溯), UNIQUE(tenant,pharmacy,code)
     * his_window_workstation- 发药工作站↔窗口关联(用户/职工绑定), UNIQUE(tenant,window,user)
     * his_window_dept_rule  - 开单科室→窗口定向映射
     * his_pharmacy_cross_config - 跨药房发药配置(源↔目标药房), UNIQUE(tenant,source,target)
     * his_window_signin     - 窗口签到轻表(避免污染 his_dispense 主表)
     */
    private void ensurePharmacyWindowTables(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_pharmacy_window ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "pharmacy_id BIGINT NOT NULL COMMENT '所属药房ID(his_pharmacy_def.id)',"
                    + "code VARCHAR(40) NOT NULL COMMENT '窗口编码',"
                    + "name VARCHAR(100) NOT NULL COMMENT '窗口名称',"
                    + "window_type VARCHAR(20) DEFAULT NULL COMMENT '窗口类型:WEST西药/CHINESE_PATENT中成药/HERB草药/NARCOTIC精麻/TOXIC毒性/DECOCT代煎/EXPRESS快递',"
                    + "assign_strategy TINYINT DEFAULT 1 COMMENT '分配策略:1剩余量最小 2平均轮询 3定向',"
                    + "is_default TINYINT DEFAULT 0 COMMENT '兜底默认窗口:1是 0否',"
                    + "open_status TINYINT DEFAULT 1 COMMENT '开窗状态:1开 0关',"
                    + "signin_required TINYINT DEFAULT 0 COMMENT '发药前需患者签到:1是 0否',"
                    + "trace_required TINYINT DEFAULT 0 COMMENT '窗口级追溯码强制:1是 0否',"
                    + "sort_no INT DEFAULT 0 COMMENT '排序号',"
                    + "status TINYINT DEFAULT 1 COMMENT '状态:1启用 0停用',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_pharmacy_code (tenant_id, pharmacy_id, code),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_pharmacy_open (tenant_id, pharmacy_id, open_status, status)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发药窗口定义'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_window_workstation ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "window_id BIGINT NOT NULL COMMENT '窗口ID(his_pharmacy_window.id)',"
                    + "user_id BIGINT DEFAULT NULL COMMENT '关联登录用户ID(sys_user.id)',"
                    + "staff_id BIGINT DEFAULT NULL COMMENT '关联职工ID(his_staff.id)',"
                    + "remark VARCHAR(200) DEFAULT NULL COMMENT '备注',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_window_user (tenant_id, window_id, user_id),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_window (tenant_id, window_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发药工作站↔窗口关联'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_window_dept_rule ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "dept_id BIGINT NOT NULL COMMENT '开单科室ID(his_dept.id)',"
                    + "window_id BIGINT NOT NULL COMMENT '定向窗口ID(his_pharmacy_window.id)',"
                    + "remark VARCHAR(200) DEFAULT NULL COMMENT '备注',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_dept_window (tenant_id, dept_id, window_id),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_dept (tenant_id, dept_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='开单科室→窗口定向规则'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_pharmacy_cross_config ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "source_pharmacy_id BIGINT NOT NULL COMMENT '源药房ID(his_pharmacy_def.id)',"
                    + "target_pharmacy_id BIGINT NOT NULL COMMENT '目标药房ID(his_pharmacy_def.id)',"
                    + "allow_cross_status TINYINT DEFAULT 0 COMMENT '允许跨状态发药:1是 0否',"
                    + "enabled TINYINT DEFAULT 1 COMMENT '启用:1是 0否',"
                    + "remark VARCHAR(200) DEFAULT NULL COMMENT '备注',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_source_target (tenant_id, source_pharmacy_id, target_pharmacy_id),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_source (tenant_id, source_pharmacy_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跨药房发药配置'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_window_signin ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "window_id BIGINT NOT NULL COMMENT '窗口ID(his_pharmacy_window.id)',"
                    + "pharmacy_id BIGINT DEFAULT NULL COMMENT '药房ID(his_pharmacy_def.id)',"
                    + "patient_id BIGINT DEFAULT NULL COMMENT '患者ID',"
                    + "visit_id BIGINT DEFAULT NULL COMMENT '就诊ID',"
                    + "prescription_id BIGINT DEFAULT NULL COMMENT '处方ID',"
                    + "signin_no VARCHAR(40) DEFAULT NULL COMMENT '签到凭证号(条码/刷卡/发票号)',"
                    + "signin_status TINYINT DEFAULT 1 COMMENT '签到状态:1已签到 0已取消',"
                    + "signin_by VARCHAR(50) DEFAULT NULL COMMENT '签到操作人',"
                    + "signin_time DATETIME DEFAULT NULL COMMENT '签到时间',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_window_patient (tenant_id, window_id, patient_id, signin_status)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发药窗口患者签到'");
        }
    }

    /** 幂等建表: 发票号池表(机构级发票号段, 收费时按池顺序取号; UNIQUE tenant_id+org_id+pool_code)。 */
    private void ensureInvoicePoolTable(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_invoice_pool ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "pool_code VARCHAR(40) NOT NULL COMMENT '号池编码',"
                    + "invoice_type VARCHAR(20) NOT NULL COMMENT '发票类型:NORMAL纸质/ELECTRONIC电子',"
                    + "prefix VARCHAR(20) DEFAULT NULL COMMENT '发票号前缀',"
                    + "start_no BIGINT NOT NULL COMMENT '起始号',"
                    + "end_no BIGINT NOT NULL COMMENT '结束号',"
                    + "current_no BIGINT NOT NULL DEFAULT 0 COMMENT '当前已用号(下一个待取号=currentNo+1)',"
                    + "status TINYINT DEFAULT 0 COMMENT '状态:0未启用 1使用中 2已用完',"
                    + "alloc_by VARCHAR(50) DEFAULT NULL COMMENT '分配人',"
                    + "alloc_time DATETIME DEFAULT NULL COMMENT '分配时间',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_org_pool (tenant_id, org_id, pool_code),"
                    + "KEY idx_tenant (tenant_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发票号池(机构级发票号段)'");
        }
    }

    /** 幂等建表: 发票表(开票记录, 作废/红冲指向原发票不物理删除; UNIQUE tenant_id+org_id+invoice_no)。 */
    private void ensureInvoiceTable(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_invoice ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "invoice_no VARCHAR(50) NOT NULL COMMENT '发票号(前缀+号池序号)',"
                    + "pool_id BIGINT DEFAULT NULL COMMENT '发票号池ID(his_invoice_pool.id)',"
                    + "bill_id BIGINT DEFAULT NULL COMMENT '关联收费单ID(his_charge_bill.id)',"
                    + "invoice_type VARCHAR(20) DEFAULT 'NORMAL' COMMENT '发票类型:NORMAL/VOID/RED',"
                    + "amount DECIMAL(12,2) DEFAULT 0 COMMENT '开票金额',"
                    + "patient_name VARCHAR(50) DEFAULT NULL COMMENT '患者姓名',"
                    + "status TINYINT DEFAULT 1 COMMENT '状态:1正常 2已作废 3已红冲',"
                    + "void_reason VARCHAR(500) DEFAULT NULL COMMENT '作废/红冲原因',"
                    + "void_by VARCHAR(50) DEFAULT NULL COMMENT '作废/红冲操作人',"
                    + "void_time DATETIME DEFAULT NULL COMMENT '作废/红冲时间',"
                    + "original_invoice_id BIGINT DEFAULT NULL COMMENT '原发票ID(作废/红冲时指向原发票)',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_org_invoice_no (tenant_id, org_id, invoice_no),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_bill (bill_id),"
                    + "KEY idx_pool (pool_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发票(开票记录)'");
        }
    }

    /** 幂等建表: 支付明细表(收费单混合支付逐笔记录; 无 org_id, 机构归属随收费单)。 */
    private void ensurePaymentDetailTable(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_payment_detail ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "bill_id BIGINT NOT NULL COMMENT '收费单ID(his_charge_bill.id)',"
                    + "pay_method VARCHAR(20) NOT NULL COMMENT '支付方式:CASH/WECHAT/ALIPAY/CARD/INSURANCE/FREE',"
                    + "amount DECIMAL(12,2) NOT NULL COMMENT '支付金额',"
                    + "pay_ref VARCHAR(100) DEFAULT NULL COMMENT '支付流水号',"
                    + "pay_time DATETIME DEFAULT NULL COMMENT '支付时间',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_bill (bill_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付明细(收费单混合支付)'");
        }
    }

    /** 幂等建表: 盘点两表(盘点单主表 + 逐批次明细, 确认后按差异生成盘盈入库/盘亏出库)。 */
    private void ensureStockCheckTables(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_stock_check ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "warehouse_id BIGINT DEFAULT NULL COMMENT '药库ID(his_warehouse_def.id)',"
                    + "check_no VARCHAR(30) NOT NULL COMMENT '盘点单号(PD+yyyyMMdd+4位)',"
                    + "check_date DATE DEFAULT NULL COMMENT '盘点日期',"
                    + "status TINYINT DEFAULT 0 COMMENT '状态:0进行中 1已完成 2已作废',"
                    + "check_by VARCHAR(50) DEFAULT NULL COMMENT '盘点人',"
                    + "confirm_by VARCHAR(50) DEFAULT NULL COMMENT '确认人',"
                    + "confirm_time DATETIME DEFAULT NULL COMMENT '确认时间',"
                    + "profit_amount DECIMAL(12,2) DEFAULT 0 COMMENT '盘盈金额合计',"
                    + "loss_amount DECIMAL(12,2) DEFAULT 0 COMMENT '盘亏金额合计',"
                    + "remark VARCHAR(500) DEFAULT NULL COMMENT '备注',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_check_no (tenant_id, check_no),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_org_status (tenant_id, org_id, status)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='盘点单'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_stock_check_item ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "stock_check_id BIGINT NOT NULL COMMENT '盘点单ID',"
                    + "drug_stock_id BIGINT DEFAULT NULL COMMENT '库存批次ID(his_drug_stock.id)',"
                    + "drug_catalog_id BIGINT DEFAULT NULL COMMENT '药品目录ID',"
                    + "drug_name VARCHAR(200) DEFAULT NULL COMMENT '药品名称(快照)',"
                    + "spec VARCHAR(200) DEFAULT NULL COMMENT '规格(快照)',"
                    + "batch_no VARCHAR(50) DEFAULT NULL COMMENT '批次号(快照)',"
                    + "system_qty DECIMAL(12,2) DEFAULT 0 COMMENT '系统账面数量',"
                    + "actual_qty DECIMAL(12,2) DEFAULT NULL COMMENT '实盘数量',"
                    + "diff_qty DECIMAL(12,2) DEFAULT NULL COMMENT '差异数量(实盘-账面; 正=盘盈 负=盘亏)',"
                    + "cost_price DECIMAL(12,4) DEFAULT NULL COMMENT '进价(快照)',"
                    + "remark VARCHAR(500) DEFAULT NULL COMMENT '备注',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_stock_check (stock_check_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='盘点明细'");
        }
    }

    /**
     * 幂等建表: 二级库存专业化 P1-P5 新表 —— 药品请领(his_requisition/_item)、库存调拨(his_transfer/_item)、
     * 药品调价单(his_drug_price_adjust/_item, 区别于医共体目录留痕 his_price_adjust)、医保药品追溯码(his_drug_trace_code)。均含 tenant_id(走租户插件)、逻辑删除。
     */
    private void ensureStockChainTables(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_requisition ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "req_no VARCHAR(30) NOT NULL COMMENT '请领单号(QL+yyyyMMdd+4位)',"
                    + "pharmacy_id BIGINT DEFAULT NULL COMMENT '请领药房ID(his_pharmacy_def.id)',"
                    + "to_warehouse_id BIGINT DEFAULT NULL COMMENT '发货来源药库ID(his_warehouse_def.id)',"
                    + "status TINYINT DEFAULT 0 COMMENT '状态:0草稿 1待审核 2已发货 3已收货 -1已驳回 -2已作废',"
                    + "apply_by VARCHAR(50) DEFAULT NULL COMMENT '申请人',"
                    + "apply_time DATETIME DEFAULT NULL COMMENT '申请时间',"
                    + "approve_by VARCHAR(50) DEFAULT NULL COMMENT '审核人',"
                    + "approve_time DATETIME DEFAULT NULL COMMENT '审核时间',"
                    + "receive_by VARCHAR(50) DEFAULT NULL COMMENT '收货人',"
                    + "receive_time DATETIME DEFAULT NULL COMMENT '收货时间',"
                    + "stock_out_id BIGINT DEFAULT NULL COMMENT '发货出库单ID(his_stock_out.id)',"
                    + "stock_in_id BIGINT DEFAULT NULL COMMENT '收货入库单ID(his_stock_in.id)',"
                    + "total_amount DECIMAL(12,2) DEFAULT 0 COMMENT '请领金额合计',"
                    + "remark VARCHAR(500) DEFAULT NULL COMMENT '备注',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_req_no (tenant_id, req_no),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_org_status (tenant_id, org_id, status)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='药品请领单(药房→药库)'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_requisition_item ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "requisition_id BIGINT NOT NULL COMMENT '请领单ID',"
                    + "drug_catalog_id BIGINT DEFAULT NULL COMMENT '药品目录ID',"
                    + "drug_code VARCHAR(50) DEFAULT NULL COMMENT '药品编码(快照)',"
                    + "drug_name VARCHAR(200) DEFAULT NULL COMMENT '药品名称(快照)',"
                    + "spec VARCHAR(200) DEFAULT NULL COMMENT '规格(快照)',"
                    + "batch_no VARCHAR(50) DEFAULT NULL COMMENT '批次号(发货回填, 空=请领时不指定)',"
                    + "qty_apply DECIMAL(12,2) DEFAULT 0 COMMENT '请领数量',"
                    + "qty_approved DECIMAL(12,2) DEFAULT NULL COMMENT '审核(发货)数量',"
                    + "qty_received DECIMAL(12,2) DEFAULT NULL COMMENT '实收数量',"
                    + "retail_price DECIMAL(12,4) DEFAULT NULL COMMENT '零售价(快照)',"
                    + "amount DECIMAL(12,2) DEFAULT NULL COMMENT '小计金额',"
                    + "remark VARCHAR(500) DEFAULT NULL COMMENT '备注',"
                    + "create_time DATETIME DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_requisition (requisition_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='药品请领明细'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_transfer ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "transfer_no VARCHAR(30) NOT NULL COMMENT '调拨单号(DB+yyyyMMdd+4位)',"
                    + "from_location_id BIGINT DEFAULT NULL COMMENT '调出库位ID(药库或药房库存位)',"
                    + "to_location_id BIGINT DEFAULT NULL COMMENT '调入库位ID',"
                    + "kind VARCHAR(24) DEFAULT NULL COMMENT '调拨类型:WH2PHARMACY/PHARMACY2PHARMACY/WH2WH',"
                    + "status TINYINT DEFAULT 0 COMMENT '状态:0草稿 1待调出确认 2已调出 3已调入 -2已作废',"
                    + "ship_by VARCHAR(50) DEFAULT NULL COMMENT '调出人',"
                    + "ship_time DATETIME DEFAULT NULL COMMENT '调出时间',"
                    + "receive_by VARCHAR(50) DEFAULT NULL COMMENT '调入人',"
                    + "receive_time DATETIME DEFAULT NULL COMMENT '调入时间',"
                    + "stock_out_id BIGINT DEFAULT NULL COMMENT '调出出库单ID',"
                    + "stock_in_id BIGINT DEFAULT NULL COMMENT '调入入库单ID',"
                    + "total_amount DECIMAL(12,2) DEFAULT 0 COMMENT '调拨金额合计',"
                    + "remark VARCHAR(500) DEFAULT NULL COMMENT '备注',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_transfer_no (tenant_id, transfer_no),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_org_status (tenant_id, org_id, status)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存调拨单'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_transfer_item ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "transfer_id BIGINT NOT NULL COMMENT '调拨单ID',"
                    + "drug_catalog_id BIGINT DEFAULT NULL COMMENT '药品目录ID',"
                    + "drug_code VARCHAR(50) DEFAULT NULL COMMENT '药品编码(快照)',"
                    + "drug_name VARCHAR(200) DEFAULT NULL COMMENT '药品名称(快照)',"
                    + "spec VARCHAR(200) DEFAULT NULL COMMENT '规格(快照)',"
                    + "batch_no VARCHAR(50) DEFAULT NULL COMMENT '批次号(随行调拨)',"
                    + "qty DECIMAL(12,2) DEFAULT 0 COMMENT '调拨数量',"
                    + "cost_price DECIMAL(12,4) DEFAULT NULL COMMENT '进价(快照)',"
                    + "retail_price DECIMAL(12,4) DEFAULT NULL COMMENT '零售价(快照)',"
                    + "amount DECIMAL(12,2) DEFAULT NULL COMMENT '小计金额',"
                    + "create_time DATETIME DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_transfer (transfer_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存调拨明细'");
            /* 药品调价单(药库/药房统一, 草稿→生效批次单): 与医共体目录逐字段留痕 his_price_adjust 不同, 故独立命名避免冲突 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_drug_price_adjust ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT DEFAULT NULL COMMENT '机构ID(空=全医共体调价)',"
                    + "adjust_no VARCHAR(30) NOT NULL COMMENT '调价单号(TJ+yyyyMMdd+4位)',"
                    + "scope VARCHAR(20) DEFAULT 'DRUG' COMMENT '范围:ALL/DRUG',"
                    + "effective_date DATE DEFAULT NULL COMMENT '生效日期',"
                    + "status TINYINT DEFAULT 0 COMMENT '状态:0草稿 1已生效 2已作废',"
                    + "reason VARCHAR(500) DEFAULT NULL COMMENT '调价原因',"
                    + "operator VARCHAR(50) DEFAULT NULL COMMENT '操作人',"
                    + "effect_time DATETIME DEFAULT NULL COMMENT '生效时间',"
                    + "total_diff_amount DECIMAL(14,2) DEFAULT 0 COMMENT '在库金额影响合计',"
                    + "remark VARCHAR(500) DEFAULT NULL COMMENT '备注',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_adjust_no (tenant_id, adjust_no),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_org_status (tenant_id, org_id, status)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='药品调价单(草稿→生效批次)'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_drug_price_adjust_item ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "price_adjust_id BIGINT NOT NULL COMMENT '调价单ID',"
                    + "drug_catalog_id BIGINT DEFAULT NULL COMMENT '药品目录ID',"
                    + "drug_code VARCHAR(50) DEFAULT NULL COMMENT '药品编码(快照)',"
                    + "drug_name VARCHAR(200) DEFAULT NULL COMMENT '药品名称(快照)',"
                    + "spec VARCHAR(200) DEFAULT NULL COMMENT '规格(快照)',"
                    + "old_purchase DECIMAL(12,4) DEFAULT NULL COMMENT '原进价',"
                    + "new_purchase DECIMAL(12,4) DEFAULT NULL COMMENT '新进价',"
                    + "old_retail DECIMAL(12,4) DEFAULT NULL COMMENT '原零售价',"
                    + "new_retail DECIMAL(12,4) DEFAULT NULL COMMENT '新零售价',"
                    + "impact_stock_qty DECIMAL(14,2) DEFAULT 0 COMMENT '当前在库数量(预览影响)',"
                    + "create_time DATETIME DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_adjust (price_adjust_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调价明细'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_drug_trace_code ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT DEFAULT NULL COMMENT '机构ID',"
                    + "location_id BIGINT DEFAULT NULL COMMENT '所属库位ID(药库/药房库存位)',"
                    + "drug_catalog_id BIGINT DEFAULT NULL COMMENT '药品目录ID',"
                    + "drug_code VARCHAR(50) DEFAULT NULL COMMENT '药品编码(快照)',"
                    + "batch_no VARCHAR(50) DEFAULT NULL COMMENT '批次号(快照)',"
                    + "trace_code VARCHAR(64) NOT NULL COMMENT '医保药品追溯码',"
                    + "status TINYINT DEFAULT 0 COMMENT '状态:0在库 1已发药 2已退货 3已报废/调拨在途 9已上报',"
                    + "min_pack_qty DECIMAL(12,2) DEFAULT 1 COMMENT '最小包装数量',"
                    + "ref_bill_type VARCHAR(20) DEFAULT NULL COMMENT '关联单据类型(in/dispense/return/transfer)',"
                    + "ref_bill_id BIGINT DEFAULT NULL COMMENT '关联单据ID',"
                    + "patient_id BIGINT DEFAULT NULL COMMENT '发药绑定患者ID',"
                    + "visit_id BIGINT DEFAULT NULL COMMENT '发药绑定就诊ID',"
                    + "dispense_id BIGINT DEFAULT NULL COMMENT '发药记录ID',"
                    + "upload_status TINYINT DEFAULT 0 COMMENT '报送状态:0未报送 1报送中 2失败待补 9已报送(批次5 M1归一)',"
                    + "upload_time DATETIME DEFAULT NULL COMMENT '报送时间',"
                    + "upload_receipt VARCHAR(200) DEFAULT NULL COMMENT '报送回执(批次号)',"
                    + "upload_msgid VARCHAR(40) DEFAULT NULL COMMENT '2404报送发送方报文ID(UNKNOWN复核/重发凭据, 批次5 M1)',"
                    + "upload_batch_no VARCHAR(40) DEFAULT NULL COMMENT '2404报送批次/平台回执报文ID(批次5 M1)',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_trace (tenant_id, trace_code),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_loc_drug (tenant_id, location_id, drug_catalog_id),"
                    + "KEY idx_status (tenant_id, status)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='医保药品追溯码'");
        }
    }

    /**
     * 幂等建表(药库管理系统升级批次A): 供应商主数据 + 智能采购规则 + 采购计划(主/明细) + 采购订单(主/明细)。
     * 采购闭环: 规则(高低储/参考入出/发药量) 计划(智能生成/手工筛选) 审批 订单 引入入库; 集采上传为占位字段。
     */
    private void ensureWarehousePurchaseTables(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_supplier ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "supplier_code VARCHAR(50) NOT NULL COMMENT '供应商编码(租户内唯一)',"
                    + "supplier_name VARCHAR(200) NOT NULL COMMENT '供应商名称',"
                    + "contact VARCHAR(50) DEFAULT NULL COMMENT '联系人',"
                    + "phone VARCHAR(50) DEFAULT NULL COMMENT '联系电话',"
                    + "address VARCHAR(200) DEFAULT NULL COMMENT '地址',"
                    + "settle_cycle INT DEFAULT NULL COMMENT '结算周期(天)',"
                    + "jt_flag TINYINT DEFAULT 0 COMMENT '集采供应商标识:1是 0否',"
                    + "status TINYINT DEFAULT 1 COMMENT '状态:1启用 0停用',"
                    + "remark VARCHAR(500) DEFAULT NULL COMMENT '备注',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_supplier_code (tenant_id, supplier_code),"
                    + "KEY idx_tenant (tenant_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商主数据'");
            addColumnIfNotExists(conn, "his_supplier", "std_sup_code", "VARCHAR(40) DEFAULT NULL COMMENT '引用全局企业字典std_supplier.sup_code(可空,从字典导入时回填)'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_purchase_rule ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT DEFAULT NULL COMMENT '机构ID',"
                    + "warehouse_id BIGINT DEFAULT NULL COMMENT '适用药库ID(空=全院默认)',"
                    + "rule_name VARCHAR(100) DEFAULT NULL COMMENT '规则名称',"
                    + "refer_months INT DEFAULT 1 COMMENT '参考月数(入出库/发药统计窗口)',"
                    + "lo_qty DECIMAL(12,2) DEFAULT NULL COMMENT '低储标准(补货点)',"
                    + "hi_qty DECIMAL(12,2) DEFAULT NULL COMMENT '高储标准',"
                    + "dispense_weight DECIMAL(4,2) DEFAULT 1.00 COMMENT '发药量权重',"
                    + "stockout_weight DECIMAL(4,2) DEFAULT 1.00 COMMENT '出库量权重',"
                    + "abc_a_ratio DECIMAL(4,2) DEFAULT 0.80 COMMENT 'ABC分类A累计占比',"
                    + "abc_b_ratio DECIMAL(4,2) DEFAULT 0.95 COMMENT 'ABC分类B累计占比',"
                    + "enabled TINYINT DEFAULT 1 COMMENT '启用:1是 0否',"
                    + "remark VARCHAR(500) DEFAULT NULL COMMENT '备注',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_org_wh (tenant_id, org_id, warehouse_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能采购规则配置'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_purchase_plan ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "plan_no VARCHAR(30) NOT NULL COMMENT '计划单号(CH+yyyyMMdd+4位)',"
                    + "warehouse_id BIGINT DEFAULT NULL COMMENT '目标药库ID',"
                    + "supplier_id BIGINT DEFAULT NULL COMMENT '供应商ID(空=多供应商, 转订单时指定)',"
                    + "gen_type VARCHAR(10) DEFAULT 'manual' COMMENT '生成方式:auto智能/manual手工',"
                    + "status TINYINT DEFAULT 0 COMMENT '状态:0草稿 1待审 2已审 3已驳回 9已转订单 -2作废',"
                    + "total_amount DECIMAL(14,2) DEFAULT 0 COMMENT '计划金额合计',"
                    + "submit_by VARCHAR(50) DEFAULT NULL COMMENT '提交人',"
                    + "submit_time DATETIME DEFAULT NULL COMMENT '提交时间',"
                    + "approve_by VARCHAR(50) DEFAULT NULL COMMENT '审批人',"
                    + "approve_time DATETIME DEFAULT NULL COMMENT '审批时间',"
                    + "remark VARCHAR(500) DEFAULT NULL COMMENT '备注',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_plan_no (tenant_id, plan_no),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_org_status (tenant_id, org_id, status)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='药品采购计划主表'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_purchase_plan_item ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "plan_id BIGINT NOT NULL COMMENT '计划单ID',"
                    + "drug_catalog_id BIGINT DEFAULT NULL COMMENT '药品目录ID',"
                    + "drug_code VARCHAR(50) DEFAULT NULL COMMENT '药品编码(快照)',"
                    + "drug_name VARCHAR(200) DEFAULT NULL COMMENT '药品名称(快照)',"
                    + "spec VARCHAR(200) DEFAULT NULL COMMENT '规格(快照)',"
                    + "manufacturer VARCHAR(200) DEFAULT NULL COMMENT '生产企业(快照)',"
                    + "major_class VARCHAR(50) DEFAULT NULL COMMENT '药品大类(快照, 筛选/分类)',"
                    + "cur_stock DECIMAL(12,2) DEFAULT 0 COMMENT '当前库存(快照)',"
                    + "lo_qty DECIMAL(12,2) DEFAULT NULL COMMENT '低储标准(快照)',"
                    + "hi_qty DECIMAL(12,2) DEFAULT NULL COMMENT '高储标准(快照)',"
                    + "last_month_in DECIMAL(12,2) DEFAULT 0 COMMENT '上月入库量(快照)',"
                    + "last_month_out DECIMAL(12,2) DEFAULT 0 COMMENT '上月出库量(快照)',"
                    + "dispense_qty DECIMAL(12,2) DEFAULT 0 COMMENT '药房发药量(快照)',"
                    + "abc_class VARCHAR(2) DEFAULT NULL COMMENT 'ABC类别 A/B/C',"
                    + "qty_suggest DECIMAL(12,2) DEFAULT 0 COMMENT '建议采购数量',"
                    + "price DECIMAL(12,4) DEFAULT NULL COMMENT '预估进价',"
                    + "amount DECIMAL(14,2) DEFAULT NULL COMMENT '预估金额',"
                    + "create_time DATETIME DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_plan (plan_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='药品采购计划明细'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_purchase_order ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "order_no VARCHAR(30) NOT NULL COMMENT '订单号(CO+yyyyMMdd+4位)',"
                    + "plan_id BIGINT DEFAULT NULL COMMENT '来源计划单ID',"
                    + "warehouse_id BIGINT DEFAULT NULL COMMENT '收货药库ID',"
                    + "supplier_id BIGINT DEFAULT NULL COMMENT '供应商ID',"
                    + "status TINYINT DEFAULT 0 COMMENT '状态:0草稿 1已下单 2部分到货 3已完成 -2作废',"
                    + "total_amount DECIMAL(14,2) DEFAULT 0 COMMENT '订单金额合计',"
                    + "upload_status TINYINT DEFAULT 0 COMMENT '集采/统采上传:0未上传 9已上传(Mock)',"
                    + "upload_time DATETIME DEFAULT NULL COMMENT '上传时间',"
                    + "upload_receipt VARCHAR(200) DEFAULT NULL COMMENT '上传回执(Mock)',"
                    + "remark VARCHAR(500) DEFAULT NULL COMMENT '备注',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_order_no (tenant_id, order_no),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_org_status (tenant_id, org_id, status),"
                    + "KEY idx_supplier (tenant_id, supplier_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='药品采购订单主表'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_purchase_order_item ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "order_id BIGINT NOT NULL COMMENT '订单ID',"
                    + "plan_item_id BIGINT DEFAULT NULL COMMENT '来源计划明细ID',"
                    + "drug_catalog_id BIGINT DEFAULT NULL COMMENT '药品目录ID',"
                    + "drug_code VARCHAR(50) DEFAULT NULL COMMENT '药品编码(快照)',"
                    + "drug_name VARCHAR(200) DEFAULT NULL COMMENT '药品名称(快照)',"
                    + "spec VARCHAR(200) DEFAULT NULL COMMENT '规格(快照)',"
                    + "manufacturer VARCHAR(200) DEFAULT NULL COMMENT '生产企业(快照)',"
                    + "qty DECIMAL(12,2) DEFAULT 0 COMMENT '订购数量',"
                    + "qty_received DECIMAL(12,2) DEFAULT 0 COMMENT '已到货入库数量',"
                    + "price DECIMAL(12,4) DEFAULT NULL COMMENT '进价',"
                    + "amount DECIMAL(14,2) DEFAULT NULL COMMENT '金额',"
                    + "create_time DATETIME DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_order (order_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='药品采购订单明细'");
        }
    }

    /**
     * 幂等建表(药库管理系统升级批次B): 财务验收单 his_stock_accept(+明细) + 平账记录 his_stock_balance;
     * 并为存量 his_stock_in / his_stock_in_item 补采购入库增强列(addColumnIfNotExists 幂等, 列已存在则跳过)。
     * 历史已确认入库单默认 accept_status=1(已验收)/purchase_mode=1(正常), 避免存量出库误判为未验收而产生平账。
     */
    private void ensureWarehouseAcceptTables(Connection conn) throws Exception {
        // his_stock_in 采购入库增强补列
        addColumnIfNotExists(conn, "his_stock_in", "purchase_mode", "TINYINT DEFAULT 1 COMMENT '购入方式:1正常 2挂账 3票未到(仅单据)'");
        addColumnIfNotExists(conn, "his_stock_in", "purchase_order_id", "BIGINT DEFAULT NULL COMMENT '来源采购订单ID(his_purchase_order.id)'");
        addColumnIfNotExists(conn, "his_stock_in", "invoice_no", "VARCHAR(50) DEFAULT NULL COMMENT '发票号'");
        addColumnIfNotExists(conn, "his_stock_in", "invoice_date", "DATE DEFAULT NULL COMMENT '发票日期'");
        addColumnIfNotExists(conn, "his_stock_in", "target_warehouse_id", "BIGINT DEFAULT NULL COMMENT '定向出库目标库ID(确认入库后自动调拨至该库)'");
        addColumnIfNotExists(conn, "his_stock_in", "accept_status", "TINYINT DEFAULT 1 COMMENT '财务验收:0未验收 1已验收'");
        addColumnIfNotExists(conn, "his_stock_in", "reversed_flag", "TINYINT DEFAULT 0 COMMENT '是否已冲红:1是 0否'");
        addColumnIfNotExists(conn, "his_stock_in", "red_of_id", "BIGINT DEFAULT NULL COMMENT '红字冲账单指向的原入库单ID'");
        addColumnIfNotExists(conn, "his_stock_in", "supplier_id", "BIGINT DEFAULT NULL COMMENT '供应商ID(his_supplier.id, 新单与 supplier 文本双写)'");
        // his_stock_in_item 多单位/挂账进价补列
        addColumnIfNotExists(conn, "his_stock_in_item", "pack_qty", "DECIMAL(12,2) DEFAULT NULL COMMENT '大包装数(多单位录入)'");
        addColumnIfNotExists(conn, "his_stock_in_item", "pack_ratio", "INT DEFAULT NULL COMMENT '包装换算比快照(大包装→最小单位)'");
        addColumnIfNotExists(conn, "his_stock_in_item", "min_qty", "DECIMAL(12,2) DEFAULT NULL COMMENT '最小单位量=大包装数*包装比'");
        addColumnIfNotExists(conn, "his_stock_in_item", "purchase_price", "DECIMAL(12,4) DEFAULT NULL COMMENT '挂账进价(待核)'");
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_stock_accept ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "accept_no VARCHAR(30) NOT NULL COMMENT '验收单号(YS+yyyyMMdd+4位)',"
                    + "warehouse_id BIGINT DEFAULT NULL COMMENT '药库ID',"
                    + "supplier_id BIGINT DEFAULT NULL COMMENT '供应商ID(集中验收按供应商归集)',"
                    + "accept_type TINYINT DEFAULT 1 COMMENT '验收方式:1单张入库 2按供应商集中',"
                    + "stock_in_id BIGINT DEFAULT NULL COMMENT '单张验收指向的入库单ID(集中验收为空)',"
                    + "bill_count INT DEFAULT 0 COMMENT '验收覆盖入库单张数',"
                    + "total_amount DECIMAL(14,2) DEFAULT 0 COMMENT '验收金额合计',"
                    + "conclusion TINYINT DEFAULT 1 COMMENT '验收结论:1合格 2异常',"
                    + "accept_by VARCHAR(50) DEFAULT NULL COMMENT '验收人',"
                    + "accept_time DATETIME DEFAULT NULL COMMENT '验收时间',"
                    + "status TINYINT DEFAULT 0 COMMENT '状态:0草稿 1已验收',"
                    + "remark VARCHAR(500) DEFAULT NULL COMMENT '备注',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_accept_no (tenant_id, accept_no),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_org_status (tenant_id, org_id, status),"
                    + "KEY idx_supplier (tenant_id, supplier_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='财务验收单主表'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_stock_accept_item ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "accept_id BIGINT NOT NULL COMMENT '验收单ID',"
                    + "stock_in_id BIGINT DEFAULT NULL COMMENT '来源入库单ID',"
                    + "drug_catalog_id BIGINT DEFAULT NULL COMMENT '药品目录ID',"
                    + "drug_code VARCHAR(50) DEFAULT NULL COMMENT '药品编码(快照)',"
                    + "drug_name VARCHAR(200) DEFAULT NULL COMMENT '药品名称(快照)',"
                    + "spec VARCHAR(200) DEFAULT NULL COMMENT '规格(快照)',"
                    + "batch_no VARCHAR(50) DEFAULT NULL COMMENT '批号(快照)',"
                    + "manufacturer VARCHAR(200) DEFAULT NULL COMMENT '生产企业(快照)',"
                    + "qty DECIMAL(12,2) DEFAULT 0 COMMENT '验收数量',"
                    + "cost_price DECIMAL(12,4) DEFAULT NULL COMMENT '进价',"
                    + "amount DECIMAL(14,2) DEFAULT NULL COMMENT '金额',"
                    + "create_time DATETIME DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_accept (accept_id),"
                    + "KEY idx_stock_in (stock_in_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='财务验收单明细'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_stock_balance ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "warehouse_id BIGINT DEFAULT NULL COMMENT '药库ID',"
                    + "stock_out_id BIGINT DEFAULT NULL COMMENT '触发出库单ID',"
                    + "stock_out_item_id BIGINT DEFAULT NULL COMMENT '触发出库明细ID',"
                    + "stock_in_id BIGINT DEFAULT NULL COMMENT '来源入库单ID(未验收)',"
                    + "stock_in_item_id BIGINT DEFAULT NULL COMMENT '来源入库明细ID',"
                    + "drug_catalog_id BIGINT DEFAULT NULL COMMENT '药品目录ID',"
                    + "drug_name VARCHAR(200) DEFAULT NULL COMMENT '药品名称(快照)',"
                    + "batch_no VARCHAR(50) DEFAULT NULL COMMENT '批号(快照)',"
                    + "orig_in_price DECIMAL(12,4) DEFAULT NULL COMMENT '原挂账进价',"
                    + "actual_in_price DECIMAL(12,4) DEFAULT NULL COMMENT '实际出库结转进价',"
                    + "qty DECIMAL(12,2) DEFAULT 0 COMMENT '冲抵数量',"
                    + "diff_amount DECIMAL(14,2) DEFAULT 0 COMMENT '进价差=（实际-原）*数量',"
                    + "balance_date DATE DEFAULT NULL COMMENT '平账日期',"
                    + "remark VARCHAR(500) DEFAULT NULL COMMENT '备注',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_org_date (tenant_id, org_id, balance_date),"
                    + "KEY idx_out_item (stock_out_item_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='未验收药品出库平账记录'");
        }
    }

    /**
     * 药库管理系统升级批次C: 供应商付款与应付。
     * his_stock_in 补已付回写列; 新建 his_supplier_payment(主) + his_supplier_payment_item(明细)。
     */
    private void ensureWarehousePaymentTables(Connection conn) throws Exception {
        addColumnIfNotExists(conn, "his_stock_in", "paid_amount", "DECIMAL(14,2) DEFAULT 0 COMMENT '已付金额(供应商付款回写)'");
        addColumnIfNotExists(conn, "his_stock_in", "paid_status", "TINYINT DEFAULT 0 COMMENT '付款状态:0未付 1部分 2已付'");
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_supplier_payment ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "pay_no VARCHAR(30) NOT NULL COMMENT '付款单号(FK+yyyyMMdd+4位)',"
                    + "supplier_id BIGINT DEFAULT NULL COMMENT '供应商ID(his_supplier.id)',"
                    + "pay_date DATE DEFAULT NULL COMMENT '付款日期',"
                    + "pay_method TINYINT DEFAULT 1 COMMENT '付款方式:1全额 2输入总额 3部分分摊',"
                    + "amount DECIMAL(14,2) DEFAULT 0 COMMENT '本次付款总额',"
                    + "pay_channel VARCHAR(50) DEFAULT NULL COMMENT '支付渠道/方式备注',"
                    + "status TINYINT DEFAULT 0 COMMENT '状态:0草稿 1已确认',"
                    + "confirm_by VARCHAR(50) DEFAULT NULL COMMENT '确认人',"
                    + "confirm_time DATETIME DEFAULT NULL COMMENT '确认时间',"
                    + "remark VARCHAR(500) DEFAULT NULL COMMENT '备注',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_pay_no (tenant_id, pay_no),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_org_status (tenant_id, org_id, status),"
                    + "KEY idx_supplier (tenant_id, supplier_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商付款单主表'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_supplier_payment_item ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "payment_id BIGINT NOT NULL COMMENT '付款单ID',"
                    + "stock_in_id BIGINT DEFAULT NULL COMMENT '被结算入库单ID',"
                    + "stock_in_no VARCHAR(30) DEFAULT NULL COMMENT '入库单号(快照)',"
                    + "in_amount DECIMAL(14,2) DEFAULT 0 COMMENT '入库应付额(快照)',"
                    + "paid_before DECIMAL(14,2) DEFAULT 0 COMMENT '结算前已付额(快照)',"
                    + "paid_amount DECIMAL(14,2) DEFAULT 0 COMMENT '本次分摊付款额',"
                    + "create_time DATETIME DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_payment (payment_id),"
                    + "KEY idx_stock_in (stock_in_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商付款单明细(关联被结算入库单与分摊额)'");
        }
    }
    
    /**
     * 幂等建表(批次D): 药品养护单 his_drug_maintenance(主) + his_drug_maintenance_item(明细)
     * + 养护模板 his_maintenance_template + 库房月结 his_stock_month_end。全部 CREATE TABLE IF NOT EXISTS。
     */
    private void ensureWarehouseOpsTables(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_drug_maintenance ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "warehouse_id BIGINT DEFAULT NULL COMMENT '药库ID(his_warehouse_def.id)',"
                    + "mnt_no VARCHAR(30) NOT NULL COMMENT '养护单号(YH+yyyyMMdd+4位)',"
                    + "mnt_date DATE DEFAULT NULL COMMENT '养护日期',"
                    + "mnt_type TINYINT DEFAULT 1 COMMENT '建单方式:1手动 2自动 3模板',"
                    + "drug_count INT DEFAULT 0 COMMENT '养护品种行数',"
                    + "abnormal_count INT DEFAULT 0 COMMENT '异常品行数',"
                    + "conclusion VARCHAR(200) DEFAULT NULL COMMENT '整体结论',"
                    + "mnt_by VARCHAR(50) DEFAULT NULL COMMENT '养护人',"
                    + "mnt_time DATETIME DEFAULT NULL COMMENT '养护完成时间',"
                    + "status TINYINT DEFAULT 0 COMMENT '状态:0草稿 1已完成',"
                    + "template_id BIGINT DEFAULT NULL COMMENT '来源模板ID(mnt_type=3)',"
                    + "remark VARCHAR(500) DEFAULT NULL COMMENT '备注',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_mnt_no (tenant_id, mnt_no),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_org_wh (tenant_id, org_id, warehouse_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='药品养护单主表'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_drug_maintenance_item ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "mnt_id BIGINT NOT NULL COMMENT '养护单ID',"
                    + "drug_catalog_id BIGINT DEFAULT NULL COMMENT '药品目录ID',"
                    + "drug_code VARCHAR(50) DEFAULT NULL COMMENT '药品编码',"
                    + "drug_name VARCHAR(200) DEFAULT NULL COMMENT '药品名称',"
                    + "spec VARCHAR(200) DEFAULT NULL COMMENT '规格',"
                    + "batch_no VARCHAR(50) DEFAULT NULL COMMENT '批次号',"
                    + "manufacturer VARCHAR(200) DEFAULT NULL COMMENT '生产厂家',"
                    + "dosform VARCHAR(50) DEFAULT NULL COMMENT '剂型',"
                    + "storage_cond VARCHAR(100) DEFAULT NULL COMMENT '储存条件',"
                    + "qty DECIMAL(14,2) DEFAULT 0 COMMENT '在库数量',"
                    + "exp_date DATE DEFAULT NULL COMMENT '有效期',"
                    + "measure VARCHAR(200) DEFAULT NULL COMMENT '养护措施',"
                    + "result TINYINT DEFAULT 1 COMMENT '养护结果:1合格 2异常',"
                    + "handler VARCHAR(50) DEFAULT NULL COMMENT '养护人',"
                    + "conclusion VARCHAR(200) DEFAULT NULL COMMENT '结论/异常描述',"
                    + "remark VARCHAR(500) DEFAULT NULL COMMENT '备注',"
                    + "create_time DATETIME DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_mnt (mnt_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='药品养护单明细'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_maintenance_template ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "template_name VARCHAR(100) NOT NULL COMMENT '模板名称',"
                    + "warehouse_id BIGINT DEFAULT NULL COMMENT '适用药库(空=全部)',"
                    + "dosform VARCHAR(50) DEFAULT NULL COMMENT '筛选:剂型',"
                    + "storage_cond VARCHAR(100) DEFAULT NULL COMMENT '筛选:储存条件',"
                    + "drug_keyword VARCHAR(100) DEFAULT NULL COMMENT '筛选:药品名称/编码关键字',"
                    + "default_measure VARCHAR(200) DEFAULT NULL COMMENT '默认养护措施',"
                    + "status TINYINT DEFAULT 1 COMMENT '状态:1启用 0停用',"
                    + "remark VARCHAR(500) DEFAULT NULL COMMENT '备注',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_tpl_name (tenant_id, org_id, template_name, deleted),"
                    + "KEY idx_tenant (tenant_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='药品养护模板(可复用筛选条件+默认养护措施)'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_stock_month_end ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "warehouse_id BIGINT DEFAULT NULL COMMENT '药库ID(空=全院口径)',"
                    + "period_start DATE NOT NULL COMMENT '本期起始日(上次月结终止+1)',"
                    + "period_end DATE NOT NULL COMMENT '本期终止日',"
                    + "acct_standard TINYINT DEFAULT 1 COMMENT '记账标准:1进价 3零售价(批发价2后补)',"
                    + "status TINYINT DEFAULT 0 COMMENT '状态:0进行中 1已月结 -1已取消',"
                    + "opening_amount DECIMAL(16,2) DEFAULT 0 COMMENT '期初金额(财务账)',"
                    + "closing_amount DECIMAL(16,2) DEFAULT 0 COMMENT '期末金额(财务账)',"
                    + "income_amount DECIMAL(16,2) DEFAULT 0 COMMENT '本期收入(入库)金额',"
                    + "expense_amount DECIMAL(16,2) DEFAULT 0 COMMENT '本期支出(出库)金额',"
                    + "opening_qty DECIMAL(16,2) DEFAULT 0 COMMENT '期初数量(实物账)',"
                    + "closing_qty DECIMAL(16,2) DEFAULT 0 COMMENT '期末数量(实物账)',"
                    + "in_qty DECIMAL(16,2) DEFAULT 0 COMMENT '本期入库数量',"
                    + "out_qty DECIMAL(16,2) DEFAULT 0 COMMENT '本期出库数量',"
                    + "drug_count INT DEFAULT 0 COMMENT '参与结账品种数',"
                    + "confirm_by VARCHAR(50) DEFAULT NULL COMMENT '月结人',"
                    + "confirm_time DATETIME DEFAULT NULL COMMENT '月结时间',"
                    + "remark VARCHAR(500) DEFAULT NULL COMMENT '备注',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_org_wh_period (tenant_id, org_id, warehouse_id, period_end)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库房月结记录(财务账+实物账双轨)'");
        }
    }

    /* ===================== 护士站/治疗管理/医技管理三模块基座建表 ===================== */

    /**
     * 幂等建表: 护士站四表 —— 医嘱执行记录(his_nurse_exec, 执行单号租户内唯一) +
     * 皮试(his_skin_test) + 输液(his_infusion_record) + 患者过敏(his_patient_allergy)。
     * exec_status 与 his_order.exec_status 联动; 皮试阳性可回写过敏登记。
     */
    private void ensureNurseTables(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            /* 医嘱执行记录: 注射/输液/皮试等护士执行统一台账, 状态流转待执行→执行中→已完成 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_nurse_exec ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "visit_id BIGINT NOT NULL COMMENT '就诊ID(his_visit.id)',"
                    + "order_id BIGINT NOT NULL COMMENT '医嘱单ID(his_order.id)',"
                    + "order_item_id BIGINT DEFAULT NULL COMMENT '医嘱明细ID(his_order_item.id)',"
                    + "patient_id BIGINT DEFAULT NULL COMMENT '患者ID',"
                    + "exec_type VARCHAR(20) DEFAULT NULL COMMENT '执行类型:injection注射/infusion输液/skin_test皮试/other其他',"
                    + "exec_no VARCHAR(30) NOT NULL COMMENT '执行单号(EX+日期+序号)',"
                    + "exec_status TINYINT DEFAULT 0 COMMENT '执行状态:0待执行 1执行中 2已完成 3已取消',"
                    + "exec_nurse_id BIGINT DEFAULT NULL COMMENT '执行护士ID(his_staff.id)',"
                    + "exec_time DATETIME DEFAULT NULL COMMENT '执行(开始)时间',"
                    + "verify_nurse_id BIGINT DEFAULT NULL COMMENT '核对护士ID(高危操作双人核对)',"
                    + "end_time DATETIME DEFAULT NULL COMMENT '结束时间',"
                    + "patient_response VARCHAR(500) DEFAULT NULL COMMENT '患者反应',"
                    + "remark VARCHAR(500) DEFAULT NULL COMMENT '备注',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_nexec_no (tenant_id, exec_no),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_org_status (tenant_id, org_id, exec_status, exec_time),"
                    + "KEY idx_order (order_id),"
                    + "KEY idx_visit (visit_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='护士执行记录(注射/输液/皮试统一台账)'");
            /* 皮试记录: 观察窗(默认20分钟)与结果, 阳性须通知医生并确认 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_skin_test ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "exec_id BIGINT NOT NULL COMMENT '执行记录ID(his_nurse_exec.id)',"
                    + "drug_name VARCHAR(100) NOT NULL COMMENT '皮试药品名称',"
                    + "drug_id BIGINT DEFAULT NULL COMMENT '药品目录ID(his_drug_catalog.id)',"
                    + "test_dose VARCHAR(50) DEFAULT NULL COMMENT '皮试剂量(如0.1mL)',"
                    + "observe_start DATETIME DEFAULT NULL COMMENT '观察开始时间',"
                    + "observe_end DATETIME DEFAULT NULL COMMENT '观察结束时间(开始+20分钟)',"
                    + "result TINYINT DEFAULT 0 COMMENT '皮试结果:0观察中 1阴性 2阳性 3未做',"
                    + "result_desc VARCHAR(500) DEFAULT NULL COMMENT '结果描述(局部反应等)',"
                    + "notify_doctor_time DATETIME DEFAULT NULL COMMENT '通知医生时间(阳性时必录)',"
                    + "doctor_confirm_time DATETIME DEFAULT NULL COMMENT '医生确认时间',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_exec (exec_id),"
                    + "KEY idx_drug (drug_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='皮试记录(观察窗与结果判定)'");
            /* 输液记录: 座位/滴速/穿刺/拔针/巡回, 隶属输液型执行记录 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_infusion_record ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "exec_id BIGINT NOT NULL COMMENT '执行记录ID(his_nurse_exec.id)',"
                    + "seat_no VARCHAR(20) DEFAULT NULL COMMENT '输液座位号',"
                    + "solution VARCHAR(200) DEFAULT NULL COMMENT '溶液(液体名称与容量)',"
                    + "drip_rate INT DEFAULT NULL COMMENT '滴速(滴/分)',"
                    + "puncture_time DATETIME DEFAULT NULL COMMENT '穿刺时间',"
                    + "puncture_site VARCHAR(50) DEFAULT NULL COMMENT '穿刺部位(左手背等)',"
                    + "puncture_nurse_id BIGINT DEFAULT NULL COMMENT '穿刺护士ID',"
                    + "remove_time DATETIME DEFAULT NULL COMMENT '拔针时间',"
                    + "remove_nurse_id BIGINT DEFAULT NULL COMMENT '拔针护士ID',"
                    + "patrol_records TEXT NULL COMMENT '巡回记录(JSON数组: 巡回时间+滴速+情况)',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_exec (exec_id),"
                    + "KEY idx_seat (tenant_id, seat_no)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='输液记录(座位/滴速/穿刺/拔针/巡回)'");
            /* 患者过敏记录: 手工登记/皮试阳性/医生站录入多来源, 有效标志控制展示 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_patient_allergy ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "patient_id BIGINT NOT NULL COMMENT '患者ID(his_patient.id)',"
                    + "allergen_type VARCHAR(20) DEFAULT NULL COMMENT '过敏原类型:drug药品/food食物/other其他',"
                    + "allergen_name VARCHAR(100) NOT NULL COMMENT '过敏原名称',"
                    + "allergen_code VARCHAR(50) DEFAULT NULL COMMENT '过敏原编码(药品为目录编码)',"
                    + "severity VARCHAR(20) DEFAULT NULL COMMENT '严重程度:mild轻度/moderate中度/severe重度',"
                    + "source VARCHAR(20) DEFAULT NULL COMMENT '来源:manual手工/skin_test皮试/doctor医生站',"
                    + "source_id BIGINT DEFAULT NULL COMMENT '来源记录ID(如his_skin_test.id)',"
                    + "record_time DATETIME DEFAULT NULL COMMENT '登记时间',"
                    + "record_by BIGINT DEFAULT NULL COMMENT '登记人ID(his_staff.id)',"
                    + "is_active TINYINT DEFAULT 1 COMMENT '是否有效:1有效 0已失效',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_patient (tenant_id, patient_id, is_active)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='患者过敏记录(多来源汇聚)'");
        }
    }

    /**
     * 幂等建表: 治疗管理三表 —— 治疗计划(his_treatment_plan, 疗程次数/频次) +
     * 治疗执行(his_treatment_exec, 逐次执行留痕) + 治疗设备(his_treatment_equipment, 机构+编码唯一)。
     */
    private void ensureTreatmentTables(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            /* 治疗计划: 疗程医嘱(总次数/频次/起止日期), 完成次数驱动状态流转 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_treatment_plan ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "plan_no VARCHAR(30) NOT NULL COMMENT '治疗计划号(ZL+日期+序号)',"
                    + "visit_id BIGINT DEFAULT NULL COMMENT '就诊ID(his_visit.id)',"
                    + "patient_id BIGINT DEFAULT NULL COMMENT '患者ID',"
                    + "doctor_id BIGINT DEFAULT NULL COMMENT '开单医师ID(his_staff.id)',"
                    + "order_id BIGINT NOT NULL COMMENT '医嘱单ID(his_order.id)',"
                    + "item_code VARCHAR(50) DEFAULT NULL COMMENT '收费项目编码(his_charge_item.item_code)',"
                    + "item_name VARCHAR(100) NOT NULL COMMENT '治疗项目名称',"
                    + "category VARCHAR(20) DEFAULT NULL COMMENT '治疗类别:physiotherapy理疗/rehab康复/tcm中医传统',"
                    + "total_sessions INT NOT NULL DEFAULT 1 COMMENT '总次数(疗程)',"
                    + "completed_sessions INT DEFAULT 0 COMMENT '已完成次数',"
                    + "frequency VARCHAR(50) DEFAULT NULL COMMENT '频次(如每日1次/隔日1次)',"
                    + "start_date DATE DEFAULT NULL COMMENT '开始日期',"
                    + "expire_date DATE DEFAULT NULL COMMENT '失效日期',"
                    + "status TINYINT DEFAULT 0 COMMENT '状态:0执行中 1已完成 2已终止',"
                    + "terminate_reason VARCHAR(200) DEFAULT NULL COMMENT '终止原因',"
                    + "remark VARCHAR(500) DEFAULT NULL COMMENT '备注',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_plan_no (tenant_id, plan_no),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_org_status (tenant_id, org_id, status),"
                    + "KEY idx_order (order_id),"
                    + "KEY idx_patient (tenant_id, patient_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='治疗计划(疗程医嘱)'");
            /* 治疗执行: 逐次执行留痕(序次/治疗师/设备/时长/参数/反应) */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_treatment_exec ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "exec_no VARCHAR(30) NOT NULL COMMENT '治疗执行单号(ZX+日期+序号)',"
                    + "plan_id BIGINT DEFAULT NULL COMMENT '治疗计划ID(his_treatment_plan.id)',"
                    + "order_id BIGINT DEFAULT NULL COMMENT '医嘱单ID(his_order.id)',"
                    + "order_item_id BIGINT DEFAULT NULL COMMENT '医嘱明细ID(his_order_item.id)',"
                    + "patient_id BIGINT DEFAULT NULL COMMENT '患者ID',"
                    + "session_index INT DEFAULT NULL COMMENT '疗程内序次(第几次)',"
                    + "exec_date DATE DEFAULT NULL COMMENT '执行日期',"
                    + "exec_therapist_id BIGINT DEFAULT NULL COMMENT '治疗师ID(his_staff.id)',"
                    + "equipment_code VARCHAR(50) DEFAULT NULL COMMENT '设备编码(his_treatment_equipment.equip_code)',"
                    + "duration_min INT DEFAULT NULL COMMENT '治疗时长(分钟)',"
                    + "parameters TEXT NULL COMMENT '治疗参数(JSON)',"
                    + "patient_response VARCHAR(500) DEFAULT NULL COMMENT '患者反应',"
                    + "checkin_time DATETIME DEFAULT NULL COMMENT '患者签到时间(非空=已签到, 排队口径)',"
                    + "cancel_reason VARCHAR(200) DEFAULT NULL COMMENT '取消原因',"
                    + "exec_status TINYINT DEFAULT 0 COMMENT '执行状态:0待执行 1执行中 2已完成 3已取消',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_texec_no (tenant_id, exec_no),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_org_status (tenant_id, org_id, exec_status),"
                    + "KEY idx_plan (plan_id),"
                    + "KEY idx_patient (tenant_id, patient_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='治疗执行记录(逐次留痕)'");
            /* 治疗设备: 机构级设备台账, 治疗执行可绑定设备编码 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_treatment_equipment ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "equip_code VARCHAR(50) NOT NULL COMMENT '设备编码',"
                    + "equip_name VARCHAR(100) NOT NULL COMMENT '设备名称',"
                    + "equip_type VARCHAR(50) DEFAULT NULL COMMENT '设备类型(理疗/康复/中医传统)',"
                    + "dept_id BIGINT DEFAULT NULL COMMENT '归属科室ID(his_dept.id)',"
                    + "status TINYINT DEFAULT 1 COMMENT '状态:1正常 2维修 0停用',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_org_equip (tenant_id, org_id, equip_code),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_org (tenant_id, org_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='治疗设备(机构级台账)'");
        }
    }

    /**
     * 幂等建表: 医技管理五表 —— 标本(his_specimen, 条码租户内唯一) +
     * 检查/检验报告(his_exam_report) + 结果明细项(his_exam_result_item) +
     * 危急值记录(his_critical_value, 报告→通知→接收→处置闭环) + 危急值规则(his_critical_rule, 阈值判定)。
     */
    private void ensureMedtechTables(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            /* 标本: 采集→签收→拒收流转, 条码即标本唯一标识 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_specimen ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "barcode VARCHAR(30) NOT NULL COMMENT '标本条码号',"
                    + "order_id BIGINT NOT NULL COMMENT '医嘱单ID(his_order.id)',"
                    + "patient_id BIGINT DEFAULT NULL COMMENT '患者ID',"
                    + "specimen_type VARCHAR(20) DEFAULT NULL COMMENT '标本类型:blood血/urine尿/stool便/sputum痰/other其他',"
                    + "tube_color VARCHAR(20) DEFAULT NULL COMMENT '采血管颜色(红/紫/蓝/黑/绿/灰/黄)',"
                    + "collect_nurse_id BIGINT DEFAULT NULL COMMENT '采集护士ID(his_staff.id)',"
                    + "collect_time DATETIME DEFAULT NULL COMMENT '采集时间',"
                    + "receive_tech_id BIGINT DEFAULT NULL COMMENT '签收技师ID(his_staff.id)',"
                    + "receive_time DATETIME DEFAULT NULL COMMENT '签收时间',"
                    + "status TINYINT DEFAULT 0 COMMENT '状态:0待采集 1已采集 2已签收 3已拒收 4已出报告',"
                    + "reject_reason VARCHAR(200) DEFAULT NULL COMMENT '拒收原因(溶血/量不足等)',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_barcode (tenant_id, barcode),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_org_status (tenant_id, org_id, status),"
                    + "KEY idx_order (order_id),"
                    + "KEY idx_patient (tenant_id, patient_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='检验标本(采集/签收/拒收)'");
            /* 报告: 检查(影像所见+结论)/检验(汇总+结论)统一单据, 报告→审核双签 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_exam_report ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "report_no VARCHAR(30) NOT NULL COMMENT '报告单号(BG+日期+序号)',"
                    + "order_id BIGINT NOT NULL COMMENT '医嘱单ID(his_order.id)',"
                    + "patient_id BIGINT DEFAULT NULL COMMENT '患者ID',"
                    + "report_type VARCHAR(20) NOT NULL COMMENT '报告类型:exam检查(影像)/lab检验(化验)',"
                    + "findings TEXT NULL COMMENT '所见(检查所见/检验结果汇总)',"
                    + "conclusion TEXT NULL COMMENT '结论(检查结论/检验诊断)',"
                    + "key_images TEXT NULL COMMENT '关键图像(JSON: 图像URL/描述数组)',"
                    + "report_doctor_id BIGINT DEFAULT NULL COMMENT '报告医师ID(his_staff.id)',"
                    + "report_time DATETIME DEFAULT NULL COMMENT '报告时间',"
                    + "review_doctor_id BIGINT DEFAULT NULL COMMENT '审核医师ID(his_staff.id)',"
                    + "review_time DATETIME DEFAULT NULL COMMENT '审核时间',"
                    + "status TINYINT DEFAULT 0 COMMENT '状态:0草稿 1已报告 2已审核 3已作废',"
                    + "critical_flag TINYINT DEFAULT 0 COMMENT '危急值标志:1有 0无',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_report_no (tenant_id, report_no),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_org_status (tenant_id, org_id, status),"
                    + "KEY idx_order (order_id),"
                    + "KEY idx_patient (tenant_id, patient_id),"
                    + "KEY idx_critical (tenant_id, critical_flag)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='检查/检验报告(报告-审核双签)'");
            /* 结果明细项: 检验逐项结果与参考范围比对, abnormal_flag 驱动异常标红与危急值判定 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_exam_result_item ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "report_id BIGINT NOT NULL COMMENT '报告ID(his_exam_report.id)',"
                    + "item_code VARCHAR(50) NOT NULL COMMENT '项目编码',"
                    + "item_name VARCHAR(100) NOT NULL COMMENT '项目名称',"
                    + "result_value VARCHAR(100) DEFAULT NULL COMMENT '结果值',"
                    + "result_unit VARCHAR(30) DEFAULT NULL COMMENT '结果单位',"
                    + "ref_range_low DECIMAL(12,4) DEFAULT NULL COMMENT '参考范围下限',"
                    + "ref_range_high DECIMAL(12,4) DEFAULT NULL COMMENT '参考范围上限',"
                    + "abnormal_flag TINYINT DEFAULT 0 COMMENT '异常标志:0正常 1偏高 2偏低 3危急值',"
                    + "remark VARCHAR(200) DEFAULT NULL COMMENT '备注',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_report (report_id),"
                    + "KEY idx_item_code (tenant_id, item_code)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='检验结果明细项'");
            /* 危急值: 报告→复核→通知→接收→处置全流程闭环留痕 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_critical_value ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "report_id BIGINT NOT NULL COMMENT '报告ID(his_exam_report.id)',"
                    + "order_id BIGINT DEFAULT NULL COMMENT '医嘱单ID(his_order.id)',"
                    + "patient_id BIGINT DEFAULT NULL COMMENT '患者ID',"
                    + "item_code VARCHAR(50) DEFAULT NULL COMMENT '项目编码',"
                    + "item_name VARCHAR(100) DEFAULT NULL COMMENT '项目名称',"
                    + "result_value VARCHAR(50) DEFAULT NULL COMMENT '结果值(触发危急值)',"
                    + "discover_time DATETIME DEFAULT NULL COMMENT '发现时间',"
                    + "verify_tech_id BIGINT DEFAULT NULL COMMENT '复核技师ID(his_staff.id)',"
                    + "verify_time DATETIME DEFAULT NULL COMMENT '复核时间',"
                    + "notify_time DATETIME DEFAULT NULL COMMENT '通知时间(电话通知临床)',"
                    + "notify_target VARCHAR(100) DEFAULT NULL COMMENT '通知对象(医生/护士)',"
                    + "receive_time DATETIME DEFAULT NULL COMMENT '接收时间(临床回执)',"
                    + "receive_person VARCHAR(50) DEFAULT NULL COMMENT '接收人',"
                    + "handle_time DATETIME DEFAULT NULL COMMENT '处置时间',"
                    + "handle_measures VARCHAR(500) DEFAULT NULL COMMENT '处置措施',"
                    + "status TINYINT DEFAULT 0 COMMENT '状态:0待复核 1已通知 2已接收 3已处置',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_org_status (tenant_id, org_id, status),"
                    + "KEY idx_report (report_id),"
                    + "KEY idx_patient (tenant_id, patient_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='危急值记录(通知-接收-处置闭环)'");
            /* 危急值规则: 检验项目阈值(低于下限/高于上限即危急), patient_type 空为通用(NULL 不参与唯一碰撞) */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_critical_rule ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "item_code VARCHAR(50) NOT NULL COMMENT '项目编码',"
                    + "item_name VARCHAR(100) DEFAULT NULL COMMENT '项目名称',"
                    + "low_threshold DECIMAL(12,4) DEFAULT NULL COMMENT '危急低阈值(低于即危急)',"
                    + "high_threshold DECIMAL(12,4) DEFAULT NULL COMMENT '危急高阈值(高于即危急)',"
                    + "patient_type VARCHAR(20) DEFAULT NULL COMMENT '患者类型:adult成人/child儿童(空=通用)',"
                    + "is_active TINYINT DEFAULT 1 COMMENT '是否启用:1启用 0停用',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_item_ptype (tenant_id, item_code, patient_type),"
                    + "KEY idx_tenant (tenant_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='危急值规则(项目阈值判定)'");
        }
    }

    /**
     * 幂等建表: 住院模块基座(12 张表, 表已存在则跳过)。
     * 覆盖 病区/床位/住院就诊/诊断/预交金/医嘱/医嘱执行/费用明细/结算/病历/护理/交接班。
     * 全表含 tenant_id(租户插件自动注入) + org_id(机构隔离) + 审计四列 + 逻辑删除。
     */
    private void ensureInpatientTables(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            /* 病区: 床位管理的组织单元, 关联科室与楼栋楼层 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_ward ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "ward_name VARCHAR(100) NOT NULL COMMENT '病区名称',"
                    + "ward_code VARCHAR(50) DEFAULT NULL COMMENT '病区编码',"
                    + "dept_id BIGINT DEFAULT NULL COMMENT '关联科室ID(his_dept.id)',"
                    + "building VARCHAR(100) DEFAULT NULL COMMENT '楼栋',"
                    + "floor VARCHAR(20) DEFAULT NULL COMMENT '楼层',"
                    + "bed_count INT DEFAULT 0 COMMENT '床位数',"
                    + "status TINYINT DEFAULT 1 COMMENT '状态:1启用 0停用',"
                    + "remark VARCHAR(500) DEFAULT NULL COMMENT '备注',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_org_wardcode (tenant_id, org_id, ward_code),"
                    + "KEY idx_org_status (tenant_id, org_id, status)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='住院病区'");
            /* 床位: 病区×房间×床位号唯一, 占用状态由入院/出院/转床链路回写 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_bed ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "bed_no VARCHAR(20) NOT NULL COMMENT '床位号',"
                    + "ward_id BIGINT NOT NULL COMMENT '病区ID(his_ward.id)',"
                    + "room_no VARCHAR(20) DEFAULT NULL COMMENT '房间号',"
                    + "bed_type TINYINT DEFAULT 1 COMMENT '床位类型:1普通 2抢救 3监护 4隔离',"
                    + "status TINYINT DEFAULT 0 COMMENT '状态:0空床 1占用 2停用',"
                    + "patient_id BIGINT DEFAULT NULL COMMENT '当前患者ID(his_patient.id)',"
                    + "inp_visit_id BIGINT DEFAULT NULL COMMENT '当前住院就诊ID(his_inp_visit.id)',"
                    + "daily_price DECIMAL(10,2) DEFAULT 0 COMMENT '床位日费用',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_ward_bedno (tenant_id, ward_id, bed_no),"
                    + "KEY idx_ward_status (tenant_id, ward_id, status),"
                    + "KEY idx_inp_visit (inp_visit_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='住院床位'");
            /* 住院就诊主表: 全生命周期状态机 1待入院→2在院→3出院办理中→4已出院/5已取消 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_inp_visit ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "inp_no VARCHAR(30) NOT NULL COMMENT '住院号',"
                    + "patient_id BIGINT NOT NULL COMMENT '患者ID(his_patient.id)',"
                    + "ward_id BIGINT DEFAULT NULL COMMENT '病区ID(his_ward.id)',"
                    + "bed_id BIGINT DEFAULT NULL COMMENT '床位ID(his_bed.id)',"
                    + "dept_id BIGINT DEFAULT NULL COMMENT '住院科室ID(his_dept.id)',"
                    + "doctor_id BIGINT DEFAULT NULL COMMENT '主治医生ID(his_staff.id)',"
                    + "nurse_id BIGINT DEFAULT NULL COMMENT '责任护士ID(his_staff.id)',"
                    + "admit_date DATETIME DEFAULT NULL COMMENT '入院日期',"
                    + "discharge_date DATETIME DEFAULT NULL COMMENT '出院日期',"
                    + "visit_status TINYINT DEFAULT 1 COMMENT '状态:1待入院 2在院 3出院办理中 4已出院 5已取消',"
                    + "admit_diag VARCHAR(500) DEFAULT NULL COMMENT '入院诊断',"
                    + "total_cost DECIMAL(12,2) DEFAULT 0 COMMENT '总费用',"
                    + "deposit_balance DECIMAL(12,2) DEFAULT 0 COMMENT '预交金余额',"
                    + "med_type VARCHAR(10) DEFAULT NULL COMMENT '医疗类别(医保)',"
                    + "psn_no VARCHAR(50) DEFAULT NULL COMMENT '医保人员编号',"
                    + "insutype VARCHAR(10) DEFAULT NULL COMMENT '险种类型',"
                    + "mdtrt_id VARCHAR(50) DEFAULT NULL COMMENT '医保就诊ID',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_org_inpno (tenant_id, org_id, inp_no),"
                    + "KEY idx_patient (tenant_id, patient_id),"
                    + "KEY idx_org_status (tenant_id, org_id, visit_status),"
                    + "KEY idx_ward (ward_id),"
                    + "KEY idx_mdtrt (mdtrt_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='住院就诊主表'");
            /* 住院诊断: 入院/补充/术后/出院四类, 主诊断标志驱动病案首页与医保上报 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_inp_diagnosis ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "inp_visit_id BIGINT NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',"
                    + "diag_type TINYINT NOT NULL COMMENT '诊断类型:1入院诊断 2补充诊断 3术后诊断 4出院诊断',"
                    + "diag_code VARCHAR(30) DEFAULT NULL COMMENT '诊断编码(ICD-10)',"
                    + "diag_name VARCHAR(200) NOT NULL COMMENT '诊断名称',"
                    + "is_main TINYINT DEFAULT 0 COMMENT '是否主诊断:1是 0否',"
                    + "diag_dept_id BIGINT DEFAULT NULL COMMENT '诊断科室ID(his_dept.id)',"
                    + "diag_doctor_id BIGINT DEFAULT NULL COMMENT '诊断医生ID(his_staff.id)',"
                    + "diag_time DATETIME DEFAULT NULL COMMENT '诊断时间',"
                    + "sort_no INT DEFAULT 0 COMMENT '排序号',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_visit (inp_visit_id),"
                    + "KEY idx_visit_type (inp_visit_id, diag_type)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='住院诊断'");
            /* 预交金流水: 缴纳/退还双向, balance_after 记录操作后余额便于对账 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_inp_deposit ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "inp_visit_id BIGINT NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',"
                    + "amount DECIMAL(12,2) NOT NULL COMMENT '金额',"
                    + "pay_type TINYINT DEFAULT 1 COMMENT '支付方式:1现金 2微信 3支付宝 4银行卡',"
                    + "direction TINYINT NOT NULL COMMENT '方向:1缴纳 2退还',"
                    + "balance_after DECIMAL(12,2) DEFAULT NULL COMMENT '操作后余额',"
                    + "operator_id BIGINT DEFAULT NULL COMMENT '操作员ID(his_staff.id)',"
                    + "receipt_no VARCHAR(50) DEFAULT NULL COMMENT '收据号',"
                    + "remark VARCHAR(500) DEFAULT NULL COMMENT '备注',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_visit (inp_visit_id),"
                    + "KEY idx_visit_time (inp_visit_id, create_time)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='住院预交金流水'");
            /* 住院医嘱: 长期/临时两类, 开嘱→审核→执行→完成/停止/作废状态机, 成组医嘱号聚合 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_inp_order ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "inp_visit_id BIGINT NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',"
                    + "order_type TINYINT NOT NULL COMMENT '医嘱类型:1长期 2临时',"
                    + "order_category TINYINT NOT NULL COMMENT '医嘱分类:1药品 2检查 3检验 4治疗 5护理 6膳食 7其他',"
                    + "order_content VARCHAR(500) NOT NULL COMMENT '医嘱内容',"
                    + "charge_item_id BIGINT DEFAULT NULL COMMENT '收费项目ID',"
                    + "drug_id BIGINT DEFAULT NULL COMMENT '药品ID(药品目录)',"
                    + "spec VARCHAR(100) DEFAULT NULL COMMENT '规格',"
                    + "dosage VARCHAR(50) DEFAULT NULL COMMENT '剂量',"
                    + "dosage_unit VARCHAR(20) DEFAULT NULL COMMENT '剂量单位',"
                    + "usage_code VARCHAR(20) DEFAULT NULL COMMENT '用法编码',"
                    + "freq_code VARCHAR(20) DEFAULT NULL COMMENT '频次编码',"
                    + "start_time DATETIME DEFAULT NULL COMMENT '开始时间',"
                    + "stop_time DATETIME DEFAULT NULL COMMENT '停止时间',"
                    + "order_status TINYINT DEFAULT 1 COMMENT '状态:1新开 2已审核 3执行中 4已完成 5已停止 6已作废',"
                    + "doctor_id BIGINT DEFAULT NULL COMMENT '开嘱医生ID(his_staff.id)',"
                    + "audit_nurse_id BIGINT DEFAULT NULL COMMENT '审核护士ID(his_staff.id)',"
                    + "audit_time DATETIME DEFAULT NULL COMMENT '审核时间',"
                    + "stop_doctor_id BIGINT DEFAULT NULL COMMENT '停嘱医生ID(his_staff.id)',"
                    + "stop_nurse_id BIGINT DEFAULT NULL COMMENT '停嘱护士确认ID(his_staff.id)',"
                    + "group_no VARCHAR(30) DEFAULT NULL COMMENT '成组医嘱号',"
                    + "quantity DECIMAL(10,2) DEFAULT NULL COMMENT '数量',"
                    + "unit_price DECIMAL(10,2) DEFAULT NULL COMMENT '单价',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_visit (inp_visit_id, order_status),"
                    + "KEY idx_visit_type (inp_visit_id, order_type),"
                    + "KEY idx_doctor (doctor_id),"
                    + "KEY idx_group (group_no)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='住院医嘱'");
            /* 医嘱执行记录: 长期医嘱按频次生成执行计划, 护士逐次签名执行 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_inp_order_exec ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "order_id BIGINT NOT NULL COMMENT '医嘱ID(his_inp_order.id)',"
                    + "inp_visit_id BIGINT NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',"
                    + "plan_time DATETIME DEFAULT NULL COMMENT '计划执行时间',"
                    + "exec_time DATETIME DEFAULT NULL COMMENT '实际执行时间',"
                    + "exec_nurse_id BIGINT DEFAULT NULL COMMENT '执行护士ID(his_staff.id)',"
                    + "exec_status TINYINT DEFAULT 1 COMMENT '状态:1待执行 2已执行 3未执行',"
                    + "exec_remark VARCHAR(500) DEFAULT NULL COMMENT '执行备注',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_order (order_id),"
                    + "KEY idx_visit (inp_visit_id),"
                    + "KEY idx_status_plan (tenant_id, org_id, exec_status, plan_time)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='住院医嘱执行记录'");
            /* 住院费用明细: 逐日记账, 医嘱驱动(药品/检查/检验/治疗)+固定费(床位), 结算前汇总 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_inp_charge_detail ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "inp_visit_id BIGINT NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',"
                    + "charge_item_id BIGINT DEFAULT NULL COMMENT '收费项目ID',"
                    + "item_name VARCHAR(200) NOT NULL COMMENT '项目名称',"
                    + "item_code VARCHAR(50) DEFAULT NULL COMMENT '项目编码',"
                    + "quantity DECIMAL(10,2) DEFAULT 1 COMMENT '数量',"
                    + "unit_price DECIMAL(10,2) NOT NULL COMMENT '单价',"
                    + "amount DECIMAL(12,2) NOT NULL COMMENT '金额',"
                    + "charge_date DATE DEFAULT NULL COMMENT '记账日期',"
                    + "order_id BIGINT DEFAULT NULL COMMENT '关联医嘱ID(his_inp_order.id)',"
                    + "fee_type TINYINT DEFAULT NULL COMMENT '费用类别:1西药 2中药 3检查 4检验 5治疗 6护理 7材料 8床位 9其他',"
                    + "status TINYINT DEFAULT 1 COMMENT '状态:1正常 2退费',"
                    + "operator_id BIGINT DEFAULT NULL COMMENT '操作员ID(his_staff.id)',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_visit (inp_visit_id, charge_date),"
                    + "KEY idx_visit_fee (inp_visit_id, fee_type),"
                    + "KEY idx_order (order_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='住院费用明细'");
            /* 住院结算: 出院/中途/退费三类, 医保结算状态与 2207/2208 链路对齐 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_inp_settle ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "inp_visit_id BIGINT NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',"
                    + "settle_no VARCHAR(30) DEFAULT NULL COMMENT '结算单号',"
                    + "total_amount DECIMAL(12,2) DEFAULT NULL COMMENT '总金额',"
                    + "self_pay DECIMAL(12,2) DEFAULT 0 COMMENT '自付金额',"
                    + "fund_pay DECIMAL(12,2) DEFAULT 0 COMMENT '基金支付',"
                    + "cash_pay DECIMAL(12,2) DEFAULT 0 COMMENT '现金支付',"
                    + "acct_pay DECIMAL(12,2) DEFAULT 0 COMMENT '个账支付',"
                    + "deposit_deduct DECIMAL(12,2) DEFAULT 0 COMMENT '预交金抵扣',"
                    + "refund_amount DECIMAL(12,2) DEFAULT 0 COMMENT '退还金额',"
                    + "settle_type TINYINT DEFAULT NULL COMMENT '结算类型:1出院结算 2中途结算 3退费',"
                    + "yb_status TINYINT DEFAULT 0 COMMENT '医保状态:0未结算 1结算中 2已结算 3撤销中 4已撤销',"
                    + "settle_time DATETIME DEFAULT NULL COMMENT '结算时间',"
                    + "operator_id BIGINT DEFAULT NULL COMMENT '操作员ID(his_staff.id)',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_settle_no (tenant_id, settle_no),"
                    + "KEY idx_visit (inp_visit_id),"
                    + "KEY idx_yb_status (tenant_id, org_id, yb_status)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='住院结算'");
            /* 住院病历: 九类结构化文书, 草稿→已提交→已审核三级审核流 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_inp_medical_record ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "inp_visit_id BIGINT NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',"
                    + "record_type TINYINT NOT NULL COMMENT '记录类型:1入院记录 2首次病程 3日常病程 4查房记录 5术前小结 6手术记录 7术后病程 8出院小结 9死亡记录',"
                    + "title VARCHAR(200) DEFAULT NULL COMMENT '标题',"
                    + "content TEXT NULL COMMENT '内容(JSON)',"
                    + "record_time DATETIME DEFAULT NULL COMMENT '记录时间',"
                    + "doctor_id BIGINT DEFAULT NULL COMMENT '记录医生ID(his_staff.id)',"
                    + "audit_doctor_id BIGINT DEFAULT NULL COMMENT '审核医生ID(his_staff.id)',"
                    + "audit_time DATETIME DEFAULT NULL COMMENT '审核时间',"
                    + "status TINYINT DEFAULT 1 COMMENT '状态:1草稿 2已提交 3已审核',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_visit (inp_visit_id, record_type),"
                    + "KEY idx_doctor (doctor_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='住院病历'");
            /* 护理记录: 体温单/评估/计划/措施/总结五类, content 为 JSON */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_inp_nursing_record ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "inp_visit_id BIGINT NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',"
                    + "record_type TINYINT NOT NULL COMMENT '记录类型:1体温单 2护理评估 3护理计划 4护理措施 5护理总结',"
                    + "content TEXT NULL COMMENT '内容(JSON)',"
                    + "record_time DATETIME DEFAULT NULL COMMENT '记录时间',"
                    + "nurse_id BIGINT DEFAULT NULL COMMENT '护士ID(his_staff.id)',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_visit (inp_visit_id, record_type),"
                    + "KEY idx_nurse (nurse_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='住院护理记录'");
            /* 交接班: 病区×日期×班次唯一, 交班人→接班人双签闭环 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_inp_shift_record ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "ward_id BIGINT NOT NULL COMMENT '病区ID(his_ward.id)',"
                    + "shift_date DATE NOT NULL COMMENT '交班日期',"
                    + "shift_type TINYINT NOT NULL COMMENT '班次:1白班 2小夜 3大夜',"
                    + "handover_nurse_id BIGINT DEFAULT NULL COMMENT '交班护士ID(his_staff.id)',"
                    + "takeover_nurse_id BIGINT DEFAULT NULL COMMENT '接班护士ID(his_staff.id)',"
                    + "total_patients INT DEFAULT 0 COMMENT '在院总数',"
                    + "new_admit INT DEFAULT 0 COMMENT '新入院',"
                    + "discharged INT DEFAULT 0 COMMENT '出院',"
                    + "critical_count INT DEFAULT 0 COMMENT '危重人数',"
                    + "content TEXT NULL COMMENT '交班内容(JSON)',"
                    + "status TINYINT DEFAULT 1 COMMENT '状态:1待接班 2已交接',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_ward_date_type (tenant_id, ward_id, shift_date, shift_type),"
                    + "KEY idx_date (tenant_id, org_id, shift_date)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='病区交接班记录'");
        }
    }

    /**
     * 幂等建表: 临床路径 + 手术麻醉两模块基座(9 张表, 表已存在则跳过)。
     * 临床路径: 模板→节点→任务三级定义, 患者实例→逐日执行记录(变异留痕), 住院医嘱挂路径实例;
     * 手术麻醉: 手术申请→排程→术中→术后→完成状态机, 麻醉记录(评估/生命体征/术中事件 JSON),
     *          手术费用明细(自动计时+手动记账)与手术耗材, 费用明细挂手术ID。
     * 存量表补列: his_inp_order.pathway_instance_id / his_inp_charge_detail.surgery_id(列已存在则跳过)。
     */
    private void ensurePathwayAndSurgeryTables(Connection conn) throws Exception {
        /* 存量表补列: 医嘱挂临床路径实例 / 住院费用明细挂手术(MySQL 不支持 ADD COLUMN IF NOT EXISTS, 走幂等助手) */
        addColumnIfNotExists(conn, "his_inp_order", "pathway_instance_id", "BIGINT DEFAULT NULL COMMENT '临床路径实例ID(his_pathway_instance.id)'");
        addColumnIfNotExists(conn, "his_inp_charge_detail", "surgery_id", "BIGINT DEFAULT NULL COMMENT '手术ID(his_surgery.id)'");
        try (Statement st = conn.createStatement()) {
            /* 路径模板: 病种入径标准(适用诊断+科室+平均住院日+预估总费用), 版本化维护 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_pathway_template ("
                    + "id BIGINT NOT NULL COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "pathway_code VARCHAR(50) NOT NULL COMMENT '路径编码',"
                    + "pathway_name VARCHAR(200) NOT NULL COMMENT '路径名称',"
                    + "disease_code VARCHAR(30) DEFAULT NULL COMMENT '适用诊断编码ICD-10',"
                    + "disease_name VARCHAR(200) DEFAULT NULL COMMENT '适用诊断名称',"
                    + "dept_id BIGINT DEFAULT NULL COMMENT '适用科室ID(his_dept.id)',"
                    + "avg_length INT DEFAULT NULL COMMENT '平均住院日',"
                    + "total_cost DECIMAL(12,2) DEFAULT NULL COMMENT '预估总费用',"
                    + "version INT DEFAULT 1 COMMENT '版本号',"
                    + "status TINYINT DEFAULT 1 COMMENT '状态:1启用 0停用',"
                    + "description VARCHAR(1000) DEFAULT NULL COMMENT '路径描述',"
                    + "create_by VARCHAR(64) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(64) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_org_status (tenant_id, org_id, status),"
                    + "KEY idx_disease (disease_code)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='临床路径模板'");
            /* 路径节点: 模板×第X天网格, 同天多节点按 sort_no 排序 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_pathway_node ("
                    + "id BIGINT NOT NULL COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "template_id BIGINT NOT NULL COMMENT '模板ID(his_pathway_template.id)',"
                    + "day_no INT NOT NULL COMMENT '第X天',"
                    + "node_name VARCHAR(200) DEFAULT NULL COMMENT '节点名称',"
                    + "node_desc VARCHAR(500) DEFAULT NULL COMMENT '节点描述',"
                    + "sort_no INT DEFAULT 0 COMMENT '排序号',"
                    + "create_by VARCHAR(64) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(64) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_template_day (template_id, day_no)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='临床路径节点'");
            /* 节点任务(医嘱模板): 医护/检查/检验/宣教五类, 药品/收费项目双挂, 必做/可选区分 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_pathway_task ("
                    + "id BIGINT NOT NULL COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "node_id BIGINT NOT NULL COMMENT '节点ID(his_pathway_node.id)',"
                    + "template_id BIGINT NOT NULL COMMENT '模板ID(his_pathway_template.id)',"
                    + "task_type TINYINT DEFAULT 1 COMMENT '任务类型:1医嘱 2护理 3检查 4检验 5宣教',"
                    + "charge_item_id BIGINT DEFAULT NULL COMMENT '收费项目ID',"
                    + "drug_id BIGINT DEFAULT NULL COMMENT '药品ID(药品目录)',"
                    + "order_type TINYINT DEFAULT NULL COMMENT '医嘱类型:1长期 2临时',"
                    + "order_category TINYINT DEFAULT NULL COMMENT '医嘱分类:1药品 2检查 3检验 4治疗 5护理 6膳食 7其他',"
                    + "order_content VARCHAR(500) DEFAULT NULL COMMENT '医嘱内容',"
                    + "spec VARCHAR(100) DEFAULT NULL COMMENT '规格',"
                    + "dosage VARCHAR(50) DEFAULT NULL COMMENT '剂量',"
                    + "dosage_unit VARCHAR(20) DEFAULT NULL COMMENT '剂量单位',"
                    + "usage_code VARCHAR(20) DEFAULT NULL COMMENT '用法编码',"
                    + "freq_code VARCHAR(20) DEFAULT NULL COMMENT '频次编码',"
                    + "quantity DECIMAL(10,2) DEFAULT NULL COMMENT '数量',"
                    + "unit_price DECIMAL(10,2) DEFAULT NULL COMMENT '单价',"
                    + "is_mandatory TINYINT DEFAULT 1 COMMENT '是否必做:1必做 0可选',"
                    + "sort_no INT DEFAULT 0 COMMENT '排序号',"
                    + "create_by VARCHAR(64) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(64) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_node (node_id),"
                    + "KEY idx_template (template_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='临床路径节点任务(医嘱模板)'");
            /* 患者路径实例: 住院就诊×模板入径, 进行中/完成/退出/暂停状态机, 退出留原因 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_pathway_instance ("
                    + "id BIGINT NOT NULL COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "inp_visit_id BIGINT NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',"
                    + "template_id BIGINT NOT NULL COMMENT '模板ID(his_pathway_template.id)',"
                    + "start_date DATETIME DEFAULT NULL COMMENT '启动日期',"
                    + "current_day INT DEFAULT 1 COMMENT '当前天数',"
                    + "end_date DATETIME DEFAULT NULL COMMENT '结束日期',"
                    + "status TINYINT DEFAULT 1 COMMENT '状态:1进行中 2已完成 3已退出 4暂停',"
                    + "exit_reason VARCHAR(500) DEFAULT NULL COMMENT '退出原因',"
                    + "doctor_id BIGINT DEFAULT NULL COMMENT '主治医生ID(his_staff.id)',"
                    + "create_by VARCHAR(64) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(64) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_visit (inp_visit_id, status),"
                    + "KEY idx_template (template_id),"
                    + "KEY idx_org_status (tenant_id, org_id, status)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='患者临床路径实例'");
            /* 路径执行记录: 实例×任务逐日执行, 待执行/已执行/跳过/变异四态, 变异留原因并回链医嘱 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_pathway_exec ("
                    + "id BIGINT NOT NULL COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "instance_id BIGINT NOT NULL COMMENT '路径实例ID(his_pathway_instance.id)',"
                    + "task_id BIGINT DEFAULT NULL COMMENT '任务ID(his_pathway_task.id)',"
                    + "node_id BIGINT DEFAULT NULL COMMENT '节点ID(his_pathway_node.id)',"
                    + "day_no INT DEFAULT NULL COMMENT '第X天',"
                    + "exec_date DATE DEFAULT NULL COMMENT '执行日期',"
                    + "exec_status TINYINT DEFAULT 1 COMMENT '执行状态:1待执行 2已执行 3跳过 4变异',"
                    + "order_id BIGINT DEFAULT NULL COMMENT '关联医嘱ID(his_inp_order.id)',"
                    + "variance_reason VARCHAR(500) DEFAULT NULL COMMENT '变异原因',"
                    + "operator_id BIGINT DEFAULT NULL COMMENT '操作员ID(his_staff.id)',"
                    + "create_by VARCHAR(64) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(64) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_instance (instance_id, exec_status),"
                    + "KEY idx_task (task_id),"
                    + "KEY idx_order (order_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='临床路径执行记录'");
            /* 手术主表: 申请→排程→术中→术后→完成状态机, 手术团队八角色 + 切皮/缝合时间轴 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_surgery ("
                    + "id BIGINT NOT NULL COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "inp_visit_id BIGINT NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',"
                    + "surgery_code VARCHAR(50) DEFAULT NULL COMMENT '手术编码ICD-9-CM-3',"
                    + "surgery_name VARCHAR(200) NOT NULL COMMENT '手术名称',"
                    + "surgery_level TINYINT DEFAULT NULL COMMENT '手术级别:1一级 2二级 3三级 4四级',"
                    + "surgeon_id BIGINT DEFAULT NULL COMMENT '主刀医师ID(his_staff.id)',"
                    + "first_assistant_id BIGINT DEFAULT NULL COMMENT '一助ID(his_staff.id)',"
                    + "second_assistant_id BIGINT DEFAULT NULL COMMENT '二助ID(his_staff.id)',"
                    + "anesthesiologist_id BIGINT DEFAULT NULL COMMENT '麻醉医师ID(his_staff.id)',"
                    + "anesthesia_nurse_id BIGINT DEFAULT NULL COMMENT '麻醉护士ID(his_staff.id)',"
                    + "instrument_nurse_id BIGINT DEFAULT NULL COMMENT '器械护士ID(his_staff.id)',"
                    + "circulating_nurse_id BIGINT DEFAULT NULL COMMENT '巡回护士ID(his_staff.id)',"
                    + "room_no VARCHAR(20) DEFAULT NULL COMMENT '手术间号',"
                    + "schedule_date DATE DEFAULT NULL COMMENT '手术日期',"
                    + "schedule_time VARCHAR(20) DEFAULT NULL COMMENT '手术时间段',"
                    + "start_time DATETIME DEFAULT NULL COMMENT '实际开始时间',"
                    + "end_time DATETIME DEFAULT NULL COMMENT '实际结束时间',"
                    + "incision_time DATETIME DEFAULT NULL COMMENT '切皮时间',"
                    + "suture_time DATETIME DEFAULT NULL COMMENT '缝合时间',"
                    + "asa_grade TINYINT DEFAULT NULL COMMENT 'ASA分级:1-5',"
                    + "incision_type TINYINT DEFAULT NULL COMMENT '切口类型:1清洁 2清洁污染 3污染 4感染',"
                    + "status TINYINT DEFAULT 1 COMMENT '状态:1申请 2排程 3术中 4术后 5完成 6取消',"
                    + "dept_id BIGINT DEFAULT NULL COMMENT '手术科室ID(his_dept.id)',"
                    + "create_by VARCHAR(64) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(64) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_visit (inp_visit_id, status),"
                    + "KEY idx_org_status (tenant_id, org_id, status),"
                    + "KEY idx_surgeon (surgeon_id),"
                    + "KEY idx_schedule (tenant_id, org_id, schedule_date)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='手术记录主表'");
            /* 麻醉记录: 一台手术一条, 术前/术后评估 + 诱导-插管-拔管-苏醒时间轴, 生命体征/术中事件 JSON */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_anesthesia ("
                    + "id BIGINT NOT NULL COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "surgery_id BIGINT NOT NULL COMMENT '手术ID(his_surgery.id)',"
                    + "anesthesia_type TINYINT DEFAULT NULL COMMENT '麻醉类型:1全麻 2局麻 3椎管内 4神经阻滞 5复合 6其他',"
                    + "anesthesia_method VARCHAR(100) DEFAULT NULL COMMENT '具体麻醉方式',"
                    + "pre_assessment TEXT NULL COMMENT '术前评估JSON',"
                    + "induction_time DATETIME DEFAULT NULL COMMENT '诱导时间',"
                    + "intubation_time DATETIME DEFAULT NULL COMMENT '插管时间',"
                    + "extubation_time DATETIME DEFAULT NULL COMMENT '拔管时间',"
                    + "recovery_time DATETIME DEFAULT NULL COMMENT '苏醒时间',"
                    + "vital_signs TEXT NULL COMMENT '生命体征JSON数组',"
                    + "anesthesia_events TEXT NULL COMMENT '术中事件JSON',"
                    + "post_assessment TEXT NULL COMMENT '术后评估JSON',"
                    + "status TINYINT DEFAULT 1 COMMENT '状态:1记录中 2已完成',"
                    + "create_by VARCHAR(64) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(64) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_surgery (surgery_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='麻醉记录'");
            /* 手术费用明细: 手术费/麻醉费/监测费/耗材/药品六类, 自动计时(分钟计价)与手动记账双轨 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_surgery_fee ("
                    + "id BIGINT NOT NULL COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "surgery_id BIGINT NOT NULL COMMENT '手术ID(his_surgery.id)',"
                    + "inp_visit_id BIGINT NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',"
                    + "charge_item_id BIGINT DEFAULT NULL COMMENT '收费项目ID',"
                    + "item_name VARCHAR(200) NOT NULL COMMENT '项目名称',"
                    + "item_code VARCHAR(50) DEFAULT NULL COMMENT '项目编码',"
                    + "fee_category TINYINT DEFAULT NULL COMMENT '费用分类:1手术费 2麻醉费 3监测费 4耗材费 5药品费 6其他',"
                    + "quantity DECIMAL(10,2) DEFAULT 1 COMMENT '数量',"
                    + "unit_price DECIMAL(10,2) NOT NULL COMMENT '单价',"
                    + "amount DECIMAL(12,2) NOT NULL COMMENT '金额',"
                    + "charge_time DATETIME DEFAULT NULL COMMENT '记账时间',"
                    + "auto_flag TINYINT DEFAULT 0 COMMENT '自动计时:1是 0手动',"
                    + "duration_minutes INT DEFAULT NULL COMMENT '计时分钟数',"
                    + "operator_id BIGINT DEFAULT NULL COMMENT '操作员ID(his_staff.id)',"
                    + "status TINYINT DEFAULT 1 COMMENT '状态:1正常 2退费',"
                    + "create_by VARCHAR(64) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(64) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_surgery (surgery_id, fee_category),"
                    + "KEY idx_visit (inp_visit_id, status)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='手术费用明细'");
            /* 手术耗材: 高值耗材逐台登记(批号/供应商可追溯), 与手术费用明细互为补充 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_surgery_material ("
                    + "id BIGINT NOT NULL COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "surgery_id BIGINT NOT NULL COMMENT '手术ID(his_surgery.id)',"
                    + "material_name VARCHAR(200) NOT NULL COMMENT '耗材名称',"
                    + "material_code VARCHAR(50) DEFAULT NULL COMMENT '耗材编码',"
                    + "spec VARCHAR(100) DEFAULT NULL COMMENT '规格',"
                    + "batch_no VARCHAR(50) DEFAULT NULL COMMENT '批号',"
                    + "quantity DECIMAL(10,2) DEFAULT 1 COMMENT '数量',"
                    + "unit_price DECIMAL(10,2) DEFAULT NULL COMMENT '单价',"
                    + "amount DECIMAL(12,2) DEFAULT NULL COMMENT '金额',"
                    + "supplier VARCHAR(200) DEFAULT NULL COMMENT '供应商',"
                    + "create_by VARCHAR(64) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(64) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_surgery (surgery_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='手术耗材'");
        }
    }

    /**
     * 幂等建表: 手麻P0升级(规范2.2.2.3.7) —— 手术申请单/手术操作权限规则/费用模板(主+明细)/通知记录 5 新表,
     * 并对存量表门诊化改造: his_surgery / his_surgery_fee 的 inp_visit_id 放宽可空(门诊/日间手术无住院就诊),
     * 补列 visit_type/visit_id/apply_id/报到时间/时限类型/一体化模块类型等。
     */
    private void ensureSurgeryApplyTables(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            /* 手术申请单: 医生申请→病区护士复核(可退回)→待安排→已安排→已完成, 可作废; 支持住院/门诊/日间三类就诊 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_surgery_apply ("
                    + "id BIGINT NOT NULL COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "apply_no VARCHAR(50) NOT NULL COMMENT '申请单号(SQ+yyyyMMdd+序号)',"
                    + "visit_type TINYINT DEFAULT 1 COMMENT '就诊类型:1住院 2门诊 3日间',"
                    + "inp_visit_id BIGINT DEFAULT NULL COMMENT '住院就诊ID(visit_type=1)',"
                    + "visit_id BIGINT DEFAULT NULL COMMENT '门诊就诊ID(his_visit.id, visit_type=2/3)',"
                    + "patient_id BIGINT DEFAULT NULL COMMENT '患者ID',"
                    + "patient_name VARCHAR(50) DEFAULT NULL COMMENT '患者姓名快照',"
                    + "gender VARCHAR(10) DEFAULT NULL COMMENT '性别快照',"
                    + "age VARCHAR(20) DEFAULT NULL COMMENT '年龄快照',"
                    + "medical_no VARCHAR(50) DEFAULT NULL COMMENT '病历号/住院号快照',"
                    + "bed_no VARCHAR(20) DEFAULT NULL COMMENT '床号快照',"
                    + "phone VARCHAR(30) DEFAULT NULL COMMENT '联系电话',"
                    + "apply_dept_id BIGINT DEFAULT NULL COMMENT '申请科室ID',"
                    + "apply_dept_name VARCHAR(100) DEFAULT NULL COMMENT '申请科室名称',"
                    + "surgery_code VARCHAR(50) DEFAULT NULL COMMENT '手术编码ICD-9-CM-3',"
                    + "surgery_name VARCHAR(200) NOT NULL COMMENT '手术名称',"
                    + "surgery_level TINYINT DEFAULT NULL COMMENT '手术级别:1-4',"
                    + "anesthesia_type TINYINT DEFAULT NULL COMMENT '拟麻醉方式:1全麻 2局麻 3椎管内 4神经阻滞 5复合 6其他',"
                    + "surgeon_id BIGINT DEFAULT NULL COMMENT '拟主刀医师ID(his_staff.id)',"
                    + "surgeon_name VARCHAR(50) DEFAULT NULL COMMENT '拟主刀医师姓名',"
                    + "expect_time DATETIME DEFAULT NULL COMMENT '医生期望手术时间(精确到分)',"
                    + "deadline_type TINYINT DEFAULT 1 COMMENT '手术时限:1择期 2限期 3急诊',"
                    + "pre_op_diag VARCHAR(500) DEFAULT NULL COMMENT '术前诊断',"
                    + "apply_reason VARCHAR(1000) DEFAULT NULL COMMENT '手术经过/病情简介',"
                    + "special_req VARCHAR(500) DEFAULT NULL COMMENT '特殊要求(体位/特殊耗材等)',"
                    + "apply_by_id BIGINT DEFAULT NULL COMMENT '申请医师ID(his_staff.id)',"
                    + "apply_by_name VARCHAR(50) DEFAULT NULL COMMENT '申请医师姓名',"
                    + "apply_time DATETIME DEFAULT NULL COMMENT '申请时间',"
                    + "status TINYINT DEFAULT 1 COMMENT '状态:1待复核 2已复核待安排 3已退回 4已安排 5已完成 6已作废',"
                    + "reconfirm_by VARCHAR(64) DEFAULT NULL COMMENT '复核护士',"
                    + "reconfirm_time DATETIME DEFAULT NULL COMMENT '复核时间',"
                    + "reject_reason VARCHAR(500) DEFAULT NULL COMMENT '退回原因',"
                    + "cancel_reason VARCHAR(500) DEFAULT NULL COMMENT '作废原因',"
                    + "surgery_id BIGINT DEFAULT NULL COMMENT '手术ID(his_surgery.id, 安排后回填)',"
                    + "create_by VARCHAR(64) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(64) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_apply_no (apply_no, deleted),"
                    + "KEY idx_org_status (tenant_id, org_id, visit_type, status),"
                    + "KEY idx_inp_visit (inp_visit_id),"
                    + "KEY idx_visit (visit_id),"
                    + "KEY idx_surgery (surgery_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='手术申请单'");
            /* 手术操作权限规则: 两种模式(按手术等级/按服务项目自定义分类), 控制可申请手术的主刀人员范围 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_surgery_auth_rule ("
                    + "id BIGINT NOT NULL COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "rule_name VARCHAR(100) NOT NULL COMMENT '规则名称',"
                    + "rule_type TINYINT NOT NULL COMMENT '规则类型:1按手术等级 2按自定义分类',"
                    + "surgery_level TINYINT DEFAULT NULL COMMENT '等级模式:受限最低手术级别(≥该级需权限校验)',"
                    + "surgery_codes TEXT NULL COMMENT '自定义模式:手术编码/名称逗号清单',"
                    + "allow_staff_ids TEXT NULL COMMENT '允许主刀的职工ID清单(his_staff.id, 逗号分隔)',"
                    + "remark VARCHAR(500) DEFAULT NULL COMMENT '备注',"
                    + "status TINYINT DEFAULT 1 COMMENT '状态:1启用 0停用',"
                    + "create_by VARCHAR(64) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(64) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_org_type (tenant_id, org_id, rule_type, status)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='手术操作权限规则'");
            /* 手术费用模板: 个人/科室/全院三级, 术后费用录入一键导入 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_surgery_fee_tpl ("
                    + "id BIGINT NOT NULL COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "tpl_name VARCHAR(100) NOT NULL COMMENT '模板名称',"
                    + "tpl_level TINYINT DEFAULT 1 COMMENT '级别:1个人 2科室 3全院',"
                    + "owner_staff_id BIGINT DEFAULT NULL COMMENT '归属职工ID(个人级)',"
                    + "dept_id BIGINT DEFAULT NULL COMMENT '归属科室ID(科室级)',"
                    + "surgery_code VARCHAR(50) DEFAULT NULL COMMENT '关联手术编码(可空=通用模板)',"
                    + "surgery_name VARCHAR(200) DEFAULT NULL COMMENT '关联手术名称',"
                    + "remark VARCHAR(500) DEFAULT NULL COMMENT '备注',"
                    + "status TINYINT DEFAULT 1 COMMENT '状态:1启用 0停用',"
                    + "create_by VARCHAR(64) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(64) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_org_level (tenant_id, org_id, tpl_level, status)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='手术费用模板'");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_surgery_fee_tpl_item ("
                    + "id BIGINT NOT NULL COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "tpl_id BIGINT NOT NULL COMMENT '模板ID(his_surgery_fee_tpl.id)',"
                    + "charge_item_id BIGINT DEFAULT NULL COMMENT '收费项目ID',"
                    + "item_name VARCHAR(200) NOT NULL COMMENT '项目名称',"
                    + "item_code VARCHAR(50) DEFAULT NULL COMMENT '项目编码',"
                    + "fee_category TINYINT DEFAULT 1 COMMENT '费用分类:1手术费 2麻醉费 3监测费 4耗材费 5药品费 6其他',"
                    + "quantity DECIMAL(10,2) DEFAULT 1 COMMENT '数量',"
                    + "unit_price DECIMAL(10,2) DEFAULT NULL COMMENT '单价',"
                    + "amount DECIMAL(12,2) DEFAULT NULL COMMENT '金额',"
                    + "create_by VARCHAR(64) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(64) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_tpl (tpl_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='手术费用模板明细'");
            /* 手术通知记录: 预约成功/安排变动/术前提醒, 短信无真实通道仅留痕模拟发送 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_surgery_notify ("
                    + "id BIGINT NOT NULL COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "apply_id BIGINT DEFAULT NULL COMMENT '申请单ID(可空)',"
                    + "surgery_id BIGINT DEFAULT NULL COMMENT '手术ID(可空)',"
                    + "patient_name VARCHAR(50) DEFAULT NULL COMMENT '患者姓名',"
                    + "phone VARCHAR(30) DEFAULT NULL COMMENT '联系电话',"
                    + "notify_type TINYINT NOT NULL COMMENT '通知类型:1预约成功 2安排变动 3术前提醒',"
                    + "channel TINYINT DEFAULT 1 COMMENT '渠道:1短信 2电话 3诊间',"
                    + "content VARCHAR(1000) DEFAULT NULL COMMENT '通知内容',"
                    + "status TINYINT DEFAULT 1 COMMENT '状态:1待通知 2已通知 3已回复',"
                    + "reply_content VARCHAR(500) DEFAULT NULL COMMENT '患者回复内容',"
                    + "send_by VARCHAR(64) DEFAULT NULL COMMENT '发送/处理人',"
                    + "send_time DATETIME DEFAULT NULL COMMENT '发送时间',"
                    + "create_by VARCHAR(64) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(64) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_org_status (tenant_id, org_id, status, notify_type),"
                    + "KEY idx_apply (apply_id),"
                    + "KEY idx_surgery (surgery_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='手术通知记录'");
            /* 存量表门诊化: inp_visit_id 放宽可空(MySQL MODIFY 幂等可重复执行) */
            st.executeUpdate("ALTER TABLE his_surgery MODIFY COLUMN inp_visit_id BIGINT NULL"
                    + " COMMENT '住院就诊ID(his_inp_visit.id, 门诊/日间手术为空)'");
            st.executeUpdate("ALTER TABLE his_surgery_fee MODIFY COLUMN inp_visit_id BIGINT NULL"
                    + " COMMENT '住院就诊ID(门诊/日间手术费用改双写 his_order_item)'");
        }
        /* his_surgery 补列: 来源申请单/就诊类型/门诊就诊/报到时间/时限/一体化模块类型 */
        addColumnIfNotExists(conn, "his_surgery", "apply_id", "BIGINT DEFAULT NULL COMMENT '手术申请单ID(his_surgery_apply.id)'");
        addColumnIfNotExists(conn, "his_surgery", "visit_type", "TINYINT DEFAULT 1 COMMENT '就诊类型:1住院 2门诊 3日间'");
        addColumnIfNotExists(conn, "his_surgery", "visit_id", "BIGINT DEFAULT NULL COMMENT '门诊就诊ID(his_visit.id, visit_type=2/3)'");
        addColumnIfNotExists(conn, "his_surgery", "register_time", "DATETIME DEFAULT NULL COMMENT '手术室报到登记时间'");
        addColumnIfNotExists(conn, "his_surgery", "deadline_type", "TINYINT DEFAULT 1 COMMENT '手术时限:1择期 2限期 3急诊'");
        addColumnIfNotExists(conn, "his_surgery", "module_type", "TINYINT DEFAULT 1 COMMENT '一体化模块(预留):1手术室 2DSA 3产科分娩 4内镜 5麻醉治疗'");
        /* his_surgery_fee 补列: 就诊类型/门诊就诊/门诊双写关联(退费定位用) */
        addColumnIfNotExists(conn, "his_surgery_fee", "visit_type", "TINYINT DEFAULT 1 COMMENT '就诊类型:1住院 2门诊 3日间'");
        addColumnIfNotExists(conn, "his_surgery_fee", "visit_id", "BIGINT DEFAULT NULL COMMENT '门诊就诊ID(his_visit.id)'");
        addColumnIfNotExists(conn, "his_surgery_fee", "order_id", "BIGINT DEFAULT NULL COMMENT '门诊双写单据ID(his_order.id)'");
        addColumnIfNotExists(conn, "his_surgery_fee", "order_item_id", "BIGINT DEFAULT NULL COMMENT '门诊双写明细ID(his_order_item.id)'");
        /* 手麻P1: his_inp_order 补列 手术医嘱关联/阶段/代开/发送药房闸门(幂等, 列已存在则跳过) */
        addColumnIfNotExists(conn, "his_inp_order", "surgery_id", "BIGINT DEFAULT NULL COMMENT '手术ID(his_surgery.id, 术中/术后医嘱)'");
        addColumnIfNotExists(conn, "his_inp_order", "surgery_apply_id", "BIGINT DEFAULT NULL COMMENT '手术申请单ID(his_surgery_apply.id, 申请阶段术前医嘱)'");
        addColumnIfNotExists(conn, "his_inp_order", "order_phase", "TINYINT DEFAULT NULL COMMENT '手术医嘱阶段:1术前 2术中 3术后(普通医嘱为空)'");
        addColumnIfNotExists(conn, "his_inp_order", "proxy_doctor_id", "BIGINT DEFAULT NULL COMMENT '代开目标医生ID(his_staff.id, 权限按其口径校验; 实际开单人记 create_by)'");
        addColumnIfNotExists(conn, "his_inp_order", "proxy_reason", "VARCHAR(255) DEFAULT NULL COMMENT '代开原因留痕'");
        addColumnIfNotExists(conn, "his_inp_order", "send_pharm_status", "TINYINT DEFAULT NULL COMMENT '手术类药品医嘱发送药房闸门:0未发送 1已发送 2已撤回(普通医嘱为空自动入队)'");
    }

    /**
     * 手麻 P2 幂等迁移:
     * (P2b) his_order_template 补 apply_scene/surgery_phase 两列, 承接手术医嘱模板(不新建表);
     * (P2c) his_surgery_notify 补 retry_count/gateway_msg_id 两列, 承接多渠道下发与真实网关回执;
     * (P2d) 新建 his_newborn 新生儿建档表(产科分娩一体化)。
     * 依赖 his_order_template(ensureInpatientEnhancementTables) 与 his_surgery_notify(ensureSurgeryApplyTables) 已建,
     * addColumnIfNotExists 内部 tableExists 兜底, 故必须在两者之后调用。
     */
    private void ensureSurgeryP2Tables(Connection conn) throws Exception {
        /* P2b: 医嘱模板手术场景扩展(apply_scene=2 手术医嘱模板, surgery_phase 目标阶段) */
        addColumnIfNotExists(conn, "his_order_template", "apply_scene",
                "TINYINT DEFAULT 1 COMMENT '适用场景:1普通住院 2手术医嘱(P2)'");
        addColumnIfNotExists(conn, "his_order_template", "surgery_phase",
                "TINYINT DEFAULT NULL COMMENT '手术模板目标阶段:1术前 2术中 3术后(apply_scene=2 时有值)'");
        /* P2c: 通知渠道下发扩展(重试次数 + 真实网关回执ID), channel 语义扩为 1短信 2电话 3诊间 4自助机 5APP 6公众号 */
        addColumnIfNotExists(conn, "his_surgery_notify", "retry_count",
                "INT DEFAULT 0 COMMENT '通知下发重试次数'");
        addColumnIfNotExists(conn, "his_surgery_notify", "gateway_msg_id",
                "VARCHAR(120) DEFAULT NULL COMMENT '短信/APP网关回执ID(真实通道回填)'");
        /* P2d: 新生儿建档表 */
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_newborn ("
                    + "id BIGINT NOT NULL COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "mother_inp_visit_id BIGINT NOT NULL COMMENT '母亲住院就诊ID(his_inp_visit.id)',"
                    + "surgery_id BIGINT DEFAULT NULL COMMENT '分娩手术ID(his_surgery.id, 剖宫产可关联)',"
                    + "baby_patient_id BIGINT DEFAULT NULL COMMENT '新生儿建档患者ID(his_patient.id)',"
                    + "baby_inp_visit_id BIGINT DEFAULT NULL COMMENT '新生儿住院就诊ID(his_inp_visit.id)',"
                    + "baby_name VARCHAR(50) DEFAULT NULL COMMENT '新生儿姓名',"
                    + "baby_sex TINYINT DEFAULT NULL COMMENT '性别:1男 2女',"
                    + "birth_time DATETIME DEFAULT NULL COMMENT '出生时间',"
                    + "apgar_1 INT DEFAULT NULL COMMENT 'Apgar 1分钟评分',"
                    + "apgar_5 INT DEFAULT NULL COMMENT 'Apgar 5分钟评分',"
                    + "apgar_10 INT DEFAULT NULL COMMENT 'Apgar 10分钟评分',"
                    + "weight_g INT DEFAULT NULL COMMENT '出生体重(克)',"
                    + "height_cm DECIMAL(5,1) DEFAULT NULL COMMENT '身长(厘米)',"
                    + "birth_type TINYINT DEFAULT 1 COMMENT '分娩方式:1顺产 2剖宫产 3产钳 4臀助 5其他',"
                    + "status TINYINT DEFAULT 1 COMMENT '状态:1在绑 2已转科 3已出院',"
                    + "remark VARCHAR(500) DEFAULT NULL COMMENT '备注',"
                    + "create_by VARCHAR(64) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(64) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_mother (mother_inp_visit_id),"
                    + "KEY idx_surgery (surgery_id),"
                    + "KEY idx_baby_visit (baby_inp_visit_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='新生儿建档(P2d 产科分娩一体化)'");
        }
    }

    /**
     * 幂等建表: 住院模型增强(15 张新表, 表已存在则跳过) + 10 张存量表扩展列(列已存在则跳过)。
     * 业务规则增强: 过敏记录/知情同意/会诊/转科转床申请/费用预警/医嘱模板套餐;
     * 护理评估量表: 量表定义→计划模板→计划实例三层;
     * 电子病历: 结构化模板/质控规则/宏变量;
     * 报表打印: 每日费用清单/打印模板/报表快照。
     * 存量表补列: his_inp_visit(+15) his_bed(+3) his_inp_order(+5) his_inp_charge_detail(+4)
     * his_inp_medical_record(+7) his_inp_nursing_record(+4) his_inp_diagnosis(+2) his_inp_settle(+3)
     * his_surgery(+3) his_inp_shift_record(+2)。
     */
    private void ensureInpatientEnhancementTables(Connection conn) throws Exception {
        /* ---------- 存量表扩展列(10 张 48 列, 列已存在则跳过) ---------- */
        /* his_inp_visit +15: 联系人/担保人/血型/入院来源/预计出院/预交金预警线/隔离/护理等级/饮食/病情等级/DRG */
        addColumnIfNotExists(conn, "his_inp_visit", "contact_name", "VARCHAR(50) DEFAULT NULL COMMENT '联系人姓名'");
        addColumnIfNotExists(conn, "his_inp_visit", "contact_phone", "VARCHAR(20) DEFAULT NULL COMMENT '联系人电话'");
        addColumnIfNotExists(conn, "his_inp_visit", "contact_relation", "VARCHAR(20) DEFAULT NULL COMMENT '联系人关系'");
        addColumnIfNotExists(conn, "his_inp_visit", "guarantor_name", "VARCHAR(50) DEFAULT NULL COMMENT '担保人姓名'");
        addColumnIfNotExists(conn, "his_inp_visit", "guarantor_phone", "VARCHAR(20) DEFAULT NULL COMMENT '担保人电话'");
        addColumnIfNotExists(conn, "his_inp_visit", "guarantor_id_no", "VARCHAR(30) DEFAULT NULL COMMENT '担保人身份证号'");
        addColumnIfNotExists(conn, "his_inp_visit", "blood_type", "VARCHAR(10) DEFAULT NULL COMMENT '血型'");
        addColumnIfNotExists(conn, "his_inp_visit", "admit_source", "INT DEFAULT NULL COMMENT '入院来源:1门诊 2急诊 3转诊 4其他'");
        addColumnIfNotExists(conn, "his_inp_visit", "expected_discharge_date", "DATE DEFAULT NULL COMMENT '预计出院日期'");
        addColumnIfNotExists(conn, "his_inp_visit", "deposit_warning_amount", "DECIMAL(12,2) DEFAULT NULL COMMENT '预交金预警线'");
        addColumnIfNotExists(conn, "his_inp_visit", "is_quarantine", "TINYINT DEFAULT 0 COMMENT '是否隔离:1是 0否'");
        addColumnIfNotExists(conn, "his_inp_visit", "nursing_level", "INT DEFAULT NULL COMMENT '护理等级:1特级 2一级 3二级 4三级'");
        addColumnIfNotExists(conn, "his_inp_visit", "diet_type", "VARCHAR(50) DEFAULT NULL COMMENT '饮食类型'");
        addColumnIfNotExists(conn, "his_inp_visit", "condition_level", "INT DEFAULT NULL COMMENT '病情等级:1危 2重 3一般'");
        addColumnIfNotExists(conn, "his_inp_visit", "drg_group_code", "VARCHAR(30) DEFAULT NULL COMMENT 'DRG分组编码'");
        addColumnIfNotExists(conn, "his_inp_visit", "admission_cert_id", "BIGINT DEFAULT NULL COMMENT '来源住院证ID(his_admission_cert.id, 持证入院溯源)'");
        /* his_admission_cert +1: 持证入院消费回写(证->住院就诊溯源) */
        addColumnIfNotExists(conn, "his_admission_cert", "admitted_visit_id", "BIGINT DEFAULT NULL COMMENT '消费本证的住院就诊ID(his_inp_visit.id, 已入院时回写)'");
        /* his_bed +3: 床位等级/加床/性别限制 */
        addColumnIfNotExists(conn, "his_bed", "bed_level", "INT DEFAULT 1 COMMENT '床位等级:1普通 2单间 3监护 4特需'");
        addColumnIfNotExists(conn, "his_bed", "is_extra_bed", "TINYINT DEFAULT 0 COMMENT '是否加床:1是 0否'");
        addColumnIfNotExists(conn, "his_bed", "gender_limit", "INT DEFAULT 0 COMMENT '性别限制:0无 1男 2女'");
        /* his_inp_order +5: 医嘱模板/套餐溯源 + 高警示/双人核对/合理用药审查 */
        addColumnIfNotExists(conn, "his_inp_order", "order_template_id", "BIGINT DEFAULT NULL COMMENT '来源医嘱模板ID(his_order_template.id)'");
        addColumnIfNotExists(conn, "his_inp_order", "order_set_id", "BIGINT DEFAULT NULL COMMENT '来源医嘱套餐ID(his_order_template.id, 套餐型)'");
        addColumnIfNotExists(conn, "his_inp_order", "high_alert_flag", "TINYINT DEFAULT 0 COMMENT '高警示药品:1是 0否'");
        addColumnIfNotExists(conn, "his_inp_order", "double_check_flag", "TINYINT DEFAULT 0 COMMENT '需双人核对:1是 0否'");
        addColumnIfNotExists(conn, "his_inp_order", "rational_check_result", "TEXT NULL COMMENT '合理用药审查结果JSON'");
        /* his_inp_charge_detail +4: 限额管控与超标审批 */
        addColumnIfNotExists(conn, "his_inp_charge_detail", "daily_limit", "DECIMAL(12,2) DEFAULT NULL COMMENT '日限额'");
        addColumnIfNotExists(conn, "his_inp_charge_detail", "total_limit", "DECIMAL(12,2) DEFAULT NULL COMMENT '总限额'");
        addColumnIfNotExists(conn, "his_inp_charge_detail", "approval_status", "INT DEFAULT 1 COMMENT '审核状态:1待审 2通过 3拒绝'");
        addColumnIfNotExists(conn, "his_inp_charge_detail", "limit_override_reason", "VARCHAR(200) DEFAULT NULL COMMENT '超标原因'");
        /* his_inp_medical_record +7: 结构化模板/时限质控/上级医师查房 */
        addColumnIfNotExists(conn, "his_inp_medical_record", "template_id", "BIGINT DEFAULT NULL COMMENT '结构化模板ID(his_emr_template.id)'");
        addColumnIfNotExists(conn, "his_inp_medical_record", "structure_data", "TEXT NULL COMMENT '结构化数据JSON'");
        addColumnIfNotExists(conn, "his_inp_medical_record", "deadline_time", "DATETIME DEFAULT NULL COMMENT '书写截止时间(时限性质控)'");
        addColumnIfNotExists(conn, "his_inp_medical_record", "quality_score", "DECIMAL(5,2) DEFAULT NULL COMMENT '质控评分'");
        addColumnIfNotExists(conn, "his_inp_medical_record", "quality_detail", "TEXT NULL COMMENT '质控明细JSON'");
        addColumnIfNotExists(conn, "his_inp_medical_record", "attending_doctor_id", "BIGINT DEFAULT NULL COMMENT '上级医师ID(his_staff.id)'");
        addColumnIfNotExists(conn, "his_inp_medical_record", "round_level", "INT DEFAULT NULL COMMENT '查房级别:1住院医师 2主治 3主任'");
        /* his_inp_nursing_record +4: 量表评估结构化 */
        addColumnIfNotExists(conn, "his_inp_nursing_record", "scale_code", "VARCHAR(30) DEFAULT NULL COMMENT '量表编码(his_nursing_scale_def.scale_code)'");
        addColumnIfNotExists(conn, "his_inp_nursing_record", "scale_score", "DECIMAL(5,1) DEFAULT NULL COMMENT '量表评分'");
        addColumnIfNotExists(conn, "his_inp_nursing_record", "scale_detail", "TEXT NULL COMMENT '量表明细JSON'");
        addColumnIfNotExists(conn, "his_inp_nursing_record", "plan_template_id", "BIGINT DEFAULT NULL COMMENT '护理计划模板ID(his_nursing_plan_template.id)'");
        /* his_inp_diagnosis +2: 入院病情与并发症(病案首页口径) */
        addColumnIfNotExists(conn, "his_inp_diagnosis", "admit_condition", "INT DEFAULT NULL COMMENT '入院病情:1危急 2严重 3一般 4不适用'");
        addColumnIfNotExists(conn, "his_inp_diagnosis", "complication_flag", "TINYINT DEFAULT 0 COMMENT '并发症标志:1是 0否'");
        /* his_inp_settle +3: DRG/DIP 分组与支付方式 */
        addColumnIfNotExists(conn, "his_inp_settle", "drg_group_code", "VARCHAR(30) DEFAULT NULL COMMENT 'DRG分组编码'");
        addColumnIfNotExists(conn, "his_inp_settle", "dip_code", "VARCHAR(30) DEFAULT NULL COMMENT 'DIP病种编码'");
        addColumnIfNotExists(conn, "his_inp_settle", "pay_method", "INT DEFAULT NULL COMMENT '支付方式'");
        /* his_surgery +3: 手术审批与WHO安全核查 */
        addColumnIfNotExists(conn, "his_surgery", "approval_status", "INT DEFAULT 0 COMMENT '审批状态:0无需 1待审 2通过 3拒绝'");
        addColumnIfNotExists(conn, "his_surgery", "approval_doctor_id", "BIGINT DEFAULT NULL COMMENT '审批医师ID(his_staff.id)'");
        addColumnIfNotExists(conn, "his_surgery", "safety_checklist", "TEXT NULL COMMENT 'WHO手术安全核查JSON'");
        /* his_inp_shift_record +2: SBAR 结构化交班 */
        addColumnIfNotExists(conn, "his_inp_shift_record", "sbar_situation", "TEXT NULL COMMENT 'SBAR-情景'");
        addColumnIfNotExists(conn, "his_inp_shift_record", "sbar_background", "TEXT NULL COMMENT 'SBAR-背景'");
        try (Statement st = conn.createStatement()) {
            /* ---------- 业务规则增强 6 张表 ---------- */
            /* 过敏记录: 住院就诊级过敏登记(药物/食物/环境/其他), 驱动开嘱过敏拦截与腕带提示 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_inp_allergy ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "inp_visit_id BIGINT NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',"
                    + "patient_id BIGINT NOT NULL COMMENT '患者ID(his_patient.id)',"
                    + "allergy_type INT NOT NULL COMMENT '过敏类型:1药物 2食物 3环境 4其他',"
                    + "allergen_name VARCHAR(100) NOT NULL COMMENT '过敏原名称',"
                    + "allergen_code VARCHAR(50) DEFAULT NULL COMMENT '过敏原编码',"
                    + "severity INT DEFAULT NULL COMMENT '严重程度:1轻 2中 3重',"
                    + "reaction_desc VARCHAR(500) DEFAULT NULL COMMENT '过敏反应描述',"
                    + "record_time DATETIME DEFAULT NULL COMMENT '记录时间',"
                    + "doctor_id BIGINT DEFAULT NULL COMMENT '记录医生ID(his_staff.id)',"
                    + "status INT DEFAULT 1 COMMENT '状态:1有效 0已失效',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_allergy_visit (inp_visit_id),"
                    + "KEY idx_allergy_patient (patient_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='住院患者过敏记录'");
            /* 知情同意书: 手术/麻醉/输血/特殊检查/特殊治疗/自费/病危七类, 患者-医师-见证人三签闭环 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_inp_informed_consent ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "inp_visit_id BIGINT NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',"
                    + "consent_type INT NOT NULL COMMENT '同意书类型:1手术 2麻醉 3输血 4特殊检查 5特殊治疗 6自费 7病危',"
                    + "title VARCHAR(200) NOT NULL COMMENT '同意书标题',"
                    + "template_id BIGINT DEFAULT NULL COMMENT '打印模板ID(his_print_template.id)',"
                    + "content TEXT NULL COMMENT '同意书内容JSON',"
                    + "patient_sign_time DATETIME DEFAULT NULL COMMENT '患者/家属签字时间',"
                    + "doctor_sign_time DATETIME DEFAULT NULL COMMENT '医师签字时间',"
                    + "witness_sign_time DATETIME DEFAULT NULL COMMENT '见证人签字时间',"
                    + "doctor_id BIGINT DEFAULT NULL COMMENT '谈话医师ID(his_staff.id)',"
                    + "status INT DEFAULT 1 COMMENT '状态:1待签 2已签 3已撤销',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_consent_visit (inp_visit_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='住院知情同意书'");
            /* 会诊记录: 普通/急会诊/MDT三类, 申请→受理→完成/拒绝/取消状态机 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_inp_consultation ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "inp_visit_id BIGINT NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',"
                    + "consult_type INT NOT NULL COMMENT '会诊类型:1普通 2急会诊 3MDT',"
                    + "apply_dept_id BIGINT DEFAULT NULL COMMENT '申请科室ID(his_dept.id)',"
                    + "apply_doctor_id BIGINT DEFAULT NULL COMMENT '申请医师ID(his_staff.id)',"
                    + "target_dept_id BIGINT DEFAULT NULL COMMENT '受邀科室ID(his_dept.id)',"
                    + "target_doctor_id BIGINT DEFAULT NULL COMMENT '受邀医师ID(his_staff.id)',"
                    + "apply_reason TEXT NULL COMMENT '申请理由',"
                    + "consult_opinion TEXT NULL COMMENT '会诊意见',"
                    + "apply_time DATETIME DEFAULT NULL COMMENT '申请时间',"
                    + "response_time DATETIME DEFAULT NULL COMMENT '受理时间',"
                    + "consult_time DATETIME DEFAULT NULL COMMENT '会诊完成时间',"
                    + "status INT DEFAULT 1 COMMENT '状态:1申请 2受理 3完成 4拒绝 5取消',"
                    + "urgency_level INT DEFAULT 1 COMMENT '紧急程度:1普通 2急 3特急',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_consult_visit (inp_visit_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='住院会诊记录'");
            /* 转科转床申请: 转科/转床/加床三类, 申请→批准→执行闭环(申请审批留痕, 区别于旧即时转科链路) */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_inp_transfer ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "inp_visit_id BIGINT NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',"
                    + "transfer_type INT NOT NULL COMMENT '申请类型:1转科 2转床 3加床',"
                    + "from_ward_id BIGINT DEFAULT NULL COMMENT '原病区ID(his_ward.id)',"
                    + "from_bed_id BIGINT DEFAULT NULL COMMENT '原床位ID(his_bed.id)',"
                    + "from_dept_id BIGINT DEFAULT NULL COMMENT '原科室ID(his_dept.id)',"
                    + "to_ward_id BIGINT DEFAULT NULL COMMENT '目标病区ID(his_ward.id)',"
                    + "to_bed_id BIGINT DEFAULT NULL COMMENT '目标床位ID(his_bed.id)',"
                    + "to_dept_id BIGINT DEFAULT NULL COMMENT '目标科室ID(his_dept.id)',"
                    + "reason VARCHAR(500) DEFAULT NULL COMMENT '申请原因',"
                    + "apply_doctor_id BIGINT DEFAULT NULL COMMENT '申请医生ID(his_staff.id)',"
                    + "approve_doctor_id BIGINT DEFAULT NULL COMMENT '审批医生ID(his_staff.id)',"
                    + "apply_time DATETIME DEFAULT NULL COMMENT '申请时间',"
                    + "approve_time DATETIME DEFAULT NULL COMMENT '审批时间',"
                    + "status INT DEFAULT 1 COMMENT '状态:1申请 2批准 3拒绝 4已执行 5取消',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_transfer_visit (inp_visit_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='住院转科转床申请'");
            /* 费用预警: 日限额/总限额/预交金不足/大额费用四类, 放行/拦截处置留痕 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_inp_fee_alert ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "inp_visit_id BIGINT NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',"
                    + "alert_type INT NOT NULL COMMENT '预警类型:1日限额 2总限额 3预交金不足 4大额费用',"
                    + "charge_detail_id BIGINT DEFAULT NULL COMMENT '触发费用明细ID(his_inp_charge_detail.id)',"
                    + "alert_amount DECIMAL(12,2) DEFAULT NULL COMMENT '预警时金额',"
                    + "threshold_amount DECIMAL(12,2) DEFAULT NULL COMMENT '阈值金额',"
                    + "handler_id BIGINT DEFAULT NULL COMMENT '处理人ID(his_staff.id)',"
                    + "handle_time DATETIME DEFAULT NULL COMMENT '处理时间',"
                    + "handle_result INT DEFAULT NULL COMMENT '处理结果:1放行 2拦截',"
                    + "override_reason VARCHAR(500) DEFAULT NULL COMMENT '放行/超标原因',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_fee_alert_visit (inp_visit_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='住院费用预警记录'");
            /* 医嘱模板/套餐: 个人/科室/全院三级, 单条/套餐两类, items 为医嘱项 JSON 数组 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_order_template ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "template_name VARCHAR(100) NOT NULL COMMENT '模板名称',"
                    + "template_type INT DEFAULT 1 COMMENT '模板级别:1个人 2科室 3全院',"
                    + "scope_type INT DEFAULT 1 COMMENT '范围类型:1单条 2套餐',"
                    + "dept_id BIGINT DEFAULT NULL COMMENT '科室ID(his_dept.id, 科室级模板)',"
                    + "doctor_id BIGINT DEFAULT NULL COMMENT '医生ID(his_staff.id, 个人级模板)',"
                    + "items TEXT NULL COMMENT '医嘱项JSON数组',"
                    + "disease_code VARCHAR(30) DEFAULT NULL COMMENT '适用病种编码',"
                    + "usage_count INT DEFAULT 0 COMMENT '使用次数',"
                    + "status INT DEFAULT 1 COMMENT '状态:1启用 0停用',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_order_tpl_type (template_type, scope_type)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='住院医嘱模板/套餐'");
        }
        try (Statement st = conn.createStatement()) {
            /* ---------- 护理评估量表 3 张表 ---------- */
            /* 量表定义: 维度定义与分数→风险映射 JSON, 按频次驱动评估任务(量表编码全局唯一) */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_nursing_scale_def ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "scale_code VARCHAR(30) NOT NULL COMMENT '量表编码',"
                    + "scale_name VARCHAR(100) NOT NULL COMMENT '量表名称',"
                    + "scale_type INT DEFAULT NULL COMMENT '量表类型:1入院评估 2专科 3风险',"
                    + "dimensions TEXT NULL COMMENT '维度定义JSON',"
                    + "score_interpretation TEXT NULL COMMENT '分数→风险映射JSON',"
                    + "required_frequency VARCHAR(200) DEFAULT NULL COMMENT '必评频次说明',"
                    + "status INT DEFAULT 1 COMMENT '状态:1启用 0停用',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_scale_code (scale_code, deleted)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='护理评估量表定义'");
            /* 护理计划模板: 按量表分值区间触发, 护理诊断/目标/措施/评价标准结构化 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_nursing_plan_template ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "plan_name VARCHAR(100) NOT NULL COMMENT '计划名称',"
                    + "trigger_scale_code VARCHAR(30) DEFAULT NULL COMMENT '触发量表编码(his_nursing_scale_def.scale_code)',"
                    + "trigger_score_range VARCHAR(50) DEFAULT NULL COMMENT '触发分值区间',"
                    + "nursing_diagnosis VARCHAR(500) DEFAULT NULL COMMENT '护理诊断',"
                    + "nursing_goal VARCHAR(500) DEFAULT NULL COMMENT '护理目标',"
                    + "interventions TEXT NULL COMMENT '护理措施JSON数组',"
                    + "evaluation_criteria VARCHAR(500) DEFAULT NULL COMMENT '评价标准',"
                    + "dept_id BIGINT DEFAULT NULL COMMENT '科室ID(his_dept.id)',"
                    + "status INT DEFAULT 1 COMMENT '状态:1启用 0停用',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='护理计划模板'");
            /* 护理计划实例: 患者级计划执行与评价, 计划/实际措施双留痕 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_nursing_plan_instance ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "inp_visit_id BIGINT NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',"
                    + "template_id BIGINT DEFAULT NULL COMMENT '护理计划模板ID(his_nursing_plan_template.id)',"
                    + "scale_record_id BIGINT DEFAULT NULL COMMENT '触发量表记录ID(his_inp_nursing_record.id)',"
                    + "nursing_diagnosis VARCHAR(500) DEFAULT NULL COMMENT '护理诊断',"
                    + "nursing_goal VARCHAR(500) DEFAULT NULL COMMENT '护理目标',"
                    + "planned_interventions TEXT NULL COMMENT '计划措施JSON',"
                    + "actual_interventions TEXT NULL COMMENT '实际措施JSON',"
                    + "start_time DATETIME DEFAULT NULL COMMENT '开始时间',"
                    + "evaluation_time DATETIME DEFAULT NULL COMMENT '评价时间',"
                    + "evaluation_result VARCHAR(500) DEFAULT NULL COMMENT '评价结果',"
                    + "status INT DEFAULT 1 COMMENT '状态:1执行中 2已评价 3已关闭',"
                    + "nurse_id BIGINT DEFAULT NULL COMMENT '责任护士ID(his_staff.id)',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_plan_inst_visit (inp_visit_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='护理计划实例'");
        }
        try (Statement st = conn.createStatement()) {
            /* ---------- 电子病历 3 张表 ---------- */
            /* 病历结构化模板: 九类文书字段定义 JSON, dept_id=0 表全院通用(模板编码全局唯一) */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_emr_template ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "template_code VARCHAR(50) NOT NULL COMMENT '模板编码',"
                    + "template_name VARCHAR(100) NOT NULL COMMENT '模板名称',"
                    + "record_type INT DEFAULT NULL COMMENT '记录类型(对应his_inp_medical_record.record_type的9种类型)',"
                    + "template_category INT DEFAULT NULL COMMENT '模板类别:1入院记录 2首次病程 3日常病程 4上级查房 5手术记录 6术后病程 7出院小结 8死亡记录 9病危通知',"
                    + "fields TEXT NULL COMMENT '字段定义JSON',"
                    + "scope TINYINT DEFAULT 1 COMMENT '适用范围:1住院 2门诊',"
                    + "layout TEXT NULL COMMENT '布局定义JSON(分节/栅格, 设计器产出)',"
                    + "staff_id BIGINT DEFAULT NULL COMMENT '个人模板归属职工ID(his_staff.id, null=科室/全院)',"
                    + "dept_id BIGINT DEFAULT 0 COMMENT '科室ID(his_dept.id, 0=全院)',"
                    + "version INT DEFAULT 1 COMMENT '版本号',"
                    + "status INT DEFAULT 1 COMMENT '状态:1启用 0停用',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_emr_tpl_code (template_code, deleted)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='病历结构化模板'");
            /* 病历质控规则: 完整性/时限性/逻辑性/规范性四类, 扣分+严重度分级 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_emr_quality_rule ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "rule_code VARCHAR(50) NOT NULL COMMENT '规则编码',"
                    + "rule_name VARCHAR(100) NOT NULL COMMENT '规则名称',"
                    + "record_type INT DEFAULT NULL COMMENT '适用记录类型(his_inp_medical_record.record_type)',"
                    + "rule_type INT DEFAULT NULL COMMENT '规则类型:1完整性 2时限性 3逻辑性 4规范性',"
                    + "rule_config TEXT NULL COMMENT '检查条件JSON',"
                    + "deduct_score DECIMAL(3,1) DEFAULT NULL COMMENT '扣分分值',"
                    + "severity INT DEFAULT NULL COMMENT '严重程度:1警告 2扣分 3一票否决',"
                    + "description VARCHAR(500) DEFAULT NULL COMMENT '规则说明',"
                    + "status INT DEFAULT 1 COMMENT '状态:1启用 0停用',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='病历质控规则'");
            /* 病历宏变量: 患者/就诊/诊断/医嘱/检验/体征六源取值, 书写时自动替换占位符(宏编码全局唯一) */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_emr_macro ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "macro_code VARCHAR(50) NOT NULL COMMENT '宏变量编码',"
                    + "macro_name VARCHAR(100) NOT NULL COMMENT '宏变量名称',"
                    + "data_source INT DEFAULT NULL COMMENT '数据来源:1患者 2就诊 3诊断 4医嘱 5检验 6体征',"
                    + "source_field VARCHAR(100) DEFAULT NULL COMMENT '来源字段',"
                    + "format_pattern VARCHAR(200) DEFAULT NULL COMMENT '格式化模式',"
                    + "description VARCHAR(500) DEFAULT NULL COMMENT '说明',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_macro_code (macro_code, deleted)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='病历宏变量定义'");
            /* 病历数据元(要素): 结构化 structure 按模板 fields 抽取为字段级可检索/可统计记录, 支撑二次利用与上报; 同步按(scope,visit/record)先删后插幂等, table/array 以 sort_no 保留多值序 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_emr_element ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "scope TINYINT DEFAULT 1 COMMENT '适用范围:1住院 2门诊',"
                    + "record_id BIGINT DEFAULT NULL COMMENT '病历记录ID(住院his_inp_medical_record.id;门诊可空)',"
                    + "visit_id BIGINT DEFAULT NULL COMMENT '就诊ID(住院his_inp_visit.id/门诊his_visit.id)',"
                    + "patient_id BIGINT DEFAULT NULL COMMENT '患者ID',"
                    + "dept_id BIGINT DEFAULT NULL COMMENT '科室ID(his_dept.id)',"
                    + "doctor_id BIGINT DEFAULT NULL COMMENT '医生/书写人ID(his_staff.id)',"
                    + "record_type INT DEFAULT NULL COMMENT '记录类型(对应record_type/template_category)',"
                    + "template_id BIGINT DEFAULT NULL COMMENT '来源模板ID(his_emr_template.id)',"
                    + "field_key VARCHAR(100) NOT NULL COMMENT '字段键(structure中的fieldKey)',"
                    + "field_label VARCHAR(100) DEFAULT NULL COMMENT '字段名称(冗余便于展示/导出)',"
                    + "term_code VARCHAR(50) DEFAULT NULL COMMENT '术语/值域编码(select/dict命中时)',"
                    + "dict_source VARCHAR(50) DEFAULT NULL COMMENT '字典来源标识',"
                    + "value_text VARCHAR(2000) DEFAULT NULL COMMENT '文本值',"
                    + "value_num DECIMAL(18,4) DEFAULT NULL COMMENT '数值值',"
                    + "value_date DATETIME DEFAULT NULL COMMENT '日期/时间值',"
                    + "value_unit VARCHAR(30) DEFAULT NULL COMMENT '单位(数值/体征)',"
                    + "sort_no INT DEFAULT 0 COMMENT '同字段多值序号(table/array元素序)',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_elem_scope_visit (scope, visit_id),"
                    + "KEY idx_elem_record (scope, record_id),"
                    + "KEY idx_elem_patient (patient_id),"
                    + "KEY idx_elem_field_text (field_key, value_text(64)),"
                    + "KEY idx_elem_field_num (field_key, value_num),"
                    + "KEY idx_elem_dept (dept_id),"
                    + "KEY idx_elem_doctor (doctor_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='病历数据元(要素)'");
            /* 病历可靠电子签名(Phase D SM2): 对 content+structure 摘要签名, 支持三级/门诊多环节签名链 + 验签可对抗篡改; 重签置旧行 valid=0 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_emr_signature ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "scope TINYINT DEFAULT 1 COMMENT '适用范围:1住院 2门诊',"
                    + "record_id BIGINT DEFAULT NULL COMMENT '住院病历ID(his_inp_medical_record.id)',"
                    + "visit_id BIGINT DEFAULT NULL COMMENT '就诊ID(住院his_inp_visit/门诊his_visit)',"
                    + "patient_id BIGINT DEFAULT NULL COMMENT '患者ID',"
                    + "stage VARCHAR(30) DEFAULT NULL COMMENT '签名环节:author/resident/attending/director(住院) doctor(门诊)',"
                    + "signer_id BIGINT DEFAULT NULL COMMENT '签名人(his_staff.id)',"
                    + "signer_name VARCHAR(50) DEFAULT NULL COMMENT '签名人姓名',"
                    + "digest VARCHAR(128) DEFAULT NULL COMMENT 'SM3摘要hex(content+structure规范化)',"
                    + "sig_value TEXT COMMENT 'SM2签名值(hex)',"
                    + "cert_sn VARCHAR(100) DEFAULT NULL COMMENT '证书/签章编号(sys_org.sign_no占位)',"
                    + "provider VARCHAR(30) DEFAULT NULL COMMENT '签名提供者:sm2/ca/tsa',"
                    + "sign_img VARCHAR(500) DEFAULT NULL COMMENT '签名图URL',"
                    + "sign_time DATETIME DEFAULT NULL COMMENT '签名时间',"
                    + "valid TINYINT DEFAULT 1 COMMENT '是否当前有效:1有效 0已被重签取代',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_sig_scope_record (scope, record_id),"
                    + "KEY idx_sig_scope_visit (scope, visit_id),"
                    + "KEY idx_sig_signer (signer_id),"
                    + "KEY idx_sig_patient (patient_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='病历可靠电子签名(SM2)'");
            /* 存量库补列: his_emr_template 设计器扩展列(新库 CREATE 已含, 旧库幂等补) */
        }
        addColumnIfNotExists(conn, "his_emr_template", "scope", "TINYINT DEFAULT 1 COMMENT '适用范围:1住院 2门诊'");
        addColumnIfNotExists(conn, "his_emr_template", "layout", "TEXT NULL COMMENT '布局定义JSON(分节/栅格, 设计器产出)'");
        addColumnIfNotExists(conn, "his_emr_template", "staff_id", "BIGINT DEFAULT NULL COMMENT '个人模板归属职工ID(his_staff.id, null=科室/全院)'");
        try (Statement st = conn.createStatement()) {
            /* ---------- 报表与打印 3 张表 ---------- */
            /* 每日费用清单: 就诊×日期一份(软删不参与唯一碰撞), 费用项 JSON + 当日/累计/预交金余额三金额, 打印留痕 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_inp_daily_bill ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "inp_visit_id BIGINT NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',"
                    + "bill_date DATE NOT NULL COMMENT '清单日期',"
                    + "items TEXT NULL COMMENT '费用项JSON数组',"
                    + "total_amount DECIMAL(12,2) DEFAULT NULL COMMENT '当日费用合计',"
                    + "cumulative_amount DECIMAL(12,2) DEFAULT NULL COMMENT '在院累计费用',"
                    + "deposit_balance DECIMAL(12,2) DEFAULT NULL COMMENT '预交金余额',"
                    + "generated_time DATETIME DEFAULT NULL COMMENT '生成时间',"
                    + "printed_flag TINYINT DEFAULT 0 COMMENT '是否已打印:1是 0否',"
                    + "print_time DATETIME DEFAULT NULL COMMENT '打印时间',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_daily_bill (inp_visit_id, bill_date, deleted),"
                    + "KEY idx_daily_bill_date (bill_date)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='住院每日费用清单'");
            /* 打印模板: HTML模板+页眉页脚+CSS, 支持纸张/方向, 版本化维护(模板编码全局唯一) */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_print_template ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "template_code VARCHAR(50) NOT NULL COMMENT '模板编码',"
                    + "template_name VARCHAR(100) NOT NULL COMMENT '模板名称',"
                    + "template_type INT DEFAULT NULL COMMENT '模板类型:1日清单 2结算单 3医嘱单 4护理记录单 5体温单 6病历 7腕带 8知情同意书',"
                    + "paper_size VARCHAR(20) DEFAULT 'A4' COMMENT '纸张尺寸',"
                    + "orientation VARCHAR(20) DEFAULT 'portrait' COMMENT '方向:portrait纵向 landscape横向',"
                    + "template_content TEXT NULL COMMENT 'HTML模板内容',"
                    + "header_html TEXT NULL COMMENT '页眉HTML',"
                    + "footer_html TEXT NULL COMMENT '页脚HTML',"
                    + "css_style TEXT NULL COMMENT 'CSS样式',"
                    + "version INT DEFAULT 1 COMMENT '版本号',"
                    + "status INT DEFAULT 1 COMMENT '状态:1启用 0停用',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_print_tpl_code (template_code, deleted)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='打印模板'");
            /* 报表快照: 床位/费用/科室/住院日/DRG五类报表按日落盘, data 为 JSON */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_inp_report_snapshot ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "report_type INT NOT NULL COMMENT '报表类型:1床位 2费用 3科室 4住院日 5DRG',"
                    + "report_date DATE NOT NULL COMMENT '报表日期',"
                    + "dept_id BIGINT DEFAULT NULL COMMENT '科室ID(his_dept.id)',"
                    + "ward_id BIGINT DEFAULT NULL COMMENT '病区ID(his_ward.id)',"
                    + "data TEXT NULL COMMENT '报表数据JSON',"
                    + "generated_time DATETIME DEFAULT NULL COMMENT '生成时间',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_report_type_date (report_type, report_date)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='住院报表快照'");
        }
    }

    /**
     * 幂等建表: UI 升级数据层(1 张新表, 表已存在则跳过)。
     * 住院通知: user_id 级站内通知(类型: 1医嘱/2会诊/3病历/4预警/5评估/6系统), 支撑未读角标分组统计与消息中心分页。
     * 索引: (user_id, is_read, deleted) 支撑未读角标; (notify_type, deleted) 支撑类型维度检索。
     */
    private void ensureUIEnhancementTables(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_inp_notification ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL DEFAULT 0 COMMENT '机构ID',"
                    + "user_id BIGINT NOT NULL COMMENT '接收用户ID(sys_user.id)',"
                    + "notify_type INT NOT NULL COMMENT '通知类型:1医嘱 2会诊 3病历 4预警 5评估 6系统',"
                    + "title VARCHAR(200) NOT NULL COMMENT '标题',"
                    + "content VARCHAR(500) DEFAULT NULL COMMENT '内容',"
                    + "ref_type VARCHAR(50) DEFAULT NULL COMMENT '关联业务类型',"
                    + "ref_id BIGINT DEFAULT NULL COMMENT '关联业务ID',"
                    + "is_read TINYINT NOT NULL DEFAULT 0 COMMENT '是否已读:1是 0否',"
                    + "create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',"
                    + "read_time DATETIME DEFAULT NULL COMMENT '读取时间',"
                    + "create_by VARCHAR(50) DEFAULT NULL, update_by VARCHAR(50) DEFAULT NULL,"
                    + "update_time DATETIME DEFAULT NULL, deleted TINYINT NOT NULL DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_user_read (user_id, is_read, deleted),"
                   + "KEY idx_type (notify_type, deleted)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='住院通知'");
        }
    }

    /**
     * 幂等建表: P4 住院发药增强(规范 16.3.1/16.3.2)。三张新表 + 两处存量补列。
     * 1) his_inp_dispense 住院发药记录(逐医嘱行级, 整包装取三量 should/actual/offset/over 与缺药替换/退药/出院带药取药核发留痕);
     * 2) his_ward_staging 病区暂存台账(退药实物未退回而暂存病区, 供下次发药冲抵应发量);
     * 3) his_discharge_pickup 出院带药取药/二次核发两段(医嘱发药记账后待发→取药发票号→二次核发人/时间);
     * 4) his_drug_return 补 keep_ward_flag(退药去向:0退回药房回库 1暂存病区不回收供冲抵);
     * 5) his_inp_order 补 dispense_status(0未发药 1已发药, 驱动住院待发药队列与防重复发药)。
     * 全程幂等: CREATE TABLE IF NOT EXISTS + addColumnIfNotExists; tenant_id 由租户插件注入, DDL 仍建该列。
     */
    private void ensureInpDispenseTables(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            /* 住院发药记录: 逐医嘱一次发药事件, 整包装取整 + 三量(应发/冲抵/实发/多发) + 缺药替换留痕 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_inp_dispense ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "dispense_no VARCHAR(30) NOT NULL COMMENT '住院发药单号',"
                    + "inp_visit_id BIGINT NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',"
                    + "order_id BIGINT NOT NULL COMMENT '医嘱ID(his_inp_order.id)',"
                    + "drug_catalog_id BIGINT DEFAULT NULL COMMENT '实发药品目录ID(his_drug_catalog.id, 替换后为替品)',"
                    + "orig_drug_catalog_id BIGINT DEFAULT NULL COMMENT '原医嘱药品目录ID(未替换时与实发相同)',"
                    + "drug_code VARCHAR(50) DEFAULT NULL COMMENT '药品编码',"
                    + "drug_name VARCHAR(200) DEFAULT NULL COMMENT '药品名称',"
                    + "spec VARCHAR(100) DEFAULT NULL COMMENT '规格',"
                    + "unit VARCHAR(20) DEFAULT NULL COMMENT '最小单位',"
                    + "replace_flag TINYINT DEFAULT 0 COMMENT '缺药替换:1是 0否',"
                    + "replace_scope TINYINT DEFAULT NULL COMMENT '替换范围:1仅本次 2本次及后续全部',"
                    + "replace_reason VARCHAR(200) DEFAULT NULL COMMENT '替换原因(缺药/禁用等)',"
                    + "patient_id BIGINT DEFAULT NULL COMMENT '患者ID',"
                    + "patient_name VARCHAR(50) DEFAULT NULL COMMENT '患者姓名',"
                    + "dept_id BIGINT DEFAULT NULL COMMENT '科室ID',"
                    + "dept_name VARCHAR(100) DEFAULT NULL COMMENT '科室名称',"
                    + "ward_id BIGINT DEFAULT NULL COMMENT '病区ID(his_ward.id)',"
                    + "ward_name VARCHAR(100) DEFAULT NULL COMMENT '病区名称',"
                    + "bed_no VARCHAR(30) DEFAULT NULL COMMENT '床号',"
                    + "pharmacy_id BIGINT DEFAULT NULL COMMENT '发药药房ID(his_pharmacy_def.id)',"
                    + "round_rule TINYINT DEFAULT NULL COMMENT '取整规则快照:1向上 2向下 3四舍五入',"
                    + "pack_ratio INT DEFAULT NULL COMMENT '包装换算比快照(大包装→最小单位)',"
                    + "pack_qty INT DEFAULT NULL COMMENT '发药包数(整包装)',"
                    + "should_qty DECIMAL(12,4) DEFAULT 0 COMMENT '应发量(医嘱量, 最小单位)',"
                    + "offset_qty DECIMAL(12,4) DEFAULT 0 COMMENT '冲抵量(病区暂存抵扣应发)',"
                    + "actual_qty DECIMAL(12,4) DEFAULT 0 COMMENT '实发量(取整后-冲抵, 最小单位)',"
                    + "over_qty DECIMAL(12,4) DEFAULT 0 COMMENT '多发量(整包装取整溢出部分)',"
                    + "stock_out_id BIGINT DEFAULT NULL COMMENT '关联出库单ID(his_stock_out.id)',"
                    + "is_discharge_pick TINYINT DEFAULT 0 COMMENT '出院带药:1是 0否',"
                    + "pickup_status TINYINT DEFAULT 0 COMMENT '取药核发:0无需 1待取药 2已取待发药核 3已二次核发',"
                    + "return_qty DECIMAL(12,4) DEFAULT 0 COMMENT '累计退回/暂存量',"
                    + "status TINYINT DEFAULT 1 COMMENT '状态:1已发药 2部分退药 3全部退药',"
                    + "dispense_by VARCHAR(50) DEFAULT NULL COMMENT '发药人',"
                    + "dispense_time DATETIME DEFAULT NULL COMMENT '发药时间',"
                    + "check_by VARCHAR(50) DEFAULT NULL COMMENT '核对人',"
                    + "check_time DATETIME DEFAULT NULL COMMENT '核对时间',"
                    + "remark VARCHAR(500) DEFAULT NULL COMMENT '备注',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_inp_disp_no (tenant_id, dispense_no),"
                    + "KEY idx_visit (inp_visit_id),"
                    + "KEY idx_order (order_id),"
                    + "KEY idx_org_status (tenant_id, org_id, status, dispense_time),"
                    + "KEY idx_ward (tenant_id, ward_id),"
                    + "KEY idx_drug (drug_catalog_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='住院发药记录'");
            /* 病区暂存台账: 退药实物暂存病区未回收, 下次发药冲抵应发量(逐条 staged/used 守恒) */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_ward_staging ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "inp_visit_id BIGINT NOT NULL COMMENT '住院就诊ID',"
                    + "patient_id BIGINT DEFAULT NULL COMMENT '患者ID',"
                    + "patient_name VARCHAR(50) DEFAULT NULL COMMENT '患者姓名',"
                    + "ward_id BIGINT DEFAULT NULL COMMENT '病区ID',"
                    + "ward_name VARCHAR(100) DEFAULT NULL COMMENT '病区名称',"
                    + "drug_catalog_id BIGINT NOT NULL COMMENT '药品目录ID',"
                    + "drug_code VARCHAR(50) DEFAULT NULL COMMENT '药品编码',"
                    + "drug_name VARCHAR(200) DEFAULT NULL COMMENT '药品名称',"
                    + "spec VARCHAR(100) DEFAULT NULL COMMENT '规格',"
                    + "unit VARCHAR(20) DEFAULT NULL COMMENT '最小单位',"
                    + "staged_qty DECIMAL(12,4) DEFAULT 0 COMMENT '暂存量(未退回可冲抵)',"
                    + "used_qty DECIMAL(12,4) DEFAULT 0 COMMENT '已冲抵量',"
                    + "source_dispense_id BIGINT DEFAULT NULL COMMENT '来源发药记录ID',"
                    + "source_return_id BIGINT DEFAULT NULL COMMENT '来源退药单ID',"
                    + "status TINYINT DEFAULT 0 COMMENT '状态:0有效 1已冲抵完',"
                    + "remark VARCHAR(500) DEFAULT NULL COMMENT '备注',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_visit_drug (inp_visit_id, drug_catalog_id, status),"
                    + "KEY idx_tenant (tenant_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='住院病区暂存冲抵台账'");
            /* 出院带药取药/二次核发: 医嘱发药记账后待发→患者取药(发票号)→药师二次核发两段 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_discharge_pickup ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "inp_visit_id BIGINT NOT NULL COMMENT '住院就诊ID',"
                    + "dispense_id BIGINT DEFAULT NULL COMMENT '关联住院发药记录ID',"
                    + "order_id BIGINT DEFAULT NULL COMMENT '来源医嘱ID',"
                    + "patient_id BIGINT DEFAULT NULL COMMENT '患者ID',"
                    + "patient_name VARCHAR(50) DEFAULT NULL COMMENT '患者姓名',"
                    + "drug_catalog_id BIGINT DEFAULT NULL COMMENT '药品目录ID',"
                    + "drug_name VARCHAR(200) DEFAULT NULL COMMENT '药品名称',"
                    + "spec VARCHAR(100) DEFAULT NULL COMMENT '规格',"
                    + "qty DECIMAL(12,4) DEFAULT 0 COMMENT '带药量(最小单位)',"
                    + "invoice_no VARCHAR(40) DEFAULT NULL COMMENT '取药发票号',"
                    + "window_id BIGINT DEFAULT NULL COMMENT '发药窗口ID',"
                    + "pickup_status TINYINT DEFAULT 1 COMMENT '状态:1待取药 2已取待发药核 3已二次核发',"
                    + "pickup_by VARCHAR(50) DEFAULT NULL COMMENT '取药经受人',"
                    + "pickup_time DATETIME DEFAULT NULL COMMENT '取药时间',"
                    + "verify2_by VARCHAR(50) DEFAULT NULL COMMENT '二次核发药师',"
                    + "verify2_time DATETIME DEFAULT NULL COMMENT '二次核发时间',"
                    + "remark VARCHAR(500) DEFAULT NULL COMMENT '备注',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_visit (inp_visit_id, pickup_status),"
                    + "KEY idx_tenant (tenant_id),"
                    + "KEY idx_dispense (dispense_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出院带药取药二次核发'");
        }
        /* 退药去向补列(0退回药房 1暂存病区): 存量退药单默认退回药房 */
        addColumnIfNotExists(conn, "his_drug_return", "keep_ward_flag", "TINYINT DEFAULT 0 COMMENT '退药去向:0退回药房回库 1暂存病区不回收供下次发药冲抵(P4)'");
        /* 住院医嘱发药状态补列: 驱动待发药队列与防重复发药 */
        addColumnIfNotExists(conn, "his_inp_order", "dispense_status", "TINYINT DEFAULT 0 COMMENT '住院发药状态:0未发药 1已发药(P4, 仅药品类医嘱使用)'");
    }

    /**
     * 幂等建表: P0 安全基座(T34)——四张新表 + his_inp_order 补药审四列 + 危急值规则种子。
     * 1) his_critical_value_rule 危急值规则(检验项目阈值, 性别/年龄分段, 低于下限或高于上限即告警);
     * 2) his_critical_value_record 危急值处理记录(报告→通知→确认→处置→关闭闭环留痕);
     * 3) his_case_front_page 病案首页(诊断/手术/费用分项/质量评分 + 医师/护士/质控签名图);
     * 4) his_signature_log 电子签名留痕(签名场景/关联单据/签名图URL/IP, 供病历与医嘱签名追溯);
     * 5) his_inp_order 药审四列(药审状态/审核药师/药审时间/驳回原因, 处方药审拦截);
     * 6) 播种 15 条常见危急值规则(tenant_id=1 默认租户, gender=0 通用; INSERT IGNORE 幂等不覆盖用户维护值)。
     * 全程幂等: CREATE TABLE IF NOT EXISTS + addColumnIfNotExists, 重复启动无副作用。
     */
    private void ensureP0SafetyTables(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            /* 危急值规则: 阈值判定元数据(通用/男女分段 + 年龄区间), 唯一键防同租户同项目同性别重复 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_critical_value_rule ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "item_code VARCHAR(50) NOT NULL COMMENT '检验项目编码',"
                    + "item_name VARCHAR(100) NOT NULL COMMENT '检验项目名称',"
                    + "unit VARCHAR(30) DEFAULT NULL COMMENT '单位',"
                    + "critical_low DECIMAL(12,4) DEFAULT NULL COMMENT '危急值下限(低于即告警)',"
                    + "critical_high DECIMAL(12,4) DEFAULT NULL COMMENT '危急值上限(高于即告警)',"
                    + "gender TINYINT DEFAULT 0 COMMENT '性别:0通用 1男 2女',"
                    + "age_min INT DEFAULT NULL COMMENT '年龄下限',"
                    + "age_max INT DEFAULT NULL COMMENT '年龄上限',"
                    + "alert_level TINYINT DEFAULT 1 COMMENT '告警级别:1危急 2异常',"
                    + "enabled TINYINT DEFAULT 1 COMMENT '是否启用',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "deleted TINYINT DEFAULT 0,"
                    + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_item_tenant (item_code, gender, tenant_id, deleted)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='危急值规则'");
            /* 危急值处理记录: 报告→通知→确认→处置→关闭闭环, 状态推进逐级留痕 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_critical_value_record ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "visit_id BIGINT NOT NULL COMMENT '就诊ID',"
                    + "patient_id BIGINT NOT NULL COMMENT '患者ID',"
                    + "item_code VARCHAR(50) NOT NULL COMMENT '检验项目编码',"
                    + "item_name VARCHAR(100) NOT NULL COMMENT '检验项目名称',"
                    + "result_value VARCHAR(50) NOT NULL COMMENT '检验结果值',"
                    + "unit VARCHAR(30) DEFAULT NULL COMMENT '单位',"
                    + "ref_range VARCHAR(100) DEFAULT NULL COMMENT '参考范围',"
                    + "alert_level TINYINT DEFAULT 1 COMMENT '告警级别:1危急 2异常',"
                    + "report_time DATETIME NOT NULL COMMENT '报告时间',"
                    + "notify_doctor_time DATETIME DEFAULT NULL COMMENT '通知医生时间',"
                    + "confirm_doctor_id BIGINT DEFAULT NULL COMMENT '确认医生ID(his_staff.id)',"
                    + "confirm_time DATETIME DEFAULT NULL COMMENT '确认时间',"
                    + "handle_measures TEXT DEFAULT NULL COMMENT '处置措施',"
                    + "handle_time DATETIME DEFAULT NULL COMMENT '处置时间',"
                    + "status TINYINT DEFAULT 1 COMMENT '状态:1待通知 2已通知 3已确认 4已处置 5已关闭',"
                    + "notification_id BIGINT DEFAULT NULL COMMENT '关联通知ID(his_inp_notification.id)',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "deleted TINYINT DEFAULT 0,"
                    + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + "update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_visit_status (visit_id, status, deleted)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='危急值处理记录'");
            /* 病案首页: 就诊唯一, 诊断/手术 JSON 明细 + 费用分项 + 质量评分 + 三类电子签名图 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_case_front_page ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "visit_id BIGINT NOT NULL COMMENT '就诊ID',"
                    + "patient_id BIGINT NOT NULL COMMENT '患者ID',"
                    + "admission_date DATETIME DEFAULT NULL COMMENT '入院时间',"
                    + "discharge_date DATETIME DEFAULT NULL COMMENT '出院时间',"
                    + "los_days INT DEFAULT NULL COMMENT '住院天数',"
                    + "admission_dept_id BIGINT DEFAULT NULL COMMENT '入院科室ID(his_dept.id)',"
                    + "discharge_dept_id BIGINT DEFAULT NULL COMMENT '出院科室ID(his_dept.id)',"
                    + "admission_diag_code VARCHAR(50) DEFAULT NULL COMMENT '入院诊断编码',"
                    + "admission_diag_name VARCHAR(200) DEFAULT NULL COMMENT '入院诊断名称',"
                    + "discharge_main_diag_code VARCHAR(50) DEFAULT NULL COMMENT '出院主要诊断编码',"
                    + "discharge_main_diag_name VARCHAR(200) DEFAULT NULL COMMENT '出院主要诊断名称',"
                    + "discharge_other_diags TEXT DEFAULT NULL COMMENT '其他出院诊断JSON',"
                    + "pathology_diag VARCHAR(200) DEFAULT NULL COMMENT '病理诊断',"
                    + "injury_poison_code VARCHAR(50) DEFAULT NULL COMMENT '损伤中毒编码',"
                    + "operation_records TEXT DEFAULT NULL COMMENT '手术操作JSON',"
                    + "blood_type VARCHAR(10) DEFAULT NULL COMMENT '血型',"
                    + "rh VARCHAR(10) DEFAULT NULL COMMENT 'Rh血型',"
                    + "allergy_drugs VARCHAR(500) DEFAULT NULL COMMENT '药物过敏史',"
                    + "autopsy TINYINT DEFAULT 0 COMMENT '尸检:0否 1是',"
                    + "total_cost DECIMAL(12,2) DEFAULT 0 COMMENT '总费用',"
                    + "drug_cost DECIMAL(12,2) DEFAULT 0 COMMENT '药品费',"
                    + "exam_cost DECIMAL(12,2) DEFAULT 0 COMMENT '检查费',"
                    + "treatment_cost DECIMAL(12,2) DEFAULT 0 COMMENT '治疗费',"
                    + "bed_cost DECIMAL(12,2) DEFAULT 0 COMMENT '床位费',"
                    + "nursing_cost DECIMAL(12,2) DEFAULT 0 COMMENT '护理费',"
                    + "material_cost DECIMAL(12,2) DEFAULT 0 COMMENT '材料费',"
                    + "other_cost DECIMAL(12,2) DEFAULT 0 COMMENT '其他费用',"
                    + "self_pay DECIMAL(12,2) DEFAULT 0 COMMENT '自付金额',"
                    + "insurance_pay DECIMAL(12,2) DEFAULT 0 COMMENT '医保支付金额',"
                    + "quality_score INT DEFAULT NULL COMMENT '病案质量评分',"
                    + "qc_doctor_id BIGINT DEFAULT NULL COMMENT '质控医生ID(his_staff.id)',"
                    + "qc_time DATETIME DEFAULT NULL COMMENT '质控时间',"
                    + "status TINYINT DEFAULT 1 COMMENT '状态:1草稿 2已提交 3已审核',"
                    + "doctor_sign_img VARCHAR(255) DEFAULT NULL COMMENT '医师签名图URL(/uploads/...)',"
                    + "nurse_sign_img VARCHAR(255) DEFAULT NULL COMMENT '护士签名图URL(/uploads/...)',"
                    + "qc_sign_img VARCHAR(255) DEFAULT NULL COMMENT '质控签名图URL(/uploads/...)',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "deleted TINYINT DEFAULT 0,"
                    + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + "update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_visit (visit_id, tenant_id, deleted)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='病案首页'");
            /* 电子签名日志: 每次签名落一行(场景/单据/签名图/IP), 供签署追溯与合规审计 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_signature_log ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "user_id BIGINT NOT NULL COMMENT '签名用户ID(sys_user.id)',"
                    + "user_name VARCHAR(50) NOT NULL COMMENT '签名用户姓名',"
                    + "action_type VARCHAR(50) NOT NULL COMMENT '签名场景:order_submit/record_submit/pharm_audit/case_page/exec_verify',"
                    + "ref_type VARCHAR(50) DEFAULT NULL COMMENT '关联类型',"
                    + "ref_id BIGINT DEFAULT NULL COMMENT '关联ID',"
                    + "sign_img_url VARCHAR(255) DEFAULT NULL COMMENT '签名图片URL(/uploads/...)',"
                    + "ip_address VARCHAR(50) DEFAULT NULL COMMENT '签名来源IP',"
                    + "sign_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '签名时间',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_ref (ref_type, ref_id, tenant_id, deleted)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='电子签名日志'");
        }
        /* 住院医嘱药审四列(药审状态/审核药师/药审时间/驳回原因): 处方下达到药房前拦截, 驳回留原因 */
        addColumnIfNotExists(conn, "his_inp_order", "pharm_audit_status", "TINYINT DEFAULT 0 COMMENT '药审状态:0无需 1待审 2通过 3驳回'");
        addColumnIfNotExists(conn, "his_inp_order", "pharm_audit_id", "BIGINT DEFAULT NULL COMMENT '审核药师ID(his_staff.id)'");
        addColumnIfNotExists(conn, "his_inp_order", "pharm_audit_time", "DATETIME DEFAULT NULL COMMENT '药审时间'");
        addColumnIfNotExists(conn, "his_inp_order", "pharm_reject_reason", "VARCHAR(500) DEFAULT NULL COMMENT '驳回原因'");
        // 播种常见危急值规则(tenant_id=1 默认租户; INSERT IGNORE 幂等, 已有规则不覆盖)
        seedCriticalValueRules(conn);
    }

    /**
     * 幂等建表 + 存量表补列: P1/P2 住院深化基座(T41)——
     * 1) his_emr_version 病历版本快照(每次 save/submit/audit 落一行, content/structure 双快照支持历史回溯);
     * 2) his_inp_io_record 出入量记录(进量/出量逐笔登记, 护士站出入量平衡统计口径);
     * 3) 存量表 12 列: 医嘱续开来源(his_inp_order.source_order_id)/病历主治·主任签名两段
     *    (his_inp_medical_record)/交接班 SBAR 评估与建议(his_inp_shift_record)/费用明细结算关联
     *    (his_inp_charge_detail.settle_id)/视频会诊房间与录像/预入院检查项与登记时间(his_inp_visit)。
     * 注意: 视频会诊两列落在实际会诊表 his_inp_consultation(任务书原写 his_inp_consult, 项目中不存在该表)。
     * 两张新表均带 create_by/create_time/update_by/update_time 审计列, 兼容实体(BaseEntity 自动填充)与 JdbcTemplate 双模式。
     * 全程幂等: CREATE TABLE IF NOT EXISTS + addColumnIfNotExists, 重复启动无副作用。
     */
    private void ensureP1P2Tables(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            /* 病历版本快照: 每次 save/submit/audit 落一行, 双快照支持富文书与结构化双模式回溯 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_emr_version ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "record_id BIGINT NOT NULL COMMENT '关联his_inp_medical_record(门诊时同 ref_id 存 visitId)',"
                    + "scope TINYINT DEFAULT 1 COMMENT '适用范围:1住院 2门诊',"
                    + "ref_id BIGINT DEFAULT NULL COMMENT '业务主键(scope=1病历id, scope=2门诊就诊id)',"
                    + "version_no INT NOT NULL COMMENT '版本号',"
                    + "content_snapshot TEXT DEFAULT NULL COMMENT '内容快照',"
                    + "structure_snapshot TEXT DEFAULT NULL COMMENT '结构化数据快照',"
                    + "operator_id BIGINT NOT NULL COMMENT '操作人ID(sys_user.id)',"
                    + "operator_name VARCHAR(50) NOT NULL COMMENT '操作人姓名',"
                    + "operate_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',"
                    + "operate_type VARCHAR(20) NOT NULL COMMENT '操作:save/submit/audit',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_record (record_id, deleted),"
                    + "KEY idx_scope_ref (scope, ref_id, deleted)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='病历版本快照'");
            /* 出入量记录: 进量/出量逐笔登记(饮水/静脉/尿量/引流等), 供护士站出入量与平衡统计 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_inp_io_record ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "visit_id BIGINT NOT NULL COMMENT '就诊ID(his_inp_visit.id)',"
                    + "patient_id BIGINT NOT NULL COMMENT '患者ID(his_patient.id)',"
                    + "record_time DATETIME NOT NULL COMMENT '记录时间',"
                    + "io_type TINYINT NOT NULL COMMENT '1进量 2出量',"
                    + "category VARCHAR(50) NOT NULL COMMENT '类别:饮水/静脉/尿量/引流等',"
                    + "volume DECIMAL(10,2) NOT NULL COMMENT '量(ml)',"
                    + "route VARCHAR(50) DEFAULT NULL COMMENT '途径',"
                    + "note VARCHAR(200) DEFAULT NULL COMMENT '备注',"
                    + "nurse_id BIGINT DEFAULT NULL COMMENT '登记护士ID(his_staff.id)',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_visit_date (visit_id, record_time, deleted)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='住院出入量记录'");
        }
        /* 医嘱续开来源: 续开医嘱回指原医嘱ID */
        addColumnIfNotExists(conn, "his_inp_order", "source_order_id", "BIGINT DEFAULT NULL COMMENT '续开来源医嘱ID'");
        /* 病历三级签名: 主治/主任签名医师与时间 */
        addColumnIfNotExists(conn, "his_inp_medical_record", "attending_sign_id", "BIGINT DEFAULT NULL COMMENT '主治签名医师ID'");
        addColumnIfNotExists(conn, "his_inp_medical_record", "attending_sign_time", "DATETIME DEFAULT NULL COMMENT '主治签名时间'");
        addColumnIfNotExists(conn, "his_inp_medical_record", "director_sign_id", "BIGINT DEFAULT NULL COMMENT '主任签名医师ID'");
        addColumnIfNotExists(conn, "his_inp_medical_record", "director_sign_time", "DATETIME DEFAULT NULL COMMENT '主任签名时间'");
        /* 交接班 SBAR: 评估(Assessment)与建议(Recommendation), 与既有 sbar_situation/sbar_background 合成四要素 */
        addColumnIfNotExists(conn, "his_inp_shift_record", "sbar_assessment", "TEXT DEFAULT NULL COMMENT 'SBAR评估'");
        addColumnIfNotExists(conn, "his_inp_shift_record", "sbar_recommendation", "TEXT DEFAULT NULL COMMENT 'SBAR建议'");
        /* 费用明细结算关联: 出院结算时回写结算单ID, 支撑按结算单追溯费用组成 */
        addColumnIfNotExists(conn, "his_inp_charge_detail", "settle_id", "BIGINT DEFAULT NULL COMMENT '关联结算ID'");
        /* 视频会诊: 视频房间ID与录像URL */
        addColumnIfNotExists(conn, "his_inp_consultation", "video_room_id", "VARCHAR(100) DEFAULT NULL COMMENT '视频房间ID'");
        addColumnIfNotExists(conn, "his_inp_consultation", "video_record_url", "VARCHAR(500) DEFAULT NULL COMMENT '视频录像URL'");
        /* 预入院: 检查项JSON与预登记时间 */
        addColumnIfNotExists(conn, "his_inp_visit", "pre_check_items", "TEXT DEFAULT NULL COMMENT '预入院检查项JSON'");
        addColumnIfNotExists(conn, "his_inp_visit", "pre_admit_time", "DATETIME DEFAULT NULL COMMENT '预入院登记时间'");
        /* Phase B 门诊并入引擎: 病历版本快照增 scope/ref_id(门诊 ref_id=visitId), his_visit 承载结构化病历 */
        addColumnIfNotExists(conn, "his_emr_version", "scope", "TINYINT DEFAULT 1 COMMENT '适用范围:1住院 2门诊'");
        addColumnIfNotExists(conn, "his_emr_version", "ref_id", "BIGINT DEFAULT NULL COMMENT '业务主键(scope=1病历id, scope=2门诊就诊id)'");
        addColumnIfNotExists(conn, "his_visit", "structure", "TEXT NULL COMMENT '结构化病历JSON(his_emr_template scope=2 fields 取值)'");
        addColumnIfNotExists(conn, "his_visit", "emr_template_id", "BIGINT DEFAULT NULL COMMENT '结构化病历模板ID(his_emr_template.id)'");
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("UPDATE his_emr_version SET ref_id = record_id, scope = 1 WHERE ref_id IS NULL AND deleted = 0");
        }
    }

    /**
     * 播种 15 条常见危急值规则(tenant_id=1 默认租户, gender=0 通用, alert_level=1 危急)。
     * 阈值方向口径: critical_low=低于即告警, critical_high=高于即告警(与 his_critical_rule.low/high_threshold 同语义);
     * WBC_LOW/PLT_LOW/HGB/PaO2 等"低值告警"单边项目的阈值统一落 critical_low, high 留空,
     * 保证通用判定(value < critical_low || value > critical_high)精确命中;
     * 幂等: 唯一键 uk_item_tenant(item_code, gender, tenant_id, deleted) + INSERT IGNORE, 重复启动不产生副本。
     */
    private void seedCriticalValueRules(Connection conn) throws Exception {
        String sql = "INSERT IGNORE INTO his_critical_value_rule"
                + " (item_code, item_name, unit, critical_low, critical_high, gender, alert_level, enabled, tenant_id, deleted)"
                + " VALUES (?, ?, ?, ?, ?, 0, 1, 1, 1, 0)";
        /* {item_code, item_name, unit, critical_low, critical_high}; 单边阈值另一侧置 null */
        Object[][] seeds = {
                {"WBC", "白细胞计数", "10^9/L", null, "30"},
                {"WBC_LOW", "白细胞计数(低)", "10^9/L", "2", null},
                {"HGB", "血红蛋白", "g/L", "60", null},
                {"PLT", "血小板计数", "10^9/L", null, "600"},
                {"PLT_LOW", "血小板计数(低)", "10^9/L", "50", null},
                {"K", "血钾", "mmol/L", "2.5", "6.5"},
                {"Na", "血钠", "mmol/L", "120", "160"},
                {"GLU", "血糖", "mmol/L", "2.8", "22.2"},
                {"BUN", "尿素氮", "mmol/L", null, "35.7"},
                {"Cr", "肌酐", "μmol/L", null, "707"},
                {"PT", "凝血酶原时间", "s", null, "30"},
                {"APTT", "活化部分凝血活酶时间", "s", null, "80"},
                {"INR", "国际标准化比值", "-", null, "4.5"},
                {"PH", "血气pH", "-", "7.20", "7.60"},
                {"PaO2", "动脉血氧分压", "mmHg", "40", null},
        };
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Object[] s : seeds) {
                ps.setString(1, (String) s[0]);
                ps.setString(2, (String) s[1]);
                ps.setString(3, (String) s[2]);
                // 数值经字符串转 BigDecimal, 避免 double 字面量引入 DECIMAL 精度噪音
                ps.setBigDecimal(4, s[3] == null ? null : new BigDecimal((String) s[3]));
                ps.setBigDecimal(5, s[4] == null ? null : new BigDecimal((String) s[4]));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * 幂等建表: 药房维度定价覆盖表 his_pharmacy_drug_price。
     * 唯一键 tenant+org+药房+药品; 有覆盖价则开方按药房价计费, 清空(物理删, 避开软删撞唯一键)回落目录价。
     */
    private void ensurePharmacyPriceTable(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_pharmacy_drug_price ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "pharmacy_id BIGINT NOT NULL COMMENT '药房ID(his_pharmacy_def.id)',"
                    + "drug_catalog_id BIGINT NOT NULL COMMENT '医共体药品目录ID(his_drug_catalog.id)',"
                    + "retail_price DECIMAL(12,6) NOT NULL COMMENT '药房零售价(最小单位, 覆盖目录价)',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_org_ph_drug (tenant_id, org_id, pharmacy_id, drug_catalog_id),"
                    + "KEY idx_pharmacy (tenant_id, pharmacy_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='药房药品定价(药房维度覆盖价)'"
            );
        }
    }

    /**
     * 住院医生站 T2 阶段0: 特殊药品抗菌分级/管制类别示例数据幂等回填。
     * 目的: 让批次D已上线的处方权限前端拦截(checkPrescribeAuth)具备真实触发材料(此前目录分级列全空, 门控休眠)。
     * 口径: abx_grade 11非限制/12限制/13特殊使用(按通用名命中); drug_class_name 麻醉/一类精神/二类精神。
     * 幂等: 仅在命中且目标列为空时更新, 可重复执行不覆盖已有维护值; 无匹配行则不更新(无害)。
     */
    private void ensureInpDoctorT2Tables(Connection conn) throws Exception {
        /* 存量表补列(幂等): his_inp_diagnosis 牙位图(镜像门诊 his_diagnosis.tooth_position 范式) */
        addColumnIfNotExists(conn, "his_inp_diagnosis", "tooth_position", "VARCHAR(200) DEFAULT NULL COMMENT '牙位编码(FDI/Palmer, 口腔诊断专用)'");
        addColumnIfNotExists(conn, "his_inp_order", "order_dept_id", "BIGINT DEFAULT NULL COMMENT '开单科室ID(his_dept.id, 开立时取开嘱医生所属科室)'");
        /* T2阶段2a: his_exam_report 报告撤回留痕(作废 status=3 时记录撤回人/时间/原因, 供医生站"看见被撤回") */
        addColumnIfNotExists(conn, "his_exam_report", "revoke_by", "BIGINT DEFAULT NULL COMMENT '撤回人(his_staff.id)'");
        addColumnIfNotExists(conn, "his_exam_report", "revoke_time", "DATETIME DEFAULT NULL COMMENT '撤回时间'");
        addColumnIfNotExists(conn, "his_exam_report", "revoke_reason", "VARCHAR(255) DEFAULT NULL COMMENT '撤回原因'");
        /* T2阶段5-1: 检查报告外部 PACS/DICOMweb 接入骨架(存 DICOM StudyInstanceUID 与 PACS 服务标识; 非真接厂外PACS, 仅URL挂接/Mock) */
        addColumnIfNotExists(conn, "his_exam_report", "pacs_study_uid", "VARCHAR(128) DEFAULT NULL COMMENT 'DICOM StudyInstanceUID(外部PACS影像挂接键)'");
        addColumnIfNotExists(conn, "his_exam_report", "pacs_server", "VARCHAR(128) DEFAULT NULL COMMENT 'PACS服务标识/来源(区分多PACS实例)'");
        /* T2阶段3: 处方点评子系统(人工点评闭环+留痕)。点评单幂等建表(新模块非启动关键路径)。 */
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_rx_review ("
                    + "id BIGINT NOT NULL COMMENT '主键(雪花)',"
                    + "inp_visit_id BIGINT NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',"
                    + "patient_id BIGINT DEFAULT NULL COMMENT '患者ID(his_patient.id)',"
                    + "order_id BIGINT DEFAULT NULL COMMENT '被点评医嘱ID(his_inp_order.id)',"
                    + "doctor_id BIGINT DEFAULT NULL COMMENT '被点评开嘱医生ID(his_staff.id)',"
                    + "org_id BIGINT DEFAULT NULL COMMENT '机构ID(发起时取就诊归属机构)',"
                    + "rx_item_snapshot TEXT DEFAULT NULL COMMENT '医嘱要素快照JSON(发起点评时冻结)',"
                    + "review_staff_id BIGINT DEFAULT NULL COMMENT '点评医师/药师ID(his_staff.id, 点评留痕)',"
                    + "review_time DATETIME DEFAULT NULL COMMENT '点评时间',"
                    + "result TINYINT DEFAULT NULL COMMENT '点评结论:1合理 2不规范 3不合理',"
                    + "problem_type VARCHAR(50) DEFAULT NULL COMMENT '问题类型编码(适应证/选药/剂量/用法/相互作用/重复给药/禁忌/其他)',"
                    + "score INT DEFAULT NULL COMMENT '点评评分(0-100)',"
                    + "comment VARCHAR(500) DEFAULT NULL COMMENT '点评意见',"
                    + "status TINYINT DEFAULT 1 COMMENT '点评状态:1待点评 2已点评',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "deleted TINYINT DEFAULT 0,"
                    + "create_by VARCHAR(50),"
                    + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + "update_by VARCHAR(50),"
                    + "update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_rr_visit (inp_visit_id, deleted),"
                    + "KEY idx_rr_reviewer (review_staff_id, deleted),"
                    + "KEY idx_rr_status (status, deleted)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='住院处方点评单(T2阶段3)'");
        }
        /* T2阶段5-3: 医师处方权限·按级授权明细(抗菌分级/麻醉/精一/精二 各自独立有效期与变更留痕) */
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_staff_rx_auth ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "staff_id BIGINT NOT NULL COMMENT '职工ID(his_staff.id)',"
                    + "auth_kind VARCHAR(20) NOT NULL COMMENT '权限类别: abx抗菌分级/narcotic麻醉/psych1精一/psych2精二',"
                    + "auth_code VARCHAR(20) NOT NULL COMMENT '类别内编码(抗菌 11/12/13; 专项填 1)',"
                    + "auth_name VARCHAR(50) DEFAULT NULL COMMENT '权限名称(字典回填)',"
                    + "valid_from DATE DEFAULT NULL COMMENT '生效日期',"
                    + "valid_until DATE DEFAULT NULL COMMENT '有效期至(到期失效, 需复训再授权)',"
                    + "auth_org VARCHAR(100) DEFAULT NULL COMMENT '授权机构',"
                    + "auth_no VARCHAR(100) DEFAULT NULL COMMENT '授权文号',"
                    + "status TINYINT DEFAULT 1 COMMENT '状态:1有效 0注销',"
                    + "org_id BIGINT DEFAULT NULL COMMENT '机构ID(跟随职工归属)',"
                    + "memo VARCHAR(255) DEFAULT NULL COMMENT '备注',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "deleted TINYINT DEFAULT 0,"
                    + "create_by VARCHAR(50),"
                    + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + "update_by VARCHAR(50),"
                    + "update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_sra_staff (staff_id, deleted),"
                    + "KEY idx_sra_kind (staff_id, auth_kind, deleted)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='医师处方权限按级授权明细(T2阶段5-3)'"
            );
        }

        try (Statement st = conn.createStatement()) {
            st.executeUpdate("UPDATE his_drug_catalog SET abx_grade='11', abx_grade_name='非限制使用级', abx_grade_src='院内演示分级'"
                    + " WHERE deleted=0 AND abx_grade IS NULL"
                    + " AND generic_name REGEXP '(青霉素|阿莫西林|氨苄西林|苯唑西林|头孢氨苄|头孢拉定|头孢硫脒|新霉素|庆大霉素|奈替米星)'"
                    );
            st.executeUpdate("UPDATE his_drug_catalog SET abx_grade='12', abx_grade_name='限制使用级', abx_grade_src='院内演示分级'"
                    + " WHERE deleted=0 AND abx_grade IS NULL"
                    + " AND generic_name REGEXP '(沙星|头孢曲松|头孢噻肟|头孢哌酮|头孢他啶|哌拉西林|阿奇霉素|克拉霉素|头孢呋辛|头孢美唑)'"
                    );
            st.executeUpdate("UPDATE his_drug_catalog SET abx_grade='13', abx_grade_name='特殊使用级', abx_grade_src='院内演示分级'"
                    + " WHERE deleted=0 AND abx_grade IS NULL"
                    + " AND generic_name REGEXP '(培南|万古霉素|替考拉宁|利奈唑胺|替加环素|多黏菌素|多粘菌素|头孢吡肟|两性霉素)'"
                    );
            st.executeUpdate("UPDATE his_drug_catalog SET drug_class_name='麻醉药品', drug_class_src='院内演示管制'"
                    + " WHERE deleted=0 AND drug_class_name IS NULL"
                    + " AND generic_name REGEXP '(吗啡|芬太尼|舒芬太尼|瑞芬太尼|哌替啶|杜冷丁|羟考酮|可待因)'"
                    );
            st.executeUpdate("UPDATE his_drug_catalog SET drug_class_name='第一类精神药品', drug_class_src='院内演示管制'"
                    + " WHERE deleted=0 AND drug_class_name IS NULL"
                    + " AND generic_name REGEXP '(氯胺酮|三唑仑|哌甲酯)'"
                    );
            st.executeUpdate("UPDATE his_drug_catalog SET drug_class_name='第二类精神药品', drug_class_src='院内演示管制'"
                    + " WHERE deleted=0 AND drug_class_name IS NULL"
                    + " AND generic_name REGEXP '(地西泮|咪达唑仑|艾司唑仑|劳拉西泮|阿普唑仑|曲马多|佐匹克隆|苯巴比妥|地佐辛)'"
                    );
            log.info("住院医生站T2阶段0: 特殊药品分级/管制示例回填完成");
        }
        /* T2阶段5-2: 处方点评自动规则引擎——点评单增列(引擎预打分留痕) + 规则表建表 + 种子规则 */
        addColumnIfNotExists(conn, "his_rx_review", "auto_findings", "TEXT DEFAULT NULL COMMENT '规则引擎命中明细JSON(发起点评时自动打分留痕)'");
        addColumnIfNotExists(conn, "his_rx_review", "auto_result", "INT DEFAULT NULL COMMENT '引擎建议结论:1合理 2不规范 3不合理'");
        addColumnIfNotExists(conn, "his_rx_review", "auto_score", "INT DEFAULT NULL COMMENT '引擎建议评分(0-100)'");
        addColumnIfNotExists(conn, "his_rx_review", "auto_problem_type", "VARCHAR(50) DEFAULT NULL COMMENT '引擎建议问题类型编码'");
        addColumnIfNotExists(conn, "his_rx_review", "auto_evaluated", "TINYINT DEFAULT 0 COMMENT '是否已自动预打分:1是 0否'");
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_rx_review_rule ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "rule_code VARCHAR(50) NOT NULL COMMENT '规则编码(唯一)',"
                    + "rule_name VARCHAR(100) NOT NULL COMMENT '规则名称',"
                    + "rule_type VARCHAR(50) NOT NULL COMMENT '规则类型(引擎分派: abx_under_level/abx_no_auth/abx_expiry/duplicate_drug/long_abx_duration)',"
                    + "severity TINYINT DEFAULT 2 COMMENT '严重度:1提示 2不规范 3不合理',"
                    + "result_hint TINYINT DEFAULT 2 COMMENT '建议结论:1合理 2不规范 3不合理',"
                    + "problem_type_hint VARCHAR(50) DEFAULT NULL COMMENT '建议问题类型编码',"
                    + "score_deduct INT DEFAULT 0 COMMENT '命中扣分值',"
                    + "enabled TINYINT DEFAULT 1 COMMENT '启用:1启用 0停用',"
                    + "params VARCHAR(500) DEFAULT NULL COMMENT '规则参数JSON(如 {\"days\":14})',"
                    + "memo VARCHAR(255) DEFAULT NULL COMMENT '备注',"
                    + "org_id BIGINT DEFAULT NULL COMMENT '机构ID(空=全院级)',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "deleted TINYINT DEFAULT 0,"
                    + "create_by VARCHAR(50),"
                    + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + "update_by VARCHAR(50),"
                    + "update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_rule_code (rule_code, tenant_id, deleted),"
                    + "KEY idx_rule_type (rule_type, enabled, deleted)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='处方点评自动规则(T2阶段5-2)'");
        }
        seedRxReviewRules(conn);
    }

    /** 播种处方点评引擎规则(tenant_id=1 默认租户; 唯一键 rule_code+tenant+deleted + INSERT IGNORE 幂等)。 */
    private void seedRxReviewRules(Connection conn) throws Exception {
        String sql = "INSERT IGNORE INTO his_rx_review_rule"
                + " (rule_code, rule_name, rule_type, severity, result_hint, problem_type_hint, score_deduct, enabled, params, tenant_id, deleted)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 0)";
        Object[][] seeds = {
                {"ABX_UNDER_LEVEL", "抗菌越级用药", "abx_under_level", 3, 3, "DRUG_CHOICE", 30, 1, "{}"},
                {"ABX_NO_AUTH", "无抗菌处方权开具抗菌药", "abx_no_auth", 3, 3, "DRUG_CHOICE", 30, 1, "{}"},
                {"ABX_EXPIRY", "抗菌处方权已到期", "abx_expiry", 2, 2, "OTHER", 15, 1, "{}"},
                {"DUPLICATE_DRUG", "同种药品重复开具", "duplicate_drug", 2, 2, "DUPLICATION", 20, 1, "{}"},
                {"LONG_ABX_DURATION", "长期抗菌医嘱疑似超疗程", "long_abx_duration", 2, 2, "DOSAGE", 10, 1, "{\"days\":14}"},
        };
        try (java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Object[] s : seeds) {
                ps.setString(1, (String) s[0]);
                ps.setString(2, (String) s[1]);
                ps.setString(3, (String) s[2]);
                ps.setInt(4, (Integer) s[3]);
                ps.setInt(5, (Integer) s[4]);
                ps.setString(6, (String) s[5]);
                ps.setInt(7, (Integer) s[6]);
                ps.setInt(8, (Integer) s[7]);
                ps.setString(9, (String) s[8]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * 门诊医生站对标优化 OP-A(接诊增强)幂等建表:
     *  his_user_display_pref 患者信息栏自定义显示偏好(个人级, 按场景存配置 JSON);
     *  his_fever_register 发热病人自动登记;
     *  his_pre_consult 诊前预问诊记录;
     *  his_vital_sign 生命体征(含血糖/血酮趋势源, 支持设备采集/手工补录);
     *  + his_visit 诊后去向补列(disposition/disposition_dept_id/disposition_note)。
     */
    private void ensureOutpWsEnhancementTables(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_user_display_pref ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT DEFAULT NULL COMMENT '机构ID',"
                    + "user_id BIGINT NOT NULL COMMENT '用户ID(sys_user.id)',"
                    + "scene VARCHAR(50) NOT NULL COMMENT '场景: dw_banner患者信息栏/dw_layout布局偏好',"
                    + "config_json TEXT COMMENT '显示配置JSON(字段开关与顺序)',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_tenant_user_scene (tenant_id, user_id, scene)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='医生站用户显示偏好'"
            );
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_fever_register ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT DEFAULT NULL COMMENT '机构ID',"
                    + "visit_id BIGINT DEFAULT NULL COMMENT '就诊ID(his_visit.id)',"
                    + "patient_id BIGINT DEFAULT NULL COMMENT '患者ID',"
                    + "patient_name VARCHAR(100) DEFAULT NULL COMMENT '患者姓名',"
                    + "temperature DECIMAL(4,1) DEFAULT NULL COMMENT '体温(℃)',"
                    + "exposure_history VARCHAR(500) DEFAULT NULL COMMENT '流行病学接触史',"
                    + "disposition VARCHAR(200) DEFAULT NULL COMMENT '处理去向',"
                    + "register_time DATETIME DEFAULT NULL COMMENT '登记时间',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_visit (tenant_id, visit_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发热病人登记'"
            );
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_pre_consult ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT DEFAULT NULL COMMENT '机构ID',"
                    + "visit_id BIGINT DEFAULT NULL COMMENT '就诊ID(his_visit.id)',"
                    + "patient_id BIGINT DEFAULT NULL COMMENT '患者ID',"
                    + "content_json TEXT COMMENT '预问诊内容JSON(症状/部位/时长/自述)',"
                    + "recorder VARCHAR(50) DEFAULT NULL COMMENT '录入人',"
                    + "recorder_id BIGINT DEFAULT NULL COMMENT '录入人ID',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_visit (tenant_id, visit_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='诊前预问诊记录'"
            );
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_vital_sign ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT DEFAULT NULL COMMENT '机构ID',"
                    + "visit_id BIGINT DEFAULT NULL COMMENT '就诊ID(his_visit.id)',"
                    + "patient_id BIGINT DEFAULT NULL COMMENT '患者ID',"
                    + "systolic INT DEFAULT NULL COMMENT '收缩压(mmHg)',"
                    + "diastolic INT DEFAULT NULL COMMENT '舒张压(mmHg)',"
                    + "pulse INT DEFAULT NULL COMMENT '脉搏(次/分)',"
                    + "temperature DECIMAL(4,1) DEFAULT NULL COMMENT '体温(℃)',"
                    + "respiration INT DEFAULT NULL COMMENT '呼吸(次/分)',"
                    + "blood_glucose DECIMAL(6,2) DEFAULT NULL COMMENT '血糖(mmol/L)',"
                    + "blood_ketone DECIMAL(6,2) DEFAULT NULL COMMENT '血酮(mmol/L)',"
                    + "weight DECIMAL(6,2) DEFAULT NULL COMMENT '体重(kg)',"
                    + "height DECIMAL(6,2) DEFAULT NULL COMMENT '身高(cm)',"
                    + "source VARCHAR(20) DEFAULT '手工' COMMENT '来源:设备/手工',"
                    + "meas_time DATETIME DEFAULT NULL COMMENT '测量时间',"
                    + "recorder_id BIGINT DEFAULT NULL COMMENT '录入人ID',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_patient_meas (tenant_id, patient_id, meas_time),"
                    + "KEY idx_visit (tenant_id, visit_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生命体征(含血糖血酮趋势源)'"
            );
        }
        // his_visit 诊后去向补列(幂等)
        addColumnIfNotExists(conn, "his_visit", "disposition", "TINYINT DEFAULT NULL COMMENT '诊后去向:1离院 2转科 3转留观 4转院'");
        addColumnIfNotExists(conn, "his_visit", "disposition_dept_id", "BIGINT DEFAULT NULL COMMENT '转科目标科室ID(his_dept.id)'");
        addColumnIfNotExists(conn, "his_visit", "disposition_note", "VARCHAR(500) DEFAULT NULL COMMENT '去向备注'");
    }

    /**
     * 门诊医生站对标优化 OP-B(诊断进阶): 诊断高频沉淀/疾病报卡/诊断→医嘱模板映射 3 新表
     * + his_diagnosis 牙位补列 + his_medical_cert 审核态列 + his_dept 证明审核开关列(幂等, 新模块非启动关键路径)。
     */
    private void ensureOutpWsDiagnosisTables(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_diag_freq ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT DEFAULT NULL COMMENT '机构ID',"
                    + "staff_id BIGINT DEFAULT NULL COMMENT '医师ID(his_staff.id, 个人常用维度)',"
                    + "dept_id BIGINT DEFAULT NULL COMMENT '科室ID(his_dept.id, 科室高频维度)',"
                    + "diag_code VARCHAR(50) NOT NULL COMMENT '诊断代码',"
                    + "diag_name VARCHAR(200) DEFAULT NULL COMMENT '诊断名称',"
                    + "diag_class VARCHAR(20) DEFAULT NULL COMMENT '诊断类别: west/tcm/symp/oper/tumor',"
                    + "use_count INT NOT NULL DEFAULT 0 COMMENT '累计使用次数',"
                    + "last_time DATETIME DEFAULT NULL COMMENT '最近使用时间',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_staff_dept_diag (tenant_id, staff_id, dept_id, diag_code),"
                    + "KEY idx_dept (tenant_id, dept_id, use_count),"
                    + "KEY idx_staff (tenant_id, staff_id, use_count)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='诊断高频使用沉淀(诊断助手数据源)'"
            );
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_disease_report ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT DEFAULT NULL COMMENT '机构ID',"
                    + "visit_id BIGINT DEFAULT NULL COMMENT '就诊ID(his_visit.id)',"
                    + "patient_id BIGINT DEFAULT NULL COMMENT '患者ID',"
                    + "diag_code VARCHAR(50) DEFAULT NULL COMMENT '诊断代码',"
                    + "diag_name VARCHAR(200) DEFAULT NULL COMMENT '诊断名称',"
                    + "report_type TINYINT DEFAULT 1 COMMENT '报卡类型:1法定传染病 2慢性病 3其他',"
                    + "report_no VARCHAR(50) DEFAULT NULL COMMENT '报卡编号',"
                    + "report_status TINYINT DEFAULT 0 COMMENT '报卡状态:0待报 1已报 2已审核',"
                    + "report_content VARCHAR(2000) DEFAULT NULL COMMENT '报卡内容摘要',"
                    + "report_time DATETIME DEFAULT NULL COMMENT '报告时间',"
                    + "reporter VARCHAR(50) DEFAULT NULL COMMENT '报告人姓名',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_visit (tenant_id, visit_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='疾病报卡(与诊断关联留痕)'"
            );
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_diag_template_link ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT DEFAULT NULL COMMENT '机构ID',"
                    + "diag_code VARCHAR(50) NOT NULL COMMENT '诊断代码',"
                    + "diag_name VARCHAR(200) DEFAULT NULL COMMENT '诊断名称',"
                    + "template_type VARCHAR(20) NOT NULL COMMENT '模板类型: rx_set处方组套/order_set医嘱组套',"
                    + "template_id BIGINT NOT NULL COMMENT '模板ID(his_medical_template.id)',"
                    + "dept_id BIGINT DEFAULT NULL COMMENT '适用科室ID(空=通用)',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_diag (tenant_id, diag_code)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='诊断→医嘱/处方模板关联映射'"
            );
        }
        // his_diagnosis 牙位补列(口腔诊断, FDI/Palmer 编码拼接串)
        addColumnIfNotExists(conn, "his_diagnosis", "tooth_position", "VARCHAR(200) DEFAULT NULL COMMENT '牙位编码(FDI/Palmer, 口腔诊断专用)'");
        // his_medical_cert 诊断证明审核流补列
        addColumnIfNotExists(conn, "his_medical_cert", "audit_status", "TINYINT DEFAULT 0 COMMENT '审核状态:0无须审核 1待审 2通过 3驳回'");
        addColumnIfNotExists(conn, "his_medical_cert", "auditor_id", "BIGINT DEFAULT NULL COMMENT '审核人ID'");
        addColumnIfNotExists(conn, "his_medical_cert", "auditor_name", "VARCHAR(50) DEFAULT NULL COMMENT '审核人姓名'");
        addColumnIfNotExists(conn, "his_medical_cert", "audit_time", "DATETIME DEFAULT NULL COMMENT '审核时间'");
        addColumnIfNotExists(conn, "his_medical_cert", "audit_remark", "VARCHAR(500) DEFAULT NULL COMMENT '审核意见/驳回原因'");
        // his_dept 诊断证明审核开关(按科室配置: 0直接可打印 1需审核)
        addColumnIfNotExists(conn, "his_dept", "cert_audit_required", "TINYINT DEFAULT 0 COMMENT '诊断证明审核开关:0直接可打印 1需审核'");
    }

    /**
     * 门诊医生站对标优化 OP-C(医嘱/处方专业化, 需求2.2.2.3.14.3/14.4):
     * 拆方规则/自动计费规则/医嘱处方高频/慢特病备案 4 新表 + his_prescription_item 草药5列 + his_drug_catalog 适应症列(幂等)。
     */
    private void ensureOutpRxOrderTables(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_rx_split_rule ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT DEFAULT NULL COMMENT '机构ID(空=租户通用)',"
                    + "dept_id BIGINT DEFAULT NULL COMMENT '科室ID(空=全院通用)',"
                    + "rule_name VARCHAR(100) DEFAULT NULL COMMENT '规则名称',"
                    + "split_dim VARCHAR(30) NOT NULL COMMENT '拆方维度: usage/insutype/chronic_dise/special_drug/pharmacy',"
                    + "dim_value VARCHAR(100) DEFAULT NULL COMMENT '维度匹配值',"
                    + "priority INT NOT NULL DEFAULT 100 COMMENT '拆分优先级(小者优先)',"
                    + "status TINYINT NOT NULL DEFAULT 1 COMMENT '状态:1启用 0停用',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_org_dept (tenant_id, org_id, dept_id, status)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='处方自动拆方规则'"
            );
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_charge_addon_rule ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT DEFAULT NULL COMMENT '机构ID(空=租户通用)',"
                    + "dept_id BIGINT DEFAULT NULL COMMENT '科室ID(空=全院通用)',"
                    + "item_id BIGINT NOT NULL COMMENT '主项目ID(his_base_item.id)',"
                    + "item_name VARCHAR(200) DEFAULT NULL COMMENT '主项目名称(冗余)',"
                    + "dim_type VARCHAR(30) NOT NULL COMMENT '加收维度: part/index/consult/herb_process',"
                    + "dim_threshold INT NOT NULL DEFAULT 1 COMMENT '触发阈值',"
                    + "calc_mode VARCHAR(20) NOT NULL DEFAULT 'fixed' COMMENT '计价方式: fixed/formula',"
                    + "unit_price DECIMAL(12,2) DEFAULT NULL COMMENT '加收单位价格',"
                    + "formula VARCHAR(500) DEFAULT NULL COMMENT '计费公式(formula模式)',"
                    + "addon_item_code VARCHAR(50) DEFAULT NULL COMMENT '加收项编码',"
                    + "addon_item_name VARCHAR(200) DEFAULT NULL COMMENT '加收项名称',"
                    + "status TINYINT NOT NULL DEFAULT 1 COMMENT '状态:1启用 0停用',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_item (tenant_id, item_id, status),"
                    + "KEY idx_org_dept (tenant_id, org_id, dept_id, status)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='开立项目自动计费(加收)规则'"
            );
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_order_freq ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT DEFAULT NULL COMMENT '机构ID',"
                    + "staff_id BIGINT DEFAULT NULL COMMENT '医师ID(个人常用维度)',"
                    + "dept_id BIGINT DEFAULT NULL COMMENT '科室ID(科室高频维度)',"
                    + "item_kind VARCHAR(20) NOT NULL COMMENT '项目类型: rx处方项/order医嘱项',"
                    + "item_code VARCHAR(50) NOT NULL COMMENT '项目代码',"
                    + "item_name VARCHAR(200) DEFAULT NULL COMMENT '项目名称',"
                    + "use_count INT NOT NULL DEFAULT 0 COMMENT '累计使用次数',"
                    + "last_time DATETIME DEFAULT NULL COMMENT '最近使用时间',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_staff_dept_item (tenant_id, staff_id, dept_id, item_kind, item_code),"
                    + "KEY idx_dept (tenant_id, dept_id, use_count),"
                    + "KEY idx_staff (tenant_id, staff_id, use_count)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='医嘱/处方高频使用沉淀(助手数据源)'"
            );
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_chronic_disease ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT DEFAULT NULL COMMENT '机构ID',"
                    + "patient_id BIGINT NOT NULL COMMENT '患者ID(his_patient.id)',"
                    + "patient_name VARCHAR(100) DEFAULT NULL COMMENT '患者姓名(冗余)',"
                    + "dise_code VARCHAR(50) NOT NULL COMMENT '病种编码',"
                    + "dise_name VARCHAR(200) DEFAULT NULL COMMENT '病种名称',"
                    + "dise_type VARCHAR(20) DEFAULT NULL COMMENT '备案类型:1门特 2门慢',"
                    + "register_no VARCHAR(50) DEFAULT NULL COMMENT '备案编号',"
                    + "valid_from DATE DEFAULT NULL COMMENT '备案有效期起',"
                    + "valid_to DATE DEFAULT NULL COMMENT '备案有效期止',"
                    + "status TINYINT NOT NULL DEFAULT 1 COMMENT '状态:1有效 0失效',"
                    + "source VARCHAR(20) DEFAULT 'manual' COMMENT '来源: manual/import/insutype',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_patient (tenant_id, patient_id, status)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='患者门诊慢特病备案'"
            );
        }
        // his_prescription_item 草药/中成药专业化补列(煎法/炮制/治法/药剂形式/倍数基础量)
        addColumnIfNotExists(conn, "his_prescription_item", "decoction", "VARCHAR(30) DEFAULT NULL COMMENT '煎法: 先煎/后煎/包煎/烊化等'");
        addColumnIfNotExists(conn, "his_prescription_item", "processing", "VARCHAR(30) DEFAULT NULL COMMENT '炮制: 炒/炙/煅/蒸等'");
        addColumnIfNotExists(conn, "his_prescription_item", "therapy", "VARCHAR(50) DEFAULT NULL COMMENT '治法: 汗/吐/下/和/温/清/消/补等'");
        addColumnIfNotExists(conn, "his_prescription_item", "herb_form", "VARCHAR(20) DEFAULT NULL COMMENT '药剂形式: 饮片/颗粒/成药/自备'");
        addColumnIfNotExists(conn, "his_prescription_item", "multiple_base", "INT DEFAULT 0 COMMENT '倍数基础量(0=不启用), 单味剂量须为其整数倍'");
        // his_drug_catalog 适应症编码补列(医嘱开立适应症/给药途径/频次联审)
        addColumnIfNotExists(conn, "his_drug_catalog", "indication_codes", "VARCHAR(500) DEFAULT NULL COMMENT '适应症编码(院内用药规则, 供联审)'");
    }

    /**
     * 幂等补列: 多库房/多药房/发票/混合支付/部分退费改造涉及的 6 张存量表新增列
     * (药库归属/药房归属/支付方式/发票号/退费关联原单/已退数量), 列已存在则跳过。
     */
    private void alterExistingTables(Connection conn) throws Exception {
        addColumnIfNotExists(conn, "his_drug_stock", "warehouse_id", "BIGINT DEFAULT NULL COMMENT '药库ID(his_warehouse_def.id)'");
        addColumnIfNotExists(conn, "his_stock_in", "warehouse_id", "BIGINT DEFAULT NULL COMMENT '药库ID(his_warehouse_def.id)'");
        addColumnIfNotExists(conn, "his_stock_out", "warehouse_id", "BIGINT DEFAULT NULL COMMENT '药库ID(his_warehouse_def.id)'");
        addColumnIfNotExists(conn, "his_dispense", "pharmacy_id", "BIGINT DEFAULT NULL COMMENT '药房ID(his_pharmacy_def.id)'");
        // P1 发药窗口: 分配结果落库 + 窗口级签到状态(0未签到 1已签到)
        addColumnIfNotExists(conn, "his_dispense", "window_id", "BIGINT DEFAULT NULL COMMENT '发药窗口ID(his_pharmacy_window.id, P1智能分窗分配结果)'");
        addColumnIfNotExists(conn, "his_dispense", "signin_status", "TINYINT DEFAULT 0 COMMENT '窗口签到状态:0未签到 1已签到(仅签到型窗口有意义)'");
        /* ---------- 三期: 发药药房路由与药房维度定价 ---------- */
        // 科室×中西药渠道默认发药药房(开方未手选时按 rxType 渠道回落)
        addColumnIfNotExists(conn, "his_dept", "def_pharmacy_west", "BIGINT DEFAULT NULL COMMENT '默认发药药房-西药渠道(his_pharmacy_def.id)'");
        addColumnIfNotExists(conn, "his_dept", "def_pharmacy_tcm", "BIGINT DEFAULT NULL COMMENT '默认发药药房-中药渠道(his_pharmacy_def.id)'");
        // 处方绑定发药药房(开方确定/改派更新; 空=发药时全院FIFO兼容存量)
        addColumnIfNotExists(conn, "his_prescription", "pharmacy_id", "BIGINT DEFAULT NULL COMMENT '发药药房ID(his_pharmacy_def.id, 开方绑定/改派更新)'");
        addColumnIfNotExists(conn, "his_prescription", "transfer_from_pharmacy_id", "BIGINT DEFAULT NULL COMMENT '改派来源药房ID(发药时随转至发药记录留痕)'");
        /* ---------- P2 门诊处方审核: 审核状态标志位(与 dispense_status 并行) + 审核来源/人/时间/驳回原因 ---------- */
        addColumnIfNotExists(conn, "his_prescription", "audit_status", "TINYINT DEFAULT 0 COMMENT '处方审核状态:0无需 1待审 2通过 3驳回(P2门诊药审)'");
        addColumnIfNotExists(conn, "his_prescription", "audit_src", "VARCHAR(10) DEFAULT NULL COMMENT '审核来源:manual人工/auto自动(P2)'");
        addColumnIfNotExists(conn, "his_prescription", "audit_by", "VARCHAR(50) DEFAULT NULL COMMENT '审核药师姓名(P2)'");
        addColumnIfNotExists(conn, "his_prescription", "audit_time", "DATETIME DEFAULT NULL COMMENT '审核时间(P2)'");
        addColumnIfNotExists(conn, "his_prescription", "reject_reason", "VARCHAR(500) DEFAULT NULL COMMENT '审核驳回原因(P2)'");
        /* ---------- P3 追溯码发药闭环: 药品目录三码校验字段 + 药品级追溯强制开关(与窗口级 trace_required 取或) ---------- */
        addColumnIfNotExists(conn, "his_drug_catalog", "commodity_code", "VARCHAR(64) DEFAULT NULL COMMENT '商品码/条形码(EAN-13等, 三码校验之一)(P3)'");
        addColumnIfNotExists(conn, "his_drug_catalog", "supervision_code", "VARCHAR(64) DEFAULT NULL COMMENT '电子监管码(中国药品电子监管码, 三码校验之一)(P3)'");
        addColumnIfNotExists(conn, "his_drug_catalog", "trace_flag", "TINYINT DEFAULT 0 COMMENT '药品级追溯码强制开关:1需扫 0否(P3, 与窗口级trace_required取或)'");
        // 发药记录留痕: 本单追溯是否强制 + 已扫追溯码数(P3 发药闭环可观测)
        addColumnIfNotExists(conn, "his_dispense", "trace_required", "TINYINT DEFAULT 0 COMMENT '本单追溯码强制:0否 1是(P3, 窗口级或药品级命中)'");
        addColumnIfNotExists(conn, "his_dispense", "trace_scanned", "INT DEFAULT 0 COMMENT '本单发药已绑定追溯码数(P3)'");
        // 发药价差对账: 实发批次零售金额与价差(=实发-计费, 仅院内对账不补退) + 改派来源房留痕
        addColumnIfNotExists(conn, "his_dispense", "stock_amount", "DECIMAL(12,2) DEFAULT NULL COMMENT '实发批次零售金额(发药时按出库批次价汇总, 院内对账)'");
        addColumnIfNotExists(conn, "his_dispense", "price_diff", "DECIMAL(12,2) DEFAULT NULL COMMENT '价差=实发-计费(不向患者补退, 仅对账)'");
        addColumnIfNotExists(conn, "his_dispense", "transfer_from_pharmacy_id", "BIGINT DEFAULT NULL COMMENT '改派来源药房ID(库存不足改派留痕)'");
        /* ---------- 四期: 药房/药库与科室一一对应(归属科室, 驱动按科室授权的库房操作权限) ---------- */
        addColumnIfNotExists(conn, "his_pharmacy_def", "dept_id", "BIGINT DEFAULT NULL COMMENT '归属科室(his_dept.id, 一一对应; 空=历史未绑定)'");
        addColumnIfNotExists(conn, "his_warehouse_def", "dept_id", "BIGINT DEFAULT NULL COMMENT '归属科室(his_dept.id, 仅kind=WAREHOUSE; 一一对应; 空=历史未绑定)'");
        addColumnIfNotExists(conn, "his_charge_bill", "pay_method", "VARCHAR(20) DEFAULT NULL COMMENT '主要支付方式:CASH/WECHAT/ALIPAY/CARD/INSURANCE/FREE'");
        /* ---------- 批次4: 收费单医保结算状态(与 cols 双保险; NOT NULL DEFAULT 0 存量行自动回填) ---------- */
        addColumnIfNotExists(conn, "his_charge_bill", "yb_status", "TINYINT NOT NULL DEFAULT 0 COMMENT '医保结算状态:0未结算 1结算中 2已结算 3撤销中 4已撤销 9冲正中'");
        addColumnIfNotExists(conn, "his_charge_bill", "origin_bill_id", "BIGINT DEFAULT NULL COMMENT '退费关联原单ID(退费单指向原收费单)'");
        addColumnIfNotExists(conn, "his_charge_bill", "invoice_no", "VARCHAR(50) DEFAULT NULL COMMENT '发票号'");
        addColumnIfNotExists(conn, "his_charge_bill_item", "refunded_qty", "DECIMAL(12,4) DEFAULT 0 COMMENT '已退数量(部分退费追踪)'");
        /* ---------- 护士站/治疗/医技三模块基座: his_order 执行状态/执行科室/收费标志(实体已映射, 关键段双保险) ---------- */
        addColumnIfNotExists(conn, "his_order", "exec_status", "TINYINT DEFAULT 0 COMMENT '执行状态:0未执行 1已执行'");
        addColumnIfNotExists(conn, "his_order", "exec_dept_id", "BIGINT DEFAULT NULL COMMENT '执行科室ID(his_dept.id)'");
        addColumnIfNotExists(conn, "his_order", "paid_flag", "TINYINT DEFAULT 0 COMMENT '收费标志:0未收费 1已收费'");
        /* 治疗执行: 患者签到时间(排队口径) + 取消原因(执行状态:0待执行 1执行中 2已完成 3已取消) */
        addColumnIfNotExists(conn, "his_treatment_exec", "checkin_time", "DATETIME DEFAULT NULL COMMENT '患者签到时间(非空=已签到, 排队口径)'");
        addColumnIfNotExists(conn, "his_treatment_exec", "cancel_reason", "VARCHAR(200) DEFAULT NULL COMMENT '取消原因'");
        /* 对账差异表 org_id 可空修正: 租户级对账无机构归属(早期 DDL 误为 NOT NULL, 存量库需 MODIFY) */
        if (tableExists(conn, "his_recon_diff") && "NO".equals(columnNullable(conn, "his_recon_diff", "org_id"))) {
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("ALTER TABLE his_recon_diff MODIFY org_id BIGINT DEFAULT NULL COMMENT '机构ID(租户级对账为空)'");
            }
        }
        /* ---------- 批次5 M1: 2404 追溯码报送凭据列 + upload_status 语义归一(存量库; 新库由建表语句自带) ---------- */
        if (tableExists(conn, "his_drug_trace_code") && !columnExists(conn, "his_drug_trace_code", "upload_msgid")) {
            addColumnIfNotExists(conn, "his_drug_trace_code", "upload_msgid", "VARCHAR(40) DEFAULT NULL COMMENT '2404报送发送方报文ID(UNKNOWN复核/重发凭据, 批次5 M1)'");
            addColumnIfNotExists(conn, "his_drug_trace_code", "upload_batch_no", "VARCHAR(40) DEFAULT NULL COMMENT '2404报送批次/平台回执报文ID(批次5 M1)'");
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("ALTER TABLE his_drug_trace_code MODIFY upload_status TINYINT DEFAULT 0 COMMENT '报送状态:0未报送 1报送中 2失败待补 9已报送(批次5 M1归一)'");
            }
        }
    }

    /**
     * 幂等建表: 门诊医生站诊间业务与集成 OP-D(需求 2.2.2.3.14.8/14.11)。
     * 5 新表 + his_medical_template.is_fav / his_admission_cert.pre_flag 两补列, 全部幂等。
     */
    private void ensureOutpWsIntegrationTables(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            /* 门诊知情同意书: 特殊检查/特殊治疗/输血/自费/病危五类, 医师谈话+患者(家属)签字闭环 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_consent ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "visit_id BIGINT NOT NULL COMMENT '门诊就诊ID(his_visit.id)',"
                    + "patient_id BIGINT DEFAULT NULL COMMENT '患者ID',"
                    + "patient_name VARCHAR(50) DEFAULT NULL COMMENT '患者姓名',"
                    + "consent_type INT NOT NULL COMMENT '同意书类型:1特殊检查 2特殊治疗 3输血 4自费 5病危',"
                    + "title VARCHAR(200) NOT NULL COMMENT '同意书标题',"
                    + "content TEXT NULL COMMENT '同意书内容(告知事项正文)',"
                    + "doctor_id BIGINT DEFAULT NULL COMMENT '谈话医师ID(his_staff.id)',"
                    + "doctor_name VARCHAR(50) DEFAULT NULL COMMENT '谈话医师姓名',"
                    + "patient_sign_name VARCHAR(50) DEFAULT NULL COMMENT '患者/家属签署姓名',"
                    + "relation VARCHAR(20) DEFAULT NULL COMMENT '签署人与患者关系(本人/配偶/父母子女等)',"
                    + "witness_name VARCHAR(50) DEFAULT NULL COMMENT '见证人姓名',"
                    + "sign_time DATETIME DEFAULT NULL COMMENT '患者签署时间',"
                    + "doctor_sign_time DATETIME DEFAULT NULL COMMENT '医师签署时间',"
                    + "status INT DEFAULT 1 COMMENT '状态:1待签 2已签 3已撤销',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_visit (visit_id),"
                    + "KEY idx_org_status (tenant_id, org_id, status)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='门诊知情同意书'");
            /* 代办登记: 家属/监护人代患者问诊留痕(身份+关系+事由) */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_outp_agent ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "visit_id BIGINT NOT NULL COMMENT '门诊就诊ID(his_visit.id)',"
                    + "patient_id BIGINT DEFAULT NULL COMMENT '患者ID',"
                    + "patient_name VARCHAR(50) DEFAULT NULL COMMENT '患者姓名',"
                    + "agent_name VARCHAR(50) NOT NULL COMMENT '代办人姓名',"
                    + "agent_id_card VARCHAR(30) DEFAULT NULL COMMENT '代办人身份证号',"
                    + "agent_phone VARCHAR(20) DEFAULT NULL COMMENT '代办人联系电话',"
                    + "relation VARCHAR(20) NOT NULL COMMENT '与患者关系',"
                    + "reason VARCHAR(200) DEFAULT NULL COMMENT '代办事由',"
                    + "status INT DEFAULT 1 COMMENT '状态:1有效 0作废',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_visit (visit_id),"
                    + "KEY idx_patient (tenant_id, patient_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='门诊代办登记'");
            /* 转诊登记: 上转/下转双向, 申请→接收→完成状态机(医共体转诊业务) */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_referral ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID(转出/接收方归属)',"
                    + "visit_id BIGINT DEFAULT NULL COMMENT '关联门诊就诊ID(his_visit.id)',"
                    + "patient_id BIGINT NOT NULL COMMENT '患者ID',"
                    + "patient_name VARCHAR(50) DEFAULT NULL COMMENT '患者姓名',"
                    + "direction INT NOT NULL DEFAULT 1 COMMENT '方向:1上转/转出 2下转/接收',"
                    + "to_hospital VARCHAR(100) NOT NULL COMMENT '目标医院',"
                    + "to_dept VARCHAR(50) DEFAULT NULL COMMENT '目标科室',"
                    + "reason VARCHAR(500) DEFAULT NULL COMMENT '转诊原因/病情',"
                    + "summary TEXT NULL COMMENT '病情摘要(转诊单正文)',"
                    + "contact_phone VARCHAR(20) DEFAULT NULL COMMENT '联系电话',"
                    + "status INT DEFAULT 1 COMMENT '状态:1已申请 2已接收 3已完成 4已取消',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_patient (tenant_id, patient_id),"
                    + "KEY idx_org_status (tenant_id, org_id, status)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='门诊转诊登记'");
            /* 绿色通道信用额度: 急危重症先诊疗后付费的信用台账 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_green_channel_credit ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "patient_id BIGINT NOT NULL COMMENT '患者ID',"
                    + "patient_name VARCHAR(50) DEFAULT NULL COMMENT '患者姓名',"
                    + "visit_id BIGINT DEFAULT NULL COMMENT '开通时就诊ID(his_visit.id)',"
                    + "credit_limit DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '信用额度(元)',"
                    + "used_amount DECIMAL(10,2) DEFAULT 0 COMMENT '已使用额度(元)',"
                    + "reason VARCHAR(200) DEFAULT NULL COMMENT '开通原因(急危重症/证件缺失等)',"
                    + "status INT DEFAULT 1 COMMENT '状态:1启用 0关闭',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_patient (tenant_id, patient_id, status)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='绿色通道信用额度(先诊疗后付费)'");
            /* 犬伤登记: 暴露分级/处置/免疫程序随访计划 */
            st.executeUpdate("CREATE TABLE IF NOT EXISTS his_dog_bite_register ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + "tenant_id BIGINT NOT NULL COMMENT '租户ID',"
                    + "org_id BIGINT NOT NULL COMMENT '机构ID',"
                    + "visit_id BIGINT NOT NULL COMMENT '门诊就诊ID(his_visit.id)',"
                    + "patient_id BIGINT DEFAULT NULL COMMENT '患者ID',"
                    + "patient_name VARCHAR(50) DEFAULT NULL COMMENT '患者姓名',"
                    + "expose_time DATETIME DEFAULT NULL COMMENT '暴露(咬伤/抓伤)时间',"
                    + "animal_type VARCHAR(20) DEFAULT NULL COMMENT '致伤动物:犬/猫/其他',"
                    + "dog_info VARCHAR(200) DEFAULT NULL COMMENT '动物来源与免疫/观察情况',"
                    + "wound_grade INT DEFAULT NULL COMMENT '伤口分级:1Ⅰ级 2Ⅱ级 3Ⅲ级',"
                    + "wound_parts VARCHAR(100) DEFAULT NULL COMMENT '暴露部位',"
                    + "wound_count INT DEFAULT 1 COMMENT '伤口数量',"
                    + "wound_handling VARCHAR(300) DEFAULT NULL COMMENT '伤口处置(冲洗/消毒等)',"
                    + "vaccine_plan VARCHAR(50) DEFAULT NULL COMMENT '免疫程序:五针法/四针法(2-1-1)',"
                    + "vaccine_first_time DATETIME DEFAULT NULL COMMENT '首针时间',"
                    + "vaccine_next_date DATE DEFAULT NULL COMMENT '下次接种日期',"
                    + "immunoglobulin TINYINT DEFAULT 0 COMMENT '被动免疫制剂:1已注射 0未注射',"
                    + "doctor_id BIGINT DEFAULT NULL COMMENT '登记医师ID(his_staff.id)',"
                    + "doctor_name VARCHAR(50) DEFAULT NULL COMMENT '登记医师姓名',"
                    + "status INT DEFAULT 1 COMMENT '状态:1已登记 0作废',"
                    + "create_by VARCHAR(50) DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by VARCHAR(50) DEFAULT NULL, update_time DATETIME DEFAULT NULL, deleted TINYINT DEFAULT 0,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_visit (visit_id),"
                    + "KEY idx_patient (tenant_id, patient_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='犬伤暴露登记'");
        }
        /* 医疗模板收藏标记(医生站模板下拉收藏置顶) */
        addColumnIfNotExists(conn, "his_medical_template", "is_fav",
                "TINYINT DEFAULT 0 COMMENT '收藏标记:1收藏(列表置顶) 0普通'");
        /* 住院证预开卡标记(先开证锁床, 持证入院核销逻辑不受影响) */
        addColumnIfNotExists(conn, "his_admission_cert", "pre_flag",
                "TINYINT DEFAULT 0 COMMENT '预开卡标记:1预开卡锁床 0常规'");
    }

    /** 幂等补列: 表存在且列不存在时 ALTER TABLE ADD COLUMN(与 cols 循环同语义, 供建表后存量表补列使用)。 */
    private void addColumnIfNotExists(Connection conn, String table, String column, String definition) throws Exception {
        if (tableExists(conn, table) && !columnExists(conn, table, column)) {
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            }
        }
    }

    /** 幂等回填 sys_org.price_lv 默认档: 县级牵头→2档(县医院二级), 乡镇/村→1档; 已有值不覆盖。 */
    private void ensureOrgPriceLv(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("UPDATE sys_org SET price_lv = CASE WHEN org_level = 1 THEN 2 ELSE 1 END WHERE price_lv IS NULL");
        }
    }

    /** 幂等补种子: 医共体药品/耗材目录管理值域(剂型/管理类别/储存条件/剂量单位/包装单位, 湖北规范未含按药事管理惯例补充) */
    private void ensureCommunityDictSeeds(Connection conn) throws Exception {
        String sql = "INSERT INTO std_cv_code (dict_code, dict_name, val_code, val_name, std_type, src_doc, vali_flag) "
                + "SELECT ?, ?, ?, ?, '医保字典', '医共体药品/耗材目录管理值域(补充种子, 药事管理惯例)', '1' "
                + "FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM std_cv_code c WHERE c.dict_code = ? AND c.val_code = ?)";
        String[][] seeds = {
                {"dosform", "剂型", "片剂"}, {"dosform", "剂型", "胶囊剂"}, {"dosform", "剂型", "颗粒剂"},
                {"dosform", "剂型", "注射剂"}, {"dosform", "剂型", "注射用无菌粉末"}, {"dosform", "剂型", "口服溶液剂"},
                {"dosform", "剂型", "丸剂"}, {"dosform", "剂型", "膏剂"}, {"dosform", "剂型", "贴剂"},
                {"dosform", "剂型", "气雾剂"}, {"dosform", "剂型", "吸入剂"}, {"dosform", "剂型", "滴眼剂"},
                {"dosform", "剂型", "鼻喷剂"}, {"dosform", "剂型", "糖浆剂"}, {"dosform", "剂型", "冻干粉针"},
                {"dosform", "剂型", "其他"},
                {"drug_class", "药品管理类别", "一般药品"}, {"drug_class", "药品管理类别", "麻醉药品"},
                {"drug_class", "药品管理类别", "第一类精神药品"}, {"drug_class", "药品管理类别", "第二类精神药品"},
                {"drug_class", "药品管理类别", "医疗用毒性药品"}, {"drug_class", "药品管理类别", "放射性药品"},
                {"drug_class", "药品管理类别", "易制毒化学品"},
                {"storage_cond", "储存条件", "常温"}, {"storage_cond", "储存条件", "阴凉"},
                {"storage_cond", "储存条件", "冷藏"}, {"storage_cond", "储存条件", "冷冻"},
                {"storage_cond", "储存条件", "避光"}, {"storage_cond", "储存条件", "密封"},
                {"dose_unit", "剂量单位", "g"}, {"dose_unit", "剂量单位", "mg"}, {"dose_unit", "剂量单位", "μg"},
                {"dose_unit", "剂量单位", "IU"}, {"dose_unit", "剂量单位", "U"}, {"dose_unit", "剂量单位", "mL"},
                {"dose_unit", "剂量单位", "L"}, {"dose_unit", "剂量单位", "%"}, {"dose_unit", "剂量单位", "片"},
                {"dose_unit", "剂量单位", "粒"}, {"dose_unit", "剂量单位", "支"}, {"dose_unit", "剂量单位", "袋"},
                {"pack_unit", "包装/发药单位", "盒"}, {"pack_unit", "包装/发药单位", "瓶"}, {"pack_unit", "包装/发药单位", "包"},
                {"pack_unit", "包装/发药单位", "袋"}, {"pack_unit", "包装/发药单位", "支"}, {"pack_unit", "包装/发药单位", "片"},
                {"pack_unit", "包装/发药单位", "粒"}, {"pack_unit", "包装/发药单位", "个"}, {"pack_unit", "包装/发药单位", "套"},
                {"pack_unit", "包装/发药单位", "贴"}, {"pack_unit", "包装/发药单位", "管"}, {"pack_unit", "包装/发药单位", "箱"},
        };
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (String[] s : seeds) {
                ps.setString(1, s[0]); ps.setString(2, s[1]); ps.setString(3, s[2]); ps.setString(4, s[2]);
                ps.setString(5, s[0]); ps.setString(6, s[2]);
                ps.executeUpdate();
            }
        }
    }

    /** 幂等补种子: 医共体值域「药品大类」(医保目录分类: 西药/中成药/中药饮片/医疗机构制剂/其他, code=name 存文本, 供药品目录大类下拉; 现有目录数据均在牵头租户 1) */
    private void ensureDrugMajorClassDict(Connection conn) throws Exception {
        String sql = "INSERT INTO his_val_dict (tenant_id, dict_type, type_name, code, name, sort_no, status, src_type, src_doc, create_time, deleted) "
                + "SELECT 1, '药品大类', '药品大类', ?, ?, ?, 1, '院内补充', '医共体目录管理值域(药品大类, 医保目录分类补充种子)', NOW(), 0 "
                + "FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM his_val_dict v WHERE v.tenant_id = 1 AND v.dict_type = '药品大类' AND v.code = ?)";
        String[][] seeds = {
                {"西药", "1"}, {"中成药", "2"}, {"中药饮片", "3"}, {"医疗机构制剂", "4"}, {"其他", "9"},
        };
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (String[] s : seeds) {
                ps.setString(1, s[0]); ps.setString(2, s[0]); ps.setString(3, s[1]); ps.setString(4, s[0]);
                ps.executeUpdate();
            }
        }
    }

    /** 查列的数据类型(information_schema.columns.data_type), 不存在返回 null。 */
    private String columnDataType(Connection conn, String table, String column) throws Exception {
        String sql = "SELECT data_type FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** 查列是否可空(information_schema.columns.is_nullable: YES/NO), 表或列不存在返回 null。 */
    private String columnNullable(Connection conn, String table, String column) throws Exception {
        String sql = "SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** 查列字符容量(information_schema.columns.character_maximum_length), 非字符列或不存在返回 null。 */
    private Integer columnCharLen(Connection conn, String table, String column) throws Exception {
        String sql = "SELECT character_maximum_length FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                int v = rs.getInt(1);
                return rs.wasNull() ? null : v;
            }
        }
    }

    private boolean columnExists(Connection conn, String table, String column) throws Exception {
        String sql = "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }
}
