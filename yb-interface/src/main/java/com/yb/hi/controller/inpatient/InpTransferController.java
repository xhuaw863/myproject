package com.yb.hi.controller.inpatient;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.dto.inpatient.TransferApplyDTO;
import com.yb.hi.entity.inpatient.HisInpTransfer;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.inpatient.InpTransferService;
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
 * 住院转科/转床/加床接口: 申请/批准/拒绝/执行/取消(1申请 2批准 3拒绝 4已执行 5取消)。
 */
@RestController
@RequestMapping("/api/his/inp/transfer")
public class InpTransferController {

    private final InpTransferService inpTransferService;

    public InpTransferController(InpTransferService inpTransferService) {
        this.inpTransferService = inpTransferService;
    }

    /** 转科/转床申请分页(visitId/status 可选) */
    @GetMapping("/list")
    public R<IPage<HisInpTransfer>> list(@RequestParam(required = false) Long visitId,
                                         @RequestParam(required = false) Integer status,
                                         @RequestParam(defaultValue = "1") long page,
                                         @RequestParam(defaultValue = "10") long size) {
        return inpTransferService.list(visitId, status,
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 200)));
    }

    /** 发起转科/转床/加床申请 */
    @PostMapping
    public R<HisInpTransfer> apply(@RequestBody TransferApplyDTO dto) {
        return inpTransferService.apply(dto);
    }

    /** 批准申请(申请→批准) */
    @PutMapping("/{id}/approve")
    public R<Void> approve(@PathVariable Long id) {
        return inpTransferService.approve(id);
    }

    /** 拒绝申请(申请→拒绝, 填写驳回原因) */
    @PutMapping("/{id}/reject")
    public R<Void> reject(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Object reason = body == null ? null : body.get("reason");
        return inpTransferService.reject(id, reason == null ? null : String.valueOf(reason));
    }

    /** 执行申请(批准→已执行: 先占新床, 再释旧床, 更新就诊在院信息) */
    @PutMapping("/{id}/execute")
    public R<Void> execute(@PathVariable Long id) {
        return inpTransferService.execute(id);
    }

    /** 取消申请(申请→取消, 仅申请人本人) */
    @PutMapping("/{id}/cancel")
    public R<Void> cancel(@PathVariable Long id) {
        return inpTransferService.cancel(id);
    }
}
