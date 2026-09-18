-- ============================================================
-- 医保原生多租户HIS - 门诊业务表(患者档案/挂号记录)
-- 数据库: yb_interface  字符集: utf8mb4
-- 所有业务表含 tenant_id(租户隔离) + 审计字段 + 逻辑删除
-- ============================================================
USE yb_interface;

-- ------------------------------------------------------------
-- 患者档案表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS his_patient;
CREATE TABLE his_patient (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '患者ID',
    tenant_id       BIGINT       NOT NULL COMMENT '租户ID',
    patient_no      VARCHAR(30)  NOT NULL COMMENT '院内患者号(就诊卡号)',
    psn_no          VARCHAR(30)  DEFAULT NULL COMMENT '医保人员编号',
    name            VARCHAR(50)  NOT NULL COMMENT '姓名',
    gender          VARCHAR(4)   DEFAULT NULL COMMENT '性别:男/女',
    birth_date      DATE         DEFAULT NULL COMMENT '出生日期',
    age             INT          DEFAULT NULL COMMENT '年龄',
    id_card         VARCHAR(30)  DEFAULT NULL COMMENT '身份证号',
    phone           VARCHAR(30)  DEFAULT NULL COMMENT '联系电话',
    address         VARCHAR(200) DEFAULT NULL COMMENT '住址',
    insutype        VARCHAR(10)  DEFAULT '310' COMMENT '险种类型:310-职工 390-居民',
    mdtrt_cert_type VARCHAR(10)  DEFAULT '02' COMMENT '就诊凭证类型:01-电子凭证 02-身份证 03-社保卡',
    mdtrt_cert_no   VARCHAR(50)  DEFAULT NULL COMMENT '就诊凭证编号',
    insuplc_admdvs  VARCHAR(20)  DEFAULT NULL COMMENT '参保地区划',
    contact_name    VARCHAR(50)  DEFAULT NULL COMMENT '联系人',
    contact_phone   VARCHAR(30)  DEFAULT NULL COMMENT '联系人电话',
    status          TINYINT      DEFAULT 1 COMMENT '状态:1-正常 0-停用',
    memo            VARCHAR(500) DEFAULT NULL COMMENT '备注',
    create_by       VARCHAR(50)  DEFAULT NULL,
    create_time     DATETIME     DEFAULT NULL,
    update_by       VARCHAR(50)  DEFAULT NULL,
    update_time     DATETIME     DEFAULT NULL,
    deleted         TINYINT      DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_tenant (tenant_id),
    KEY idx_tenant_patient_no (tenant_id, patient_no),
    KEY idx_id_card (id_card),
    KEY idx_psn_no (psn_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='患者档案表';

-- ------------------------------------------------------------
-- 挂号记录表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS his_registration;
CREATE TABLE his_registration (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '挂号ID',
    tenant_id       BIGINT       NOT NULL COMMENT '租户ID',
    reg_no          VARCHAR(30)  NOT NULL COMMENT '挂号单号',
    patient_id      BIGINT       NOT NULL COMMENT '患者ID',
    patient_no      VARCHAR(30)  DEFAULT NULL COMMENT '院内患者号',
    patient_name    VARCHAR(50)  DEFAULT NULL COMMENT '患者姓名(冗余)',
    psn_no          VARCHAR(30)  DEFAULT NULL COMMENT '医保人员编号',
    insutype        VARCHAR(10)  DEFAULT NULL COMMENT '险种类型',
    mdtrt_cert_type VARCHAR(10)  DEFAULT NULL COMMENT '就诊凭证类型',
    mdtrt_cert_no   VARCHAR(50)  DEFAULT NULL COMMENT '就诊凭证编号',
    dept_id         BIGINT       DEFAULT NULL COMMENT '科室ID',
    dept_code       VARCHAR(30)  DEFAULT NULL COMMENT '科室编码',
    dept_name       VARCHAR(100) DEFAULT NULL COMMENT '科室名称',
    caty            VARCHAR(20)  DEFAULT NULL COMMENT '科别(医保)',
    staff_id        BIGINT       DEFAULT NULL COMMENT '医师ID',
    atddr_no        VARCHAR(30)  DEFAULT NULL COMMENT '医师医保编码',
    dr_name         VARCHAR(50)  DEFAULT NULL COMMENT '医师姓名',
    schedule_id     BIGINT       DEFAULT NULL COMMENT '排班ID',
    work_date       DATE         DEFAULT NULL COMMENT '出诊日期',
    time_type       VARCHAR(10)  DEFAULT NULL COMMENT '时段:am/pm/night',
    reg_level_code  VARCHAR(30)  DEFAULT NULL COMMENT '号别编码',
    reg_level_name  VARCHAR(50)  DEFAULT NULL COMMENT '号别名称',
    reg_fee         DECIMAL(10,2) DEFAULT 0.00 COMMENT '挂号费',
    med_type        VARCHAR(10)  DEFAULT '11' COMMENT '医疗类别:11-普通门诊',
    ipt_otp_no      VARCHAR(30)  DEFAULT NULL COMMENT '院内就诊流水号(门诊号)',
    mdtrt_id        VARCHAR(30)  DEFAULT NULL COMMENT '医保就诊ID(2201回填)',
    reg_time        DATETIME     DEFAULT NULL COMMENT '挂号时间',
    status          TINYINT      DEFAULT 1 COMMENT '状态:1-已挂号 2-已退号 3-已就诊',
    cancel_time     DATETIME     DEFAULT NULL COMMENT '退号时间',
    cancel_reason   VARCHAR(200) DEFAULT NULL COMMENT '退号原因',
    operator        VARCHAR(50)  DEFAULT NULL COMMENT '挂号员',
    create_by       VARCHAR(50)  DEFAULT NULL,
    create_time     DATETIME     DEFAULT NULL,
    update_by       VARCHAR(50)  DEFAULT NULL,
    update_time     DATETIME     DEFAULT NULL,
    deleted         TINYINT      DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_tenant (tenant_id),
    KEY idx_reg_no (reg_no),
    KEY idx_patient (patient_id),
    KEY idx_mdtrt (mdtrt_id),
    KEY idx_ipt_otp (ipt_otp_no),
    KEY idx_work_date (work_date),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='挂号记录表';
