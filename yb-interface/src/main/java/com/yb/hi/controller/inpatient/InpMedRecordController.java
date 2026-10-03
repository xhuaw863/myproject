package com.yb.hi.controller.inpatient;

import com.yb.hi.dto.inpatient.InpMedRecordDTO;
import com.yb.hi.entity.inpatient.HisInpMedicalRecord;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.inpatient.InpMedRecordService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 住院病历接口: 病历列表 / 分组列表 / 详情 / 新建 / 模板建档 / 医嘱联动建档 / 编辑(仅草稿) /
 * 提交 / 审核 / 三级查房签名(T43) / 删除(仅草稿) / 版本历史与对比(T43)。
 * 状态机与机构隔离在 Service 层(见 InpMedRecordService)。
 * 路径变量约束: 所有 Long ID 一律 [0-9]+ 数字匹配, 防止 /grouped、/version/diff 等静态路径
 * 被 /{id}、/version/{versionId} 动态路由歧义捕获(会产生 NumberFormatException)。
 */
@RestController
@RequestMapping("/api/his/inp/record")
public class InpMedRecordController {

    private final InpMedRecordService inpMedRecordService;

    public InpMedRecordController(InpMedRecordService inpMedRecordService) {
        this.inpMedRecordService = inpMedRecordService;
    }

    /** 病历列表(recordType可选筛选, 按记录时间倒序) */
    @GetMapping("/list/{visitId:[0-9]+}")
    public R<List<HisInpMedicalRecord>> list(
            @PathVariable Long visitId,
            @RequestParam(required = false) Integer recordType) {
        return R.ok(inpMedRecordService.listByVisit(visitId, recordType));
    }

    /** 按文书类别分组病历列表(入院出院类/病程类/手术类/其他, 组内记录时间倒序) */
    @GetMapping("/grouped")
    public R<Map<String, Object>> grouped(@RequestParam Long inpVisitId) {
        return R.ok(inpMedRecordService.listGroupedByType(inpVisitId));
    }

    /** 病历详情 */
    @GetMapping("/{id:[0-9]+}")
    public R<HisInpMedicalRecord> detail(@PathVariable Long id) {
        return R.ok(inpMedRecordService.detail(id));
    }

    /** 新建病历(草稿态) */
    @PostMapping
    public R<HisInpMedicalRecord> create(@RequestBody InpMedRecordDTO dto) {
        return R.ok(inpMedRecordService.create(dto));
    }

    /** 按结构化模板建档: 字段宏预填充生成草稿(前端 POST /from-template {visitId, templateId}) */
    @PostMapping("/from-template")
    public R<HisInpMedicalRecord> createFromTemplate(@RequestBody Map<String, Object> body) {
        Long visitId = body.get("visitId") == null ? null : Long.valueOf(String.valueOf(body.get("visitId")));
        Long templateId = body.get("templateId") == null ? null : Long.valueOf(String.valueOf(body.get("templateId")));
        return R.ok(inpMedRecordService.createFromTemplate(visitId, templateId, null));
    }

    /** 医嘱联动建档: 按记录类型默认模板生成草稿并标注来源医嘱, 返回记录ID(前端 POST /from-order) */
    @PostMapping("/from-order")
    public R<Long> createFromOrder(@RequestBody Map<String, Object> body) {
        Long visitId = body.get("visitId") == null ? null : Long.valueOf(String.valueOf(body.get("visitId")));
        Long orderId = body.get("orderId") == null ? null : Long.valueOf(String.valueOf(body.get("orderId")));
        Integer recordType = body.get("recordType") == null ? null : Integer.valueOf(String.valueOf(body.get("recordType")));
        if (recordType == null) {
            throw new BizException(400, "recordType 不能为空");
        }
        return R.ok(inpMedRecordService.createFromOrder(visitId, orderId, recordType));
    }

    /** 编辑病历(仅草稿状态可编辑) */
    @PutMapping("/{id:[0-9]+}")
    public R<HisInpMedicalRecord> update(@PathVariable Long id, @RequestBody InpMedRecordDTO dto) {
        return R.ok(inpMedRecordService.update(id, dto));
    }

    /** 删除病历(仅草稿状态可删, 逻辑删除并留痕) */
    @DeleteMapping("/{id:[0-9]+}")
    public R<Void> delete(@PathVariable Long id) {
        inpMedRecordService.delete(id);
        return R.ok();
    }

    /** 提交病历(1草稿 → 2已提交) */
    @PutMapping("/{id:[0-9]+}/submit")
    public R<HisInpMedicalRecord> submit(@PathVariable Long id) {
        return R.ok(inpMedRecordService.submit(id));
    }

    /** 审核病历(2已提交 → 3已审核, 记录审核医生与审核时间) */
    @PutMapping("/{id:[0-9]+}/audit")
    public R<HisInpMedicalRecord> audit(@PathVariable Long id) {
        return R.ok(inpMedRecordService.audit(id));
    }

    /** 三级查房签名(主治/主任): signLevel=attending|director, 需先经签名板完成电子签名留痕 */
    @PostMapping("/{id:[0-9]+}/sign")
    public R<HisInpMedicalRecord> sign(@PathVariable Long id, @RequestParam String signLevel) {
        return R.ok(inpMedRecordService.signRecord(id, signLevel));
    }

    /** 病历版本列表(按版本号倒序, 经归属校验) */
    @GetMapping("/{id:[0-9]+}/versions")
    public R<List<Map<String, Object>>> versions(@PathVariable Long id) {
        return R.ok(inpMedRecordService.versionList(id));
    }

    /** 指定版本详情(含双快照正文) */
    @GetMapping("/version/{versionId:[0-9]+}")
    public R<Map<String, Object>> versionDetail(@PathVariable Long versionId) {
        return R.ok(inpMedRecordService.versionDetail(versionId));
    }

    /** 两版本行级差异对比(v1/v2 为版本ID, 须同一病历): {v1,v2,summary,diffs[]} */
    @GetMapping("/version/diff")
    public R<Map<String, Object>> versionDiff(@RequestParam Long v1, @RequestParam Long v2) {
        return R.ok(inpMedRecordService.versionDiff(v1, v2));
    }
}
