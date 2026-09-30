package com.yb.hi.service.inpatient;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.inpatient.InpNotification;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.inpatient.InpNotificationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 住院通知服务(站内消息): 未读角标(按类型分组) / 分页列表 / 单条已读 / 全部已读 / 创建通知。
 * 说明: 通知为用户级数据(user_id), 已读操作仅允许本人; 写查询经 MP 租户插件自动隔离。
 */
@Slf4j
@Service
public class InpNotificationService {

    /** 通知类型: 1医嘱 2会诊 3病历 4预警 5评估 6系统 */
    public static final int TYPE_ORDER = 1;
    public static final int TYPE_CONSULT = 2;
    public static final int TYPE_RECORD = 3;
    public static final int TYPE_ALERT = 4;
    public static final int TYPE_ASSESS = 5;
    public static final int TYPE_SYSTEM = 6;

    private final InpNotificationMapper notificationMapper;
    private final JdbcTemplate jdbcTemplate;

    public InpNotificationService(InpNotificationMapper notificationMapper, JdbcTemplate jdbcTemplate) {
        this.notificationMapper = notificationMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 未读计数(按类型分组): 返回 {total: 未读总数, byType: {"1": n, ..., "6": n}},
     * 六类均预置为0, 前端角标可直接按 key 取值。
     */
    public Map<String, Object> getUnreadCount(Long userId) {
        requireUser(userId);
        Long tenant = TenantContext.get();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT notify_type AS notifyType, COUNT(*) AS cnt FROM his_inp_notification"
                        + " WHERE user_id = ? AND is_read = 0 AND deleted = 0 AND tenant_id = ?"
                        + " GROUP BY notify_type",
                userId, tenant == null ? 0L : tenant);
        Map<String, Object> byType = new LinkedHashMap<>();
        for (int t = TYPE_ORDER; t <= TYPE_SYSTEM; t++) {
            byType.put(String.valueOf(t), 0);
        }
        long total = 0L;
        for (Map<String, Object> row : rows) {
            Object type = row.get("notifyType");
            Object cnt = row.get("cnt");
            if (type == null || cnt == null) {
                continue;
            }
            int c = ((Number) cnt).intValue();
            byType.put(String.valueOf(((Number) type).intValue()), c);
            total += c;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("byType", byType);
        return out;
    }

    /** 通知分页(未读在前, 时间倒序): type 可选过滤(1-6), 仅本人数据。 */
    public IPage<InpNotification> listNotifications(Long userId, Integer type, int page, int size) {
        requireUser(userId);
        long p = page < 1 ? 1 : page;
        long s = size < 1 ? 20 : Math.min(size, 200);
        LambdaQueryWrapper<InpNotification> qw = new LambdaQueryWrapper<InpNotification>()
                .eq(InpNotification::getUserId, userId)
                .eq(type != null, InpNotification::getNotifyType, type)
                .orderByAsc(InpNotification::getIsRead)
                .orderByDesc(InpNotification::getCreateTime)
                .orderByDesc(InpNotification::getId);
        return notificationMapper.selectPage(new Page<>(p, s), qw);
    }

    /** 单条标记已读(is_read=1, read_time=NOW()); 已读的记录幂等返回成功。 */
    public R<Void> markAsRead(Long id) {
        if (id == null) {
            throw new BizException(400, "通知ID不能为空");
        }
        Long userId = currentUserId();
        requireUser(userId);
        InpNotification n = notificationMapper.selectById(id);
        if (n == null) {
            throw new BizException(404, "通知不存在");
        }
        if (!userId.equals(n.getUserId())) {
            throw new BizException(403, "无权操作他人的通知");
        }
        if (n.getIsRead() != null && n.getIsRead() == 1) {
            return R.ok();
        }
        notificationMapper.update(null, Wrappers.<InpNotification>lambdaUpdate()
                .eq(InpNotification::getId, id)
                .eq(InpNotification::getUserId, userId)
                .set(InpNotification::getIsRead, 1)
                .set(InpNotification::getReadTime, LocalDateTime.now()));
        log.info("通知已读: id={}, userId={}", id, userId);
        return R.ok();
    }

    /** 全部标记已读: 本人所有未读 -> 已读。 */
    public R<Void> markAllAsRead(Long userId) {
        requireUser(userId);
        int affected = notificationMapper.update(null, Wrappers.<InpNotification>lambdaUpdate()
                .eq(InpNotification::getUserId, userId)
                .eq(InpNotification::getIsRead, 0)
                .set(InpNotification::getIsRead, 1)
                .set(InpNotification::getReadTime, LocalDateTime.now()));
        log.info("通知全部已读: userId={}, affected={}", userId, affected);
        return R.ok();
    }

    /**
     * 创建通知(业务模块调用入口): 归属机构取当前登录用户机构(无则0).
     * 返回新通知ID, 供调用方记录业务关联。
     */
    public Long createNotification(Long userId, int type, String title, String content,
                                   String refType, Long refId) {
        requireUser(userId);
        if (!StringUtils.hasText(title)) {
            throw new BizException(400, "通知标题不能为空");
        }
        InpNotification n = new InpNotification();
        LoginUser lu = UserContext.get();
        n.setOrgId(lu != null && lu.getOrgId() != null ? lu.getOrgId() : 0L);
        n.setUserId(userId);
        n.setNotifyType(type);
        n.setTitle(title);
        n.setContent(content);
        n.setRefType(refType);
        n.setRefId(refId);
        n.setIsRead(0);
        notificationMapper.insert(n);
        log.info("住院通知创建: id={}, userId={}, type={}, refType={}, refId={}",
                n.getId(), userId, type, refType, refId);
        return n.getId();
    }

    /** 当前登录用户ID(UserContext) */
    private Long currentUserId() {
        LoginUser u = UserContext.get();
        return u == null ? null : u.getUserId();
    }

    private void requireUser(Long userId) {
        if (userId == null) {
            throw new BizException(401, "未登录");
        }
    }
}
