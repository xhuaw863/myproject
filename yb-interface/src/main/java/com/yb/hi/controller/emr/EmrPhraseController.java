package com.yb.hi.controller.emr;

import com.yb.hi.entity.emr.HisEmrPhrase;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.service.emr.EmrPhraseService;
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

/**
 * 病历常用语接口(病历P2):
 *  - GET    /api/his/emr/phrase/list?category&deptCode  分类列表(全局+本科室+个人, 热度降序);
 *  - POST   /api/his/emr/phrase/use/{id}                使用计数+1;
 *  - POST   /api/his/emr/phrase                         新增个人常用语(scope 强制 2);
 *  - PUT    /api/his/emr/phrase/{id}                    修改(个人仅创建人, 全局/科室仅管理员);
 *  - DELETE /api/his/emr/phrase/{id}                    删除(逻辑删, 权限同修改)。
 * 认证由 AuthInterceptor(/api/**)把守, 租户隔离由 MyBatis-Plus 租户插件自动注入。
 */
@RestController
@RequestMapping("/api/his/emr/phrase")
public class EmrPhraseController {

    private final EmrPhraseService phraseService;

    public EmrPhraseController(EmrPhraseService phraseService) {
        this.phraseService = phraseService;
    }

    /**
     * 分类常用语列表: 全局(scope=0) + 本科室(scope=1, deptCode 匹配) + 个人(scope=2, 当前职工),
     * 仅启用行, 使用次数降序。
     *
     * @param category 分类(chief_complaint/present_illness/...), 空则全部分类
     * @param deptCode 当前科室编码, 空则跳过科室维度
     */
    @GetMapping("/list")
    public R<List<HisEmrPhrase>> list(@RequestParam(required = false) String category,
                                      @RequestParam(required = false) String deptCode) {
        LoginUser cur = UserContext.get();
        Long creatorId = cur == null ? null : cur.getStaffId();
        return R.ok(phraseService.listByCategory(category, deptCode, creatorId));
    }

    /** 使用计数+1(前端插入书写区时调用, 支撑热度排序) */
    @PostMapping("/use/{id}")
    public R<Void> use(@PathVariable Long id) {
        phraseService.incrementUsage(id);
        return R.ok();
    }

    /** 新增个人常用语(scope 强制 2, 创建人取当前登录职工; 请求体 category/content/enabled 生效) */
    @PostMapping
    public R<Long> create(@RequestBody HisEmrPhrase phrase) {
        return R.ok(phraseService.create(phrase));
    }

    /** 修改常用语(作用域/创建人不允许改; 个人仅创建人, 全局/科室仅管理员) */
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody HisEmrPhrase phrase) {
        phrase.setId(id);
        phraseService.update(phrase);
        return R.ok();
    }

    /** 删除常用语(逻辑删; 个人仅创建人, 全局/科室仅管理员) */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        phraseService.delete(id);
        return R.ok();
    }
}
