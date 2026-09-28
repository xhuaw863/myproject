package com.yb.hi.dto;

import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.PropertyNamingStrategy;
import com.alibaba.fastjson2.annotation.JSONField;
import com.alibaba.fastjson2.annotation.JSONType;
import lombok.Data;

/**
 * 【9101】文件上传(节点: fsUploadIn, 单行; 输出无节点)
 * 规范表415(严格按字段字义, 不增不减): in(字节数组)/filename(200)/fixmedins_code(30)
 * 文件以流式数据传输(规范: 除 9101/9102 外所有交易均为 JSON 报文, 文件交易走流);
 * in 为字节数组, JSON 报文中按 Base64 编码传输(报文字符串约定)。
 */
@Data
@JSONType(naming = PropertyNamingStrategy.SnakeCase)
public class FileUploadReq {

    /** 文件字节流(Base64 编码传输) */
    @JSONField(serializeFeatures = JSONWriter.Feature.WriteByteArrayAsBase64)
    private byte[] in;
    /** 文件名(200位) */
    private String filename;
    /** 定点医药机构编号(30位) */
    private String fixmedinsCode;
}
