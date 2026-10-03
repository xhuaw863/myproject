package com.yb.hi.controller.doctor;

import com.yb.hi.dto.doctor.PrescriptionBatchReq;
import com.yb.hi.dto.doctor.PrescriptionReq;
import com.yb.hi.entity.doctor.HisPrescription;
import com.yb.hi.entity.doctor.HisPrescriptionItem;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.doctor.HisPrescriptionService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 处方接口: 开方 / 查询就诊处方 / 查询明细
 */
@RestController
@RequestMapping("/api/his/prescription")
public class HisPrescriptionController {

    private final HisPrescriptionService service;

    public HisPrescriptionController(HisPrescriptionService service) {
        this.service = service;
    }

    /** 查询某次就诊的处方列表 */
    @GetMapping("/list")
    public R<List<HisPrescription>> list(@RequestParam Long visitId) {
        return R.ok(service.listByVisit(visitId));
    }

    /** 查询处方明细 */
    @GetMapping("/items")
    public R<List<HisPrescriptionItem>> items(@RequestParam Long prescriptionId) {
        return R.ok(service.listItems(prescriptionId));
    }

    /** 开处方 */
    @PostMapping("/create")
    public R<HisPrescription> create(@RequestBody PrescriptionReq req) {
        return R.ok(service.create(req));
    }

    /** 批量开处方(拆方原子提交, C7): 单事务开立全部批次, 任一批失败整体回滚 */
    @PostMapping("/create-batch")
    public R<List<HisPrescription>> createBatch(@RequestBody PrescriptionBatchReq req) {
        return R.ok(service.createBatch(req));
    }

    /** 作废未收费处方 */
    @PostMapping("/cancel")
    public R<HisPrescription> cancel(@RequestParam Long id) {
        return R.ok(service.cancel(id));
    }

    /** 获取处方笺打印数据 */
    @GetMapping("/print-data")
    public R<Map<String, Object>> printData(@RequestParam Long id) {
        return R.ok(service.printData(id));
    }

    /* ================= P8a-2: 草药方引用(病历编辑器引用草药处方, 前端 inp-emr-writer.js 已预埋调用) ================= */

    /**
     * 患者草药处方列表(供病历编辑器"引用草药方"选择):
     * 返回 [{prescriptionId, id, rxNo, rxName, formulaName, rxType, drName, deptName, createTime, herbCount}]。
     */
    @GetMapping("/herb-formulas")
    public R<List<Map<String, Object>>> herbFormulas(@RequestParam Long patientId,
                                                    @RequestParam(required = false) Long visitId) {
        return R.ok(service.listHerbFormulas(patientId, visitId));
    }

    /** 草药处方格式化引用文本: 返回 {prescriptionId, text}(text 为多行"【草药方】…"格式) */
    @GetMapping("/formula-text")
    public R<Map<String, Object>> formulaText(@RequestParam Long prescriptionId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("prescriptionId", prescriptionId);
        out.put("text", service.formulaToText(prescriptionId));
        return R.ok(out);
    }

    /** 草药方引用插入病历: body {recordId, prescriptionId}(追加段落至 his_inp_medical_record.content 密文轨) */
    @PostMapping("/insert-formula-to-record")
    public R<Void> insertFormulaToRecord(@RequestBody Map<String, Object> body) {
        if (body == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        service.insertFormulaToRecord(toLong(body.get("recordId")), toLong(body.get("prescriptionId")));
        return R.ok();
    }

    /** 请求体数值字段归一(数字/字符串均兼容) */
    private static Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : Long.valueOf(s);
    }
}
