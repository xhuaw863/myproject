package com.yb.hi.controller.inpatient;

import com.yb.hi.dto.inpatient.NursingTransferDTO;
import com.yb.hi.entity.inpatient.HisNursingTransfer;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.inpatient.NursingTransferService;
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
 * 护理转运交接单接口(护士站): 创建(草稿) / 交接 / 确认 / 列表 / 详情 / 核查模板。
 * 生命周期状态机(0草稿→1已交接→2已确认)与归属机构校验均在 Service 内完成。
 */
@RestController
@RequestMapping("/api/his/inp/nursing/transfer")
public class NursingTransferController {

    private final NursingTransferService transferService;

    public NursingTransferController(NursingTransferService transferService) {
        this.transferService = transferService;
    }

    /** 创建交接单: transferType 限固定枚举集, checklist 缺省套用类型默认核查模板, status=0 草稿。 */
    @PostMapping({"", "/"})
    public R<HisNursingTransfer> create(@RequestBody NursingTransferDTO dto) {
        return R.ok(transferService.create(dto));
    }

    /** 交接: 草稿单回填接收人/交接时间, 置 status=1 已交接。 */
    @PutMapping("/{id}/handover")
    public R<HisNursingTransfer> handover(@PathVariable Long id,
                                          @RequestParam(required = false) Long receiverId,
                                          @RequestParam(required = false) String receiverName) {
        return R.ok(transferService.handover(id, receiverId, receiverName));
    }

    /** 确认: 已交接单接收方点验后置 status=2 已确认。 */
    @PutMapping("/{id}/confirm")
    public R<HisNursingTransfer> confirm(@PathVariable Long id) {
        return R.ok(transferService.confirm(id));
    }

    /** 交接单列表(按就诊): 状态升序, 交接时间倒序。 */
    @GetMapping("/list")
    public R<List<HisNursingTransfer>> list(@RequestParam Long inpVisitId) {
        return R.ok(transferService.listByVisit(inpVisitId));
    }

    /** 交接单详情。 */
    @GetMapping("/{id}")
    public R<HisNursingTransfer> getById(@PathVariable Long id) {
        return R.ok(transferService.getById(id));
    }

    /** 交接核查模板(按类型): [{item, checked:false}], 供勾选面板直接渲染。 */
    @GetMapping("/checklist-template")
    public R<List<Map<String, Object>>> checklistTemplate(@RequestParam String transferType) {
        return R.ok(transferService.getChecklistTemplate(transferType));
    }
}
