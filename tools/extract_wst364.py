# -*- coding: utf-8 -*-
"""WS/T 364—2023 卫生健康信息数据元值域代码 抽取工具。

从"字典标准/WST-(363-364)-2023卫生健康信息数据元目录与值域代码"目录下各分册 PDF
抽取全部值域代码表(CV), 生成 classpath 种子文件 seed/std_wst364_code.tsv(首行表头),
供 StdDictImportService 的种子分支(表头驱动)导入 std_wst364_code 表。

抽取方法(表格法 + 文本兜底):
  - pdfplumber.find_tables() 检测表头含"值/值含义"的值域表;
  - 题注「表N CVxxxx 名称」与表格关联采用混合法:
      * 本页 题注数==值域表数 → 按 top 顺序 1:1 配对(修正题注被排版挤到表格下方的异常);
      * 否则 → 就近取表格上方题注(position), 并在页尾把"位于最后一个表格之下、其表体在
        下一页"的题注 carry-forward 到下一页(修正题注在页底、表在次页顶的情况);
  - 对仍未覆盖的 CV 用文本行兜底(ROW 正则)。
  - 丢弃无 CV 标识的行(总则中的示例表, 非正式字典)。

依赖: pip install pdfplumber
用法: python tools/extract_wst364.py
输出: yb-interface/src/main/resources/seed/std_wst364_code.tsv 及 tools/extract_wst364.out(覆盖率报告)
"""
import os
import re
from collections import OrderedDict
import pdfplumber

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)  # 仓库根目录
BASE = os.path.join(ROOT, "字典标准", "WST-(363-364)-2023卫生健康信息数据元目录与值域代码")
SEED = os.path.join(ROOT, "yb-interface", "src", "main", "resources", "seed", "std_wst364_code.tsv")
OUT = os.path.join(HERE, "extract_wst364.out")

CAP = re.compile(r'^表\s*\d+\s*(CV[0-9A-Za-z.]+)\s*(.+)$')
PART = re.compile(r'第(\d+)部分[:：]?\s*(.*)$')
# 停止行: 页眉/续表/纯页码/章节号(必须带小数点, 避免误杀 '1 精神分裂症' 这类值行)
STOP = re.compile(r'^(WS/T|_\d|_+$|本标准|续表|表\s*\d+\s*续|^\d+$|\d+\.\d+\s|附\s*录|前\s*言|参\s*考)')
ROW = re.compile(r'^([0-9A-Za-z]{1,12})\s+(.+)$')


def clean(s):
    if s is None:
        return ''
    return re.sub(r'\s+', ' ', str(s).replace('\u2002', ' ')).strip()


def norm(fn):
    return fn.replace('\u2002', '').replace(' ', '')


def parse_part(fn):
    m = PART.search(norm(fn).replace('.pdf', ''))
    return (m.group(1), m.group(2).strip()) if m else ('', '')


def _parse_val_table(tb):
    """解析一个表格: 若为值域表(表头含'值'与'值含义')返回 (top, [(vc,vn,rk),...]), 否则 None。"""
    data = tb.extract()
    if not data:
        return None
    head = [clean(c) for c in data[0]]
    is_val = any(c == '值' for c in head) and any('值含义' in c for c in head)
    if not is_val:
        return None
    body = []
    for r in data[1:]:
        cells = [clean(c) for c in r]
        if not cells or not cells[0]:
            continue
        if cells[0] == '值' or any('值含义' in c for c in cells[:2]):
            continue  # 跨页重复表头
        vc = cells[0]
        vn = cells[1] if len(cells) > 1 else ''
        rk = cells[2] if len(cells) > 2 else ''
        if not vn and not rk:
            continue
        body.append((vc, vn, rk))
    return (tb.bbox[1], body)


def extract(path, part_no, part_name):
    """返回 (rows, all_captions, covered_cv). rows: (cv_code,cv_name,val_code,val_name,remark)"""
    rows = []
    all_caps = []          # (cv_code, cv_name)
    covered = set()        # 有数据行的 cv_code
    last_cap = (None, None)
    with pdfplumber.open(path) as pdf:
        for page in pdf.pages:
            caps = []
            try:
                for ln in page.extract_text_lines():
                    m = CAP.match(ln['text'].strip())
                    if m:
                        cc, cn = clean(m.group(1)), clean(m.group(2))
                        caps.append((ln['top'], cc, cn))
                        all_caps.append((cc, cn))
            except Exception:
                pass
            caps.sort(key=lambda x: x[0])
            try:
                tables = page.find_tables()
            except Exception:
                tables = []
            vts = []
            for tb in tables:
                pv = _parse_val_table(tb)
                if pv is not None:
                    vts.append(pv)
            vts.sort(key=lambda x: x[0])

            if caps and len(caps) == len(vts):
                # 顺序 1:1 配对
                for i, (ttop, body) in enumerate(vts):
                    cc, cn = caps[i][1], caps[i][2]
                    last_cap = (cc, cn)
                    for (vc, vn, rk) in body:
                        rows.append((cc, cn, vc, vn, rk))
                        covered.add(cc)
            else:
                # position + carry-forward
                for (ttop, body) in vts:
                    above = [c for c in caps if c[0] <= ttop + 2]
                    if above:
                        last_cap = (above[-1][1], above[-1][2])
                    cc, cn = last_cap
                    for (vc, vn, rk) in body:
                        rows.append((cc or '', cn or '', vc, vn, rk))
                        if cc:
                            covered.add(cc)
                if caps:
                    last_ttop = max(t[0] for t in vts) if vts else -1
                    trailing = [c for c in caps if c[0] > last_ttop]
                    if trailing:
                        last_cap = (trailing[-1][1], trailing[-1][2])
    return rows, all_caps, covered


def text_fallback(path, want_cvs):
    """对表格法未覆盖的 cv, 用文本行解析补齐(仅 值/值含义 两列)。"""
    out = []
    if not want_cvs:
        return out
    cur = (None, None)
    with pdfplumber.open(path) as pdf:
        for page in pdf.pages:
            try:
                lines = [clean(l['text']) for l in page.extract_text_lines()]
            except Exception:
                lines = (page.extract_text() or '').split('\n')
            for ln in lines:
                ln = ln.strip()
                if not ln:
                    continue
                m = CAP.match(ln)
                if m:
                    cur = (clean(m.group(1)), clean(m.group(2)))
                    continue
                if cur[0] in want_cvs:
                    if STOP.match(ln):
                        continue
                    rm = ROW.match(ln)
                    if rm:
                        out.append((cur[0], cur[1], clean(rm.group(1)), clean(rm.group(2)), ''))
    return out


def main():
    lines = []

    def w(s=''):
        lines.append(str(s))

    names = sorted(os.listdir(BASE))
    parts = []
    for n in names:
        nn = norm(n)
        if '值域代码' in nn and '第' in nn and nn.endswith('.pdf') and not nn.startswith('WST-364'):
            parts.append(n)
    w("值域代码分册数 = %d" % len(parts))

    all_rows = []
    seen = set()
    maxrk = 0
    maxvn = 0
    dropped_nocv = 0
    for fn in parts:
        pno, pname = parse_part(fn)
        path = os.path.join(BASE, fn)
        rows, all_caps, covered = extract(path, pno, pname)
        cap_cvs = list(OrderedDict.fromkeys([c[0] for c in all_caps]))
        missed = [c for c in cap_cvs if c and c not in covered]
        fb = text_fallback(path, set(missed)) if missed else []
        # 合并去重(丢弃无CV标识的行: 总则中的示例表, 非正式字典)
        part_rows = []
        for r in rows + fb:
            if not r[0]:
                dropped_nocv += 1
                continue
            k = (r[0], r[2], r[3])
            if k in seen:
                continue
            seen.add(k)
            part_rows.append((r[0], r[1], r[2], r[3], r[4], pno, pname))
            maxrk = max(maxrk, len(r[4]))
            maxvn = max(maxvn, len(r[3]))
        all_rows.extend(part_rows)
        w("part %-2s %-18s 题注CV=%-3d 命中=%-3d 漏=%-2d 兜底行=%-3d 本册行=%d" %
          (pno, pname[:18], len(cap_cvs), len(covered), len(missed), len(fb), len(part_rows)))
        if missed:
            w("      漏检CV(已兜底): %r" % (missed,))

    w("\n合计: 分册=%d  CV代码表(distinct)=%d  值行=%d  最长值含义=%d  最长说明=%d" %
      (len(parts), len(set(r[0] for r in all_rows if r[0])), len(all_rows), maxvn, maxrk))
    w("丢弃的无CV标识行(总则示例) = %d" % dropped_nocv)

    # 写种子 TSV(表头驱动: cv_code,cv_name,val_code,val_name,remark,part_no,part_name)
    with open(SEED, 'w', encoding='utf-8', newline='\n') as f:
        f.write("cv_code\tcv_name\tval_code\tval_name\tremark\tpart_no\tpart_name\n")
        for r in all_rows:
            f.write("\t".join(x.replace('\t', ' ').replace('\n', ' ') for x in r) + "\n")
    w("\nTSV written: %s (%d 行 + 表头)" % (SEED, len(all_rows)))

    with open(OUT, 'w', encoding='utf-8') as f:
        f.write("\n".join(lines))
    print("written", OUT, "rows", len(all_rows))


if __name__ == '__main__':
    main()
