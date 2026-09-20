package com.yb.hi.stddict;

import lombok.Getter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 标准字典的单个数据来源(一个 xlsx 文件中的一个 sheet)。
 * 采用"列索引 -> 数据库列"的映射方式, 不依赖表头文字, 对全角括号/换行表头更健壮。
 */
@Getter
public class StdSource {

    /** 相对 std-dict.base-path 的文件路径 */
    private final String relPath;
    /** sheet 序号(0基), 主数据表均为固定序号, 避免sheet名含全角括号匹配问题 */
    private final int sheetNo;
    /** 表头行数(数据从 headRow+1 行开始), 默认1 */
    private int headRow = 1;

    /** 数据库列名(与 srcIdx 一一对应, 取自源sheet的列) */
    private final List<String> dbCols = new ArrayList<>();
    /** 源sheet列索引(0基) */
    private final List<Integer> srcIdx = new ArrayList<>();
    /** 常量列(如 ver / src / src_sheet / map_type), 有序 */
    private final Map<String, String> consts = new LinkedHashMap<>();

    public StdSource(String relPath, int sheetNo) {
        this.relPath = relPath;
        this.sheetNo = sheetNo;
    }

    /** 表头行数 */
    public StdSource head(int headRow) {
        this.headRow = headRow;
        return this;
    }

    /** 映射: 源sheet第 idx 列 -> 数据库列 db */
    public StdSource col(String db, int idx) {
        this.dbCols.add(db);
        this.srcIdx.add(idx);
        return this;
    }

    /** 常量列: 数据库列 db 固定取值 val */
    public StdSource cst(String db, String val) {
        this.consts.put(db, val);
        return this;
    }
}
