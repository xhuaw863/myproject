package com.yb.hi.controller.inpatient;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.entity.inpatient.HisSurgeryPacu;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.inpatient.PacuService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 术毕复苏(PACU)接口(手麻P3b): 入复苏 / 生命体征登记 / 出复苏 / 复苏工作台列表 / 按手术回显。
 * 机构隔离与 SurgeryController 同档位: 读走 scopeOrgId, 写以 currentOrgId 归属校验(守卫在 Service)。
 */
@RestController
@RequestMapping("/api/his/surgery-pacu")
public class SurgeryPacuController {

    private final PacuService pacuService;
    private final OrgAccessGuard guard;

    public SurgeryPacuController(PacuService pacuService, OrgAccessGuard guard) {
        this.pacuService = pacuService;
        this.guard = guard;
    }

    /** 入复苏(守卫手术status=4术后窗口 + 无在途单): body 可带 aldreteAdmit/admitNote */
    @PostMapping("/{surgeryId}/admit")
    public R<HisSurgeryPacu> admit(@PathVariable Long surgeryId,
                                   @RequestBody(required = false) Map<String, Object> body) {
        Integer aldrete = body == null || body.get("aldreteAdmit") == null
                ? null : Integer.valueOf(String.valueOf(body.get("aldreteAdmit")));
        String note = body == null || body.get("admitNote") == null ? null : String.valueOf(body.get("admitNote"));
        return R.ok(pacuService.admit(surgeryId, aldrete, note));
    }

    /** 生命体征登记(仅复苏中可追加): body = {t?,hp,hr,p,s,tm} 单时间点 */
    @PostMapping("/{id}/vital")
    public R<HisSurgeryPacu> recordVital(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return R.ok(pacuService.recordVital(id, body));
    }

    /** 出复苏: Aldrete>=9 直接放行, <9 须在 dischargeNote 说明理由; dest 1回病房 2转ICU 3门诊随访 */
    @PostMapping("/{id}/discharge")
    public R<HisSurgeryPacu> discharge(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Integer aldrete = body.get("aldreteDischarge") == null
                ? null : Integer.valueOf(String.valueOf(body.get("aldreteDischarge")));
        Integer dest = body.get("dischargeDest") == null
                ? null : Integer.valueOf(String.valueOf(body.get("dischargeDest")));
        String note = body.get("dischargeNote") == null ? null : String.valueOf(body.get("dischargeNote"));
        return R.ok(pacuService.discharge(id, aldrete, dest, note));
    }

    /** 复苏工作台列表(分页; status/kw 姓名或住院号 可选过滤) */
    @GetMapping("/list")
    public R<IPage<Map<String, Object>>> list(
            @RequestParam(required = false) Long orgId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String kw,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(pacuService.list(guard.scopeOrgId(orgId), status, kw, page, size));
    }

    /** 按手术查询复苏单(详情抽屉回显, 历史多条最近在前) */
    @GetMapping("/by-surgery/{surgeryId}")
    public R<List<HisSurgeryPacu>> listBySurgery(@PathVariable Long surgeryId) {
        return R.ok(pacuService.listBySurgery(surgeryId));
    }
}
