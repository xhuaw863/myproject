package com.yb.hi.controller.inpatient;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.entity.inpatient.InpNotification;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.service.inpatient.InpNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 住院通知接口: 未读角标 / 消息列表 / 单条已读 / 全部已读。
 * userId 一律从登录上下文(UserContext)获取, 不接受入参, 防止越权读取他人通知。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/his/inp/notification")
public class InpNotificationController {

    private final InpNotificationService notificationService;

    /** 未读计数(按类型分组): {total, byType:{1..6}}。 */
    @GetMapping("/unread-count")
    public R<Map<String, Object>> unreadCount() {
        return R.ok(notificationService.getUnreadCount(currentUserId()));
    }

    /** 通知分页: type 可选(1医嘱 2会诊 3病历 4预警 5评估 6系统), 未读在前按时间倒序。 */
    @GetMapping("/list")
    public R<IPage<InpNotification>> list(@RequestParam(required = false) Integer type,
                                          @RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return R.ok(notificationService.listNotifications(currentUserId(), type, page, size));
    }

    /** 单条标记已读(仅本人通知)。 */
    @PutMapping("/{id}/read")
    public R<Void> read(@PathVariable Long id) {
        return notificationService.markAsRead(id);
    }

    /** 全部标记已读(本人所有未读)。 */
    @PutMapping("/read-all")
    public R<Void> readAll() {
        return notificationService.markAllAsRead(currentUserId());
    }

    /** 当前登录用户ID(未登录由鉴权拦截器前置拦截, 此处防御性校验) */
    private Long currentUserId() {
        LoginUser u = UserContext.get();
        if (u == null || u.getUserId() == null) {
            throw new BizException(401, "未登录");
        }
        return u.getUserId();
    }
}
