-- ============================================================
-- 湖北省医保接口对接 - 基础字典库表结构
-- 数据库: MySQL 8.x / 5.7  字符集: utf8mb4
-- ============================================================

CREATE DATABASE IF NOT EXISTS yb_interface DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE yb_interface;

-- ------------------------------------------------------------
-- 字典版本管理表(记录各字典本地已下载的最大版本号)
-- ------------------------------------------------------------
DROP TABLE IF EXISTS dict_version;
CREATE TABLE dict_version (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    dict_type    VARCHAR(50)  NOT NULL COMMENT '字典类型标识',
    dict_name    VARCHAR(100) DEFAULT NULL COMMENT '字典名称',
    infno        VARCHAR(10)  DEFAULT NULL COMMENT '对应交易编号',
    max_ver      VARCHAR(30)  NOT NULL DEFAULT '0' COMMENT '本地最大版本号',
    last_dld_time DATETIME    DEFAULT NULL COMMENT '最近下载时间',
    updt_time    DATETIME     DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_dict_type (dict_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字典版本管理表';

-- 初始化各字典版本记录
INSERT INTO dict_version (dict_type, dict_name, infno, max_ver) VALUES
('drug_catalog',        '西药中成药目录',   '1301', '0'),
('tcm_catalog',         '中药饮片目录',     '1302', '0'),
('preparation_catalog', '医疗机构制剂目录', '1303', '0'),
('med_service_catalog', '医疗服务项目目录', '1305', '0'),
('consumable_catalog',  '医用耗材目录',     '1306', '0'),
('disease_catalog',     '疾病与诊断目录',   '1307', '0');

-- ------------------------------------------------------------
-- 【1301】西药中成药目录
-- ------------------------------------------------------------
DROP TABLE IF EXISTS drug_catalog;
CREATE TABLE drug_catalog (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    med_list_codg  VARCHAR(50)  DEFAULT NULL COMMENT '医疗目录编码',
    drug_prodname  VARCHAR(500) DEFAULT NULL COMMENT '药品商品名',
    genname_codg   VARCHAR(50)  DEFAULT NULL COMMENT '通用名编号',
    drug_genname   VARCHAR(500) DEFAULT NULL COMMENT '药品通用名',
    chemname       VARCHAR(200) DEFAULT NULL COMMENT '化学名称',
    alis           VARCHAR(200) DEFAULT NULL COMMENT '别名',
    eng_name       VARCHAR(255) DEFAULT NULL COMMENT '英文名称',
    dosform        VARCHAR(50)  DEFAULT NULL COMMENT '药品剂型',
    dosform_name   VARCHAR(100) DEFAULT NULL COMMENT '药品剂型名称',
    drug_type      VARCHAR(20)  DEFAULT NULL COMMENT '药品类别',
    drug_type_name VARCHAR(100) DEFAULT NULL COMMENT '药品类别名称',
    drug_spec      VARCHAR(255) DEFAULT NULL COMMENT '药品规格',
    min_useunt     VARCHAR(30)  DEFAULT NULL COMMENT '最小使用单位',
    min_salunt     VARCHAR(30)  DEFAULT NULL COMMENT '最小销售单位',
    min_unt        VARCHAR(30)  DEFAULT NULL COMMENT '最小计量单位',
    min_prcunt     VARCHAR(50)  DEFAULT NULL COMMENT '最小计价单位',
    wubi           VARCHAR(50)  DEFAULT NULL COMMENT '五笔助记码',
    pinyin         VARCHAR(50)  DEFAULT NULL COMMENT '拼音助记码',
    prod_entp_name VARCHAR(200) DEFAULT NULL COMMENT '生产企业名称',
    vali_flag      VARCHAR(3)   DEFAULT NULL COMMENT '有效标志',
    rid            VARCHAR(40)  DEFAULT NULL COMMENT '唯一记录号',
    ver            VARCHAR(30)  DEFAULT NULL COMMENT '版本号',
    ver_name       VARCHAR(100) DEFAULT NULL COMMENT '版本名称',
    raw_data       LONGTEXT     DEFAULT NULL COMMENT '原始数据行(TAB分隔)',
    PRIMARY KEY (id),
    KEY idx_med_list_codg (med_list_codg),
    KEY idx_ver (ver),
    KEY idx_rid (rid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='西药中成药目录';

-- ------------------------------------------------------------
-- 【1302】中药饮片目录
-- ------------------------------------------------------------
DROP TABLE IF EXISTS tcm_catalog;
CREATE TABLE tcm_catalog (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    med_list_codg    VARCHAR(50)  DEFAULT NULL COMMENT '医疗目录编码',
    drug_name        VARCHAR(200) DEFAULT NULL COMMENT '单味药名称',
    scmp_flag        VARCHAR(3)   DEFAULT NULL COMMENT '单复方标志',
    qual_lv          VARCHAR(20)  DEFAULT NULL COMMENT '质量等级',
    medi_part        VARCHAR(100) DEFAULT NULL COMMENT '药用部位',
    safe_dose        VARCHAR(100) DEFAULT NULL COMMENT '安全计量',
    conv_usage       VARCHAR(200) DEFAULT NULL COMMENT '常规用法',
    nature_flavor    VARCHAR(100) DEFAULT NULL COMMENT '性味',
    meridian_tropism VARCHAR(100) DEFAULT NULL COMMENT '归经',
    variety          VARCHAR(100) DEFAULT NULL COMMENT '品种',
    vali_flag        VARCHAR(3)   DEFAULT NULL COMMENT '有效标志',
    rid              VARCHAR(40)  DEFAULT NULL COMMENT '唯一记录号',
    ver              VARCHAR(30)  DEFAULT NULL COMMENT '版本号',
    ver_name         VARCHAR(100) DEFAULT NULL COMMENT '版本名称',
    raw_data         LONGTEXT     DEFAULT NULL COMMENT '原始数据行(TAB分隔)',
    PRIMARY KEY (id),
    KEY idx_med_list_codg (med_list_codg),
    KEY idx_ver (ver)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='中药饮片目录';

-- ------------------------------------------------------------
-- 【1303】医疗机构制剂目录
-- ------------------------------------------------------------
DROP TABLE IF EXISTS preparation_catalog;
CREATE TABLE preparation_catalog (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    med_list_codg  VARCHAR(50)  DEFAULT NULL COMMENT '医疗目录编码',
    drug_prodname  VARCHAR(500) DEFAULT NULL COMMENT '药品商品名',
    alis           VARCHAR(200) DEFAULT NULL COMMENT '别名',
    dosform        VARCHAR(50)  DEFAULT NULL COMMENT '剂型',
    dosform_name   VARCHAR(100) DEFAULT NULL COMMENT '剂型名称',
    ing            VARCHAR(1000) DEFAULT NULL COMMENT '成分',
    efcc_atd       VARCHAR(1000) DEFAULT NULL COMMENT '功能主治',
    drug_spec      VARCHAR(255) DEFAULT NULL COMMENT '药品规格',
    drug_type      VARCHAR(20)  DEFAULT NULL COMMENT '药品类别',
    drug_type_name VARCHAR(100) DEFAULT NULL COMMENT '药品类别名称',
    prod_entp_name VARCHAR(200) DEFAULT NULL COMMENT '生产企业名称',
    vali_flag      VARCHAR(3)   DEFAULT NULL COMMENT '有效标志',
    rid            VARCHAR(40)  DEFAULT NULL COMMENT '唯一记录号',
    ver            VARCHAR(30)  DEFAULT NULL COMMENT '版本号',
    ver_name       VARCHAR(100) DEFAULT NULL COMMENT '版本名称',
    raw_data       LONGTEXT     DEFAULT NULL COMMENT '原始数据行(TAB分隔)',
    PRIMARY KEY (id),
    KEY idx_med_list_codg (med_list_codg),
    KEY idx_ver (ver)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='医疗机构制剂目录';

-- ------------------------------------------------------------
-- 【1305】医疗服务项目目录
-- ------------------------------------------------------------
DROP TABLE IF EXISTS med_service_catalog;
CREATE TABLE med_service_catalog (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    med_list_codg    VARCHAR(50)  DEFAULT NULL COMMENT '医疗目录编码',
    prcunt           VARCHAR(50)  DEFAULT NULL COMMENT '计价单位',
    prcunt_name      VARCHAR(100) DEFAULT NULL COMMENT '计价单位名称',
    item_explain     VARCHAR(2000) DEFAULT NULL COMMENT '诊疗项目说明',
    item_excluded    VARCHAR(1000) DEFAULT NULL COMMENT '诊疗除外内容',
    item_connotation VARCHAR(2000) DEFAULT NULL COMMENT '诊疗项目内涵',
    vali_flag        VARCHAR(3)   DEFAULT NULL COMMENT '有效标志',
    memo             VARCHAR(500) DEFAULT NULL COMMENT '备注',
    item_cat         VARCHAR(50)  DEFAULT NULL COMMENT '服务项目类别',
    item_name        VARCHAR(500) DEFAULT NULL COMMENT '医疗服务项目名称',
    item_explain2    VARCHAR(2000) DEFAULT NULL COMMENT '项目说明',
    rid              VARCHAR(40)  DEFAULT NULL COMMENT '唯一记录号',
    ver              VARCHAR(30)  DEFAULT NULL COMMENT '版本号',
    ver_name         VARCHAR(100) DEFAULT NULL COMMENT '版本名称',
    raw_data         LONGTEXT     DEFAULT NULL COMMENT '原始数据行(TAB分隔)',
    PRIMARY KEY (id),
    KEY idx_med_list_codg (med_list_codg),
    KEY idx_ver (ver)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='医疗服务项目目录';

-- ------------------------------------------------------------
-- 【1306】医用耗材目录
-- ------------------------------------------------------------
DROP TABLE IF EXISTS consumable_catalog;
CREATE TABLE consumable_catalog (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    med_list_codg  VARCHAR(50)  DEFAULT NULL COMMENT '医疗目录编码',
    cons_name      VARCHAR(500) DEFAULT NULL COMMENT '耗材名称',
    udi            VARCHAR(100) DEFAULT NULL COMMENT '医疗器械唯一标识码',
    genname_code   VARCHAR(50)  DEFAULT NULL COMMENT '医保通用名代码',
    genname        VARCHAR(500) DEFAULT NULL COMMENT '医保通用名',
    prod_model     VARCHAR(200) DEFAULT NULL COMMENT '产品型号',
    spec_code      VARCHAR(100) DEFAULT NULL COMMENT '规格代码',
    spec           VARCHAR(255) DEFAULT NULL COMMENT '规格',
    cons_cat       VARCHAR(50)  DEFAULT NULL COMMENT '耗材分类',
    spec_model     VARCHAR(255) DEFAULT NULL COMMENT '规格型号',
    min_useunt     VARCHAR(30)  DEFAULT NULL COMMENT '最小使用单位',
    min_salunt     VARCHAR(30)  DEFAULT NULL COMMENT '最小销售单位',
    hi_value_flag  VARCHAR(3)   DEFAULT NULL COMMENT '高值耗材标志',
    vali_flag      VARCHAR(3)   DEFAULT NULL COMMENT '有效标志',
    rid            VARCHAR(40)  DEFAULT NULL COMMENT '唯一记录号',
    ver            VARCHAR(30)  DEFAULT NULL COMMENT '版本号',
    ver_name       VARCHAR(100) DEFAULT NULL COMMENT '版本名称',
    raw_data       LONGTEXT     DEFAULT NULL COMMENT '原始数据行(TAB分隔)',
    PRIMARY KEY (id),
    KEY idx_med_list_codg (med_list_codg),
    KEY idx_ver (ver)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='医用耗材目录';

-- ------------------------------------------------------------
-- 【1307】疾病与诊断目录
-- ------------------------------------------------------------
DROP TABLE IF EXISTS disease_catalog;
CREATE TABLE disease_catalog (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    dise_code        VARCHAR(50)  DEFAULT NULL COMMENT '西医疾病诊断ID',
    chapter          VARCHAR(50)  DEFAULT NULL COMMENT '章',
    chapter_name     VARCHAR(200) DEFAULT NULL COMMENT '章名称',
    cat_code         VARCHAR(50)  DEFAULT NULL COMMENT '类目代码',
    cat_name         VARCHAR(200) DEFAULT NULL COMMENT '类目名称',
    subcat_code      VARCHAR(50)  DEFAULT NULL COMMENT '亚目代码',
    subcat_name      VARCHAR(200) DEFAULT NULL COMMENT '亚目名称',
    diag_code        VARCHAR(50)  DEFAULT NULL COMMENT '诊断代码',
    diag_name        VARCHAR(500) DEFAULT NULL COMMENT '诊断名称',
    use_flag         VARCHAR(10)  DEFAULT NULL COMMENT '使用标记',
    nat_std_diag_code VARCHAR(50) DEFAULT NULL COMMENT '国标版诊断代码',
    nat_std_diag_name VARCHAR(500) DEFAULT NULL COMMENT '国标版诊断名称',
    clin_diag_code   VARCHAR(50)  DEFAULT NULL COMMENT '临床版诊断代码',
    clin_diag_name   VARCHAR(500) DEFAULT NULL COMMENT '临床版诊断名称',
    vali_flag        VARCHAR(3)   DEFAULT NULL COMMENT '有效标志',
    rid              VARCHAR(40)  DEFAULT NULL COMMENT '唯一记录号',
    ver              VARCHAR(30)  DEFAULT NULL COMMENT '版本号',
    ver_name         VARCHAR(100) DEFAULT NULL COMMENT '版本名称',
    raw_data         LONGTEXT     DEFAULT NULL COMMENT '原始数据行(TAB分隔)',
    PRIMARY KEY (id),
    KEY idx_diag_code (diag_code),
    KEY idx_ver (ver)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='疾病与诊断目录';
