-- ============================================================
-- 医保原生多租户HIS - 医共体机构树 + RBAC(角色/菜单/授权) 平台层库表
-- 数据库: yb_interface  字符集: utf8mb4
-- 说明: 用 mysql 客户端执行前连接字符集必须为 utf8mb4, 否则中文 COMMENT 乱码。
--       实际由部署脚本(pymysql, charset=utf8mb4)应用; 已有数据表(sys_user/his_patient/
--       his_staff)只做 ALTER ADD COLUMN, 不在此文件重建。
-- 隔离: sys_org 走租户插件自动过滤; sys_menu/sys_role/sys_role_menu 为全局/半全局表,
--       已加入 MybatisPlusConfig.IGNORE_TABLES, 由服务层显式按 tenant_id 处理。
-- ============================================================
SET NAMES utf8mb4;
USE yb_interface;

-- ------------------------------------------------------------
-- 医共体机构树(县/乡/村三级)  按租户隔离
-- ------------------------------------------------------------
DROP TABLE IF EXISTS sys_org;
CREATE TABLE sys_org (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '机构ID',
    tenant_id      BIGINT       NOT NULL COMMENT '租户(医共体)ID',
    org_code       VARCHAR(50)  NOT NULL COMMENT '机构编码(医共体内唯一)',
    org_name       VARCHAR(200) NOT NULL COMMENT '机构名称',
    org_level      TINYINT      DEFAULT 1 COMMENT '机构级别:1-县级 2-乡镇 3-村(牵头与否见 is_lead)',
    is_lead        TINYINT      NOT NULL DEFAULT 0 COMMENT '是否牵头机构:1-牵头(每医共体唯一) 0-成员',
    parent_id      BIGINT       DEFAULT 0 COMMENT '上级机构ID(县级为0)',
    org_type       VARCHAR(30)  DEFAULT NULL COMMENT '机构类型:综合医院/乡镇卫生院/村卫生室等',
    fixmedins_code VARCHAR(30)  DEFAULT NULL COMMENT '定点医药机构编号(机构级)',
    fixmedins_name VARCHAR(200) DEFAULT NULL COMMENT '定点医药机构名称(医保登记名)',
    uscc           VARCHAR(50)  DEFAULT NULL COMMENT '统一社会信用代码',
    fixmedins_type VARCHAR(6)   DEFAULT NULL COMMENT '定点医疗服务机构类型编码(cv_code:fixmedins_type)',
    fixmedins_type_name VARCHAR(100) DEFAULT NULL COMMENT '定点医疗服务机构类型名称(字典回填)',
    fixmedins_type_src  VARCHAR(50)  DEFAULT NULL COMMENT '定点机构类型来源标识',
    hosp_lv        VARCHAR(6)   DEFAULT NULL COMMENT '医院等级编码(cv_code:hosp_lv)',
    hosp_lv_name   VARCHAR(100) DEFAULT NULL COMMENT '医院等级名称(字典回填)',
    hosp_lv_src    VARCHAR(50)  DEFAULT NULL COMMENT '医院等级来源标识',
    pd_license_no  VARCHAR(50)  DEFAULT NULL COMMENT '医疗机构执业许可证号',
    bed_cnt        INT          DEFAULT NULL COMMENT '编制床位数',
    admvs_code     VARCHAR(20)  DEFAULT NULL COMMENT '行政区划代码(关联area_code_2021)',
    leader         VARCHAR(50)  DEFAULT NULL COMMENT '负责人',
    phone          VARCHAR(30)  DEFAULT NULL COMMENT '联系电话',
    address        VARCHAR(300) DEFAULT NULL COMMENT '机构地址',
    sort_no        INT          DEFAULT 0 COMMENT '排序号',
    status         TINYINT      DEFAULT 1 COMMENT '状态:1-启用 0-停用',
    create_by      VARCHAR(50)  DEFAULT NULL COMMENT '创建人',
    create_time    DATETIME     DEFAULT NULL COMMENT '创建时间',
    update_by      VARCHAR(50)  DEFAULT NULL COMMENT '更新人',
    update_time    DATETIME     DEFAULT NULL COMMENT '更新时间',
    deleted        TINYINT      DEFAULT 0 COMMENT '逻辑删除:0-正常 1-删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_org_code (tenant_id, org_code),
    KEY idx_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='医共体机构树(县乡村三级)';

-- ------------------------------------------------------------
-- 菜单/权限(全局真源, 无 tenant_id)
-- ------------------------------------------------------------
DROP TABLE IF EXISTS sys_menu;
CREATE TABLE sys_menu (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '菜单ID',
    parent_id   BIGINT      DEFAULT 0 COMMENT '父菜单ID(0=顶级)',
    menu_key    VARCHAR(50) NOT NULL COMMENT '菜单键(=前端组件路由键)',
    menu_name   VARCHAR(50) NOT NULL COMMENT '菜单名称',
    menu_type   TINYINT     DEFAULT 2 COMMENT '类型:1-目录 2-菜单',
    comp        VARCHAR(50) DEFAULT NULL COMMENT '前端HIS.views组件名(目录为空)',
    phase       VARCHAR(10) DEFAULT NULL COMMENT '建设阶段占位(无comp时显示建设中)',
    icon        VARCHAR(50) DEFAULT NULL COMMENT '图标',
    sort_no     INT         DEFAULT 0 COMMENT '排序号',
    visible     TINYINT     DEFAULT 1 COMMENT '是否显示:1-是 0-否',
    status      TINYINT     DEFAULT 1 COMMENT '状态:1-启用 0-停用',
    create_by   VARCHAR(50) DEFAULT NULL COMMENT '创建人',
    create_time DATETIME    DEFAULT NULL COMMENT '创建时间',
    update_by   VARCHAR(50) DEFAULT NULL COMMENT '更新人',
    update_time DATETIME    DEFAULT NULL COMMENT '更新时间',
    deleted     TINYINT     DEFAULT 0 COMMENT '逻辑删除:0-正常 1-删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_menu_key (menu_key),
    KEY idx_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜单/权限(全局真源)';

-- ------------------------------------------------------------
-- 角色(全局预置 tenant_id=NULL + 租户自定义)
-- ------------------------------------------------------------
DROP TABLE IF EXISTS sys_role;
CREATE TABLE sys_role (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '角色ID',
    tenant_id   BIGINT       DEFAULT NULL COMMENT '租户ID(NULL=全局预置角色)',
    role_code   VARCHAR(50)  NOT NULL COMMENT '角色编码',
    role_name   VARCHAR(50)  NOT NULL COMMENT '角色名称',
    role_type   TINYINT      DEFAULT 2 COMMENT '类型:1-全局预置 2-租户自定义',
    all_menus   TINYINT      DEFAULT 0 COMMENT '是否拥有全部菜单:1-是(ADMIN/SUPER_ADMIN)',
    remark      VARCHAR(200) DEFAULT NULL COMMENT '备注',
    status      TINYINT      DEFAULT 1 COMMENT '状态:1-启用 0-停用',
    create_by   VARCHAR(50)  DEFAULT NULL COMMENT '创建人',
    create_time DATETIME     DEFAULT NULL COMMENT '创建时间',
    update_by   VARCHAR(50)  DEFAULT NULL COMMENT '更新人',
    update_time DATETIME     DEFAULT NULL COMMENT '更新时间',
    deleted     TINYINT      DEFAULT 0 COMMENT '逻辑删除:0-正常 1-删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_role_code (tenant_id, role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色(全局预置+租户自定义)';

-- ------------------------------------------------------------
-- 角色-菜单关联(纯连接表, 按 role_id 管理)
-- ------------------------------------------------------------
DROP TABLE IF EXISTS sys_role_menu;
CREATE TABLE sys_role_menu (
    id          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    role_id     BIGINT   NOT NULL COMMENT '角色ID',
    menu_id     BIGINT   NOT NULL COMMENT '菜单ID',
    tenant_id   BIGINT   DEFAULT NULL COMMENT '租户ID(冗余,随角色)',
    create_time DATETIME DEFAULT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_menu (role_id, menu_id),
    KEY idx_role (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-菜单关联';

-- ------------------------------------------------------------
-- 已有数据表增列(由部署脚本按 information_schema 幂等执行, 此处仅存档):
--   ALTER TABLE sys_user    ADD COLUMN org_id  BIGINT DEFAULT NULL COMMENT '归属机构ID';
--   ALTER TABLE sys_user    ADD COLUMN role_id BIGINT DEFAULT NULL COMMENT '角色ID(sys_role)';
--   ALTER TABLE his_patient ADD COLUMN org_id  BIGINT DEFAULT NULL COMMENT '建档/首诊机构ID';
--   ALTER TABLE his_staff   ADD COLUMN org_id  BIGINT DEFAULT NULL COMMENT '职工归属机构ID';
--   ALTER TABLE his_dept    ADD COLUMN org_id  BIGINT DEFAULT NULL COMMENT '科室归属机构ID';
-- ------------------------------------------------------------
-- P0 业务字段增列(医保/执业资质/统计上报, 由 DictSchemaMigration@Order(0) 幂等执行):
--   sys_org:   fixmedins_name, uscc, fixmedins_type(+_name/_src), hosp_lv(+_name/_src), pd_license_no, bed_cnt
--   his_dept:  dept_caty_name, dept_caty_src  (dept_caty 三件套化, 字典 cv_code:caty)
--   his_staff: med_insur_code, prac_cate(+_name/_src), dr_qual_cert_no, prac_cert_no, birth_date
-- ------------------------------------------------------------

