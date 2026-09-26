package com.yb.hi.service.community;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.entity.community.HisMedDict;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.util.PinyinUtil;
import com.yb.hi.mapper.community.HisMedDictMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 医共体用药字典服务(L2, 牵头机构维护): 用法(给药途径)/用药频次, 单表按 dict_type 区分。
 * 支持从医保标准值域批量导入(按 dict_type+code 幂等 upsert), 频次导入时按名称 best-effort 解析每日次数。
 */
@Service
public class HisMedDictService extends ServiceImpl<HisMedDictMapper, HisMedDict> {

    public static final String TYPE_USAGE = "usage";
    public static final String TYPE_FREQ = "freq";

    /** 频次名称中的每日次数解析: 优先"每[天/日/周]N次", 次选拉丁缩写 qd/bid/tid/qid, 再选 qNh */
    private static final Pattern P_CN = Pattern.compile("每([天日周])\\s*([0-9一二三四五六七八九十]+)\\s*次");
    private static final Pattern P_QN = Pattern.compile("(?i)q(\\d+)h");

    /** 分页查询(关键字: 名称/院内码/医保码) */
    public IPage<HisMedDict> pageQuery(String dictType, String keyword, Integer status, long page, long size) {
        LambdaQueryChainWrapper<HisMedDict> q = lambdaQuery()
                .eq(StringUtils.hasText(dictType), HisMedDict::getDictType, dictType)
                .eq(status != null, HisMedDict::getStatus, status);
        if (StringUtils.hasText(keyword)) {
            q.and(w -> w.like(HisMedDict::getName, keyword)
                    .or().like(HisMedDict::getCode, keyword)
                    .or().like(HisMedDict::getYbCode, keyword)
                    .or().like(HisMedDict::getPyCode, keyword)
                    .or().like(HisMedDict::getAbbrCode, keyword));
        }
        return q.orderByAsc(HisMedDict::getSortNo).orderByAsc(HisMedDict::getId)
                .page(new Page<>(page, size));
    }

    @Override
    public boolean save(HisMedDict e) {
        fillPyCode(e);
        return super.save(e);
    }

    @Override
    public boolean updateById(HisMedDict e) {
        fillPyCode(e);
        return super.updateById(e);
    }

    /** 拼音简码随名称自动重算(只读); 自定义码 abbr_code 由维护页透传不覆盖 */
    private void fillPyCode(HisMedDict e) {
        if (e != null && StringUtils.hasText(e.getName())) {
            e.setPyCode(PinyinUtil.initials(e.getName()));
        }
    }

    /** 本机构启用项查询(医生站下拉用): status=1, 按排序号/ID 升序 */
    public List<HisMedDict> enabledList(String dictType) {
        return lambdaQuery()
                .eq(StringUtils.hasText(dictType), HisMedDict::getDictType, dictType)
                .eq(HisMedDict::getStatus, 1)
                .orderByAsc(HisMedDict::getSortNo).orderByAsc(HisMedDict::getId)
                .list();
    }

    /**
     * 批量导入(幂等): 同 dict_type+code 已存在则更新名称/医保码/每日次数/来源, 否则新增。
     * 频次未显式给出 daily_times 时按名称 best-effort 解析。返回写入条数。
     */
    public int importBatch(String dictType, List<HisMedDict> items) {
        if (!StringUtils.hasText(dictType)) {
            throw new BizException(400, "字典类型不能为空");
        }
        if (items == null || items.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (HisMedDict in : items) {
            if (in == null) {
                continue;
            }
            in.setDictType(dictType);
            if (!StringUtils.hasText(in.getCode())) {
                in.setCode(in.getYbCode());
            }
            if (!StringUtils.hasText(in.getCode()) || !StringUtils.hasText(in.getName())) {
                continue;
            }
            if (TYPE_FREQ.equals(dictType) && in.getDailyTimes() == null) {
                in.setDailyTimes(parseDailyTimes(in.getName()));
            }
            HisMedDict exist = lambdaQuery()
                    .eq(HisMedDict::getDictType, dictType)
                    .eq(HisMedDict::getCode, in.getCode()).one();
            if (exist != null) {
                in.setId(exist.getId());
                updateById(in);
            } else {
                in.setId(null);
                save(in);
            }
            n++;
        }
        return n;
    }

    /** 按名称解析每日次数: 每天N次 > qd/bid/tid/qid > qNh; 无法解析返回 null(可人工补录) */
    public BigDecimal parseDailyTimes(String name) {
        if (!StringUtils.hasText(name)) {
            return null;
        }
        String s = name.toLowerCase();
        Matcher m = P_CN.matcher(name);
        if (m.find()) {
            Integer n = cnNum(m.group(2));
            if (n != null && n > 0) {
                // 每周N次 -> 折算为每日 N/7 次
                return "周".equals(m.group(1))
                        ? BigDecimal.valueOf(n).divide(BigDecimal.valueOf(7), 4, BigDecimal.ROUND_HALF_UP)
                        : BigDecimal.valueOf(n);
            }
        }
        if (s.contains("qid") || s.contains("q6h")) {
            return BigDecimal.valueOf(4);
        }
        if (s.contains("tid") || s.contains("q8h")) {
            return BigDecimal.valueOf(3);
        }
        if (s.contains("bid") || s.contains("q12h")) {
            return BigDecimal.valueOf(2);
        }
        if (s.contains("qd") || s.contains("q24h") || s.contains("qn") || s.contains("每天一次") || s.contains("每日一次")) {
            return BigDecimal.ONE;
        }
        if (s.contains("qod") || s.contains("隔日")) {
            return new BigDecimal("0.5");
        }
        Matcher qn = P_QN.matcher(s);
        if (qn.find()) {
            try {
                int h = Integer.parseInt(qn.group(1));
                if (h > 0) {
                    return BigDecimal.valueOf(24.0 / h).setScale(4, BigDecimal.ROUND_HALF_UP);
                }
            } catch (NumberFormatException ignore) {
                // 保留 null, 由人工补录
            }
        }
        return null;
    }

    /** 中文/阿拉伯数字小写解析(1-10), 无法识别返回 null */
    private Integer cnNum(String t) {
        if (!StringUtils.hasText(t)) {
            return null;
        }
        String v = t.trim();
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException ignore) {
            // 转中文数字
        }
        String[] cn = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};
        for (int i = 1; i < cn.length; i++) {
            if (cn[i].equals(v)) {
                return i;
            }
        }
        if ("两".equals(v)) {
            return 2;
        }
        return null;
    }
}
