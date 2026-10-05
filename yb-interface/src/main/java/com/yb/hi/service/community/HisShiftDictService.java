package com.yb.hi.service.community;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.entity.community.HisShiftDict;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.util.PinyinUtil;
import com.yb.hi.mapper.community.HisShiftDictMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 医共体门诊班次字典服务(L1, 牵头机构维护): 排班/号源时段(time_type)的唯一受控来源。
 * 排班与模板服务的时段白名单校验、周视图列顺序均以本服务 enabledCodes() 为准。
 */
@Service
public class HisShiftDictService extends ServiceImpl<HisShiftDictMapper, HisShiftDict> {

    /** 分页查询(关键字: 名称/编码/拼音简码/自定义码) */
    public IPage<HisShiftDict> pageQuery(String keyword, Integer status, long page, long size) {
        LambdaQueryChainWrapper<HisShiftDict> q = lambdaQuery()
                .eq(status != null, HisShiftDict::getStatus, status);
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            q.and(w -> w.like(HisShiftDict::getName, kw)
                    .or().like(HisShiftDict::getCode, kw)
                    .or().like(HisShiftDict::getPyCode, kw)
                    .or().like(HisShiftDict::getAbbrCode, kw));
        }
        return q.orderByAsc(HisShiftDict::getSortNo).orderByAsc(HisShiftDict::getId)
                .page(new Page<>(page, size));
    }

    @Override
    public boolean save(HisShiftDict e) {
        validate(e, null);
        fillPyCode(e);
        return super.save(e);
    }

    @Override
    public boolean updateById(HisShiftDict e) {
        validate(e, e == null ? null : e.getId());
        fillPyCode(e);
        return super.updateById(e);
    }

    /** 必填与时机格式校验 + 租户内编码唯一(友好报错, 兜底仍靠 uk_tenant_code) */
    private void validate(HisShiftDict e, Long excludeId) {
        if (e == null || !StringUtils.hasText(e.getCode()) || !StringUtils.hasText(e.getName())) {
            throw new BizException(400, "班次编码与名称必填");
        }
        e.setCode(e.getCode().trim());
        e.setName(e.getName().trim());
        if (!e.getCode().matches("[a-zA-Z][a-zA-Z0-9_]{0,19}")) {
            throw new BizException(400, "班次编码须为字母开头的字母/数字/下划线(≤20位): " + e.getCode());
        }
        checkHhmm(e.getStartTime(), "开始时间");
        checkHhmm(e.getEndTime(), "结束时间");
        Long dup = lambdaQuery()
                .eq(HisShiftDict::getCode, e.getCode())
                .ne(excludeId != null, HisShiftDict::getId, excludeId)
                .count() >= 1 ? 1L : 0L;
        if (dup > 0) {
            throw new BizException(400, "班次编码已存在: " + e.getCode());
        }
    }

    /** HH:mm 格式校验(空放行=未定义精确时间) */
    private static void checkHhmm(String v, String label) {
        if (StringUtils.hasText(v) && !v.trim().matches("([01]?[0-9]|2[0-3]):[0-5][0-9]")) {
            throw new BizException(400, label + "格式须为 HH:mm: " + v);
        }
    }

    /** 拼音简码随名称自动重算(只读); 自定义码由维护页透传不覆盖 */
    private void fillPyCode(HisShiftDict e) {
        if (e != null && StringUtils.hasText(e.getName())) {
            e.setPyCode(PinyinUtil.initials(e.getName()));
        }
    }

    /** 启用班次(有序): 排班/模板/号源按钮的唯一数据源 */
    public List<HisShiftDict> enabledList() {
        return lambdaQuery()
                .eq(HisShiftDict::getStatus, 1)
                .orderByAsc(HisShiftDict::getSortNo).orderByAsc(HisShiftDict::getId)
                .list();
    }

    /** 启用班次编码有序列表; 字典未初始化时返回空列表(调用方回落存量硬编码三值防锁死) */
    public List<String> enabledCodes() {
        return enabledList().stream().map(HisShiftDict::getCode).collect(Collectors.toList());
    }

    /** 班次名称(含停用, 供历史数据翻译); 查不到返回 null 由调用方兜底 */
    public String nameOf(String code) {
        if (!StringUtils.hasText(code)) {
            return null;
        }
        HisShiftDict d = lambdaQuery().eq(HisShiftDict::getCode, code.trim()).last("LIMIT 1").one();
        return d == null ? null : d.getName();
    }
}
