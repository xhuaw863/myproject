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
                /* ---------- 医保对照变更留痕: 生效时间 + 变更前医保码(三目录统一) ---------- */
                {"his_drug_catalog", "prev_yb_code", "VARCHAR(64) NULL COMMENT '变更前医保码(上一次对照的医保编码)'"},
                {"his_drug_catalog", "yb_map_eff_time", "DATETIME NULL COMMENT '医保对照生效时间(当前医保码开始生效时刻)'"},
                {"his_cons_catalog", "prev_yb_code", "VARCHAR(64) NULL COMMENT '变更前医保码(上一次对照的医保编码)'"},
                {"his_cons_catalog", "yb_map_eff_time", "DATETIME NULL COMMENT '医保对照生效时间(当前医保码开始生效时刻)'"},
                {"his_charge_item", "prev_yb_code", "VARCHAR(64) NULL COMMENT '变更前医保码(上一次对照的医保编码)'"},
                {"his_charge_item", "yb_map_eff_time", "DATETIME NULL COMMENT '医保对照生效时间(当前医保码开始生效时刻)'"},
        };
        int added = 0;
        try (Connection conn = dataSource.getConnection()) {
            ensureChangeLogTable(conn);
            ensurePatientInsuTable(conn);
            ensurePsnInsuStasDict(conn);
            ensureCommunityDictTables(conn);
            ensureYbMapLogTable(conn);
            ensureCommunityDictSeeds(conn);
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
            // 患者出生日期精度升级: DATE -> DATETIME(新生儿需精确到时分秒; 幂等, 仅当前类型为 date 时 MODIFY)
            if ("date".equalsIgnoreCase(columnDataType(conn, "his_patient", "birth_date"))) {
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate("ALTER TABLE his_patient MODIFY COLUMN birth_date DATETIME NULL COMMENT '出生日期时间(精确到秒)'");
                    added++;
                }
            }
        } catch (Exception e) {
            log.warn("字典化字段建列迁移跳过: {}", e.getMessage());
            return;
        }
        if (added > 0) {
            log.info("字典化字段建列迁移完成, 新增/变更 {} 列", added);
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
