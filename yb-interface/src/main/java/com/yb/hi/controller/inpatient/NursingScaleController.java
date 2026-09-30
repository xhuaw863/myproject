package com.yb.hi.controller.inpatient;

import com.yb.hi.dto.inpatient.NursingAssessmentDTO;
import com.yb.hi.entity.inpatient.HisInpNursingRecord;
import com.yb.hi.entity.inpatient.HisNursingScaleDef;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.inpatient.NursingScaleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 护理评估量表接口(护士站): 量表定义读取 + 量表评估执行 + 评估历史/评分趋势。
 * 评估落 his_inp_nursing_record(record_type=2): scale_score 总分, scale_detail 各维度得分 JSON,
 * content 快照 {score, scaleCode, scaleName, level, color, answers};
 * 评分命中护理计划模板触发区间时自动创建计划实例并回写 plan_template_id。
 */
@RestController
@RequestMapping("/api/his/inp/nursing-scale")
public class NursingScaleController {

    private final NursingScaleService scaleService;

    public NursingScaleController(NursingScaleService scaleService) {
        this.scaleService = scaleService;
    }

    /** 量表定义列表(全部启用量表: 含 dimensions 维度定义与 scoreInterpretation 分级映射)。 */
    @GetMapping("/list")
    public R<List<HisNursingScaleDef>> list() {
        return R.ok(scaleService.listScales());
    }

    /** 量表详情(含维度定义/分级映射), 供评估表单渲染。 */
    @GetMapping("/{scaleCode}")
    public R<HisNursingScaleDef> detail(@PathVariable String scaleCode) {
        return R.ok(scaleService.getScale(scaleCode));
    }

    /**
     * 执行评估: scaleDetail 传各维度答案(选项分值/选项标签/多选数组, 如
     * {"sensory":1,"riskFactors":["卧床>72h","恶性肿瘤"]}), 服务端计算总分与风险分级;
     * 评分命中计划模板触发区间时自动创建护理计划实例。
     */
    @PostMapping("/assess")
    public R<HisInpNursingRecord> assess(@RequestBody NursingAssessmentDTO dto) {
        return R.ok(scaleService.assess(dto));
    }

    /** 评估历史(按就诊, 可选量表编码筛选, 时间倒序)。 */
    @GetMapping("/history")
    public R<List<HisInpNursingRecord>> history(@RequestParam Long visitId,
                                                @RequestParam(required = false) String scaleCode) {
        return R.ok(scaleService.getAssessmentHistory(visitId, scaleCode));
    }

    /** 评分趋势(time+score 升序列表, 前端折线图数据源)。 */
    @GetMapping("/trend")
    public R<List<Map<String, Object>>> trend(@RequestParam Long visitId,
                                              @RequestParam(required = false) String scaleCode) {
        return R.ok(scaleService.getScoreTrend(visitId, scaleCode));
    }
}
