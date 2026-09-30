package com.yb.hi.controller.inpatient;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.dto.inpatient.ConsultationApplyDTO;
import com.yb.hi.entity.inpatient.HisInpConsultation;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.inpatient.InpConsultationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 住院会诊接口: 申请/受理/完成/拒绝/取消(1申请 2受理 3完成 4拒绝 5取消)。
 */
@RestController
@RequestMapping("/api/his/inp/consultation")
public class InpConsultationController {

    private final InpConsultationService inpConsultationService;

    public InpConsultationController(InpConsultationService inpConsultationService) {
        this.inpConsultationService = inpConsultationService;
    }

    /** 会诊分页(visitId/status 可选) */
    @GetMapping("/list")
    public R<IPage<HisInpConsultation>> list(@RequestParam(required = false) Long visitId,
                                             @RequestParam(required = false) Integer status,
                                             @RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "10") long size) {
        return inpConsultationService.list(visitId, status,
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 200)));
    }

    /** 发起会诊申请 */
    @PostMapping
    public R<HisInpConsultation> apply(@RequestBody ConsultationApplyDTO dto) {
        return inpConsultationService.apply(dto);
    }

    /** 受理会诊(申请→受理) */
    @PutMapping("/{id}/accept")
    public R<Void> accept(@PathVariable Long id) {
        return inpConsultationService.accept(id);
    }

    /** 完成会诊(受理→完成, 填写会诊意见) */
    @PutMapping("/{id}/complete")
    public R<Void> complete(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Object opinion = body == null ? null : body.get("opinion");
        return inpConsultationService.complete(id, opinion == null ? null : String.valueOf(opinion));
    }

    /** 拒绝会诊(申请→拒绝, 填写拒绝原因) */
    @PutMapping("/{id}/reject")
    public R<Void> reject(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Object reason = body == null ? null : body.get("reason");
        return inpConsultationService.reject(id, reason == null ? null : String.valueOf(reason));
    }

    /** 取消会诊(申请→取消, 仅申请人本人) */
    @PutMapping("/{id}/cancel")
    public R<Void> cancel(@PathVariable Long id) {
        return inpConsultationService.cancel(id);
    }
}
