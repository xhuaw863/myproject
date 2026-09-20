# -*- coding: utf-8 -*-
"""武汉市全民健康信息平台数据元值域代码规范 抽取工具。

从 "字典标准/武汉市全民健康信息平台医疗服务数据标准20240905_V1 - (20250326修订)/
武汉市全民健康信息平台数据元值域代码规范20240905_V1 - (20250326修订).docx"
抽取全部值域代码表, 生成 classpath 种子文件 seed/std_whvalue_code.tsv(首行表头),
供 StdDictImportService 的种子分支(表头驱动)导入 std_whvalue_code 表。

抽取方法(文档顺序 + 标题关联):
  - 按 body 子元素文档顺序遍历: Heading 2=章(chapter_name), Heading 3=代码表标题;
  - Heading 3 形如 "<代码> <名称>"(代码与名称间可无空格), 代码前缀支持
    CT/CV/WHCT/WHCV/WHDB/WH<数字>/GB/GB/T/WHGB/T 等; 解析出 dict_code 与 dict_name;
  - 每个 <w:tbl> 归属最近一个 Heading 3; 列角色 col0=值, col1=值含义, col2..=说明(合并 remark);
  - 跳过表内重复表头行与空行; 无内联表的标题(引用外部大表)自然无行。

依赖: pip install python-docx
用法: python tools/extract_wh_value.py
输出: yb-interface/src/main/resources/seed/std_whvalue_code.tsv 及 tools/extract_wh_value.out(报告)
"""
import os
import re
import docx
from docx.table import Table
from docx.text.paragraph import Paragraph

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)  # 仓库根目录
BASE = os.path.join(ROOT, "字典标准", "武汉市全民健康信息平台医疗服务数据标准20240905_V1 - (20250326修订)")
SRC = os.path.join(BASE, "武汉市全民健康信息平台数据元值域代码规范20240905_V1 - (20250326修订).docx")
SEED = os.path.join(ROOT, "yb-interface", "src", "main", "resources", "seed", "std_whvalue_code.tsv")
OUT = os.path.join(HERE, "extract_wh_value.out")

# 标题3 前缀代码: WHGB/T…, GB/T…, GB…, WHCT/WHCV/WHDB/MD…, CT/CV/DB…, WH<数字>…
CODE = re.compile(r'^((?:WH)?(?:GB/T|GB)\s?\d[\d./\-]*|(?:WH)?[A-Z]{2,}\d[\d.\-]*|WH\d[\d.\-]*)\s*(.+)$')
HDR_CODE = {'值', '代码', '值(国标)', '值（国标）'}
HDR_NAME_KEY = ('含义', '名称')


def clean(s):
    if s is None:
        return ''
    return re.sub(r'\s+', ' ', s.replace('\n', ' ').replace('\t', ' ')).strip()


def parse_h3(text):
    """Heading 3 '<代码> <名称>' -> (dict_code, dict_name)。"""
    m = CODE.match(text.strip())
    if m:
        return clean(m.group(1)), clean(m.group(2))
    return '', clean(text)


def is_header_row(cells):
    c0 = clean(cells[0]) if cells else ''
    c1 = clean(cells[1]) if len(cells) > 1 else ''
    return c0 in HDR_CODE and any(k in c1 for k in HDR_NAME_KEY)


def extract():
    d = docx.Document(SRC)
    body = d.element.body
    chapter = ''
    cur = None  # (dict_code, dict_name)
    rows = []
    hdrs = {}
    tbl_total = 0
    tbl_with_rows = 0
    tbl_empty = []
    for child in body.iterchildren():
        tag = child.tag.split('}')[-1]
        if tag == 'p':
            p = Paragraph(child, d)
            st = p.style.name or ''
            t = p.text.strip()
            if not t or st.startswith('toc'):
                continue
            if st.startswith('Heading 2'):
                chapter = t
                continue
            if st.startswith('Heading 3'):
                cur = parse_h3(t)
        elif tag == 'tbl':
            tbl_total += 1
            tb = Table(child, d)
            trows = tb.rows
            if not trows:
                tbl_empty.append(tbl_total)
                continue
            hdrs[" | ".join(clean(c.text) for c in trows[0].cells)] = \
                hdrs.get(" | ".join(clean(c.text) for c in trows[0].cells), 0) + 1
            got = 0
            for ri, row in enumerate(trows):
                cells = [c.text for c in row.cells]
                # 仅按表头特征跳过(部分表无表头, 首行即数据, 不能一律跳 ri==0)
                if is_header_row(cells):
                    continue
                vc = clean(cells[0]) if cells else ''
                vn = clean(cells[1]) if len(cells) > 1 else ''
                if not vc and not vn:
                    continue
                remark = ' '.join(x for x in (clean(c) for c in cells[2:]) if x)
                dcode, dname = cur if cur else ('', '')
                rows.append((chapter, dcode, dname, vc, vn, remark))
                got += 1
            if got:
                tbl_with_rows += 1
            else:
                tbl_empty.append(tbl_total)
    return rows, tbl_total, tbl_with_rows, tbl_empty, hdrs


def main():
    rows, tbl_total, tbl_with_rows, tbl_empty, hdrs = extract()
    kept = [r for r in rows if r[2]]
    dropped = len(rows) - len(kept)
    dicts = {}
    for r in kept:
        dicts[(r[1], r[2])] = dicts.get((r[1], r[2]), 0) + 1
    no_code = sum(1 for r in kept if not r[3])
    lines = []
    lines.append("tables=%d tables_with_rows=%d tables_empty=%s" % (tbl_total, tbl_with_rows, tbl_empty))
    lines.append("header signatures: " + " ;; ".join("%s x%d" % (k, v) for k, v in hdrs.items()))
    lines.append("rows=%d kept=%d dropped_nodict=%d" % (len(rows), len(kept), dropped))
    lines.append("distinct_dict=%d rows_empty_valcode=%d" % (len(dicts), no_code))
    os.makedirs(os.path.dirname(SEED), exist_ok=True)
    with open(SEED, 'w', encoding='utf-8', newline='') as f:
        f.write("chapter_name\tdict_code\tdict_name\tval_code\tval_name\tremark\n")
        for r in kept:
            f.write("\t".join(r) + "\n")
    lines.append("seed written: %s (%d data rows)" % (SEED, len(kept)))
    lines.append("--- dict sample (first 15) ---")
    for (code, name), n in list(dicts.items())[:15]:
        lines.append("  %s %s = %d rows" % (code, name, n))
    with open(OUT, 'w', encoding='utf-8') as f:
        f.write("\n".join(lines))
    print("\n".join(lines))


if __name__ == '__main__':
    main()
