package com.yb.hi.service.emr;

/**
 * 病历模块实时事件类型(SSE 推送): 事件名即枚举名(英文, 供前端 switch 路由),
 * displayName 为中文展示名(前端可直接用作提示文案)。
 */
public enum EmrEventType {

    /** 质控提醒: 病历质控环节发现问题, 提醒医生修改 */
    QC_REMINDER("病历质控提醒"),
    /** 质控扣分: 病历质控评分产生扣分记录 */
    QC_PENALTY("病历质控扣分"),
    /** 待签名: 病历流转到当前用户需签名(住院三级/门诊) */
    SIGNATURE_REQUIRED("病历待签名提醒"),
    /** 病历锁定: 病历已被质控/病案锁定, 不可再编辑 */
    RECORD_LOCKED("病历锁定通知"),
    /** 病历解锁: 锁定被撤销, 可继续编辑 */
    RECORD_UNLOCKED("病历解锁通知"),
    /** 会诊更新: 会诊申请/应答/完成状态变化 */
    CONSULTATION_UPDATE("会诊状态更新"),
    /** 模板更新: 病历模板被创建/修改/传播 */
    TEMPLATE_UPDATED("病历模板更新"),
    /** 签署完成: 病历签名链全部必需环节已签完 */
    RECORD_SIGNED("病历签署完成"),
    /** 时效预警: 病历书写临近超时(到期前2h/到期前1h加急), P5a 时效质控引擎推送 */
    EMR_QC_DEADLINE_WARN("病历时效临近超时预警"),
    /** 时效超时: 病历书写已超时, 通知整改并自动落质控缺陷(时效) */
    EMR_QC_OVERDUE("病历时效超时通知"),
    /** 整改通知: 质控员对缺陷病历下发整改通知单(质控整改闭环, P5b-4) */
    EMR_QC_NOTICE("病历质控整改通知"),
    /** 申诉结果: 缺陷申诉审核通过/驳回结果通知(质控整改闭环, P5b-4) */
    EMR_QC_APPEAL_RESULT("病历缺陷申诉结果通知"),
    /** 病历归档完成: 病历完成归档(PDF生成)通知(P7a) */
    RECORD_ARCHIVED("病历归档完成"),
    /** 病历召回申请: 已归档病历发起召回申请(待审批)通知(P7a) */
    RECORD_RECALLED("病历召回申请"),
    /** 病历封存: 病历被封存(医疗纠纷争议固定证据)通知(P7a) */
    RECORD_SEALED("病历封存"),
    /** 病历解封: 封存被解除通知(P7a) */
    RECORD_UNSEALED("病历解封");

    private final String displayName;

    EmrEventType(String displayName) {
        this.displayName = displayName;
    }

    /** 中文展示名 */
    public String getDisplayName() {
        return displayName;
    }
}
