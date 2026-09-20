# -*- coding: utf-8 -*-
"""
extract_cv_dict.py — 医保字典值域代码抽取工具

从《湖北省医疗保障信息平台定点医药机构接口规范V1.2.02》PDF 第6章「字典表」
抽取全部 (字典类型代码, 字典类型名称, 国家字典值代码, 国家字典值名称) 四元组,
生成规范种子文件 src/main/resources/seed/std_cv_code.tsv, 供 Java 导入器(cv_code)加载。

第6章为一张跨页的大表(合并单元格): 字典类型代码/名称仅出现在每组首行,
后续行为空需前向填充。本脚本用 pdfplumber 的表格线检测逐页解析并 forward-fill。

依赖: pip install pdfplumber
用法: python tools/extract_cv_dict.py [PDF绝对路径]
PDF 更新后重跑本脚本即可再生成种子文件; 导入为幂等(TRUNCATE 后全量重写)。
"""
import sys, io, os, json
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
import pdfplumber

# 项目根 = 本文件上级(tools/)的上级
PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_PDF = r"D:/study/ybtest/湖北省医疗保障信息平台定点医药机构接口规范V1.2.02.pdf"
OUT_TSV = os.path.join(PROJECT_ROOT, "src/main/resources/seed/std_cv_code.tsv")

# 第6章页范围(0基): 书签定位「第6章 字典表」=1169, 「第7章」=1505
START, END = 1169, 1504
HEADER0 = "字典类型代码"


def clean(x):
    if x is None:
        return ""
    return str(x).replace("\n", "").replace("\r", "").strip()


def extract(pdf_path):
    rows, seen = [], set()
    cur_code = cur_name = ""
    with pdfplumber.open(pdf_path) as pdf:
        hi = min(END, len(pdf.pages) - 1)
        for i in range(START, hi + 1):
            txt = pdf.pages[i].extract_text() or ""
            if ("第 7 章" in txt) or ("第7章" in txt):
                break
            for tb in pdf.pages[i].extract_tables():
                if not tb or 4 not in set(len(r) for r in tb):
                    continue
                for r in tb:
                    if len(r) != 4:
                        continue
                    c0, c1, c2, c3 = clean(r[0]), clean(r[1]), clean(r[2]), clean(r[3])
                    if c0 == HEADER0 or not (c0 or c1 or c2 or c3):
                        continue
                    if c0:
                        cur_code = c0
                    if c1:
                        cur_name = c1
                    if not c2 and not c3:
                        continue
                    key = (cur_code, c2, c3)
                    if key in seen:
                        continue
                    seen.add(key)
                    rows.append((cur_code, cur_name, c2, c3))
    return rows


def main():
    pdf_path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_PDF
    rows = extract(pdf_path)
    os.makedirs(os.path.dirname(OUT_TSV), exist_ok=True)
    with open(OUT_TSV, "w", encoding="utf-8", newline="\n") as f:
        for dc, dn, vc, vn in rows:
            f.write(f"{dc}\t{dn}\t{vc}\t{vn}\n")
    types = []
    stat = {}
    for dc, dn, vc, vn in rows:
        if dc not in stat:
            stat[dc] = {"name": dn, "count": 0}
            types.append(dc)
        stat[dc]["count"] += 1
    print(f"PDF: {pdf_path}")
    print(f"OUT: {OUT_TSV}")
    print(f"TOTAL ROWS: {len(rows)}  DISTINCT TYPES: {len(types)}")


if __name__ == "__main__":
    main()
