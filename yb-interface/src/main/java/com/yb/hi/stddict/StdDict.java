package com.yb.hi.stddict;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * 一类标准字典的定义: 目标表 + 若干数据来源 + 统一查询映射。
 */
@Getter
public class StdDict {

    /** 字典标识(接口路径 type) */
    private final String key;
    /** 目标表名 */
    private final String table;
    /** 字典中文名称 */
    private final String name;
    /** 字典标准类型(按来源分3类): 医保字典 / 国家临床标准 / 中医标准 */
    private String stdType = "医保字典";
    /** 来源文档(哪一份规范/数据库的标准) */
    private String srcDoc = "";
    /** 种子导入版本号(仅无 xlsx 源、走 seed TSV 的字典使用) */
    private String seedVer = "V1.2.02";
    /** 种子导入来源标签(登记到 std_dict_version.sheet) */
    private String seedLabel = "接口规范第6章字典表";
    /** 数据来源(可多个, 合并入同一张表) */
    private final List<StdSource> sources = new ArrayList<>();

    // ---- 统一查询映射(供业务对照选择) ----
    private String codeCol;
    private String nameCol;
    private String specCol;
    private String extraCol;
    private String[] searchCols = new String[0];

    public StdDict(String key, String table, String name) {
        this.key = key;
        this.table = table;
        this.name = name;
    }

    public StdDict add(StdSource source) {
        this.sources.add(source);
        return this;
    }

    /** 设置字典标准类型(医保字典/国家临床标准/中医标准), 导入时写入每行 std_type 列 */
    public StdDict stdType(String stdType) {
        this.stdType = stdType;
        return this;
    }

    /** 设置来源文档名称, 导入时写入每行 src_doc 列 */
    public StdDict srcDoc(String srcDoc) {
        this.srcDoc = srcDoc;
        return this;
    }

    /** 设置种子导入的版本号与来源标签(仅 seed TSV 字典) */
    public StdDict seed(String seedVer, String seedLabel) {
        this.seedVer = seedVer;
        this.seedLabel = seedLabel;
        return this;
    }

    /** 设置统一查询映射: code/name/spec/extra 列 + 关键字模糊搜索列 */
    public StdDict query(String codeCol, String nameCol, String specCol, String extraCol, String... searchCols) {
        this.codeCol = codeCol;
        this.nameCol = nameCol;
        this.specCol = specCol;
        this.extraCol = extraCol;
        this.searchCols = searchCols;
        return this;
    }
}
