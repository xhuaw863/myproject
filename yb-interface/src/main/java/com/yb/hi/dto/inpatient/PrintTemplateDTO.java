package com.yb.hi.dto.inpatient;

import lombok.Data;

/**
 * 打印模板请求
 */
@Data
public class PrintTemplateDTO {

    /** 模板编码 */
    private String templateCode;
    /** 模板名称 */
    private String templateName;
    /** 模板类型: 1日清单 2结算单 3医嘱单 4护理记录单 5体温单 6病历 7腕带 8知情同意书 */
    private Integer templateType;
    /** 纸张尺寸 */
    private String paperSize;
    /** 方向: portrait纵向 landscape横向 */
    private String orientation;
    /** HTML模板内容 */
    private String templateContent;
    /** 页眉HTML */
    private String headerHtml;
    /** 页脚HTML */
    private String footerHtml;
    /** CSS样式 */
    private String cssStyle;
    /** 版本号 */
    private Integer version;
    /** 状态: 1启用 0停用 */
    private Integer status;
}
