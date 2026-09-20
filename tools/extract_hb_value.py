# -*- coding: utf-8 -*-
"""湖北省健康医疗大数据采集规范--数据元值域代码 抽取工具。

从仓库根目录 "1.2_湖北省健康医疗大数据采集规范--数据元值域代码20240826.docx"
抽取全部值域代码表, 生成 classpath 种子文件 seed/std_hbvalue_code.tsv(首行表头),
供 StdDictImportService 的种子分支(表头驱动)导入 std_hbvalue_code 表。

抽取方法(文档顺序 + 节标题关联):
  - 按 body 子元素文档顺序遍历, 跟踪当前章(Heading 1)与当前节(文本匹配 ^\\d+\\.\\d+);
  - 每个 <w:tbl> 归属"最近一个 x.y 节标题"(可正确跳过 12-6…/遵循《…》这类非节段落);
  - 节标题解析: 节号 section_no、括号内代码表标识 dict_code(CV/GB/HBCV/CT/T-CIATCM…)、
    去掉节号/括号/☆■△✱ 符号后的 dict_name; 无括号时回退匹配尾部代码串;
  - 表内首行为表头, 列角色: col0=值/代码, col1=值含义/名称, col2..=说明/备注(合并为 remark);
  - 跳过表内重复表头行与空行。

依赖: pip install python-docx
用法: python tools/extract_hb_value.py
输出: yb-interface/src/main/resources/seed/std_hbvalue_code.tsv 及 tools/extract_hb_value.out(报告)
"""
import os
import re
import docx
from docx.table import Table
from docx.text.paragraph import Paragraph

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)  # 仓库根目录
SRC = os.path.join(ROOT, "1.2_湖北省健康医疗大数据采集规范--数据元值域代码20240826.docx")
SEED = os.path.join(ROOT, "yb-interface", "src", "main", "resources", "seed", "std_hbvalue_code.tsv")
OUT = os.path.join(HERE, "extract_hb_value.out")

SEC = re.compile(r'^(\d+\.\d+)\s*(.+)$')                       # x.y 节标题
PAREN = re.compile(r'[（(]([^（）()]*)[）)]')                    # 括号内代码表标识
TRAIL_CODE = re.compile(r'(GB/T?\s*\d[\d.]*-\d{4}|HBCV\d[\w.]*|CV\d[\w.]*|CT\d[\w.]*)\s*$')
SYMBOLS = '☆■△✱*＊◇○'
HDR_CODE = {'值', '代码', '值(国标)', '值（国标）'}
HDR_NAME_KEY = ('含义', '名称', '学历')


def clean(s):
    if s is None:
        return ''
    return re.sub(r'\s+', ' ', s.replace('\n', ' ').replace('\t', ' ')).strip()


def parse_heading(text):
    """解析 x.y 节标题 -> (section_no, dict_code, dict_name)。"""
    m = SEC.match(text)
    if not m:
        return None
    sec_no, rest = m.group(1), m.group(2)
    code = ''
    pm = PAREN.search(rest)
    if pm:
        code = clean(pm.group(1))
        rest = PAREN.sub('', rest, count=1)
    name = clean(rest)
    if not code:
        tm = TRAIL_CODE.search(name)
        if tm:
            code = clean(tm.group(1))
            name = clean(name[:tm.start()])
    name = name.strip(SYMBOLS).strip()
    return sec_no, code, name


def is_header_row(cells):
    c0 = clean(cells[0]) if cells else ''
    c1 = clean(cells[1]) if len(cells) > 1 else ''
    return c0 in HDR_CODE and any(k in c1 for k in HDR_NAME_KEY)


def extract():
    d = docx.Document(SRC)
    body = d.element.body
    chapter = ''
    section = None  # (sec_no, dict_code, dict_name)
    rows = []
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
            if st.startswith('Heading 1'):
                chapter = re.sub(r'^[\d.]+\s*', '', t).strip()
                continue
            ph = parse_heading(t)
            if ph:
                section = ph
        elif tag == 'tbl':
            tbl_total += 1
            tb = Table(child, d)
            trows = tb.rows
            if not trows:
                tbl_empty.append(tbl_total)
                continue
            got = 0
            for ri, row in enumerate(trows):
                cells = [c.text for c in row.cells]
                if ri == 0 or is_header_row(cells):
                    continue
                vc = clean(cells[0]) if cells else ''
                vn = clean(cells[1]) if len(cells) > 1 else ''
                if not vc and not vn:
                    continue
                remark = ' '.join(x for x in (clean(c) for c in cells[2:]) if x)
                sec_no, dcode, dname = section if section else ('', '', '')
                rows.append((sec_no, chapter, dcode, dname, vc, vn, remark))
                got += 1
            if got:
                tbl_with_rows += 1
            else:
                tbl_empty.append(tbl_total)
    return rows, tbl_total, tbl_with_rows, tbl_empty


def main():
    rows, tbl_total, tbl_with_rows, tbl_empty = extract()
    # 丢弃无节归属的行
    kept = [r for r in rows if r[3]]
    dropped = len(rows) - len(kept)
    dicts = {}
    for r in kept:
        dicts.setdefault((r[0], r[3]), 0)
        dicts[(r[0], r[3])] += 1
    no_code = sum(1 for r in kept if not r[4])
    lines = []
    lines.append("tables=%d tables_with_rows=%d tables_empty=%s" % (tbl_total, tbl_with_rows, tbl_empty))
    lines.append("rows=%d kept=%d dropped_nosection=%d" % (len(rows), len(kept), dropped))
    lines.append("distinct_dict=%d rows_empty_valcode=%d" % (len(dicts), no_code))
    os.makedirs(os.path.dirname(SEED), exist_ok=True)
    with open(SEED, 'w', encoding='utf-8', newline='') as f:
        f.write("section_no\tchapter_name\tdict_code\tdict_name\tval_code\tval_name\tremark\n")
        for r in kept:
            f.write("\t".join(r) + "\n")
    lines.append("seed written: %s (%d data rows)" % (SEED, len(kept)))
    lines.append("--- dict sample (first 15) ---")
    for (sec, name), n in list(dicts.items())[:15]:
        lines.append("  %s %s = %d rows" % (sec, name, n))
    with open(OUT, 'w', encoding='utf-8') as f:
        f.write("\n".join(lines))
    print("\n".join(lines))


if __name__ == '__main__':
    main()
