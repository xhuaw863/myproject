-- ============================================================
-- 医保原生多租户HIS - 平台层库表(租户/用户)
-- 数据库: yb_interface  字符集: utf8mb4
-- ============================================================
USE yb_interface;

-- ------------------------------------------------------------
-- 租户表(医院注册 + 医保接口配置)  全局表, 不做租户隔离
-- ------------------------------------------------------------
DROP TABLE IF EXISTS sys_tenant;
CREATE TABLE sys_tenant (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '租户ID(主键)',
    tenant_code       VARCHAR(50)  NOT NULL COMMENT '医院登录码(唯一)',
    tenant_name       VARCHAR(200) NOT NULL COMMENT '医院名称',
    -- 医保接口配置(每租户独立)
    fixmedins_code    VARCHAR(30)  DEFAULT NULL COMMENT '定点医药机构编号',
    fixmedins_name    VARCHAR(200) DEFAULT NULL COMMENT '定点医药机构名称',
    mdtrtarea_admvs   VARCHAR(20)  DEFAULT NULL COMMENT '就诊地区行政区划',
    insuplc_admdvs    VARCHAR(20)  DEFAULT NULL COMMENT '参保地行政区划',
    api_url           VARCHAR(500) DEFAULT NULL COMMENT '医保交易接口地址',
    file_download_url VARCHAR(500) DEFAULT NULL COMMENT '医保文件下载地址',
    recer_sys_code    VARCHAR(30)  DEFAULT 'HIS' COMMENT '受理系统编号',
    infver            VARCHAR(20)  DEFAULT 'V1.0' COMMENT '接口版本号',
    opter_type        VARCHAR(10)  DEFAULT '2' COMMENT '经办人类别',
    opter             VARCHAR(50)  DEFAULT NULL COMMENT '经办人编码',
    opter_name        VARCHAR(50)  DEFAULT NULL COMMENT '经办人姓名',
    sign_no           VARCHAR(100) DEFAULT NULL COMMENT '签名证书编号',
    sm2_private_key   VARCHAR(500) DEFAULT NULL COMMENT 'SM2私钥',
    sm2_public_key    VARCHAR(500) DEFAULT NULL COMMENT 'SM2公钥',
    enc_type          VARCHAR(20)  DEFAULT NULL COMMENT '加密类型',
    mock_enabled      TINYINT      DEFAULT 1 COMMENT '模拟平台模式:1-模拟 0-真实',
    -- 租户状态
    status            TINYINT      DEFAULT 1 COMMENT '状态:1-启用 0-停用',
    expire_time       DATETIME     DEFAULT NULL COMMENT '服务到期时间',
    contact           VARCHAR(50)  DEFAULT NULL COMMENT '联系人',
    phone             VARCHAR(30)  DEFAULT NULL COMMENT '联系电话',
    address           VARCHAR(300) DEFAULT NULL COMMENT '医院地址',
    -- 审计
    create_by         VARCHAR(50)  DEFAULT NULL COMMENT '创建人',
    create_time       DATETIME     DEFAULT NULL COMMENT '创建时间',
    update_by         VARCHAR(50)  DEFAULT NULL COMMENT '更新人',
    update_time       DATETIME     DEFAULT NULL COMMENT '更新时间',
    deleted           TINYINT      DEFAULT 0 COMMENT '逻辑删除:0-正常 1-删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_code (tenant_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户(医院)表';

-- ------------------------------------------------------------
-- 用户表(职工登录账号)  按租户隔离
-- ------------------------------------------------------------
DROP TABLE IF EXISTS sys_user;
CREATE TABLE sys_user (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    tenant_id    BIGINT       NOT NULL COMMENT '租户ID',
    username     VARCHAR(50)  NOT NULL COMMENT '登录账号',
    password     VARCHAR(100) NOT NULL COMMENT '密码(BCrypt)',
    real_name    VARCHAR(50)  DEFAULT NULL COMMENT '姓名',
    role         VARCHAR(30)  NOT NULL DEFAULT 'DOCTOR' COMMENT '角色:ADMIN/REGISTRAR/DOCTOR/PHARMACIST/CASHIER/NURSE',
    staff_id     BIGINT       DEFAULT NULL COMMENT '关联职工ID',
    dept_id      BIGINT       DEFAULT NULL COMMENT '关联科室ID',
    phone        VARCHAR(30)  DEFAULT NULL COMMENT '手机号',
    status       TINYINT      DEFAULT 1 COMMENT '状态:1-启用 0-停用',
    create_by    VARCHAR(50)  DEFAULT NULL COMMENT '创建人',
    create_time  DATETIME     DEFAULT NULL COMMENT '创建时间',
    update_by    VARCHAR(50)  DEFAULT NULL COMMENT '更新人',
    update_time  DATETIME     DEFAULT NULL COMMENT '更新时间',
    deleted      TINYINT      DEFAULT 0 COMMENT '逻辑删除:0-正常 1-删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_username (tenant_id, username),
    KEY idx_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

