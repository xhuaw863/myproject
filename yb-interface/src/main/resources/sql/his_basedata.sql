-- ============================================================
-- 医保原生多租户HIS - 基础数据库表(科室/职工/排班/收费项目)
-- 数据库: yb_interface  字符集: utf8mb4
-- 所有业务表含 tenant_id(租户隔离) + 审计字段 + 逻辑删除
-- ============================================================
USE yb_interface;

-- ------------------------------------------------------------
-- 科室表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS his_dept;
CREATE TABLE his_dept (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '科室ID',
    tenant_id    BIGINT       NOT NULL COMMENT '租户ID',
    org_id       BIGINT       DEFAULT NULL COMMENT '归属机构ID(sys_org)',
    dept_code    VARCHAR(30)  NOT NULL COMMENT '科室编码(院内)',
    dept_name    VARCHAR(100) NOT NULL COMMENT '科室名称',
    dept_type    VARCHAR(20)  DEFAULT '临床' COMMENT '科室类型:临床/医技/行政',
    dept_caty    VARCHAR(50)  DEFAULT NULL COMMENT '医保科别编码(cv_code:caty, 用于2201/2203)',
    dept_caty_name VARCHAR(100) DEFAULT NULL COMMENT '医保科别名称(字典回填)',
    dept_caty_src VARCHAR(50)  DEFAULT NULL COMMENT '医保科别来源标识',
    parent_id    BIGINT       DEFAULT 0 COMMENT '上级科室ID(0/null=顶级大类节点)',
    dept_category VARCHAR(20) DEFAULT NULL COMMENT '科室大类(门诊科室/住院科室/病区护理/医技科室/行政后勤)',
    dept_level   TINYINT      DEFAULT 2 COMMENT '层级:1-大类 2-科室 3-窗口/诊室',
    yb_dept_code VARCHAR(30)  DEFAULT NULL COMMENT '医保科室编码(如与院内不同)',
    phone        VARCHAR(30)  DEFAULT NULL COMMENT '联系电话',
    loc_desc     VARCHAR(200) DEFAULT NULL COMMENT '位置描述',
    sort_no      INT          DEFAULT 0 COMMENT '排序号',
    status       TINYINT      DEFAULT 1 COMMENT '状态:1-启用 0-停用',
    open_clinic  TINYINT      DEFAULT 1 COMMENT '门诊开诊:1-开诊 0-未开诊(仅门诊科室大类生效, 排班/挂号只列开诊科室)',
    memo         VARCHAR(500) DEFAULT NULL COMMENT '备注',
    create_by    VARCHAR(50)  DEFAULT NULL,
    create_time  DATETIME     DEFAULT NULL,
    update_by    VARCHAR(50)  DEFAULT NULL,
    update_time  DATETIME     DEFAULT NULL,
    deleted      TINYINT      DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_tenant (tenant_id),
    KEY idx_org (org_id),
    KEY idx_tenant_dept (tenant_id, dept_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='科室表';

-- ------------------------------------------------------------
-- 职工(医师/护士/药师/技师)表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS his_staff;
CREATE TABLE his_staff (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '职工ID',
    tenant_id     BIGINT       NOT NULL COMMENT '租户ID',
    staff_no      VARCHAR(30)  NOT NULL COMMENT '职工工号(院内)',
    staff_name    VARCHAR(50)  NOT NULL COMMENT '姓名',
    staff_type    VARCHAR(20)  DEFAULT '医师' COMMENT '职工类别:医师/护士/药师/技师/管理',
    gender        VARCHAR(4)   DEFAULT NULL COMMENT '性别:男/女',
    title_code    VARCHAR(30)  DEFAULT NULL COMMENT '职称编码',
    title_name    VARCHAR(50)  DEFAULT NULL COMMENT '职称名称',
    dept_id       BIGINT       DEFAULT NULL COMMENT '所属科室ID',
    atddr_no      VARCHAR(30)  DEFAULT NULL COMMENT '主治医师医保编码(2201/2203)',
    dise_dor_no   VARCHAR(30)  DEFAULT NULL COMMENT '诊断医师医保编码',
    id_card       VARCHAR(30)  DEFAULT NULL COMMENT '身份证号',
    birth_date    DATE         DEFAULT NULL COMMENT '出生日期',
    med_insur_code VARCHAR(30) DEFAULT NULL COMMENT '国家医保业务编码(医师/药师/护士)',
    prac_cate     VARCHAR(4)   DEFAULT NULL COMMENT '执业类别编码(whvalue:CT98.00.024 临床/口腔/公共卫生/中医)',
    prac_cate_name VARCHAR(50) DEFAULT NULL COMMENT '执业类别名称(字典回填)',
    prac_cate_src VARCHAR(50)  DEFAULT NULL COMMENT '执业类别来源标识',
    dr_qual_cert_no VARCHAR(50) DEFAULT NULL COMMENT '医师资格证号',
    prac_cert_no  VARCHAR(50)  DEFAULT NULL COMMENT '医师执业证书编码',
    phone         VARCHAR(30)  DEFAULT NULL COMMENT '联系电话',
    can_register  TINYINT      DEFAULT 0 COMMENT '是否可挂号:1-是 0-否',
    reg_fee       DECIMAL(10,2) DEFAULT 0.00 COMMENT '默认挂号费(诊查费)',
    sort_no       INT          DEFAULT 0 COMMENT '排序号',
    status        TINYINT      DEFAULT 1 COMMENT '状态:1-在职 0-停用',
    avatar_url    VARCHAR(255) DEFAULT NULL COMMENT '头像图片URL(/uploads/...)',
    sign_img_url  VARCHAR(255) DEFAULT NULL COMMENT '签名图片URL(电子处方/文档签名)',
    -- 医师处方权限(药事管理强管控: 处方权/精麻/抗菌分级/授权留痕)
    rx_right       TINYINT      DEFAULT 0 COMMENT '处方权(总):1-具备 0-无',
    narcotic_right TINYINT      DEFAULT 0 COMMENT '麻醉药品处方权:1-有 0-无',
    psych1_right   TINYINT      DEFAULT 0 COMMENT '第一类精神药品处方权:1-有 0-无',
    psych2_right   TINYINT      DEFAULT 0 COMMENT '第二类精神药品处方权:1-有 0-无',
    antibiotic_level      VARCHAR(6)  DEFAULT NULL COMMENT '抗菌药物处方权级别(hbvalue:HBCV08.50.029 11一级/12二级/13三级)',
    antibiotic_level_name VARCHAR(50) DEFAULT NULL COMMENT '抗菌药物处方权级别名称(字典回填)',
    antibiotic_level_src  VARCHAR(50) DEFAULT NULL COMMENT '抗菌药物处方权级别来源标识',
    surgery_level      VARCHAR(6)  DEFAULT NULL COMMENT '手术级别权限(cv_code:oprn_lv_code 1一级/2二级/3三级/4四级/5其他)',
    surgery_level_name VARCHAR(50) DEFAULT NULL COMMENT '手术级别权限名称(字典回填)',
    surgery_level_src  VARCHAR(50) DEFAULT NULL COMMENT '手术级别权限来源标识',
    rx_auth_org    VARCHAR(100) DEFAULT NULL COMMENT '处方权授权机构(医务科/授权部门)',
    rx_auth_no     VARCHAR(50)  DEFAULT NULL COMMENT '处方权授权文号',
    rx_auth_date   DATE         DEFAULT NULL COMMENT '处方权授权日期',
    rx_valid_until DATE         DEFAULT NULL COMMENT '处方权有效期至(到期需复训)',
    memo          VARCHAR(500) DEFAULT NULL COMMENT '备注',
    create_by     VARCHAR(50)  DEFAULT NULL,
    create_time   DATETIME     DEFAULT NULL,
    update_by     VARCHAR(50)  DEFAULT NULL,
    update_time   DATETIME     DEFAULT NULL,
    deleted       TINYINT      DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_tenant (tenant_id),
    KEY idx_tenant_staff (tenant_id, staff_no),
    KEY idx_dept (dept_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='职工表';

-- ------------------------------------------------------------
-- 排班号源表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS his_schedule;
CREATE TABLE his_schedule (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '排班ID',
    tenant_id      BIGINT       NOT NULL COMMENT '租户ID',
    dept_id        BIGINT       NOT NULL COMMENT '科室ID',
    staff_id       BIGINT       NOT NULL COMMENT '职工(医师)ID',
    work_date      DATE         NOT NULL COMMENT '出诊日期',
    time_type      VARCHAR(10)  DEFAULT 'am' COMMENT '时段:am-上午 pm-下午 night-晚间',
    reg_level_code VARCHAR(30)  DEFAULT '01' COMMENT '号别编码',
    reg_level_name VARCHAR(50)  DEFAULT '普通号' COMMENT '号别名称:普通号/副主任/主任',
    reg_fee        DECIMAL(10,2) DEFAULT 0.00 COMMENT '挂号费',
    total_num      INT          DEFAULT 0 COMMENT '总号源数',
    left_num       INT          DEFAULT 0 COMMENT '剩余号源数',
    status         TINYINT      DEFAULT 1 COMMENT '状态:1-开放 0-停诊',
    create_by      VARCHAR(50)  DEFAULT NULL,
    create_time    DATETIME     DEFAULT NULL,
    update_by      VARCHAR(50)  DEFAULT NULL,
    update_time    DATETIME     DEFAULT NULL,
    deleted        TINYINT      DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_tenant (tenant_id),
    KEY idx_staff_date (staff_id, work_date),
    KEY idx_dept_date (dept_id, work_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='排班号源表';

-- ------------------------------------------------------------
-- 收费项目(本院统一目录: 药品/诊疗/耗材)
-- ------------------------------------------------------------
DROP TABLE IF EXISTS his_charge_item;
CREATE TABLE his_charge_item (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '项目ID',
    tenant_id        BIGINT       NOT NULL COMMENT '租户ID',
    item_code        VARCHAR(40)  NOT NULL COMMENT '院内收费项目编码',
    item_name        VARCHAR(200) NOT NULL COMMENT '院内项目名称',
    item_type        VARCHAR(20)  DEFAULT '诊疗' COMMENT '项目大类:药品/诊疗/耗材/其他',
    item_cat         VARCHAR(50)  DEFAULT NULL COMMENT '细分类别',
    spec             VARCHAR(200) DEFAULT NULL COMMENT '规格',
    unit             VARCHAR(30)  DEFAULT NULL COMMENT '单位',
    price            DECIMAL(12,4) DEFAULT 0.0000 COMMENT '单价',
    med_list_codg    VARCHAR(50)  DEFAULT NULL COMMENT '医保医疗目录编码(对照)',
    medins_list_codg VARCHAR(50)  DEFAULT NULL COMMENT '医保机构目录编码(对照)',
    med_chrgitm_type VARCHAR(10)  DEFAULT NULL COMMENT '医疗收费项目类别:01-药品 02-诊疗 03-耗材',
    chrgitm_lv       VARCHAR(10)  DEFAULT NULL COMMENT '收费项目等级:01-甲 02-乙 03-丙',
    selfpay_prop     DECIMAL(5,4) DEFAULT 0.0000 COMMENT '自付比例(0-1)',
    status           TINYINT      DEFAULT 1 COMMENT '状态:1-启用 0-停用',
    memo             VARCHAR(500) DEFAULT NULL COMMENT '备注',
    create_by        VARCHAR(50)  DEFAULT NULL,
    create_time      DATETIME     DEFAULT NULL,
    update_by        VARCHAR(50)  DEFAULT NULL,
    update_time      DATETIME     DEFAULT NULL,
    deleted          TINYINT      DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_tenant (tenant_id),
    KEY idx_tenant_item (tenant_id, item_code),
    KEY idx_med_list_codg (med_list_codg)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收费项目(本院目录)表';
