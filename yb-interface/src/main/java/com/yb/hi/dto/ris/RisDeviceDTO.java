package com.yb.hi.dto.ris;

import lombok.Data;

import java.time.LocalDate;

/**
 * 影像设备保存请求(落 his_imaging_device)
 */
@Data
public class RisDeviceDTO {

    /** 设备ID(编辑时传) */
    private Long id;
    /** 设备编号(空则服务端生成) */
    private String deviceCode;
    /** 设备名称 */
    private String deviceName;
    /** 设备类型: DR/CT/MRI/US/DSA/ENDO */
    private String deviceType;
    /** DICOM Modality: CR/CT/MR/US/XA/ES */
    private String modality;
    /** 所属科室(his_dept.id) */
    private Long deptId;
    /** 机房号 */
    private String roomNo;
    /** DICOM AE Title */
    private String aeTitle;
    /** 设备IP */
    private String ipAddress;
    /** DICOM端口 */
    private Integer port;
    /** 厂商 */
    private String manufacturer;
    /** 型号 */
    private String model;
    /** 设备序列号 */
    private String serialNo;
    /** 安装日期 */
    private LocalDate installDate;
    /** 每日最大检查量(排程用) */
    private Integer maxDailySlots;
    /** 状态: 1正常/2维修中/3停用 */
    private Integer status;
    /** 是否启用剂量追踪 */
    private Integer doseTracking;
}
