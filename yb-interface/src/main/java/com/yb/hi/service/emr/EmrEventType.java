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
    RECORD_SIGNED("病历签署完成");

    private final String displayName;

    EmrEventType(String displayName) {
        this.displayName = displayName;
    }

    /** 中文展示名 */
    public String getDisplayName() {
        return displayName;
    }
}
