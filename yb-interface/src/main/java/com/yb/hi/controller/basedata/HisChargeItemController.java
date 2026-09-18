package com.yb.hi.controller.basedata;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.entity.basedata.HisChargeItem;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.basedata.HisChargeItemService;
import org.springframework.web.bind.annotation.*;

/**
 * 收费项目(本院目录)管理接口
 */
@RestController
@RequestMapping("/api/his/charge-item")
public class HisChargeItemController {

    private final HisChargeItemService service;

    public HisChargeItemController(HisChargeItemService service) {
        this.service = service;
    }

    @GetMapping("/page")
    public R<IPage<HisChargeItem>> page(@RequestParam(defaultValue = "1") long page,
                                        @RequestParam(defaultValue = "20") long size,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) String itemType) {
        return R.ok(service.pageQuery(page, size, keyword, itemType));
    }

    @GetMapping("/{id}")
    public R<HisChargeItem> get(@PathVariable Long id) {
        return R.ok(service.getById(id));
    }

    @PostMapping
    public R<Void> create(@RequestBody HisChargeItem e) {
        service.save(e);
        return R.ok();
    }

    @PutMapping
    public R<Void> update(@RequestBody HisChargeItem e) {
        service.updateById(e);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        service.removeById(id);
        return R.ok();
    }
}
