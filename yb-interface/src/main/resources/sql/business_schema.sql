-- ============================================================
-- 湖北省医保接口对接 - 业务库表结构
-- ============================================================

USE yb_interface;

-- ------------------------------------------------------------
-- 结算记录表(留存门诊/住院结算结果)
-- ------------------------------------------------------------
DROP TABLE IF EXISTS setl_record;
CREATE TABLE setl_record (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    setl_id        VARCHAR(30)  DEFAULT NULL COMMENT '结算ID',
    mdtrt_id       VARCHAR(30)  DEFAULT NULL COMMENT '就诊ID',
    psn_no         VARCHAR(30)  DEFAULT NULL COMMENT '人员编号',
    psn_name       VARCHAR(50)  DEFAULT NULL COMMENT '人员姓名',
    insutype       VARCHAR(6)   DEFAULT NULL COMMENT '险种类型',
    med_type       VARCHAR(6)   DEFAULT NULL COMMENT '医疗类别',
    biz_type       VARCHAR(20)  DEFAULT NULL COMMENT '业务类型 outpatient/inpatient',
    infno          VARCHAR(10)  DEFAULT NULL COMMENT '交易编号',
    setl_time      VARCHAR(30)  DEFAULT NULL COMMENT '结算时间',
    medfee_sumamt  DECIMAL(16,2) DEFAULT NULL COMMENT '医疗费总额',
    fund_pay_sumamt DECIMAL(16,2) DEFAULT NULL COMMENT '基金支付总额',
    psn_part_amt   DECIMAL(16,2) DEFAULT NULL COMMENT '个人负担总金额',
    acct_pay       DECIMAL(16,2) DEFAULT NULL COMMENT '个人账户支出',
    psn_cash_pay   DECIMAL(16,2) DEFAULT NULL COMMENT '个人现金支出',
    status         VARCHAR(3)   DEFAULT '1' COMMENT '状态 1-已结算 0-已撤销',
    setlinfo_json  LONGTEXT     DEFAULT NULL COMMENT '结算信息原始JSON',
    crte_time      DATETIME     DEFAULT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_setl_id (setl_id),
    KEY idx_mdtrt_id (mdtrt_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='结算记录表';
