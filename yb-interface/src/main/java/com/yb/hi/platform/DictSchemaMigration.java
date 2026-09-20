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
        };
        int added = 0;
        try (Connection conn = dataSource.getConnection()) {
            for (String[] c : cols) {
                if (!columnExists(conn, c[0], c[1])) {
                    try (Statement st = conn.createStatement()) {
                        st.executeUpdate("ALTER TABLE " + c[0] + " ADD COLUMN " + c[1] + " " + c[2]);
                        added++;
                    }
                }
            }
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
