package com.yb.hi.platform;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
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
            seedRegLevelDict(conn);
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
            /* ---------- 多库房/多药房/发票/混合支付/盘点: 幂等建表(表已存在则跳过) ---------- */
            ensureWarehouseDefTable(conn);
            ensurePharmacyDefTable(conn);
            ensureInvoicePoolTable(conn);
            ensureInvoiceTable(conn);
            ensurePaymentDetailTable(conn);
            ensureStockCheckTables(conn);
            // 二级库存专业化(P1-P5): 请领/调拨/调价/追溯码 新表(幂等, 新模块非启动关键路径)
            ensureStockChainTables(conn);
            // 三期: 药房维度定价覆盖表(新发药/定价链路直接依赖, 与关键段双保险幂等)
            ensurePharmacyPriceTable(conn);
            // 护士站/治疗管理/医技管理三模块基座: 12 张新表(幂等, 新模块非启动关键路径)
            ensureNurseTables(conn);
            ensureTreatmentTables(conn);
            ensureMedtechTables(conn);
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
                    + "biz_type VARCHAR(10) DEFAULT NULL COMMENT '业务类型: REG/VISIT/RX/FEE/SETL/CANCEL',"
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
                    + "upload_status TINYINT DEFAULT 0 COMMENT '报送状态:0未报送 9已报送',"
                    + "upload_time DATETIME DEFAULT NULL COMMENT '报送时间',"
                    + "upload_receipt VARCHAR(200) DEFAULT NULL COMMENT '报送回执( Mock)',"
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
     * 幂等补列: 多库房/多药房/发票/混合支付/部分退费改造涉及的 6 张存量表新增列
     * (药库归属/药房归属/支付方式/发票号/退费关联原单/已退数量), 列已存在则跳过。
     */
    private void alterExistingTables(Connection conn) throws Exception {
        addColumnIfNotExists(conn, "his_drug_stock", "warehouse_id", "BIGINT DEFAULT NULL COMMENT '药库ID(his_warehouse_def.id)'");
        addColumnIfNotExists(conn, "his_stock_in", "warehouse_id", "BIGINT DEFAULT NULL COMMENT '药库ID(his_warehouse_def.id)'");
        addColumnIfNotExists(conn, "his_stock_out", "warehouse_id", "BIGINT DEFAULT NULL COMMENT '药库ID(his_warehouse_def.id)'");
        addColumnIfNotExists(conn, "his_dispense", "pharmacy_id", "BIGINT DEFAULT NULL COMMENT '药房ID(his_pharmacy_def.id)'");
        /* ---------- 三期: 发药药房路由与药房维度定价 ---------- */
        // 科室×中西药渠道默认发药药房(开方未手选时按 rxType 渠道回落)
        addColumnIfNotExists(conn, "his_dept", "def_pharmacy_west", "BIGINT DEFAULT NULL COMMENT '默认发药药房-西药渠道(his_pharmacy_def.id)'");
        addColumnIfNotExists(conn, "his_dept", "def_pharmacy_tcm", "BIGINT DEFAULT NULL COMMENT '默认发药药房-中药渠道(his_pharmacy_def.id)'");
        // 处方绑定发药药房(开方确定/改派更新; 空=发药时全院FIFO兼容存量)
        addColumnIfNotExists(conn, "his_prescription", "pharmacy_id", "BIGINT DEFAULT NULL COMMENT '发药药房ID(his_pharmacy_def.id, 开方绑定/改派更新)'");
        addColumnIfNotExists(conn, "his_prescription", "transfer_from_pharmacy_id", "BIGINT DEFAULT NULL COMMENT '改派来源药房ID(发药时随转至发药记录留痕)'");
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
