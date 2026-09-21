# -*- coding: utf-8 -*-
"""从 全国医疗服务项目技术规范(2023年版).xlsx 生成物价分类标准字典种子 + 回填分类码。

产物:
 1. seed/std_msi_cat.tsv: 3级分类(类/章/节), 列 cat_code/cat_name/parent_code/lv/item_count;
    编码沿用 xlsx 原生字母码(类=1字母, 章=2字母, 节=3字母; 区间码取首段如 KB-KC->KB);
    第四级组(仅9个)并入节; 区间横幅行(如 HLB-HL1, 其后同级标题首段相同)不入字典;
    附录表(行>=12840, 器械和器具等)不纳入。
 2. seed/std_msi_nat.tsv: 原有18列逐字节保留, 末尾追加第19列 cat_code
    (节级码优先, 无节标题时章级码; 附录项目为空)。

标题行约定: 表头行 col0='项目编码' 跳过(含表内重复表头); col0 单字母=类, 2字母/2字母区间=章,
3字母/3字母区间=节, 4位纯字母=组, 8位字母数字=项目行; 翻页标记行(类码重复且类名不变)不重置上下文。
"""
import os
import re
import openpyxl

PATH = r"D:\study\ybtest\字典标准\国家和湖北省医疗物价规范\全国医疗服务项目技术规范(2023年版).xlsx"
SEED = r"D:\study\ybtest\yb-interface\src\main\resources\seed"
APPENDIX_ROW = 12840
ITEM_RE = re.compile(r"^(?=.*\d)[A-Z0-9]{8}$")
RANGE_RE = re.compile(r"^([A-Z]{2,4})-[A-Z0-9]{2,4}$")


def cl(v):
    if v is None:
        return ""
    return re.sub(r"\s+", " ", str(v).replace("\r", "").replace("\n", "").replace("\t", " ")).strip()


def main():
    wb = openpyxl.load_workbook(PATH, read_only=True, data_only=True)
    ws = wb["Sheet1"]
    rows = []
    hdr = -1
    for i, row in enumerate(ws.iter_rows(values_only=True)):
        if hdr < 0:
            if row and cl(row[0]) == "项目编码":
                hdr = i
            continue
        if i + 1 >= APPENDIX_ROW:
            break
        code = cl(row[0]) if row and row[0] is not None else ""
        if not code or code == "项目编码":
            continue
        name = cl(row[2]) if len(row) > 2 else ""
        if not name or name == "项目名称(中文)":
            continue
        rows.append((code, name))
    wb.close()

    # 标题级: 1类 2章 3节 4组; 区间码取首段
    def level_of(code):
        m = RANGE_RE.match(code)
        base = m.group(1) if m else code
        if len(base) == 1 and base.isalpha():
            return 1, base, bool(m)
        if len(base) == 2:
            return 2, base, bool(m)
        if len(base) == 3:
            return 3, base, bool(m)
        if len(base) == 4 and base.isalpha():
            return 4, base, bool(m)
        return 0, base, bool(m)

    # 预判区间横幅: 区间标题且其后第一个同级标题首段相同 -> 横幅(不入字典/不占上下文)
    title_idx = [i for i, (c, n) in enumerate(rows) if not ITEM_RE.match(c)]
    banner = set()
    for pos, i in enumerate(title_idx):
        lv, base, is_range = level_of(rows[i][0])
        if not is_range or lv == 0:
            continue
        nxt = None
        for j in title_idx[pos + 1:]:
            lv2, base2, _ = level_of(rows[j][0])
            if lv2 == lv:
                nxt = base2
                break
        if nxt == base:
            banner.add(i)

    cur = [None, None, None, None]  # [code, name] x4
    cnt = {}                        # (lv1code,lv2code,lv3code) -> 项目数
    node = {}                       # (lv, code) -> [name, parent_code, 出现序]
    seq = [0]
    item_cat = {}                   # item_code -> cat_code
    for i, (code, name) in enumerate(rows):
        if ITEM_RE.match(code):
            if cur[2]:
                cc = cur[2][0]
            elif cur[1]:
                cc = cur[1][0]
            elif cur[0]:
                cc = cur[0][0]
            else:
                cc = ""
            item_cat[code] = cc
            key = tuple((c[0] if c else "") for c in cur[:3])
            cnt[key] = cnt.get(key, 0) + 1
            continue
        if i in banner:
            continue
        lv, base, _ = level_of(code)
        if lv == 1:
            if cur[0] and cur[0][1] == name:
                continue  # 翻页标记行
            cur = [(base, name), None, None, None]
            node.setdefault((1, base), [name, "", seq[0]])
        elif lv == 2:
            cur[1] = (base, name)
            cur[2] = cur[3] = None
            node.setdefault((2, base), [name, cur[0][0] if cur[0] else "", seq[0]])
        elif lv == 3:
            cur[2] = (base, name)
            cur[3] = None
            node.setdefault((3, base), [name, cur[1][0] if cur[1] else "", seq[0]])
        elif lv == 4:
            cur[3] = (base, name)  # 组: 仅占上下文, 项目数并入节
        seq[0] += 1

    # 节点项目数: 节=自身(含组); 章=下属节合计+章直属; 类=章合计
    sec_cnt = {}
    chap_cnt = {}
    cls_cnt = {}
    for (c1, c2, c3), n in cnt.items():
        if c3:
            sec_cnt[(c2, c3)] = sec_cnt.get((c2, c3), 0) + n
        if c2:
            chap_cnt[(c1, c2)] = chap_cnt.get((c1, c2), 0) + n
        if c1:
            cls_cnt[c1] = cls_cnt.get(c1, 0) + n

    # 按出现顺序输出3级行
    out_rows = []
    for (lv, code), (name, parent, s) in node.items():
        if lv == 1:
            n = cls_cnt.get(code, 0)
        elif lv == 2:
            n = chap_cnt.get((code[0], code), 0)
        else:
            n = sec_cnt.get((code[:2], code), 0)
        out_rows.append((lv, code, name, parent, n, s))
    out_rows.sort(key=lambda r: (r[0], r[5]))  # 级内按源表出现序=树序

    # 编码唯一性校验
    dup = {}
    for lv, code, name, parent, n, s in out_rows:
        dup.setdefault((lv, code), []).append(name)
    dups = {k: v for k, v in dup.items() if len(v) > 1}
    if dups:
        raise SystemExit("编码冲突: %s" % dups)

    cat_path = os.path.join(SEED, "std_msi_cat.tsv")
    with open(cat_path, "w", encoding="utf-8", newline="") as f:
        f.write("cat_code\tcat_name\tparent_code\tlv\titem_count\n")
        for lv, code, name, parent, n, s in out_rows:
            f.write("%s\t%s\t%s\t%d\t%d\n" % (code, name, parent, lv, n))

    # std_msi_nat.tsv 追加 cat_code 列(原有列逐字节保留)
    nat_path = os.path.join(SEED, "std_msi_nat.tsv")
    with open(nat_path, "r", encoding="utf-8") as f:
        lines = f.read().split("\n")
    if lines and lines[0].endswith("\tcat_code"):
        raise SystemExit("std_msi_nat.tsv 已含 cat_code 列, 拒绝重复追加")
    out = []
    filled = 0
    for li, line in enumerate(lines):
        if line == "":
            continue
        if li == 0:
            out.append(line + "\tcat_code")
            continue
        code = line.split("\t", 1)[0]
        cc = item_cat.get(code, "")
        if cc:
            filled += 1
        out.append(line + "\t" + cc)
    with open(nat_path, "w", encoding="utf-8", newline="") as f:
        f.write("\n".join(out) + "\n")

    n1 = len([r for r in out_rows if r[0] == 1])
    n2 = len([r for r in out_rows if r[0] == 2])
    n3 = len([r for r in out_rows if r[0] == 3])
    leaf_chap = len([r for r in out_rows if r[0] == 2 and not any(x[0] == 3 and x[3] == r[1] for x in out_rows)])
    print("std_msi_cat rows=%d (类%d 章%d[叶%d] 节%d); msi_nat cat_code 非空=%d / 总=%d"
          % (len(out_rows), n1, n2, leaf_chap, n3, filled, len(out) - 1))


if __name__ == "__main__":
    main()
