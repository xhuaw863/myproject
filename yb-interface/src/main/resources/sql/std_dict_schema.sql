-- ============================================================
-- 湖北省医保接口对接 - 标准字典库(std_*)表结构
-- 数据来源: 湖北省医保编码数据库(2024版) + 字典标准文件夹(国家临床版/中医分类)
-- 说明: 标准字典为国家/省级权威参照数据, 全院共用一份, 不做租户隔离(无 tenant_id)
-- 数据库: MySQL 8.x / 5.7  字符集: utf8mb4
-- ============================================================

CREATE DATABASE IF NOT EXISTS yb_interface DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE yb_interface;

-- ------------------------------------------------------------
-- 标准字典导入登记表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS std_dict_version;
CREATE TABLE std_dict_version (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    dict_key     VARCHAR(40)  NOT NULL COMMENT '字典标识',
    dict_name    VARCHAR(100) DEFAULT NULL COMMENT '字典名称',
    source_file  VARCHAR(500) DEFAULT NULL COMMENT '源文件名',
    sheet        VARCHAR(100) DEFAULT NULL COMMENT '源sheet(序号或名称)',
    ver          VARCHAR(30)  DEFAULT NULL COMMENT '数据版本',
    row_count    BIGINT       DEFAULT 0 COMMENT '导入行数',
    status       VARCHAR(20)  DEFAULT NULL COMMENT '状态(SUCCESS/FAIL)',
    message      VARCHAR(1000) DEFAULT NULL COMMENT '结果信息',
    import_time  DATETIME     DEFAULT NULL COMMENT '导入时间',
    PRIMARY KEY (id),
    KEY idx_dict_key (dict_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标准字典导入登记表';

-- ------------------------------------------------------------
-- 【0】医保字典值域代码(接口规范V1.2.02 第6章 字典表)
-- 来源: 湖北省医疗保障信息平台定点医药机构接口规范V1.2.02 第6章
-- 结构: (字典类型代码,字典类型名称,国家字典值代码,国家字典值名称) 四元组
-- 由 tools/extract_cv_dict.py 离线抽取为 resources/seed/std_cv_code.tsv, 导入器加载
-- ------------------------------------------------------------
DROP TABLE IF EXISTS std_cv_code;
CREATE TABLE std_cv_code (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    dict_code  VARCHAR(64)  DEFAULT NULL COMMENT '字典类型代码',
    dict_name  VARCHAR(200) DEFAULT NULL COMMENT '字典类型名称',
    val_code   VARCHAR(64)  DEFAULT NULL COMMENT '国家字典值代码',
    val_name   VARCHAR(500) DEFAULT NULL COMMENT '国家字典值名称',
    ver        VARCHAR(30)  DEFAULT NULL COMMENT '数据版本',
    PRIMARY KEY (id),
    KEY idx_cv_dict_code (dict_code),
    KEY idx_cv_val_code (val_code),
    KEY idx_cv_dict_name (dict_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='医保字典值域代码(接口规范第6章)';

-- ------------------------------------------------------------
-- 【1】西药中成药(湖北医保药品编码库)
-- ------------------------------------------------------------
DROP TABLE IF EXISTS std_drug;
CREATE TABLE std_drug (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    seq            VARCHAR(20)  DEFAULT NULL COMMENT '序号',
    major_class    VARCHAR(50)  DEFAULT NULL COMMENT '大类名称',
    drug_code      VARCHAR(50)  DEFAULT NULL COMMENT '药品代码(医保)',
    reg_name       VARCHAR(500) DEFAULT NULL COMMENT '注册名称',
    trade_name     VARCHAR(500) DEFAULT NULL COMMENT '商品名称',
    reg_dosform    VARCHAR(100) DEFAULT NULL COMMENT '注册剂型',
    act_dosform    VARCHAR(100) DEFAULT NULL COMMENT '实际剂型',
    reg_spec       VARCHAR(255) DEFAULT NULL COMMENT '注册规格',
    act_spec       VARCHAR(255) DEFAULT NULL COMMENT '实际规格',
    pack_material  VARCHAR(100) DEFAULT NULL COMMENT '包装材质',
    min_pack_qty   VARCHAR(30)  DEFAULT NULL COMMENT '最小包装数量',
    min_prep_unit  VARCHAR(30)  DEFAULT NULL COMMENT '最小制剂单位',
    min_pack_unit  VARCHAR(30)  DEFAULT NULL COMMENT '最小包装单位',
    drug_entp      VARCHAR(200) DEFAULT NULL COMMENT '药品企业',
    mkt_holder     VARCHAR(200) DEFAULT NULL COMMENT '上市药品持有人',
    approval_no    VARCHAR(100) DEFAULT NULL COMMENT '批准文号',
    drug_std_code  VARCHAR(50)  DEFAULT NULL COMMENT '药品本位码',
    market_status  VARCHAR(30)  DEFAULT NULL COMMENT '市场状态',
    subpack_entp   VARCHAR(200) DEFAULT NULL COMMENT '分包装企业名称',
    hi_drug_name   VARCHAR(500) DEFAULT NULL COMMENT '医保药品名称',
    chrgitm_lv     VARCHAR(20)  DEFAULT NULL COMMENT '甲乙丙类标识',
    hi_dosform     VARCHAR(100) DEFAULT NULL COMMENT '医保剂型',
    gen_no         VARCHAR(50)  DEFAULT NULL COMMENT '编号',
    memo           VARCHAR(500) DEFAULT NULL COMMENT '备注',
    msd_flag       VARCHAR(20)  DEFAULT NULL COMMENT '是否对应门诊特殊疾病',
    nego_flag      VARCHAR(20)  DEFAULT NULL COMMENT '协议期内谈判药品标识',
    nego_start     VARCHAR(30)  DEFAULT NULL COMMENT '谈判药品协议有效期起始日期',
    nego_end       VARCHAR(30)  DEFAULT NULL COMMENT '谈判药品协议有效期截止日期',
    ltd_self_flag  VARCHAR(20)  DEFAULT NULL COMMENT '限定支付范围药品自费标识',
    pay_std_prep   VARCHAR(30)  DEFAULT NULL COMMENT '医保支付标准(最小制剂单位)',
    pay_std_pack   VARCHAR(30)  DEFAULT NULL COMMENT '医保支付标准(最小包装单位)',
    data_source    VARCHAR(50)  DEFAULT NULL COMMENT '数据来源',
    chg_field      VARCHAR(200) DEFAULT NULL COMMENT '修改字段',
    ver            VARCHAR(30)  DEFAULT NULL COMMENT '数据版本',
    PRIMARY KEY (id),
    KEY idx_drug_code (drug_code),
    KEY idx_drug_std_code (drug_std_code),
    KEY idx_approval_no (approval_no),
    KEY idx_reg_name (reg_name(100))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标准字典-西药中成药';

-- ------------------------------------------------------------
-- 【2】医用耗材(20位, 湖北医保)
-- ------------------------------------------------------------
DROP TABLE IF EXISTS std_consumable;
CREATE TABLE std_consumable (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    seq          VARCHAR(20)  DEFAULT NULL COMMENT '序号',
    cons_code    VARCHAR(50)  DEFAULT NULL COMMENT '耗材代码(20位)',
    cat1         VARCHAR(100) DEFAULT NULL COMMENT '一级分类',
    cat2         VARCHAR(100) DEFAULT NULL COMMENT '二级分类',
    cat3         VARCHAR(100) DEFAULT NULL COMMENT '三级分类',
    hi_genname   VARCHAR(200) DEFAULT NULL COMMENT '医保通用名',
    material     VARCHAR(100) DEFAULT NULL COMMENT '材质',
    feature      VARCHAR(100) DEFAULT NULL COMMENT '特征',
    cons_entp    VARCHAR(200) DEFAULT NULL COMMENT '耗材企业',
    policy_flag  VARCHAR(20)  DEFAULT NULL COMMENT '政策标识区',
    pay_std      VARCHAR(50)  DEFAULT NULL COMMENT '支付标准',
    reg_cert_no  VARCHAR(500) DEFAULT NULL COMMENT '注册证号',
    cons_type    VARCHAR(30)  DEFAULT NULL COMMENT '耗材类型',
    data_source  VARCHAR(50)  DEFAULT NULL COMMENT '数据来源',
    chg_log      VARCHAR(500) DEFAULT NULL COMMENT '变更日志',
    ver          VARCHAR(30)  DEFAULT NULL COMMENT '数据版本',
    PRIMARY KEY (id),
    KEY idx_cons_code (cons_code),
    KEY idx_hi_genname (hi_genname(80))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标准字典-医用耗材(20位)';

-- ------------------------------------------------------------
-- 【3】医疗服务项目(湖北医保)
-- ------------------------------------------------------------
DROP TABLE IF EXISTS std_med_service;
CREATE TABLE std_med_service (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    seq               VARCHAR(20)  DEFAULT NULL COMMENT '序号',
    nat_item_code     VARCHAR(50)  DEFAULT NULL COMMENT '国家医疗服务项目代码',
    nat_item_name     VARCHAR(500) DEFAULT NULL COMMENT '国家医疗服务项目名称',
    loc_item_code     VARCHAR(80)  DEFAULT NULL COMMENT '地方医疗服务项目代码',
    loc_item_name     VARCHAR(1000) DEFAULT NULL COMMENT '地方医疗服务项目名称',
    item_connotation  VARCHAR(2000) DEFAULT NULL COMMENT '项目内涵',
    item_excluded     VARCHAR(1000) DEFAULT NULL COMMENT '除外内容',
    prc_unit          VARCHAR(50)  DEFAULT NULL COMMENT '计价单位',
    item_explain      VARCHAR(2000) DEFAULT NULL COMMENT '项目说明',
    policy_flag       VARCHAR(50)  DEFAULT NULL COMMENT '政策标识',
    pay_std           VARCHAR(50)  DEFAULT NULL COMMENT '支付标准',
    memo              VARCHAR(500) DEFAULT NULL COMMENT '备注',
    data_source       VARCHAR(50)  DEFAULT NULL COMMENT '数据来源',
    ver               VARCHAR(30)  DEFAULT NULL COMMENT '数据版本',
    PRIMARY KEY (id),
    KEY idx_nat_item_code (nat_item_code),
    KEY idx_loc_item_code (loc_item_code),
    KEY idx_nat_item_name (nat_item_name(80))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标准字典-医疗服务项目';

-- ------------------------------------------------------------
-- 【4】中药饮片(湖北医保)
-- ------------------------------------------------------------
DROP TABLE IF EXISTS std_tcm;
CREATE TABLE std_tcm (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    seq             VARCHAR(20)  DEFAULT NULL COMMENT '序号',
    major_class     VARCHAR(50)  DEFAULT NULL COMMENT '大类名称',
    nat_tcm_code    VARCHAR(50)  DEFAULT NULL COMMENT '国家中药饮片代码',
    tcm_name        VARCHAR(200) DEFAULT NULL COMMENT '中药饮片名称',
    material_name   VARCHAR(200) DEFAULT NULL COMMENT '药材名称',
    proc_method     VARCHAR(500) DEFAULT NULL COMMENT '炮制方法',
    efcc_class      VARCHAR(100) DEFAULT NULL COMMENT '功效分类',
    material_family VARCHAR(100) DEFAULT NULL COMMENT '药材科(族)来源',
    material_species VARCHAR(200) DEFAULT NULL COMMENT '药材种来源',
    medi_part       VARCHAR(100) DEFAULT NULL COMMENT '药用部位',
    nature_meridian VARCHAR(200) DEFAULT NULL COMMENT '性味与归经',
    func_indication VARCHAR(2000) DEFAULT NULL COMMENT '功能与主治',
    usage_dosage    VARCHAR(500) DEFAULT NULL COMMENT '用法与用量',
    pay_policy      VARCHAR(200) DEFAULT NULL COMMENT '医保支付政策',
    chrgitm_lv      VARCHAR(20)  DEFAULT NULL COMMENT '甲乙丙类标识',
    data_source     VARCHAR(50)  DEFAULT NULL COMMENT '数据来源',
    ver             VARCHAR(30)  DEFAULT NULL COMMENT '数据版本',
    PRIMARY KEY (id),
    KEY idx_nat_tcm_code (nat_tcm_code),
    KEY idx_tcm_name (tcm_name(80))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标准字典-中药饮片';

-- ------------------------------------------------------------
-- 【5】医疗机构制剂(湖北医保)
-- ------------------------------------------------------------
DROP TABLE IF EXISTS std_preparation;
CREATE TABLE std_preparation (
    id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    seq                 VARCHAR(20)  DEFAULT NULL COMMENT '序号',
    chg_log             VARCHAR(200) DEFAULT NULL COMMENT '变更日志',
    policy_flag         VARCHAR(50)  DEFAULT NULL COMMENT '政策标识',
    region              VARCHAR(50)  DEFAULT NULL COMMENT '地区',
    prep_code           VARCHAR(50)  DEFAULT NULL COMMENT '制剂代码',
    applicant           VARCHAR(200) DEFAULT NULL COMMENT '申请人单位名称',
    prep_class          VARCHAR(50)  DEFAULT NULL COMMENT '制剂类别',
    prep_name           VARCHAR(500) DEFAULT NULL COMMENT '制剂名称',
    dosform             VARCHAR(100) DEFAULT NULL COMMENT '剂型',
    spec                VARCHAR(255) DEFAULT NULL COMMENT '规格',
    min_pack_qty        VARCHAR(30)  DEFAULT NULL COMMENT '最小包装数量',
    min_pack_unit       VARCHAR(30)  DEFAULT NULL COMMENT '最小包装单位',
    min_prep_unit       VARCHAR(30)  DEFAULT NULL COMMENT '最小制剂单位',
    pack_material       VARCHAR(100) DEFAULT NULL COMMENT '包装材质',
    entrust_entp        VARCHAR(200) DEFAULT NULL COMMENT '委托制剂配制单位名称',
    entrust_addr        VARCHAR(500) DEFAULT NULL COMMENT '委托制剂配置地址',
    approval_no         VARCHAR(100) DEFAULT NULL COMMENT '批准文号',
    approval_valid_date VARCHAR(40)  DEFAULT NULL COMMENT '批准文号有效期',
    license_no          VARCHAR(100) DEFAULT NULL COMMENT '许可证编号',
    exec_std            VARCHAR(200) DEFAULT NULL COMMENT '执行标准',
    indication          VARCHAR(2000) DEFAULT NULL COMMENT '适应症/功能主治',
    usage_method        VARCHAR(1000) DEFAULT NULL COMMENT '用法用量',
    child_use           VARCHAR(500) DEFAULT NULL COMMENT '儿童用药',
    elder_use           VARCHAR(500) DEFAULT NULL COMMENT '老年患者用药',
    ver                 VARCHAR(30)  DEFAULT NULL COMMENT '数据版本',
    PRIMARY KEY (id),
    KEY idx_prep_code (prep_code),
    KEY idx_prep_name (prep_name(80))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标准字典-医疗机构制剂';

-- ------------------------------------------------------------
-- 【6】体外诊断试剂(湖北医保)
-- ------------------------------------------------------------
DROP TABLE IF EXISTS std_ivd;
CREATE TABLE std_ivd (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    seq            VARCHAR(20)  DEFAULT NULL COMMENT '序号',
    ivd_code       VARCHAR(50)  DEFAULT NULL COMMENT '医保体外诊断试剂分类代码',
    cat1           VARCHAR(100) DEFAULT NULL COMMENT '一级分类',
    cat2           VARCHAR(100) DEFAULT NULL COMMENT '二级分类',
    cat3           VARCHAR(100) DEFAULT NULL COMMENT '三级分类',
    test_class     VARCHAR(100) DEFAULT NULL COMMENT '检测类别',
    test_index     VARCHAR(200) DEFAULT NULL COMMENT '检测指标',
    app_mode       VARCHAR(50)  DEFAULT NULL COMMENT '应用方式',
    test_type      VARCHAR(50)  DEFAULT NULL COMMENT '检测类型',
    test_item      VARCHAR(200) DEFAULT NULL COMMENT '检测项',
    entp_name      VARCHAR(200) DEFAULT NULL COMMENT '企业名称',
    result_attr    VARCHAR(50)  DEFAULT NULL COMMENT '检测结果属性',
    prod_name      VARCHAR(500) DEFAULT NULL COMMENT '单件产品名称',
    reg_record_no  VARCHAR(200) DEFAULT NULL COMMENT '注册备案号',
    pack_spec      VARCHAR(200) DEFAULT NULL COMMENT '包装规格',
    pack_unit      VARCHAR(50)  DEFAULT NULL COMMENT '包装计量单位',
    volume_ml      VARCHAR(30)  DEFAULT NULL COMMENT '容量(ml)',
    human_dose     VARCHAR(30)  DEFAULT NULL COMMENT '人份',
    other          VARCHAR(200) DEFAULT NULL COMMENT '其他',
    applicable_inst VARCHAR(200) DEFAULT NULL COMMENT '适用仪器',
    udi            VARCHAR(100) DEFAULT NULL COMMENT 'UDI',
    data_source    VARCHAR(50)  DEFAULT NULL COMMENT '数据来源',
    chg_log        VARCHAR(200) DEFAULT NULL COMMENT '变更日志',
    ver            VARCHAR(30)  DEFAULT NULL COMMENT '数据版本',
    PRIMARY KEY (id),
    KEY idx_ivd_code (ivd_code),
    KEY idx_prod_name (prod_name(80))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标准字典-体外诊断试剂';

-- ------------------------------------------------------------
-- 【7】医用耗材与医疗服务项目对应关系(湖北医保)
-- ------------------------------------------------------------
DROP TABLE IF EXISTS std_cons_item_rel;
CREATE TABLE std_cons_item_rel (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    seq            VARCHAR(20)  DEFAULT NULL COMMENT '序号',
    code           VARCHAR(50)  DEFAULT NULL COMMENT '编码',
    diag_item_code VARCHAR(50)  DEFAULT NULL COMMENT '诊疗项目编码',
    item_cat_name  VARCHAR(500) DEFAULT NULL COMMENT '类别(项目)名称',
    cons_variety   VARCHAR(200) DEFAULT NULL COMMENT '医用材料品种',
    cons_code20    VARCHAR(50)  DEFAULT NULL COMMENT '耗材代码20位',
    cat1           VARCHAR(100) DEFAULT NULL COMMENT '一级分类',
    cat2           VARCHAR(100) DEFAULT NULL COMMENT '二级分类',
    cat3           VARCHAR(100) DEFAULT NULL COMMENT '三级分类',
    hi_genname     VARCHAR(200) DEFAULT NULL COMMENT '医保通用名',
    material       VARCHAR(100) DEFAULT NULL COMMENT '材质',
    feature        VARCHAR(100) DEFAULT NULL COMMENT '特征',
    cons_entp      VARCHAR(200) DEFAULT NULL COMMENT '耗材企业',
    policy_flag    VARCHAR(20)  DEFAULT NULL COMMENT '政策标识',
    reg_cert_no    VARCHAR(200) DEFAULT NULL COMMENT '注册证号',
    cons_type      VARCHAR(30)  DEFAULT NULL COMMENT '耗材类型',
    data_source    VARCHAR(50)  DEFAULT NULL COMMENT '数据来源',
    memo           VARCHAR(200) DEFAULT NULL COMMENT '备注',
    ver            VARCHAR(30)  DEFAULT NULL COMMENT '数据版本',
    PRIMARY KEY (id),
    KEY idx_cons_code20 (cons_code20),
    KEY idx_diag_item_code (diag_item_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标准字典-耗材与医疗服务项目对应关系';

-- ------------------------------------------------------------
-- 【8】医保ICD10疾病诊断(湖北2.0, 完整分类与代码)
-- ------------------------------------------------------------
DROP TABLE IF EXISTS std_icd10;
CREATE TABLE std_icd10 (
    id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    chapter            VARCHAR(20)  DEFAULT NULL COMMENT '章',
    chapter_code_range VARCHAR(50)  DEFAULT NULL COMMENT '章代码范围',
    chapter_name       VARCHAR(200) DEFAULT NULL COMMENT '章的名称',
    section_code_range VARCHAR(50)  DEFAULT NULL COMMENT '节代码范围',
    section_name       VARCHAR(200) DEFAULT NULL COMMENT '节名称',
    cat_code           VARCHAR(50)  DEFAULT NULL COMMENT '类目代码',
    cat_name           VARCHAR(200) DEFAULT NULL COMMENT '类目名称',
    subcat_code        VARCHAR(50)  DEFAULT NULL COMMENT '亚目代码',
    subcat_name        VARCHAR(500) DEFAULT NULL COMMENT '亚目名称',
    diag_code          VARCHAR(50)  DEFAULT NULL COMMENT '诊断代码',
    diag_name          VARCHAR(500) DEFAULT NULL COMMENT '诊断名称',
    src_sheet          VARCHAR(50)  DEFAULT NULL COMMENT '来源sheet',
    ver                VARCHAR(30)  DEFAULT NULL COMMENT '数据版本',
    PRIMARY KEY (id),
    KEY idx_diag_code (diag_code),
    KEY idx_diag_name (diag_name(80))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标准字典-医保ICD10疾病诊断';

-- ------------------------------------------------------------
-- 【9】医保ICD9手术操作(湖北2.0, 完整分类与代码)
-- ------------------------------------------------------------
DROP TABLE IF EXISTS std_icd9;
CREATE TABLE std_icd9 (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    chapter      VARCHAR(20)  DEFAULT NULL COMMENT '章',
    chapter_name VARCHAR(200) DEFAULT NULL COMMENT '章的名称',
    cat_code     VARCHAR(50)  DEFAULT NULL COMMENT '类目代码',
    cat_name     VARCHAR(200) DEFAULT NULL COMMENT '类目名称',
    subcat_code  VARCHAR(50)  DEFAULT NULL COMMENT '亚目代码',
    subcat_name  VARCHAR(200) DEFAULT NULL COMMENT '亚目名称',
    detail_code  VARCHAR(50)  DEFAULT NULL COMMENT '细目代码',
    detail_name  VARCHAR(500) DEFAULT NULL COMMENT '细目名称',
    oper_code    VARCHAR(50)  DEFAULT NULL COMMENT '手术操作代码',
    oper_name    VARCHAR(500) DEFAULT NULL COMMENT '手术操作名称',
    src_sheet    VARCHAR(50)  DEFAULT NULL COMMENT '来源sheet',
    ver          VARCHAR(30)  DEFAULT NULL COMMENT '数据版本',
    PRIMARY KEY (id),
    KEY idx_oper_code (oper_code),
    KEY idx_oper_name (oper_name(80))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标准字典-医保ICD9手术操作';

-- ------------------------------------------------------------
-- 【10】国家临床版2.0疾病分类与代码
-- ------------------------------------------------------------
DROP TABLE IF EXISTS std_icd10_nat;
CREATE TABLE std_icd10_nat (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    main_code    VARCHAR(50)  DEFAULT NULL COMMENT '主要编码',
    add_code     VARCHAR(50)  DEFAULT NULL COMMENT '附加编码',
    disease_name VARCHAR(500) DEFAULT NULL COMMENT '疾病名称',
    src          VARCHAR(50)  DEFAULT NULL COMMENT '来源',
    ver          VARCHAR(30)  DEFAULT NULL COMMENT '数据版本',
    PRIMARY KEY (id),
    KEY idx_main_code (main_code),
    KEY idx_disease_name (disease_name(80))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标准字典-国家临床版疾病分类与代码';

-- ------------------------------------------------------------
-- 【11】国家临床版手术操作分类与代码(3.0 与 ICD-9-CM3 2.0)
-- ------------------------------------------------------------
DROP TABLE IF EXISTS std_icd9_nat;
CREATE TABLE std_icd9_nat (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    main_code    VARCHAR(50)  DEFAULT NULL COMMENT '主要编码/手术操作编码',
    add_code     VARCHAR(50)  DEFAULT NULL COMMENT '附加编码',
    oper_name    VARCHAR(500) DEFAULT NULL COMMENT '手术操作名称',
    oper_cat     VARCHAR(100) DEFAULT NULL COMMENT '类别',
    input_option VARCHAR(50)  DEFAULT NULL COMMENT '录入选项',
    src          VARCHAR(50)  DEFAULT NULL COMMENT '来源',
    ver          VARCHAR(30)  DEFAULT NULL COMMENT '数据版本',
    PRIMARY KEY (id),
    KEY idx_main_code (main_code),
    KEY idx_oper_name (oper_name(80))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标准字典-国家临床版手术操作分类与代码';

-- ------------------------------------------------------------
-- 【12】肿瘤形态学编码(M码)
-- ------------------------------------------------------------
DROP TABLE IF EXISTS std_morphology;
CREATE TABLE std_morphology (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    morph_code      VARCHAR(50)  DEFAULT NULL COMMENT '肿瘤形态学编码/形态学分类代码',
    morph_name      VARCHAR(200) DEFAULT NULL COMMENT '肿瘤形态学名称/形态学分类',
    tumor_type_code VARCHAR(50)  DEFAULT NULL COMMENT '肿瘤/细胞类型代码',
    tumor_type_name VARCHAR(200) DEFAULT NULL COMMENT '肿瘤/细胞类型',
    src             VARCHAR(50)  DEFAULT NULL COMMENT '来源',
    ver             VARCHAR(30)  DEFAULT NULL COMMENT '数据版本',
    PRIMARY KEY (id),
    KEY idx_morph_code (morph_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标准字典-肿瘤形态学编码';

-- ------------------------------------------------------------
-- 【13】中医疾病分类与代码(GB/T 15657)
-- ------------------------------------------------------------
DROP TABLE IF EXISTS std_tcm_disease;
CREATE TABLE std_tcm_disease (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    dept_cat_code  VARCHAR(20)  DEFAULT NULL COMMENT '科别类目代码',
    dept_cat_name  VARCHAR(100) DEFAULT NULL COMMENT '科别类目名称',
    spec_sys_code  VARCHAR(20)  DEFAULT NULL COMMENT '专科系统分类目代码',
    spec_sys_name  VARCHAR(100) DEFAULT NULL COMMENT '专科系统分类目名称',
    dis_class_code VARCHAR(20)  DEFAULT NULL COMMENT '疾病分类代码',
    dis_class_name VARCHAR(200) DEFAULT NULL COMMENT '疾病分类名称',
    ver            VARCHAR(30)  DEFAULT NULL COMMENT '数据版本',
    PRIMARY KEY (id),
    KEY idx_dis_class_code (dis_class_code),
    KEY idx_dis_class_name (dis_class_name(80))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标准字典-中医疾病分类与代码';

-- ------------------------------------------------------------
-- 【14】中医证候分类与代码(GB/T 15657)
-- ------------------------------------------------------------
DROP TABLE IF EXISTS std_tcm_syndrome;
CREATE TABLE std_tcm_syndrome (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    syn_cat_code   VARCHAR(20)  DEFAULT NULL COMMENT '证候类目代码',
    syn_cat_name   VARCHAR(100) DEFAULT NULL COMMENT '证候类目名称',
    syn_attr_code  VARCHAR(20)  DEFAULT NULL COMMENT '证候属性代码',
    syn_attr_name  VARCHAR(100) DEFAULT NULL COMMENT '证候属性',
    syn_class_code VARCHAR(20)  DEFAULT NULL COMMENT '证候分类代码',
    syn_class_name VARCHAR(200) DEFAULT NULL COMMENT '证候分类名称',
    ver            VARCHAR(30)  DEFAULT NULL COMMENT '数据版本',
    PRIMARY KEY (id),
    KEY idx_syn_class_code (syn_class_code),
    KEY idx_syn_class_name (syn_class_name(80))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标准字典-中医证候分类与代码';

-- ------------------------------------------------------------
-- 【15】中医疾病/证候/治法新老对照
-- ------------------------------------------------------------
DROP TABLE IF EXISTS std_tcm_mapping;
CREATE TABLE std_tcm_mapping (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    map_type     VARCHAR(20)  DEFAULT NULL COMMENT '映射类型(疾病/证候/治法)',
    seq          VARCHAR(20)  DEFAULT NULL COMMENT '序号',
    new_code     VARCHAR(50)  DEFAULT NULL COMMENT '修订版代码',
    new_no       VARCHAR(50)  DEFAULT NULL COMMENT '编号',
    new_name     VARCHAR(200) DEFAULT NULL COMMENT '修订版名称',
    new_optional VARCHAR(200) DEFAULT NULL COMMENT '修订版可选用词',
    old_code     VARCHAR(50)  DEFAULT NULL COMMENT '原代码',
    old_name     VARCHAR(200) DEFAULT NULL COMMENT '原名称',
    ver          VARCHAR(30)  DEFAULT NULL COMMENT '数据版本',
    PRIMARY KEY (id),
    KEY idx_map_type (map_type),
    KEY idx_new_code (new_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标准字典-中医新老对照';

-- ============================================================
-- 字典标准类型标识 std_type (按数据来源分3类, 每行落库可查)
--   医保字典     : 湖北医保编码库 + 医保接口规范第6章 + 医保版ICD
--   国家临床标准 : 国家临床版 ICD / 肿瘤形态学
--   中医标准     : 中医疾病/证候分类与新老对照
-- 说明: ADD COLUMN 带 DEFAULT 会将现有行回填为对应标准类型;
--       导入器亦会显式写入 std_type, 两者取值一致。
-- ============================================================
ALTER TABLE std_cv_code       ADD COLUMN std_type VARCHAR(30) DEFAULT '医保字典'     COMMENT '字典标准类型';
ALTER TABLE std_drug          ADD COLUMN std_type VARCHAR(30) DEFAULT '医保字典'     COMMENT '字典标准类型';
ALTER TABLE std_consumable    ADD COLUMN std_type VARCHAR(30) DEFAULT '医保字典'     COMMENT '字典标准类型';
ALTER TABLE std_med_service   ADD COLUMN std_type VARCHAR(30) DEFAULT '医保字典'     COMMENT '字典标准类型';
ALTER TABLE std_tcm           ADD COLUMN std_type VARCHAR(30) DEFAULT '医保字典'     COMMENT '字典标准类型';
ALTER TABLE std_preparation   ADD COLUMN std_type VARCHAR(30) DEFAULT '医保字典'     COMMENT '字典标准类型';
ALTER TABLE std_ivd           ADD COLUMN std_type VARCHAR(30) DEFAULT '医保字典'     COMMENT '字典标准类型';
ALTER TABLE std_cons_item_rel ADD COLUMN std_type VARCHAR(30) DEFAULT '医保字典'     COMMENT '字典标准类型';
ALTER TABLE std_icd10         ADD COLUMN std_type VARCHAR(30) DEFAULT '医保字典'     COMMENT '字典标准类型';
ALTER TABLE std_icd9          ADD COLUMN std_type VARCHAR(30) DEFAULT '医保字典'     COMMENT '字典标准类型';
ALTER TABLE std_icd10_nat     ADD COLUMN std_type VARCHAR(30) DEFAULT '国家临床标准' COMMENT '字典标准类型';
ALTER TABLE std_icd9_nat      ADD COLUMN std_type VARCHAR(30) DEFAULT '国家临床标准' COMMENT '字典标准类型';
ALTER TABLE std_morphology    ADD COLUMN std_type VARCHAR(30) DEFAULT '国家临床标准' COMMENT '字典标准类型';
ALTER TABLE std_tcm_disease   ADD COLUMN std_type VARCHAR(30) DEFAULT '中医标准'     COMMENT '字典标准类型';
ALTER TABLE std_tcm_syndrome  ADD COLUMN std_type VARCHAR(30) DEFAULT '中医标准'     COMMENT '字典标准类型';
ALTER TABLE std_tcm_mapping   ADD COLUMN std_type VARCHAR(30) DEFAULT '中医标准'     COMMENT '字典标准类型';

-- 标准类型查询索引(便于业务按标准来源过滤)
ALTER TABLE std_cv_code       ADD KEY idx_cv_std_type (std_type);
ALTER TABLE std_drug          ADD KEY idx_drug_std_type (std_type);
ALTER TABLE std_icd10         ADD KEY idx_icd10_std_type (std_type);

-- ============================================================
-- 来源文档标识 src_doc (哪一份规范/数据库的标准, 每行落库可查)
-- 多源字典(icd9_nat/morphology)逐行来源不同, 由导入器按源写入;
-- 下方 DEFAULT 仅为单源表/占位, 具体以导入器写入为准。
-- ============================================================
ALTER TABLE std_cv_code       ADD COLUMN src_doc VARCHAR(200) DEFAULT NULL COMMENT '来源文档';
ALTER TABLE std_drug          ADD COLUMN src_doc VARCHAR(200) DEFAULT NULL COMMENT '来源文档';
ALTER TABLE std_consumable    ADD COLUMN src_doc VARCHAR(200) DEFAULT NULL COMMENT '来源文档';
ALTER TABLE std_med_service   ADD COLUMN src_doc VARCHAR(200) DEFAULT NULL COMMENT '来源文档';
ALTER TABLE std_tcm           ADD COLUMN src_doc VARCHAR(200) DEFAULT NULL COMMENT '来源文档';
ALTER TABLE std_preparation   ADD COLUMN src_doc VARCHAR(200) DEFAULT NULL COMMENT '来源文档';
ALTER TABLE std_ivd           ADD COLUMN src_doc VARCHAR(200) DEFAULT NULL COMMENT '来源文档';
ALTER TABLE std_cons_item_rel ADD COLUMN src_doc VARCHAR(200) DEFAULT NULL COMMENT '来源文档';
ALTER TABLE std_icd10         ADD COLUMN src_doc VARCHAR(200) DEFAULT NULL COMMENT '来源文档';
ALTER TABLE std_icd9          ADD COLUMN src_doc VARCHAR(200) DEFAULT NULL COMMENT '来源文档';
ALTER TABLE std_icd10_nat     ADD COLUMN src_doc VARCHAR(200) DEFAULT NULL COMMENT '来源文档';
ALTER TABLE std_icd9_nat      ADD COLUMN src_doc VARCHAR(200) DEFAULT NULL COMMENT '来源文档';
ALTER TABLE std_morphology    ADD COLUMN src_doc VARCHAR(200) DEFAULT NULL COMMENT '来源文档';
ALTER TABLE std_tcm_disease   ADD COLUMN src_doc VARCHAR(200) DEFAULT NULL COMMENT '来源文档';
ALTER TABLE std_tcm_syndrome  ADD COLUMN src_doc VARCHAR(200) DEFAULT NULL COMMENT '来源文档';
ALTER TABLE std_tcm_mapping   ADD COLUMN src_doc VARCHAR(200) DEFAULT NULL COMMENT '来源文档';

-- ============================================================
-- 有效性生命周期字段 (每行可维护有效标志与生失效时间)
-- vali_flag: 1-有效 0-无效(作废); begn_time: 生效时间; end_time: 作废时间
-- 默认 vali_flag='1', 时间留空; 导入为 TRUNCATE+全量重写, 新行由 DEFAULT 自动置'1'
-- ============================================================
ALTER TABLE std_cv_code       ADD COLUMN vali_flag VARCHAR(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效';
ALTER TABLE std_cv_code       ADD COLUMN begn_time DATETIME   DEFAULT NULL COMMENT '生效时间';
ALTER TABLE std_cv_code       ADD COLUMN end_time  DATETIME   DEFAULT NULL COMMENT '作废时间';
ALTER TABLE std_drug          ADD COLUMN vali_flag VARCHAR(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效';
ALTER TABLE std_drug          ADD COLUMN begn_time DATETIME   DEFAULT NULL COMMENT '生效时间';
ALTER TABLE std_drug          ADD COLUMN end_time  DATETIME   DEFAULT NULL COMMENT '作废时间';
ALTER TABLE std_consumable    ADD COLUMN vali_flag VARCHAR(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效';
ALTER TABLE std_consumable    ADD COLUMN begn_time DATETIME   DEFAULT NULL COMMENT '生效时间';
ALTER TABLE std_consumable    ADD COLUMN end_time  DATETIME   DEFAULT NULL COMMENT '作废时间';
ALTER TABLE std_med_service   ADD COLUMN vali_flag VARCHAR(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效';
ALTER TABLE std_med_service   ADD COLUMN begn_time DATETIME   DEFAULT NULL COMMENT '生效时间';
ALTER TABLE std_med_service   ADD COLUMN end_time  DATETIME   DEFAULT NULL COMMENT '作废时间';
ALTER TABLE std_tcm           ADD COLUMN vali_flag VARCHAR(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效';
ALTER TABLE std_tcm           ADD COLUMN begn_time DATETIME   DEFAULT NULL COMMENT '生效时间';
ALTER TABLE std_tcm           ADD COLUMN end_time  DATETIME   DEFAULT NULL COMMENT '作废时间';
ALTER TABLE std_preparation   ADD COLUMN vali_flag VARCHAR(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效';
ALTER TABLE std_preparation   ADD COLUMN begn_time DATETIME   DEFAULT NULL COMMENT '生效时间';
ALTER TABLE std_preparation   ADD COLUMN end_time  DATETIME   DEFAULT NULL COMMENT '作废时间';
ALTER TABLE std_ivd           ADD COLUMN vali_flag VARCHAR(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效';
ALTER TABLE std_ivd           ADD COLUMN begn_time DATETIME   DEFAULT NULL COMMENT '生效时间';
ALTER TABLE std_ivd           ADD COLUMN end_time  DATETIME   DEFAULT NULL COMMENT '作废时间';
ALTER TABLE std_cons_item_rel ADD COLUMN vali_flag VARCHAR(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效';
ALTER TABLE std_cons_item_rel ADD COLUMN begn_time DATETIME   DEFAULT NULL COMMENT '生效时间';
ALTER TABLE std_cons_item_rel ADD COLUMN end_time  DATETIME   DEFAULT NULL COMMENT '作废时间';
ALTER TABLE std_icd10         ADD COLUMN vali_flag VARCHAR(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效';
ALTER TABLE std_icd10         ADD COLUMN begn_time DATETIME   DEFAULT NULL COMMENT '生效时间';
ALTER TABLE std_icd10         ADD COLUMN end_time  DATETIME   DEFAULT NULL COMMENT '作废时间';
ALTER TABLE std_icd9          ADD COLUMN vali_flag VARCHAR(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效';
ALTER TABLE std_icd9          ADD COLUMN begn_time DATETIME   DEFAULT NULL COMMENT '生效时间';
ALTER TABLE std_icd9          ADD COLUMN end_time  DATETIME   DEFAULT NULL COMMENT '作废时间';
ALTER TABLE std_icd10_nat     ADD COLUMN vali_flag VARCHAR(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效';
ALTER TABLE std_icd10_nat     ADD COLUMN begn_time DATETIME   DEFAULT NULL COMMENT '生效时间';
ALTER TABLE std_icd10_nat     ADD COLUMN end_time  DATETIME   DEFAULT NULL COMMENT '作废时间';
ALTER TABLE std_icd9_nat      ADD COLUMN vali_flag VARCHAR(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效';
ALTER TABLE std_icd9_nat      ADD COLUMN begn_time DATETIME   DEFAULT NULL COMMENT '生效时间';
ALTER TABLE std_icd9_nat      ADD COLUMN end_time  DATETIME   DEFAULT NULL COMMENT '作废时间';
ALTER TABLE std_morphology    ADD COLUMN vali_flag VARCHAR(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效';
ALTER TABLE std_morphology    ADD COLUMN begn_time DATETIME   DEFAULT NULL COMMENT '生效时间';
ALTER TABLE std_morphology    ADD COLUMN end_time  DATETIME   DEFAULT NULL COMMENT '作废时间';
ALTER TABLE std_tcm_disease   ADD COLUMN vali_flag VARCHAR(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效';
ALTER TABLE std_tcm_disease   ADD COLUMN begn_time DATETIME   DEFAULT NULL COMMENT '生效时间';
ALTER TABLE std_tcm_disease   ADD COLUMN end_time  DATETIME   DEFAULT NULL COMMENT '作废时间';
ALTER TABLE std_tcm_syndrome  ADD COLUMN vali_flag VARCHAR(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效';
ALTER TABLE std_tcm_syndrome  ADD COLUMN begn_time DATETIME   DEFAULT NULL COMMENT '生效时间';
ALTER TABLE std_tcm_syndrome  ADD COLUMN end_time  DATETIME   DEFAULT NULL COMMENT '作废时间';
ALTER TABLE std_tcm_mapping   ADD COLUMN vali_flag VARCHAR(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效';
ALTER TABLE std_tcm_mapping   ADD COLUMN begn_time DATETIME   DEFAULT NULL COMMENT '生效时间';
ALTER TABLE std_tcm_mapping   ADD COLUMN end_time  DATETIME   DEFAULT NULL COMMENT '作废时间';

-- ============================================================
-- 【新】WS/T 364—2023 卫生健康信息数据元值域代码(标准类型=卫生健康标准)
--   来源: 字典标准/WST-(363-364)-2023 各分册 PDF 离线抽取(表格法+题注定位)
--   粒度: 一行 = 一个值域代码表(CV)的一个值; cv_code 如 CV02.01.101
--   元数据列(std_type/src_doc/vali_flag/begn_time/end_time)随建表内联, 与其他 std_* 表保持一致
-- ============================================================
DROP TABLE IF EXISTS std_wst364_code;
CREATE TABLE std_wst364_code (
    id         BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    cv_code    VARCHAR(30)   DEFAULT NULL COMMENT '值域代码表标识(如CV02.01.101)',
    cv_name    VARCHAR(200)  DEFAULT NULL COMMENT '值域代码表名称',
    val_code   VARCHAR(30)   DEFAULT NULL COMMENT '值',
    val_name   VARCHAR(200)  DEFAULT NULL COMMENT '值含义',
    remark     VARCHAR(1000) DEFAULT NULL COMMENT '说明',
    part_no    VARCHAR(10)   DEFAULT NULL COMMENT '所属部分号(第N部分)',
    part_name  VARCHAR(100)  DEFAULT NULL COMMENT '所属部分名称',
    ver        VARCHAR(30)   DEFAULT NULL COMMENT '数据版本',
    std_type   VARCHAR(30)   DEFAULT '卫生健康标准' COMMENT '字典标准类型',
    src_doc    VARCHAR(200)  DEFAULT NULL COMMENT '来源文档',
    vali_flag  VARCHAR(3)    DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
    begn_time  DATETIME      DEFAULT NULL COMMENT '生效时间',
    end_time   DATETIME      DEFAULT NULL COMMENT '作废时间',
    PRIMARY KEY (id),
    KEY idx_wst364_cv_code (cv_code),
    KEY idx_wst364_val_code (val_code),
    KEY idx_wst364_cv_name (cv_name),
    KEY idx_wst364_std_type (std_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='WS/T 364-2023 卫生健康信息数据元值域代码';

-- ============================================================
-- 【新】湖北省健康医疗大数据采集规范--数据元值域代码(标准类型=卫生健康标准)
--   来源: 仓库根 1.2_湖北省健康医疗大数据采集规范--数据元值域代码20240826.docx 离线抽取(文档顺序+节标题关联)
--   粒度: 一行 = 一个值域代码表(节)的一个值; dict_code 如 CV02.01.101 / GB/T 2261.1-2003 / HBCV…
--   元数据列(std_type/src_doc/vali_flag/begn_time/end_time)随建表内联, 与其他 std_* 表保持一致
-- ============================================================
DROP TABLE IF EXISTS std_hbvalue_code;
CREATE TABLE std_hbvalue_code (
    id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    section_no   VARCHAR(20)   DEFAULT NULL COMMENT '规范节号(如3.1)',
    chapter_name VARCHAR(100)  DEFAULT NULL COMMENT '所属章名称(如人口学及社会经济学特征)',
    dict_code    VARCHAR(60)   DEFAULT NULL COMMENT '值域代码表标识(如CV02.01.101/GB/T 2261.1-2003/HBCV…)',
    dict_name    VARCHAR(200)  DEFAULT NULL COMMENT '值域代码表名称(如身份证件类别代码)',
    val_code     VARCHAR(50)   DEFAULT NULL COMMENT '值',
    val_name     VARCHAR(200)  DEFAULT NULL COMMENT '值含义',
    remark       VARCHAR(1000) DEFAULT NULL COMMENT '说明/备注',
    ver          VARCHAR(30)   DEFAULT NULL COMMENT '数据版本',
    std_type     VARCHAR(30)   DEFAULT '卫生健康标准' COMMENT '字典标准类型',
    src_doc      VARCHAR(200)  DEFAULT NULL COMMENT '来源文档',
    vali_flag    VARCHAR(3)    DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
    begn_time    DATETIME      DEFAULT NULL COMMENT '生效时间',
    end_time     DATETIME      DEFAULT NULL COMMENT '作废时间',
    PRIMARY KEY (id),
    KEY idx_hbvalue_dict_code (dict_code),
    KEY idx_hbvalue_val_code (val_code),
    KEY idx_hbvalue_dict_name (dict_name),
    KEY idx_hbvalue_section (section_no),
    KEY idx_hbvalue_std_type (std_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='湖北省健康医疗大数据采集规范-数据元值域代码';

-- ============================================================
-- 【新】武汉市全民健康信息平台数据元值域代码规范(标准类型=卫生健康标准)
--   来源: 字典标准/武汉市…20240905_V1 - (20250326修订)/武汉市全民健康信息平台数据元值域代码规范….docx 离线抽取
--   粒度: 一行 = 一个值域代码表(Heading3)的一个值; dict_code 如 CT01.00.002 / CV02.01.101 / GB/T 2261.1-2003 / WH…
--   元数据列(std_type/src_doc/vali_flag/begn_time/end_time)随建表内联, 与其他 std_* 表保持一致
-- ============================================================
DROP TABLE IF EXISTS std_whvalue_code;
CREATE TABLE std_whvalue_code (
    id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    chapter_name VARCHAR(100)  DEFAULT NULL COMMENT '所属章名称(如人口学及社会经济学特征)',
    dict_code    VARCHAR(60)   DEFAULT NULL COMMENT '值域代码表标识(如CT01.00.002/CV02.01.101/GB/T 2261.1-2003/WH…)',
    dict_name    VARCHAR(200)  DEFAULT NULL COMMENT '值域代码表名称(如身份证件类别)',
    val_code     VARCHAR(50)   DEFAULT NULL COMMENT '值',
    val_name     VARCHAR(200)  DEFAULT NULL COMMENT '值含义',
    remark       VARCHAR(1000) DEFAULT NULL COMMENT '说明',
    ver          VARCHAR(30)   DEFAULT NULL COMMENT '数据版本',
    std_type     VARCHAR(30)   DEFAULT '卫生健康标准' COMMENT '字典标准类型',
    src_doc      VARCHAR(200)  DEFAULT NULL COMMENT '来源文档',
    vali_flag    VARCHAR(3)    DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
    begn_time    DATETIME      DEFAULT NULL COMMENT '生效时间',
    end_time     DATETIME      DEFAULT NULL COMMENT '作废时间',
    PRIMARY KEY (id),
    KEY idx_whvalue_dict_code (dict_code),
    KEY idx_whvalue_val_code (val_code),
    KEY idx_whvalue_dict_name (dict_name),
    KEY idx_whvalue_std_type (std_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='武汉市全民健康信息平台数据元值域代码规范';

-- ============================================================
-- 【新】中医疾病分类与代码(新版/修订版 GB/T 15657-2021 第1部分)(标准类型=中医标准)
--   来源: 字典标准/…/中医诊断/中医疾病新老对照.xlsx sheet0 的"修订版"列(新代码A01…+名称)
--   与老版 std_tcm_disease(BNF码·20191201)并存; 新老对应关系见 std_tcm_mapping
--   元数据列(std_type/src_doc/vali_flag/begn_time/end_time)随建表内联, 与其他 std_* 表保持一致
-- ============================================================
DROP TABLE IF EXISTS std_tcm_disease_new;
CREATE TABLE std_tcm_disease_new (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    seq           INT          DEFAULT NULL COMMENT '序号',
    dis_code      VARCHAR(30)  DEFAULT NULL COMMENT '新版疾病代码(如A01.01.01)',
    dis_no        VARCHAR(30)  DEFAULT NULL COMMENT '编号(第1部分章节号,如2.1.1)',
    dis_name      VARCHAR(200) DEFAULT NULL COMMENT '新版疾病名称(如感冒)',
    optional_name VARCHAR(200) DEFAULT NULL COMMENT '可选用词(同义/近义词)',
    ver           VARCHAR(30)  DEFAULT NULL COMMENT '数据版本',
    std_type      VARCHAR(30)  DEFAULT '中医标准' COMMENT '字典标准类型',
    src_doc       VARCHAR(200) DEFAULT NULL COMMENT '来源文档',
    vali_flag     VARCHAR(3)   DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
    begn_time     DATETIME     DEFAULT NULL COMMENT '生效时间',
    end_time      DATETIME     DEFAULT NULL COMMENT '作废时间',
    PRIMARY KEY (id),
    KEY idx_tcmdnew_dis_code (dis_code),
    KEY idx_tcmdnew_dis_name (dis_name),
    KEY idx_tcmdnew_std_type (std_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='中医疾病分类与代码(新版GB/T 15657-2021第1部分)';

-- ============================================================
-- 【新】中医证候分类与代码(新版/修订版 GB/T 15657-2021 第2部分)(标准类型=中医标准)
--   来源: 字典标准/…/中医诊断/中医疾病新老对照.xlsx sheet1 的"修订版"列(新代码B01…+名称)
--   与老版 std_tcm_syndrome(ZBF码·20191201)并存; 新老对应关系见 std_tcm_mapping
--   元数据列(std_type/src_doc/vali_flag/begn_time/end_time)随建表内联, 与其他 std_* 表保持一致
-- ============================================================
DROP TABLE IF EXISTS std_tcm_syndrome_new;
CREATE TABLE std_tcm_syndrome_new (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    seq           INT          DEFAULT NULL COMMENT '序号',
    syn_code      VARCHAR(30)  DEFAULT NULL COMMENT '新版证候代码(如B01.03.01)',
    syn_no        VARCHAR(30)  DEFAULT NULL COMMENT '编号(第2部分章节号,如2.3.1)',
    syn_name      VARCHAR(200) DEFAULT NULL COMMENT '新版证候名称(如表虚证)',
    optional_name VARCHAR(200) DEFAULT NULL COMMENT '可选用词(同义/近义词)',
    ver           VARCHAR(30)  DEFAULT NULL COMMENT '数据版本',
    std_type      VARCHAR(30)  DEFAULT '中医标准' COMMENT '字典标准类型',
    src_doc       VARCHAR(200) DEFAULT NULL COMMENT '来源文档',
    vali_flag     VARCHAR(3)   DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
    begn_time     DATETIME     DEFAULT NULL COMMENT '生效时间',
    end_time      DATETIME     DEFAULT NULL COMMENT '作废时间',
    PRIMARY KEY (id),
    KEY idx_tcmsnew_syn_code (syn_code),
    KEY idx_tcmsnew_syn_name (syn_name),
    KEY idx_tcmsnew_std_type (std_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='中医证候分类与代码(新版GB/T 15657-2021第2部分)';

-- ============================================================
-- 【新】全国医疗服务项目技术规范(2023年版)(标准类型=物价标准)
--   源: 全国医疗服务项目技术规范(2023年版).xlsx Sheet1 原样全列导入(不合并/不对照)
--   8位字母数字混合项目码为数据行, 1-4位纯字母层级标题记入 cat_name 路径
--   由 tools/extract_med_service_price.py 离线抽取为 seed/std_msi_nat.tsv
-- ============================================================
DROP TABLE IF EXISTS std_msi_nat;
CREATE TABLE std_msi_nat (
    id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    item_code       VARCHAR(32)   DEFAULT NULL COMMENT '项目编码(8位字母数字混合)',
    item_name       VARCHAR(300)  DEFAULT NULL COMMENT '项目名称(中文)',
    item_name_en    VARCHAR(500)  DEFAULT NULL COMMENT '项目名称(英文)',
    item_content    VARCHAR(2000) DEFAULT NULL COMMENT '项目内涵',
    consumable_req  VARCHAR(1000) DEFAULT NULL COMMENT '必需耗材',
    consumable_opt  VARCHAR(1000) DEFAULT NULL COMMENT '可选耗材',
    consumable_low  VARCHAR(500)  DEFAULT NULL COMMENT '低值耗材分档',
    hr_time         VARCHAR(200)  DEFAULT NULL COMMENT '基本人力消耗及耗时',
    tech_difficulty VARCHAR(100)  DEFAULT NULL COMMENT '技术难度',
    risk_level      VARCHAR(100)  DEFAULT NULL COMMENT '风险程度',
    hr_value        VARCHAR(50)   DEFAULT NULL COMMENT '人力资源消耗相对值',
    unit            VARCHAR(50)   DEFAULT NULL COMMENT '计量单位',
    remark          VARCHAR(1000) DEFAULT NULL COMMENT '说明',
    adjust_coef     VARCHAR(200)  DEFAULT NULL COMMENT '特殊情况资源消耗调整系数',
    invoice_class   VARCHAR(100)  DEFAULT NULL COMMENT '收费票据分类',
    acct_class      VARCHAR(100)  DEFAULT NULL COMMENT '会计科目分类',
    mr_cost_class   VARCHAR(300)  DEFAULT NULL COMMENT '病案首页费用分类',
    cat_name        VARCHAR(300)  DEFAULT NULL COMMENT '分类路径(类>章>节>组)',
    cat_code        VARCHAR(20)   DEFAULT NULL COMMENT '分类码(std_msi_cat.cat_code, 节级优先, 无节时章级, 附录为空)',
    ver             VARCHAR(30)   DEFAULT NULL COMMENT '数据版本',
    std_type        VARCHAR(30)   DEFAULT '物价标准' COMMENT '字典标准类型',
    src_doc         VARCHAR(200)  DEFAULT NULL COMMENT '来源文档',
    vali_flag       VARCHAR(3)    DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
    begn_time       DATETIME      DEFAULT NULL COMMENT '生效时间',
    end_time        DATETIME      DEFAULT NULL COMMENT '作废时间',
    PRIMARY KEY (id),
    KEY idx_msin_item_code (item_code),
    KEY idx_msin_item_name (item_name),
    KEY idx_msin_std_type (std_type),
    KEY idx_msin_cat_code (cat_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全国医疗服务项目技术规范(2023年版)原样全列';

-- ============================================================
-- 【新】医疗服务项目物价分类(2023技术规范·类/章/节三级)(标准类型=物价标准)
--   源: 全国医疗服务项目技术规范(2023年版).xlsx Sheet1 层级标题行
--   编码沿用原生字母码(类1字母/章2字母/节3字母, 区间码取首段), 第四级组并入节,
--   区间横幅行与附录表(器械和器具等)不纳入
--   由 tools/extract_msi_cat_tree.py 离线抽取为 seed/std_msi_cat.tsv
-- ============================================================
DROP TABLE IF EXISTS std_msi_cat;
CREATE TABLE std_msi_cat (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    cat_code    VARCHAR(20)  NOT NULL COMMENT '分类码(原生字母码: 类1/章2/节3字母, 区间取首段)',
    cat_name    VARCHAR(200) DEFAULT NULL COMMENT '分类名称(类/章/节)',
    parent_code VARCHAR(20)  DEFAULT NULL COMMENT '上级分类码(类为空)',
    lv          TINYINT      DEFAULT NULL COMMENT '层级: 1-类 2-章 3-节',
    item_count  INT          DEFAULT NULL COMMENT '下属项目数(节含组, 章含节与直属)',
    ver         VARCHAR(30)  DEFAULT NULL COMMENT '数据版本',
    std_type    VARCHAR(30)  DEFAULT '物价标准' COMMENT '字典标准类型',
    src_doc     VARCHAR(200) DEFAULT NULL COMMENT '来源文档',
    PRIMARY KEY (id),
    UNIQUE KEY uk_msic_cat_code (cat_code),
    KEY idx_msic_parent (parent_code),
    KEY idx_msic_lv (lv)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='医疗服务项目物价分类(2023技术规范类/章/节三级)';

-- ============================================================
-- 【新】湖北省医疗服务价格项目及医保支付目录(2023版)(标准类型=物价标准)
--   源: 湖北省医疗服务价格项目及医保支付目录(2023版).xlsx sheet湖北医保物价目录 原样导入
--   9位数字码=基础项, 9位+字母后缀=子项, 均原样成行, 层级标题记入 cat_name
--   由 tools/extract_med_service_price.py 离线抽取为 seed/std_msi_hb.tsv
-- ============================================================
DROP TABLE IF EXISTS std_msi_hb;
CREATE TABLE std_msi_hb (
    id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    item_code    VARCHAR(32)   DEFAULT NULL COMMENT '编码(9位基础码或9位+字母后缀子项码)',
    item_name    VARCHAR(300)  DEFAULT NULL COMMENT '项目名称',
    item_content VARCHAR(2000) DEFAULT NULL COMMENT '项目内涵',
    excluded     VARCHAR(1000) DEFAULT NULL COMMENT '除外内容',
    unit         VARCHAR(50)   DEFAULT NULL COMMENT '计价单位',
    pay_cat      VARCHAR(50)   DEFAULT NULL COMMENT '医保支付类别(甲类/乙类/自费)',
    item_explain VARCHAR(1000) DEFAULT NULL COMMENT '说明',
    remark       VARCHAR(1000) DEFAULT NULL COMMENT '备注',
    trial        VARCHAR(50)   DEFAULT NULL COMMENT '试行项目标志',
    cat_name     VARCHAR(300)  DEFAULT NULL COMMENT '分类路径(类>章>节)',
    ver          VARCHAR(30)   DEFAULT NULL COMMENT '数据版本',
    std_type     VARCHAR(30)   DEFAULT '物价标准' COMMENT '字典标准类型',
    src_doc      VARCHAR(200)  DEFAULT NULL COMMENT '来源文档',
    vali_flag    VARCHAR(3)    DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
    begn_time    DATETIME      DEFAULT NULL COMMENT '生效时间',
    end_time     DATETIME      DEFAULT NULL COMMENT '作废时间',
    PRIMARY KEY (id),
    KEY idx_msih_item_code (item_code),
    KEY idx_msih_item_name (item_name),
    KEY idx_msih_std_type (std_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='湖北省医疗服务价格项目及医保支付目录(2023版)原样';

-- ============================================================
-- 【新】医疗服务项目相关财务归集口径规范(标准类型=物价标准)
--   源: 医疗服务项目相关财务归集口径规范.pdf 827页线框表逐行原样导入(含层级行/续行)
--   列即原文表列: 2023码/名, 2012码/名, 2001码/名, 收费票据分类, 会计科目分类, 病案首页费用分类
--   由 tools/extract_med_service_price.py 离线抽取为 seed/std_msi_fin.tsv
-- ============================================================
DROP TABLE IF EXISTS std_msi_fin;
CREATE TABLE std_msi_fin (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    code_2023     VARCHAR(32)  DEFAULT NULL COMMENT '2023年版项目编码(层级行/续行为空)',
    name_2023     VARCHAR(300) DEFAULT NULL COMMENT '2023年版项目名称',
    code_2012     VARCHAR(32)  DEFAULT NULL COMMENT '2012年版项目编码',
    name_2012     VARCHAR(300) DEFAULT NULL COMMENT '2012年版项目名称',
    code_2001     VARCHAR(32)  DEFAULT NULL COMMENT '2001/2007年版项目编码',
    name_2001     VARCHAR(300) DEFAULT NULL COMMENT '2001/2007年版项目名称',
    invoice_class VARCHAR(100) DEFAULT NULL COMMENT '收费票据分类',
    acct_class    VARCHAR(100) DEFAULT NULL COMMENT '会计科目分类',
    mr_cost_class VARCHAR(300) DEFAULT NULL COMMENT '病案首页费用分类',
    ver           VARCHAR(30)  DEFAULT NULL COMMENT '数据版本',
    std_type      VARCHAR(30)  DEFAULT '物价标准' COMMENT '字典标准类型',
    src_doc       VARCHAR(200) DEFAULT NULL COMMENT '来源文档',
    vali_flag     VARCHAR(3)   DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
    begn_time     DATETIME     DEFAULT NULL COMMENT '生效时间',
    end_time      DATETIME     DEFAULT NULL COMMENT '作废时间',
    PRIMARY KEY (id),
    KEY idx_msif_code_2023 (code_2023),
    KEY idx_msif_code_2012 (code_2012),
    KEY idx_msif_code_2001 (code_2001),
    KEY idx_msif_name_2023 (name_2023),
    KEY idx_msif_std_type (std_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='医疗服务项目相关财务归集口径规范原样表行';

-- ============================================================
-- 【新】病案首页费用分类(标准类型=物价标准)
--   源: 医疗服务项目相关财务归集口径规范(std_msi_fin 的 mr_cost_class 列去重规范化)
--   由 tools/extract_msi_fin_classes.py 离线抽取为 seed/std_mr_cost_class.tsv
--   层级: 大类(综合医疗服务/诊断/治疗/康复/中医) -> 费用分项(源文括号序号1~12)
-- ============================================================
DROP TABLE IF EXISTS std_mr_cost_class;
CREATE TABLE std_mr_cost_class (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    cat_no     VARCHAR(10)  DEFAULT NULL COMMENT '大类序号(1-5)',
    cat_name   VARCHAR(100) DEFAULT NULL COMMENT '大类名称(综合医疗服务类/诊断类/治疗类/康复类/中医类)',
    item_code  VARCHAR(20)  DEFAULT NULL COMMENT '费用分项编码(源文括号序号1~12,中医手术费/诊断费无编码为空)',
    item_name  VARCHAR(200) DEFAULT NULL COMMENT '费用分项名称',
    raw_value  VARCHAR(300) DEFAULT NULL COMMENT '源文件原始完整值(大类:分项 形式)',
    ver        VARCHAR(30)  DEFAULT NULL COMMENT '数据版本',
    std_type   VARCHAR(30)  DEFAULT '物价标准' COMMENT '字典标准类型',
    src_doc    VARCHAR(200) DEFAULT NULL COMMENT '来源文档',
    vali_flag  VARCHAR(3)   DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
    begn_time  DATETIME     DEFAULT NULL COMMENT '生效时间',
    end_time   DATETIME     DEFAULT NULL COMMENT '作废时间',
    PRIMARY KEY (id),
    KEY idx_mrcost_cat_no (cat_no),
    KEY idx_mrcost_item_code (item_code),
    KEY idx_mrcost_item_name (item_name),
    KEY idx_mrcost_std_type (std_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='病案首页费用分类(源:医疗服务项目相关财务归集口径规范)';

-- ============================================================
-- 【新】收费票据分类(标准类型=物价标准)
--   源: 医疗服务项目相关财务归集口径规范(std_msi_fin 的 invoice_class 列去重)
--   由 tools/extract_msi_fin_classes.py 离线抽取为 seed/std_invoice_class.tsv
--   源文无编码, class_code 为字典内序号; 与会计科目分类逐行 1:1 对应
-- ============================================================
DROP TABLE IF EXISTS std_invoice_class;
CREATE TABLE std_invoice_class (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    class_code  VARCHAR(20)  DEFAULT NULL COMMENT '分类编码(字典内序号,源文无编码)',
    class_name  VARCHAR(100) DEFAULT NULL COMMENT '收费票据分类名称',
    acct_class  VARCHAR(100) DEFAULT NULL COMMENT '对应会计科目分类',
    item_count  VARCHAR(20)  DEFAULT NULL COMMENT '归集医疗服务项目数(源文件出现次数)',
    ver         VARCHAR(30)  DEFAULT NULL COMMENT '数据版本',
    std_type    VARCHAR(30)  DEFAULT '物价标准' COMMENT '字典标准类型',
    src_doc     VARCHAR(200) DEFAULT NULL COMMENT '来源文档',
    vali_flag   VARCHAR(3)   DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
    begn_time   DATETIME     DEFAULT NULL COMMENT '生效时间',
    end_time    DATETIME     DEFAULT NULL COMMENT '作废时间',
    PRIMARY KEY (id),
    KEY idx_inv_code (class_code),
    KEY idx_inv_name (class_name),
    KEY idx_inv_std_type (std_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收费票据分类(源:医疗服务项目相关财务归集口径规范)';

-- ============================================================
-- 【新】会计科目分类(标准类型=物价标准)
--   源: 医疗服务项目相关财务归集口径规范(std_msi_fin 的 acct_class 列去重)
--   由 tools/extract_msi_fin_classes.py 离线抽取为 seed/std_acct_class.tsv
--   源文无编码, class_code 为字典内序号; 与收费票据分类逐行 1:1 对应
-- ============================================================
DROP TABLE IF EXISTS std_acct_class;
CREATE TABLE std_acct_class (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    class_code    VARCHAR(20)  DEFAULT NULL COMMENT '分类编码(字典内序号,源文无编码)',
    class_name    VARCHAR(100) DEFAULT NULL COMMENT '会计科目分类名称',
    invoice_class VARCHAR(100) DEFAULT NULL COMMENT '对应收费票据分类',
    item_count    VARCHAR(20)  DEFAULT NULL COMMENT '归集医疗服务项目数(源文件出现次数)',
    ver           VARCHAR(30)  DEFAULT NULL COMMENT '数据版本',
    std_type      VARCHAR(30)  DEFAULT '物价标准' COMMENT '字典标准类型',
    src_doc       VARCHAR(200) DEFAULT NULL COMMENT '来源文档',
    vali_flag     VARCHAR(3)   DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
    begn_time     DATETIME     DEFAULT NULL COMMENT '生效时间',
    end_time      DATETIME     DEFAULT NULL COMMENT '作废时间',
    PRIMARY KEY (id),
    KEY idx_acct_code (class_code),
    KEY idx_acct_name (class_name),
    KEY idx_acct_std_type (std_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会计科目分类(源:医疗服务项目相关财务归集口径规范)';
