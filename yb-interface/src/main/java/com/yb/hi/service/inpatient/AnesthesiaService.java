package com.yb.hi.service.inpatient;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yb.hi.dto.inpatient.AnesthesiaDTO;
import com.yb.hi.entity.inpatient.HisAnesthesia;
import com.yb.hi.entity.inpatient.HisSurgery;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.mapper.inpatient.HisAnesthesiaMapper;
import com.yb.hi.mapper.inpatient.HisSurgeryMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 麻醉记录服务: 一台手术一条记录(术前评估 + 时间轴 + 生命体征/术中事件JSON追加 + 完成)。
 * 生命体征/术中事件以 JSON 数组存库(fastjson2 解析追加), 服务端补 time 缺省值便于前端曲线渲染。
 */
@Slf4j
@Service
public class AnesthesiaService {

    /** 生命体征/事件时间缺省格式(时分秒定位术中时间轴) */
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final HisAnesthesiaMapper anesthesiaMapper;
    private final HisSurgeryMapper surgeryMapper;
    private final OrgAccessGuard guard;

    public AnesthesiaService(HisAnesthesiaMapper anesthesiaMapper, HisSurgeryMapper surgeryMapper,
                             OrgAccessGuard guard) {
        this.anesthesiaMapper = anesthesiaMapper;
        this.surgeryMapper = surgeryMapper;
        this.guard = guard;
    }

    /* ==================== 查询 / 创建 / 编辑 ==================== */

    /** 手术的麻醉记录(一对一; 未创建时返回 null, 由前端引导创建) */
    public HisAnesthesia getBySurgeryId(Long surgeryId) {
        requireSurgery(surgeryId);
        List<HisAnesthesia> list = anesthesiaMapper.selectList(new LambdaQueryWrapper<HisAnesthesia>()
                .eq(HisAnesthesia::getSurgeryId, surgeryId)
                .orderByDesc(HisAnesthesia::getId)
                .last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    /** 创建麻醉记录(一台手术仅一条, 重复创建拦截) */
    @Transactional(rollbackFor = Exception.class)
    public HisAnesthesia create(AnesthesiaDTO dto, Long orgId) {
        if (dto == null || dto.getSurgeryId() == null) {
            throw new BizException(400, "手术ID不能为空");
        }
        HisSurgery surgery = requireSurgery(dto.getSurgeryId());
        long exist = anesthesiaMapper.selectCount(new LambdaQueryWrapper<HisAnesthesia>()
                .eq(HisAnesthesia::getSurgeryId, dto.getSurgeryId()));
        if (exist > 0) {
            throw new BizException("该手术已存在麻醉记录, 不能重复创建");
        }

        HisAnesthesia a = new HisAnesthesia();
        a.setOrgId(surgery.getOrgId() != null ? surgery.getOrgId() : orgId);
        a.setSurgeryId(dto.getSurgeryId());
        a.setAnesthesiaType(dto.getAnesthesiaType());
        a.setAnesthesiaMethod(dto.getAnesthesiaMethod());
        a.setPreAssessment(dto.getPreAssessment());
        a.setPostAssessment(dto.getPostAssessment());
        a.setStatus(1);
        anesthesiaMapper.insert(a);
        log.info("创建麻醉记录: id={}, surgeryId={}", a.getId(), dto.getSurgeryId());
        return a;
    }

    /** 编辑麻醉记录(仅 status=1 记录中可编辑) */
    @Transactional(rollbackFor = Exception.class)
    public HisAnesthesia update(Long id, AnesthesiaDTO dto) {
        HisAnesthesia a = requireAnesthesia(id);
        if (a.getStatus() == null || a.getStatus() != 1) {
            throw new BizException("麻醉记录已完成, 不能编辑");
        }
        if (dto != null) {
            if (dto.getAnesthesiaType() != null) {
                a.setAnesthesiaType(dto.getAnesthesiaType());
            }
            if (dto.getAnesthesiaMethod() != null) {
                a.setAnesthesiaMethod(dto.getAnesthesiaMethod());
            }
            if (dto.getPreAssessment() != null) {
                a.setPreAssessment(dto.getPreAssessment());
            }
            if (dto.getPostAssessment() != null) {
                a.setPostAssessment(dto.getPostAssessment());
            }
        }
        anesthesiaMapper.updateById(a);
        log.info("编辑麻醉记录: id={}", id);
        return anesthesiaMapper.selectById(id);
    }

    /* ==================== JSON 追加(生命体征 / 术中事件) ==================== */

    /**
     * 追加生命体征: 读取 vital_signs JSON 数组 -> 追加 {time, hr, sbp, dbp, spo2, temp, etco2, rr} -> 回写。
     * time 缺省时服务端补当前时间。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisAnesthesia appendVitalSign(Long id, Map<String, Object> data) {
        HisAnesthesia a = requireAnesthesia(id);
        if (data == null || data.isEmpty()) {
            throw new BizException(400, "生命体征数据不能为空");
        }
        JSONArray arr = parseArray(a.getVitalSigns());
        JSONObject rec = new JSONObject(data);
        if (rec.get("time") == null) {
            rec.put("time", LocalDateTime.now().format(TS));
        }
        arr.add(rec);
        a.setVitalSigns(arr.toJSONString());
        anesthesiaMapper.updateById(a);
        log.info("追加生命体征: anesthesiaId={}, 条数={}", id, arr.size());
        return anesthesiaMapper.selectById(id);
    }

    /** 追加术中事件: 读取 anesthesia_events JSON 数组 -> 追加 {time, eventType, description} -> 回写 */
    @Transactional(rollbackFor = Exception.class)
    public HisAnesthesia appendEvent(Long id, Map<String, Object> data) {
        HisAnesthesia a = requireAnesthesia(id);
        if (data == null || data.isEmpty()) {
            throw new BizException(400, "事件数据不能为空");
        }
        JSONArray arr = parseArray(a.getAnesthesiaEvents());
        JSONObject rec = new JSONObject(data);
        if (rec.get("time") == null) {
            rec.put("time", LocalDateTime.now().format(TS));
        }
        arr.add(rec);
        a.setAnesthesiaEvents(arr.toJSONString());
        anesthesiaMapper.updateById(a);
        log.info("追加术中事件: anesthesiaId={}, 条数={}", id, arr.size());
        return anesthesiaMapper.selectById(id);
    }

    /* ==================== 完成 ==================== */

    /** 完成麻醉记录(status 1->2, 乐观更新) */
    @Transactional(rollbackFor = Exception.class)
    public HisAnesthesia complete(Long id) {
        requireAnesthesia(id);
        int affected = anesthesiaMapper.update(null, new LambdaUpdateWrapper<HisAnesthesia>()
                .set(HisAnesthesia::getStatus, 2)
                .set(HisAnesthesia::getUpdateTime, LocalDateTime.now())
                .eq(HisAnesthesia::getId, id)
                .eq(HisAnesthesia::getStatus, 1));
        if (affected == 0) {
            throw new BizException("麻醉记录已完成或状态已变化, 请刷新后重试");
        }
        log.info("完成麻醉记录: id={}", id);
        return anesthesiaMapper.selectById(id);
    }

    /* ==================== 校验 / 工具 ==================== */

    /** 麻醉记录存在性 + 经手术做机构归属校验 */
    private HisAnesthesia requireAnesthesia(Long id) {
        if (id == null) {
            throw new BizException(400, "麻醉记录ID不能为空");
        }
        HisAnesthesia a = anesthesiaMapper.selectById(id);
        if (a == null) {
            throw new BizException(404, "麻醉记录不存在");
        }
        if (a.getSurgeryId() != null) {
            requireSurgery(a.getSurgeryId());
        }
        return a;
    }

    /** 手术存在性 + 机构归属校验(非牵头机构仅本机构可访问) */
    private HisSurgery requireSurgery(Long surgeryId) {
        if (surgeryId == null) {
            throw new BizException(400, "手术ID不能为空");
        }
        HisSurgery s = surgeryMapper.selectById(surgeryId);
        if (s == null) {
            throw new BizException(404, "手术记录不存在");
        }
        Long scope = guard.scopeOrgId(s.getOrgId());
        if (scope == null || !scope.equals(s.getOrgId())) {
            throw new BizException(403, "无权访问其他机构的手术数据");
        }
        return s;
    }

    /** 解析 JSON 数组(空/null 或解析失败时按空数组处理, 避免脏数据阻塞追加) */
    private JSONArray parseArray(String json) {
        if (!StringUtils.hasText(json)) {
            return new JSONArray();
        }
        try {
            JSONArray arr = JSON.parseArray(json);
            return arr == null ? new JSONArray() : arr;
        } catch (Exception e) {
            log.warn("麻醉JSON解析失败, 按空数组处理: {}", e.getMessage());
            return new JSONArray();
        }
    }
}
