package com.yb.hi.controller.emr;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.entity.inpatient.HisEmrDataset;
import com.yb.hi.entity.inpatient.HisEmrDatasetElement;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.emr.EmrDatasetService;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 病历数据集接口: "章节→小节→数据元"三级结构维护(数据集列表/详情树/保存/删除/克隆, 数据元增删排序)。
 * 表 his_emr_dataset / his_emr_dataset_element 由 DictSchemaMigration 启动期幂等建出;
 * 写操作仅牵头机构管理员(服务层 OrgAccessGuard 守卫), 读受租户插件隔离。
 */
@RestController
@RequestMapping("/api/his/emr/dataset")
public class EmrDatasetController {

    private final EmrDatasetService datasetService;

    public EmrDatasetController(EmrDatasetService datasetService) {
        this.datasetService = datasetService;
    }

    /** 数据集分页列表(keyword 模糊匹配编码/名称; scope: 0全部 1住院 2门诊 3护理) */
    @GetMapping("/list")
    public R<IPage<HisEmrDataset>> list(@RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) Integer scope,
                                        @RequestParam(defaultValue = "1") long page,
                                        @RequestParam(defaultValue = "20") long size) {
        return datasetService.list(keyword, scope, page, size);
    }

    /** 数据集详情(含 章节→小节→数据元 树) */
    @GetMapping("/{id}")
    public R<Map<String, Object>> get(@PathVariable Long id) {
        return datasetService.get(id);
    }

    /** 新建/更新数据集(id 空=新建; 仅牵头机构管理员) */
    @PostMapping("/save")
    public R<HisEmrDataset> save(@RequestBody HisEmrDataset dataset) {
        return datasetService.save(dataset);
    }

    /** 删除数据集(逻辑删除, 级联其数据元; 仅牵头机构管理员) */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        return datasetService.delete(id);
    }

    /** 数据集全部数据元(按章节→小节分组) */
    @GetMapping("/{id}/elements")
    public R<List<Map<String, Object>>> listElements(@PathVariable Long id) {
        return datasetService.listElements(id);
    }

    /** 新建/更新数据元(id 空=新建; 仅牵头机构管理员) */
    @PostMapping("/element/save")
    public R<HisEmrDatasetElement> saveElement(@RequestBody HisEmrDatasetElement element) {
        return datasetService.saveElement(element);
    }

    /** 删除数据元(逻辑删除; 仅牵头机构管理员) */
    @DeleteMapping("/element/{id}")
    public R<Void> deleteElement(@PathVariable Long id) {
        return datasetService.deleteElement(id);
    }

    /** 批量更新数据元排序: body=[{id, sortNo}, ...](仅牵头机构管理员) */
    @PostMapping("/element/reorder")
    public R<Void> reorder(@RequestBody List<Map<String, Object>> items) {
        return datasetService.reorderElements(items);
    }

    /** 克隆数据集(深拷贝全部数据元); body 可选 {newCode, newName}(仅牵头机构管理员) */
    @PostMapping("/{id}/clone")
    public R<HisEmrDataset> clone(@PathVariable Long id,
                                  @RequestBody(required = false) Map<String, Object> body) {
        String newCode = body == null || body.get("newCode") == null ? null : String.valueOf(body.get("newCode"));
        String newName = body == null || body.get("newName") == null ? null : String.valueOf(body.get("newName"));
        return datasetService.cloneDataset(id, newCode, newName);
    }
}
