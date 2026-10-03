package com.yb.hi.service.basedata;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.basedata.HisMedTypeDict;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.util.PinyinUtil;
import com.yb.hi.mapper.basedata.HisMedTypeDictMapper;
import com.yb.hi.platform.entity.SysOrg;
import com.yb.hi.platform.mapper.SysOrgMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.StdDictQueryService;
import com.yb.hi.stddict.StdDict;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 医疗类别字典服务(医共体级模板): 医保 cv_code:med_type 整组导入叠加门诊/住院启停与按机构级别开放。
 * 读隔离=本租户全量(牵头维护页看全部条); 业务下拉按当前登录机构 orgLevel + 场景开关收窄。
 * 写守卫 requireLeadOrg(仅牵头机构管理员维护); code 为医保权威锚点不可改。
 */
@Slf4j
@Service
public class MedTypeDictService {

    /** 导入源(医保字典)与其 med_type 分组码 */
    private static final String SRC_KEY = "cv_code";
    private static final String GROUP_CODE = "med_type";
    /** 合法机构级别 token(orgLevel: 1县/2乡/3村) */
    private static final Set<String> LEVEL_TOKENS = new HashSet<>(Arrays.asList("1", "2", "3"));

    private final HisMedTypeDictMapper mapper;
    private final StdDictQueryService stdQuery;
    private final DataSource dataSource;
    private final OrgAccessGuard guard;
    private final SysOrgMapper orgMapper;

    public MedTypeDictService(HisMedTypeDictMapper mapper, StdDictQueryService stdQuery, DataSource dataSource,
                              OrgAccessGuard guard, SysOrgMapper orgMapper) {
        this.mapper = mapper;
        this.stdQuery = stdQuery;
        this.dataSource = dataSource;
        this.guard = guard;
        this.orgMapper = orgMapper;
    }

    /** 分页列表(keyword 命中 code/name/py_code; otp/ipt/level/status 过滤)。租户全量, 牵头维护页看全部条。 */
    public IPage<HisMedTypeDict> listPage(long page, long size, String keyword,
                                          Integer otp, Integer ipt, String level, Integer status) {
        QueryWrapper<HisMedTypeDict> qw = new QueryWrapper<>();
        if (otp != null) {
            qw.eq("otp_use_flag", otp);
        }
        if (ipt != null) {
            qw.eq("ipt_use_flag", ipt);
        }
        if (StringUtils.hasText(level)) {
            qw.apply("FIND_IN_SET({0}, open_levels)", level.trim());
        }
        if (status != null) {
            qw.eq("status", status);
        }
        if (StringUtils.hasText(keyword)) {
            String like = "%" + keyword.trim() + "%";
            qw.and(w -> w.like("code", like).or().like("name", like).or().like("py_code", like));
        }
        qw.orderByAsc("sort_no").orderByAsc("id");
        return mapper.selectPage(new Page<>(Math.max(1, page), size <= 0 ? 20 : Math.min(size, 500)), qw);
    }

    /**
     * 业务下拉取数: status=1 且场景开关命中(OTP→otp_use_flag=1 / IPT→ipt_use_flag=1)
     * 且当前登录机构级别在 open_levels 内。级别查不到时不过滤级别(兜底全级别, 避免空列表)。按 sort_no 排序。
     */
    public List<HisMedTypeDict> optionsByScene(String scene) {
        String s = scene == null ? "" : scene.trim().toUpperCase();
        QueryWrapper<HisMedTypeDict> qw = new QueryWrapper<>();
        qw.eq("status", 1);
        if ("OTP".equals(s)) {
            qw.eq("otp_use_flag", 1);
        } else if ("IPT".equals(s)) {
            qw.eq("ipt_use_flag", 1);
        }
        Integer level = currentOrgLevel();
        if (level != null) {
            qw.apply("FIND_IN_SET({0}, open_levels)", String.valueOf(level));
        }
        qw.orderByAsc("sort_no").orderByAsc("id");
        return mapper.selectList(qw);
    }

    /** 按编码查名称(消费端标签回落用): 本租户命中返回名称, 否则 null。 */
    public String nameOf(String code) {
        if (!StringUtils.hasText(code)) {
            return null;
        }
        HisMedTypeDict e = mapper.selectOne(new QueryWrapper<HisMedTypeDict>()
                .eq("code", code.trim()).last("LIMIT 1"));
        return e == null ? null : e.getName();
    }

    @Transactional(rollbackFor = Exception.class)
    public HisMedTypeDict update(Long id, HisMedTypeDict body) {
        guard.requireLeadOrg("仅牵头机构管理员可维护医疗类别");
        HisMedTypeDict cur = mapper.selectById(id);
        if (cur == null) {
            throw new BizException(400, "医疗类别字典项不存在");
        }
        // code 为医保权威锚点不可改; 名称可改并重算简码
        cur.setName(StringUtils.hasText(body.getName()) ? body.getName().trim() : cur.getName());
        cur.setOtpUseFlag(body.getOtpUseFlag() == null ? cur.getOtpUseFlag() : normFlag(body.getOtpUseFlag()));
        cur.setIptUseFlag(body.getIptUseFlag() == null ? cur.getIptUseFlag() : normFlag(body.getIptUseFlag()));
        cur.setOpenLevels(normalizeLevels(body.getOpenLevels(), cur.getOpenLevels()));
        cur.setSortNo(body.getSortNo() == null ? cur.getSortNo() : body.getSortNo());
        cur.setStatus(body.getStatus() == null ? cur.getStatus() : normFlag(body.getStatus()));
        cur.setMemo(body.getMemo());
        cur.setPyCode(PinyinUtil.initials(cur.getName()));
        mapper.updateById(cur);
        return cur;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        guard.requireLeadOrg("仅牵头机构管理员可维护医疗类别");
        HisMedTypeDict cur = mapper.selectById(id);
        if (cur == null) {
            throw new BizException(400, "医疗类别字典项不存在");
        }
        // 删叠加配置行(医保标准码集仍在, 可再整组导入恢复)
        mapper.deleteById(id);
    }

    /**
     * 从医保字典 cv_code:med_type 整组导入(幂等 upsert 且保留已维护开关):
     * 命中(code 已存在)只更新 name/溯源/py_code, 不覆盖 otp/ipt/open_levels/status;
     * 新行插入默认 otp_use=0,ipt_use=0,open_levels='1,2,3',status=1。返回 total/inserted/skipped。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> importFromStd() {
        guard.requireLeadOrg("仅牵头机构管理员可维护医疗类别");
        StdDict d = stdQuery.get(SRC_KEY);
        if (d == null) {
            throw new BizException(400, "未知标准字典: " + SRC_KEY);
        }
        // 存量 code -> 实体(命中保留已维护开关)
        Map<String, HisMedTypeDict> exist = new HashMap<>();
        for (HisMedTypeDict e : mapper.selectList(new QueryWrapper<HisMedTypeDict>()
                .select("id", "code", "name"))) {
            exist.put(e.getCode(), e);
        }
        int total = 0;
        int inserted = 0;
        int skipped = 0;
        Set<String> seen = new HashSet<>();
        List<HisMedTypeDict> addBatch = new ArrayList<>();
        String sql = "SELECT " + d.getCodeCol() + ", " + d.getNameCol() + " FROM " + d.getTable()
                + " WHERE " + d.getExtraCol() + " = ? ORDER BY " + d.getCodeCol();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, GROUP_CODE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    total++;
                    String code = rs.getString(1);
                    String name = rs.getString(2);
                    if (!StringUtils.hasText(code) || !StringUtils.hasText(name) || !seen.add(code.trim())) {
                        continue;
                    }
                    code = code.trim();
                    name = name.trim();
                    HisMedTypeDict cur = exist.get(code);
                    if (cur != null) {
                        skipped++;
                        continue;
                    }
                    addBatch.add(newRow(code, name, d.getSrcDoc()));
                }
            }
        } catch (SQLException ex) {
            throw new BizException(500, "读取医保医疗类别值域失败: " + ex.getMessage());
        }
        if (addBatch.isEmpty() && total > 0) {
            log.info("医疗类别字典导入: 标准组 {} 共 {} 码, 全部已存在无需新增", GROUP_CODE, total);
        }
        for (HisMedTypeDict e : addBatch) {
            mapper.insert(e);
            inserted++;
        }
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("total", total);
        ret.put("inserted", inserted);
        ret.put("skipped", skipped);
        return ret;
    }

    private HisMedTypeDict newRow(String code, String name, String srcDoc) {
        HisMedTypeDict e = new HisMedTypeDict();
        e.setCode(code);
        e.setName(name);
        e.setPyCode(PinyinUtil.initials(name));
        e.setOtpUseFlag(0);
        e.setIptUseFlag(0);
        e.setOpenLevels("1,2,3");
        e.setYbCode(code);
        e.setSrcType(SRC_KEY);
        e.setSrcDoc(srcDoc);
        e.setSrcCode(code);
        e.setSortNo(0);
        e.setStatus(1);
        return e;
    }

    /** 当前登录机构级别(sys_org.org_level); 查不到返回 null(调用侧兜底不过滤级别)。 */
    private Integer currentOrgLevel() {
        try {
            Long orgId = guard.currentOrgId();
            SysOrg org = orgMapper.selectById(orgId);
            return org == null ? null : org.getOrgLevel();
        } catch (Exception e) {
            log.warn("医疗类别下拉取数: 当前机构级别解析失败, 跳过级别过滤: {}", e.getMessage());
            return null;
        }
    }

    private Integer normFlag(Integer v) {
        return (v != null && v == 1) ? 1 : 0;
    }

    /** 开放级别 csv 归一: 仅保留 {1,2,3} token, 排序去重; 非法或空则回落 fallback(默认全级别)。 */
    private String normalizeLevels(String raw, String fallback) {
        if (!StringUtils.hasText(raw)) {
            return StringUtils.hasText(fallback) ? fallback : "1,2,3";
        }
        Set<String> tokens = new HashSet<>();
        for (String t : raw.split(",")) {
            String token = t.trim();
            if (token.isEmpty()) {
                continue;
            }
            if (!LEVEL_TOKENS.contains(token)) {
                throw new BizException(400, "非法开放级别: " + token);
            }
            tokens.add(token);
        }
        if (tokens.isEmpty()) {
            throw new BizException(400, "至少保留一个开放级别");
        }
        List<String> ordered = new ArrayList<>(tokens);
        ordered.sort(null);
        return String.join(",", ordered);
    }
}
