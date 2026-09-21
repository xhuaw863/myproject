package com.yb.hi.dto.community;

import com.yb.hi.entity.community.HisMedDict;
import lombok.Data;

import java.util.List;

/**
 * 医共体用药字典批量导入请求(L2, 牵头机构): 从医保标准值域勾选用法/频次批量写入。
 * dictType 统一覆盖每条明细的类型; items 携带 code/name/ybCode/dailyTimes/来源三件套。
 */
@Data
public class MedDictImportReq {
    /** 字典类型: usage-用法(给药途径) freq-用药频次 */
    private String dictType;
    /** 待导入明细 */
    private List<HisMedDict> items;
}
