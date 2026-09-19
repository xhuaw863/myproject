-- ============================================================
-- 医保原生多租户HIS - 医生站表(就诊/诊断/病历/处方/检查单)
-- 数据库: yb_interface  字符集: utf8mb4
-- 所有业务表含 tenant_id(租户隔离) + 审计字段 + 逻辑删除
-- ============================================================
USE yb_interface;

-- ------------------------------------------------------------
-- 就诊记录表(医生站接诊主表, 与挂号1:1)
-- ------------------------------------------------------------
DROP TABLE IF EXISTS his_visit;
CREATE TABLE his_visit (
    id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '就诊ID',
    tenant_id          BIGINT       NOT NULL COMMENT '租户ID',
    registration_id    BIGINT       DEFAULT NULL COMMENT '挂号记录ID',
    reg_no             VARCHAR(30)  DEFAULT NULL COMMENT '挂号单号',
    mdtrt_id           VARCHAR(30)  DEFAULT NULL COMMENT '医保就诊ID',
    ipt_otp_no         VARCHAR(30)  DEFAULT NULL COMMENT '院内就诊流水号',
    patient_id         BIGINT       DEFAULT NULL COMMENT '患者ID',
    patient_no         VARCHAR(30)  DEFAULT NULL COMMENT '院内患者号',
    patient_name       VARCHAR(50)  DEFAULT NULL COMMENT '患者姓名',
    gender             VARCHAR(4)   DEFAULT NULL COMMENT '性别',
    age                INT          DEFAULT NULL COMMENT '年龄',
    psn_no             VARCHAR(30)  DEFAULT NULL COMMENT '医保人员编号',
    insutype           VARCHAR(10)  DEFAULT NULL COMMENT '险种类型',
    dept_id            BIGINT       DEFAULT NULL COMMENT '科室ID',
    dept_code          VARCHAR(30)  DEFAULT NULL COMMENT '科室编码',
    dept_name          VARCHAR(100) DEFAULT NULL COMMENT '科室名称',
    staff_id           BIGINT       DEFAULT NULL COMMENT '医师ID',
    atddr_no           VARCHAR(30)  DEFAULT NULL COMMENT '医师医保编码',
    dr_name            VARCHAR(50)  DEFAULT NULL COMMENT '医师姓名',
    work_date          DATE         DEFAULT NULL COMMENT '就诊日期',
    visit_status       TINYINT      DEFAULT 1 COMMENT '就诊状态:1-候诊 2-接诊中 3-已完成 4-已取消',
    chief_complaint    VARCHAR(500)  DEFAULT NULL COMMENT '主诉',
    present_illness    VARCHAR(1000) DEFAULT NULL COMMENT '现病史',
    past_history       VARCHAR(1000) DEFAULT NULL COMMENT '既往史',
    physical_exam      VARCHAR(1000) DEFAULT NULL COMMENT '体格检查',
    treatment_opinion  VARCHAR(1000) DEFAULT NULL COMMENT '处理意见',
    visit_time         DATETIME     DEFAULT NULL COMMENT '接诊时间',
    finish_time        DATETIME     DEFAULT NULL COMMENT '完成时间',
    create_by          VARCHAR(50)  DEFAULT NULL,
    create_time        DATETIME     DEFAULT NULL,
    update_by          VARCHAR(50)  DEFAULT NULL,
    update_time        DATETIME     DEFAULT NULL,
    deleted            TINYINT      DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_tenant (tenant_id),
    KEY idx_registration (registration_id),
    KEY idx_patient (patient_id),
    KEY idx_status_date (visit_status, work_date),
    KEY idx_staff (staff_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='就诊记录表';

-- ------------------------------------------------------------
-- 诊断表(取自医保疾病目录, 供2203上传)
-- ------------------------------------------------------------
DROP TABLE IF EXISTS his_diagnosis;
CREATE TABLE his_diagnosis (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '诊断ID',
    tenant_id      BIGINT       NOT NULL COMMENT '租户ID',
    visit_id       BIGINT       NOT NULL COMMENT '就诊ID',
    diag_type      VARCHAR(10)  DEFAULT '1' COMMENT '诊断类别:1-门诊诊断',
    diag_srt_no    INT          DEFAULT 1 COMMENT '诊断排序号',
    diag_code      VARCHAR(50)  DEFAULT NULL COMMENT '诊断代码(医保疾病目录)',
    diag_name      VARCHAR(200) DEFAULT NULL COMMENT '诊断名称',
    maindiag_flag  VARCHAR(2)   DEFAULT '0' COMMENT '主诊断标识:0-否 1-是',
    diag_dept      VARCHAR(100) DEFAULT NULL COMMENT '诊断科室',
    dise_dor_no    VARCHAR(30)  DEFAULT NULL COMMENT '诊断医生编码',
    dise_dor_name  VARCHAR(50)  DEFAULT NULL COMMENT '诊断医生姓名',
    diag_time      DATETIME     DEFAULT NULL COMMENT '诊断时间',
    adm_cond       VARCHAR(10)  DEFAULT NULL COMMENT '入院病情(门诊可空)',
    vali_flag      VARCHAR(2)   DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
    create_by      VARCHAR(50)  DEFAULT NULL,
    create_time    DATETIME     DEFAULT NULL,
    update_by      VARCHAR(50)  DEFAULT NULL,
    update_time    DATETIME     DEFAULT NULL,
    deleted        TINYINT      DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_tenant (tenant_id),
    KEY idx_visit (visit_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='诊断表';

-- ------------------------------------------------------------
-- 门诊病历表(SOAP结构)
-- ------------------------------------------------------------
DROP TABLE IF EXISTS his_medical_record;
CREATE TABLE his_medical_record (
    id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '病历ID',
    tenant_id    BIGINT        NOT NULL COMMENT '租户ID',
    visit_id     BIGINT        NOT NULL COMMENT '就诊ID',
    subjective   VARCHAR(2000) DEFAULT NULL COMMENT 'S-主观资料(主诉/现病史)',
    objective    VARCHAR(2000) DEFAULT NULL COMMENT 'O-客观资料(查体)',
    assessment   VARCHAR(2000) DEFAULT NULL COMMENT 'A-评估(诊断)',
    plan         VARCHAR(2000) DEFAULT NULL COMMENT 'P-计划(处理)',
    dr_name      VARCHAR(50)   DEFAULT NULL COMMENT '书写医师',
    dr_sign      VARCHAR(50)   DEFAULT NULL COMMENT '医师签名',
    record_time  DATETIME      DEFAULT NULL COMMENT '记录时间',
    create_by    VARCHAR(50)   DEFAULT NULL,
    create_time  DATETIME      DEFAULT NULL,
    update_by    VARCHAR(50)   DEFAULT NULL,
    update_time  DATETIME      DEFAULT NULL,
    deleted      TINYINT       DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_tenant (tenant_id),
    KEY idx_visit (visit_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='门诊病历表(SOAP)';

-- ------------------------------------------------------------
-- 处方主表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS his_prescription;
CREATE TABLE his_prescription (
    id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '处方ID',
    tenant_id     BIGINT        NOT NULL COMMENT '租户ID',
    visit_id      BIGINT        NOT NULL COMMENT '就诊ID',
    rx_no         VARCHAR(30)   NOT NULL COMMENT '处方号',
    patient_id    BIGINT        DEFAULT NULL COMMENT '患者ID',
    patient_name  VARCHAR(50)   DEFAULT NULL COMMENT '患者姓名',
    dept_id       BIGINT        DEFAULT NULL COMMENT '科室ID',
    dept_name     VARCHAR(100)  DEFAULT NULL COMMENT '科室名称',
    dr_id         BIGINT        DEFAULT NULL COMMENT '医师ID',
    dr_name       VARCHAR(50)   DEFAULT NULL COMMENT '医师姓名',
    rx_type       VARCHAR(20)   DEFAULT '西药' COMMENT '处方类型:西药/中药',
    diag_name     VARCHAR(500)  DEFAULT NULL COMMENT '临床诊断',
    total_amount  DECIMAL(12,2) DEFAULT 0.00 COMMENT '处方金额',
    status        TINYINT       DEFAULT 1 COMMENT '状态:1-已开 2-已发药 3-已退药',
    create_by     VARCHAR(50)   DEFAULT NULL,
    create_time   DATETIME      DEFAULT NULL,
    update_by     VARCHAR(50)   DEFAULT NULL,
    update_time   DATETIME      DEFAULT NULL,
    deleted       TINYINT       DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_tenant (tenant_id),
    KEY idx_visit (visit_id),
    KEY idx_rx_no (rx_no),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='处方主表';

-- ------------------------------------------------------------
-- 处方明细表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS his_prescription_item;
CREATE TABLE his_prescription_item (
    id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '明细ID',
    tenant_id       BIGINT        NOT NULL COMMENT '租户ID',
    prescription_id BIGINT        NOT NULL COMMENT '处方ID',
    item_id         BIGINT        DEFAULT NULL COMMENT '收费项目ID(his_charge_item)',
    item_code       VARCHAR(40)   DEFAULT NULL COMMENT '院内项目编码',
    item_name       VARCHAR(200)  DEFAULT NULL COMMENT '项目名称',
    spec            VARCHAR(200)  DEFAULT NULL COMMENT '规格',
    unit            VARCHAR(30)   DEFAULT NULL COMMENT '单位',
    price           DECIMAL(12,4) DEFAULT 0.0000 COMMENT '单价',
    quantity        DECIMAL(12,2) DEFAULT 0.00 COMMENT '数量',
    amount          DECIMAL(12,2) DEFAULT 0.00 COMMENT '金额',
    dosage          VARCHAR(50)   DEFAULT NULL COMMENT '单次剂量',
    dosage_unit     VARCHAR(20)   DEFAULT NULL COMMENT '剂量单位',
    usage_method    VARCHAR(50)   DEFAULT NULL COMMENT '用法',
    frequency       VARCHAR(50)   DEFAULT NULL COMMENT '频次',
    administration  VARCHAR(50)   DEFAULT NULL COMMENT '给药途径',
    group_no        VARCHAR(20)   DEFAULT NULL COMMENT '用药组号',
    days            INT           DEFAULT NULL COMMENT '用药天数',
    med_list_codg   VARCHAR(50)   DEFAULT NULL COMMENT '医保目录编码',
    create_by       VARCHAR(50)   DEFAULT NULL,
    create_time     DATETIME      DEFAULT NULL,
    update_by       VARCHAR(50)   DEFAULT NULL,
    update_time     DATETIME      DEFAULT NULL,
    deleted         TINYINT       DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_tenant (tenant_id),
    KEY idx_prescription (prescription_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='处方明细表';

-- ------------------------------------------------------------
-- 检查/检验/治疗单主表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS his_order;
CREATE TABLE his_order (
    id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '单据ID',
    tenant_id     BIGINT        NOT NULL COMMENT '租户ID',
    visit_id      BIGINT        NOT NULL COMMENT '就诊ID',
    order_no      VARCHAR(30)   NOT NULL COMMENT '单据号',
    patient_id    BIGINT        DEFAULT NULL COMMENT '患者ID',
    patient_name  VARCHAR(50)   DEFAULT NULL COMMENT '患者姓名',
    dept_id       BIGINT        DEFAULT NULL COMMENT '开单科室ID',
    dept_name     VARCHAR(100)  DEFAULT NULL COMMENT '开单科室名称',
    dr_id         BIGINT        DEFAULT NULL COMMENT '医师ID',
    dr_name       VARCHAR(50)   DEFAULT NULL COMMENT '医师姓名',
    order_type    VARCHAR(20)   DEFAULT '检查' COMMENT '单据类型:检查/检验/治疗',
    diag_name     VARCHAR(500)  DEFAULT NULL COMMENT '临床诊断',
    total_amount  DECIMAL(12,2) DEFAULT 0.00 COMMENT '单据金额',
    status        TINYINT       DEFAULT 1 COMMENT '状态:1-已开 2-已执行 3-已退',
    create_by     VARCHAR(50)   DEFAULT NULL,
    create_time   DATETIME      DEFAULT NULL,
    update_by     VARCHAR(50)   DEFAULT NULL,
    update_time   DATETIME      DEFAULT NULL,
    deleted       TINYINT       DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_tenant (tenant_id),
    KEY idx_visit (visit_id),
    KEY idx_order_no (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='检查检验治疗单主表';

-- ------------------------------------------------------------
-- 检查/检验/治疗单明细表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS his_order_item;
CREATE TABLE his_order_item (
    id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '明细ID',
    tenant_id     BIGINT        NOT NULL COMMENT '租户ID',
    order_id      BIGINT        NOT NULL COMMENT '单据ID',
    item_id       BIGINT        DEFAULT NULL COMMENT '收费项目ID(his_charge_item)',
    item_code     VARCHAR(40)   DEFAULT NULL COMMENT '院内项目编码',
    item_name     VARCHAR(200)  DEFAULT NULL COMMENT '项目名称',
    spec          VARCHAR(200)  DEFAULT NULL COMMENT '规格',
    unit          VARCHAR(30)   DEFAULT NULL COMMENT '单位',
    price         DECIMAL(12,4) DEFAULT 0.0000 COMMENT '单价',
    quantity      DECIMAL(12,2) DEFAULT 0.00 COMMENT '数量',
    amount        DECIMAL(12,2) DEFAULT 0.00 COMMENT '金额',
    med_list_codg VARCHAR(50)   DEFAULT NULL COMMENT '医保目录编码',
    exec_dept     VARCHAR(100)  DEFAULT NULL COMMENT '执行科室',
    create_by     VARCHAR(50)   DEFAULT NULL,
    create_time   DATETIME      DEFAULT NULL,
    update_by     VARCHAR(50)   DEFAULT NULL,
    update_time   DATETIME      DEFAULT NULL,
    deleted       TINYINT       DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_tenant (tenant_id),
    KEY idx_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='检查检验治疗单明细表';
