package com.yb.hi.controller.basedata;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.entity.basedata.HisMedTypeDict;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.basedata.MedTypeDictService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 医疗类别字典接口(医共体级模板): 医保 med_type 导入叠加门诊/住院启停与按机构级别开放。
 * 读: 登录即可(业务下拉消费); 写: Service 内 requireLeadOrg(仅牵头机构管理员维护)。
 */
@RestController
@RequestMapping("/api/his/med-type")
public class MedTypeDictController {

    private final MedTypeDictService service;

    public MedTypeDictController(MedTypeDictService service) {
        this.service = service;
    }

    @GetMapping("/page")
    public R<IPage<HisMedTypeDict>> page(@RequestParam(defaultValue = "1") long page,
                                         @RequestParam(defaultValue = "20") long size,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) Integer otp,
                                         @RequestParam(required = false) Integer ipt,
                                         @RequestParam(required = false) String level,
                                         @RequestParam(required = false) Integer status) {
        return R.ok(service.listPage(page, size, keyword, otp, ipt, level, status));
    }

    /** 业务下拉(scene=OTP/IPT, 按当前机构级别与场景开关收窄) */
    @GetMapping("/options")
    public R<List<HisMedTypeDict>> options(@RequestParam(required = false) String scene) {
        return R.ok(service.optionsByScene(scene));
    }

    @PutMapping("/update")
    public R<HisMedTypeDict> update(@RequestParam Long id, @RequestBody HisMedTypeDict body) {
        return R.ok(service.update(id, body));
    }

    @DeleteMapping("/delete")
    public R<Void> delete(@RequestParam Long id) {
        service.delete(id);
        return R.ok(null);
    }

    /** 从医保字典 cv_code:med_type 整组导入(幂等, 保留已维护开关) */
    @PostMapping("/import")
    public R<Map<String, Object>> importStd() {
        return R.ok(service.importFromStd());
    }
}
