package com.yb.hi.controller.inpatient;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.dto.inpatient.InformedConsentDTO;
import com.yb.hi.entity.inpatient.HisInpInformedConsent;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.inpatient.InpInformedConsentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 住院知情同意书接口: 列表/详情/创建/编辑/签署/撤销(1待签 2已签 3已撤销)。
 */
@RestController
@RequestMapping("/api/his/inp/consent")
public class InpInformedConsentController {

    private final InpInformedConsentService inpInformedConsentService;

    public InpInformedConsentController(InpInformedConsentService inpInformedConsentService) {
        this.inpInformedConsentService = inpInformedConsentService;
    }

    /** 就诊维度同意书分页 */
    @GetMapping("/list")
    public R<IPage<HisInpInformedConsent>> list(@RequestParam Long visitId,
                                                @RequestParam(defaultValue = "1") long page,
                                                @RequestParam(defaultValue = "10") long size) {
        return inpInformedConsentService.listByVisit(visitId,
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 200)));
    }

    /** 同意书详情 */
    @GetMapping("/{id}")
    public R<HisInpInformedConsent> detail(@PathVariable Long id) {
        return inpInformedConsentService.getDetail(id);
    }

    /** 创建同意书(待签) */
    @PostMapping
    public R<HisInpInformedConsent> create(@RequestBody InformedConsentDTO dto) {
        return inpInformedConsentService.create(dto);
    }

    /** 编辑同意书(仅待签) */
    @PutMapping("/{id}")
    public R<HisInpInformedConsent> update(@PathVariable Long id, @RequestBody InformedConsentDTO dto) {
        return inpInformedConsentService.update(id, dto);
    }

    /** 签署(待签→已签, 记录患者/医师签署时间) */
    @PutMapping("/{id}/sign")
    public R<Void> sign(@PathVariable Long id) {
        return inpInformedConsentService.sign(id);
    }

    /** 撤销(仅已签可撤销) */
    @PutMapping("/{id}/revoke")
    public R<Void> revoke(@PathVariable Long id) {
        return inpInformedConsentService.revoke(id);
    }
}
