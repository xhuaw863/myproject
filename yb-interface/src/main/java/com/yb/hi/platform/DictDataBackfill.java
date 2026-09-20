package com.yb.hi.platform;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * 字典化字段数据回填(@Order(6), 在演示数据/ RBAC 初始化插入之后执行, 幂等)。
 * 将 his_patient / his_staff / sys_org 存量自由文本值转为字典编码, 并按编码回填名称与来源标识。
 * 编码来源: 性别/险种/就诊凭证/机构类型 取医保字典 std_cv_code; 职称取 std_wst364_code CV08.30.005;
 * 参保地区划取 area_code_2021; 职工类别无国标字典, 标记为本地受控枚举 local:staff_type。
 */
@Slf4j
@Order(6)
@Component
public class DictDataBackfill implements ApplicationRunner {

    private final DataSource dataSource;

    @Value("${his.dict-migration.enabled:true}")
    private boolean enabled;

    public DictDataBackfill(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        String[] sql = {
                /* ---------- 国籍值域修复: std_hbvalue_code 中"国籍代码"表源头无 dict_code(外部引用表),
                   按采集规范 GJ 字段所引用的 GB/T 2659.1-2022 补全分组编码, 使其可经标准值域接口下拉/回填(幂等) ---------- */
                "UPDATE std_hbvalue_code SET dict_code='GB/T 2659.1-2022' "
                        + "WHERE dict_name='国籍代码' AND (dict_code IS NULL OR dict_code='')",
                /* ---------- his_patient: 性别 男/女 -> gend 编码 ---------- */
                "UPDATE his_patient SET gender='1' WHERE gender='男'",
                "UPDATE his_patient SET gender='2' WHERE gender='女'",
                /* ---------- his_patient: 就诊凭证旧码 01/02/03 -> 医保 1/2/3 ---------- */
                "UPDATE his_patient SET mdtrt_cert_type='1' WHERE mdtrt_cert_type='01'",
                "UPDATE his_patient SET mdtrt_cert_type='2' WHERE mdtrt_cert_type='02'",
                "UPDATE his_patient SET mdtrt_cert_type='3' WHERE mdtrt_cert_type='03'",
                /* ---------- his_patient: 按编码回填 名称 + 来源 ---------- */
                "UPDATE his_patient p JOIN std_cv_code c ON c.dict_code='gend' AND c.val_code=p.gender "
                        + "SET p.gender_name=c.val_name, p.gender_src='cv_code:gend' "
                        + "WHERE p.gender IS NOT NULL AND p.gender<>''",
                "UPDATE his_patient p JOIN std_cv_code c ON c.dict_code='insutype' AND c.val_code=p.insutype "
                        + "SET p.insutype_name=c.val_name, p.insutype_src='cv_code:insutype' "
                        + "WHERE p.insutype IS NOT NULL AND p.insutype<>''",
                "UPDATE his_patient p JOIN std_cv_code c ON c.dict_code='mdtrt_cert_type' AND c.val_code=p.mdtrt_cert_type "
                        + "SET p.mdtrt_cert_type_name=c.val_name, p.mdtrt_cert_type_src='cv_code:mdtrt_cert_type' "
                        + "WHERE p.mdtrt_cert_type IS NOT NULL AND p.mdtrt_cert_type<>''",
                "UPDATE his_patient SET insuplc_admdvs_src='area_code_2021' WHERE insuplc_admdvs REGEXP '^[0-9]+$'",
                /* area_code_2021 为12位区划码; 医保 admdvs 常为6位, 按位数补足12位后匹配 */
                "UPDATE his_patient p JOIN area_code_2021 a ON a.code = CAST(p.insuplc_admdvs AS UNSIGNED) "
                        + "* POW(10, 12 - CHAR_LENGTH(TRIM(p.insuplc_admdvs))) "
                        + "SET p.insuplc_admdvs_name=a.name "
                        + "WHERE p.insuplc_admdvs REGEXP '^[0-9]+$' AND CHAR_LENGTH(TRIM(p.insuplc_admdvs)) <= 12",

                /* ---------- his_staff: 性别 ---------- */
                "UPDATE his_staff SET gender='1' WHERE gender='男'",
                "UPDATE his_staff SET gender='2' WHERE gender='女'",
                "UPDATE his_staff s JOIN std_cv_code c ON c.dict_code='gend' AND c.val_code=s.gender "
                        + "SET s.gender_name=c.val_name, s.gender_src='cv_code:gend' "
                        + "WHERE s.gender IS NOT NULL AND s.gender<>''",
                /* ---------- his_staff: 职工类别(本地受控枚举) ---------- */
                "UPDATE his_staff SET staff_type_name=staff_type, staff_type_src='local:staff_type' "
                        + "WHERE staff_type IS NOT NULL AND staff_type<>''",
                /* ---------- his_staff: 职称名 -> CV08.30.005 职务类别编码(仅补空编码) ---------- */
                "UPDATE his_staff SET title_code='1' WHERE (title_code IS NULL OR title_code='') "
                        + "AND title_name IN ('主任医师','主任护师','主任药师','主任技师')",
                "UPDATE his_staff SET title_code='2' WHERE (title_code IS NULL OR title_code='') "
                        + "AND title_name IN ('副主任医师','副主任护师','副主任药师','副主任技师')",
                "UPDATE his_staff SET title_code='3' WHERE (title_code IS NULL OR title_code='') "
                        + "AND title_name IN ('主治医师','主管护师','主管药师','主管技师')",
                "UPDATE his_staff SET title_code='4' WHERE (title_code IS NULL OR title_code='') "
                        + "AND title_name IN ('住院医师','护师','药师','技师')",
                "UPDATE his_staff SET title_code='5' WHERE (title_code IS NULL OR title_code='') "
                        + "AND title_name IN ('医士','护士')",
                "UPDATE his_staff s JOIN std_wst364_code c ON c.cv_code='CV08.30.005' AND c.val_code=s.title_code "
                        + "SET s.title_name=c.val_name, s.title_src='wst364:CV08.30.005' "
                        + "WHERE s.title_code IS NOT NULL AND s.title_code<>''",

                /* ---------- sys_org: 机构类型名 -> MEDINS_TYPE 编码 ---------- */
                "UPDATE sys_org SET org_type='A100' WHERE org_type='综合医院'",
                "UPDATE sys_org SET org_type='A2' WHERE org_type='中医医院'",
                "UPDATE sys_org SET org_type='G100' WHERE org_type='妇幼保健院'",
                "UPDATE sys_org SET org_type='C210' WHERE org_type='中心卫生院'",
                "UPDATE sys_org SET org_type='C220' WHERE org_type='乡卫生院'",
                "UPDATE sys_org SET org_type='C2' WHERE org_type='乡镇卫生院'",
                "UPDATE sys_org SET org_type='D600' WHERE org_type='村卫生室'",
                "UPDATE sys_org o JOIN std_cv_code c ON c.dict_code='MEDINS_TYPE' AND c.val_code=o.org_type "
                        + "SET o.org_type_name=c.val_name, o.org_type_src='cv_code:MEDINS_TYPE' "
                        + "WHERE o.org_type IS NOT NULL AND o.org_type<>''",

                /* ---------- P0: his_dept 存量自由文本科别 -> cv_code:caty 编码(按科室名映射, 幂等) ---------- */
                "UPDATE his_dept SET dept_caty='A03' WHERE dept_name='内科' AND dept_caty='普通'",
                "UPDATE his_dept SET dept_caty='A04' WHERE dept_name='外科' AND dept_caty='普通'",
                "UPDATE his_dept SET dept_caty='A07' WHERE dept_name='儿科' AND dept_caty='普通'",
                "UPDATE his_dept SET dept_caty='A05' WHERE dept_name='妇产科' AND dept_caty='普通'",
                "UPDATE his_dept SET dept_caty='A20' WHERE dept_name='急诊科' AND dept_caty='急诊'",
                "UPDATE his_dept SET dept_caty='A50' WHERE dept_name='中医科' AND dept_caty='中医'",
                /* ---------- P0: his_dept 医保科别 -> cv_code:caty 名称/来源 ---------- */
                "UPDATE his_dept d JOIN std_cv_code c ON c.dict_code='caty' AND c.val_code=d.dept_caty "
                        + "SET d.dept_caty_name=c.val_name, d.dept_caty_src='cv_code:caty' "
                        + "WHERE d.dept_caty IS NOT NULL AND d.dept_caty<>''",

                /* ---------- P0: his_staff 执业类别 -> whvalue:CT98.00.024 名称/来源 ---------- */
                "UPDATE his_staff s JOIN std_whvalue_code c ON c.dict_code='CT98.00.024' AND c.val_code=s.prac_cate "
                        + "SET s.prac_cate_name=c.val_name, s.prac_cate_src='whvalue:CT98.00.024' "
                        + "WHERE s.prac_cate IS NOT NULL AND s.prac_cate<>''",
                /* ---------- P0: his_staff 出生日期从18位身份证推导(仅补空) ---------- */
                "UPDATE his_staff SET birth_date=STR_TO_DATE(SUBSTRING(id_card,7,8),'%Y%m%d') "
                        + "WHERE birth_date IS NULL AND CHAR_LENGTH(TRIM(id_card))=18 "
                        + "AND SUBSTRING(id_card,7,8) REGEXP '^[0-9]{8}$'",

                /* ---------- P0: sys_org 医院等级 -> cv_code:hosp_lv 名称/来源 ---------- */
                "UPDATE sys_org o JOIN std_cv_code c ON c.dict_code='hosp_lv' AND c.val_code=o.hosp_lv "
                        + "SET o.hosp_lv_name=c.val_name, o.hosp_lv_src='cv_code:hosp_lv' "
                        + "WHERE o.hosp_lv IS NOT NULL AND o.hosp_lv<>''",
                /* ---------- P0: sys_org 定点机构类型 -> cv_code:fixmedins_type 名称/来源 ---------- */
                "UPDATE sys_org o JOIN std_cv_code c ON c.dict_code='fixmedins_type' AND c.val_code=o.fixmedins_type "
                        + "SET o.fixmedins_type_name=c.val_name, o.fixmedins_type_src='cv_code:fixmedins_type' "
                        + "WHERE o.fixmedins_type IS NOT NULL AND o.fixmedins_type<>''",
        };
        int total = 0;
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            for (String s : sql) {
                total += st.executeUpdate(s);
            }
        } catch (Exception e) {
            log.warn("字典化字段数据回填跳过: {}", e.getMessage());
            return;
        }
        log.info("字典化字段数据回填完成, 累计影响 {} 行", total);
    }
}
