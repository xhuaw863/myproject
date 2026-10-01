package com.yb.hi.controller.mr;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.entity.mr.HisMrBorrow;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.mr.MrBorrowService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 病案借阅接口(P1-B): 借阅作业列表 / 借出登记 / 归还登记 / 逾期刷新 / 借阅单打印数据源。
 * 写接口 requireSelfOrgWrite(本机构管理员) 在 Service 层。
 */
@RestController
@RequestMapping("/api/his/mr/borrow")
public class MrBorrowController {

    private final MrBorrowService borrowService;

    public MrBorrowController(MrBorrowService borrowService) {
        this.borrowService = borrowService;
    }

    /** 借阅作业列表(分页, 状态/科室/关键字过滤)。 */
    @GetMapping("/list")
    public R<IPage<Map<String, Object>>> list(@RequestParam(defaultValue = "1") long page,
                                              @RequestParam(defaultValue = "20") long size,
                                              @RequestParam(required = false) Integer status,
                                              @RequestParam(required = false) Long borrowerDeptId,
                                              @RequestParam(required = false) String keyword) {
        return R.ok(borrowService.listPage(page, size, status, keyword, borrowerDeptId));
    }

    /** 借出登记: body {visitId, borrowerName, borrowerId?, borrowerDeptId?, borrowerDeptName?, purpose, expectReturnDate?}。 */
    @PostMapping("/lend")
    public R<HisMrBorrow> lend(@RequestBody Map<String, Object> body) {
        return R.ok(borrowService.lend(body));
    }

    /** 归还登记。 */
    @PutMapping("/{id}/return")
    public R<HisMrBorrow> giveBack(@PathVariable Long id) {
        return R.ok(borrowService.giveBack(id));
    }

    /** 逾期刷新: 将到期未还置为逾期, 返回处理数。 */
    @PostMapping("/refresh-overdue")
    public R<Integer> refreshOverdue() {
        return R.ok(borrowService.refreshOverdue());
    }

    /** 借阅单打印数据源。 */
    @GetMapping("/slip/{id}")
    public R<Map<String, Object>> slip(@PathVariable Long id) {
        return R.ok(borrowService.slip(id));
    }
}
