# -*- coding: utf-8 -*-
"""
从已入库的 seed/std_msi_fin.tsv(医疗服务项目相关财务归集口径规范原样表行)中,
抽取三类费用分类的去重取值, 生成三张标准值域字典的种子 TSV:

  1. 病案首页费用分类 -> std_mr_cost_class.tsv (层级: 大类 -> 费用分项, 解析源文括号序号)
  2. 收费票据分类     -> std_invoice_class.tsv (扁平, 附对应会计科目分类 + 归集项目数)
  3. 会计科目分类     -> std_acct_class.tsv    (扁平, 附对应收费票据分类 + 归集项目数)

设计原则(与既有 std_* 字典保持一致):
  - 单一权威源为 std_msi_fin(财务归集口径规范)。std_msi_nat 的对应列含 PDF 抽取噪声
    (如 "1%体表面积"/"次"/截断值), 故不采用, 仅做去重/规范化, 不做跨源合并。
  - 输出表头只含数据列; ver/std_type/src_doc 由 StdDictImportService.importFromSeed 注入。
  - 收费票据分类 <-> 会计科目分类 在源数据中逐行成对出现(1:1), 对应关系由数据统计得出。

用法: python extract_msi_fin_classes.py
"""
import csv
import os
import re
import collections

# ---- 路径: 脚本位于 yb-interface/tools/, 种子位于 ../src/main/resources/seed/ ----
HERE = os.path.dirname(os.path.abspath(__file__))
SEED_DIR = os.path.normpath(os.path.join(HERE, os.pardir, "src", "main", "resources", "seed"))
SRC = os.path.join(SEED_DIR, "std_msi_fin.tsv")

# 需过滤的表头重复行(PDF 表头被当作数据行抽出)
HEADER_DUPS = {"病案首页费用分类", "收费票据分类", "会计科目分类"}

# 病案首页费用分类解析: "3.治疗类：(10)手术治疗费" -> (3, 治疗类, 10, 手术治疗费)
MR_PAT = re.compile(r"^(\d+)\.([^：:]+)[：:](?:\((\d+)\))?(.+)$")

# 收费票据分类/会计科目分类的展示排序(按病案首页大类逻辑分组); 源文无编码, 编码为字典内序号
INVOICE_ORDER = [
    "诊察费", "床位费", "护理费",
    "检查费", "检查费-病理", "检查费-麻醉", "检查费-中医",
    "化验费",
    "治疗费", "治疗费-麻醉", "治疗费-中医",
    "手术费", "手术费-麻醉", "手术费-中医",
    "其他门诊/住院收费",
]


def norm(s):
    """去除全部空白(含全角空格), 便于合并 PDF 抽取产生的多余空格。"""
    if s is None:
        return ""
    return re.sub(r"[\s\u3000]+", "", s)


def load_rows():
    with open(SRC, encoding="utf-8") as f:
        r = csv.reader(f, delimiter="\t")
        header = next(r)
        rows = [row for row in r]
    return header, rows


def col_idx(header, name):
    return header.index(name)


def build_mr_cost_class(rows, header):
    """病案首页费用分类: 去重 + 解析大类/分项, 按 (大类序号, 分项序号, 分项名) 排序。"""
    i = col_idx(header, "mr_cost_class")
    raws = collections.OrderedDict()  # normalized raw -> True
    for row in rows:
        if len(row) <= i:
            continue
        v = norm(row[i])
        if not v or v in HEADER_DUPS:
            continue
        raws[v] = True

    out = []
    for raw in raws:
        m = MR_PAT.match(raw)
        if m:
            cat_no, cat_name, item_code, item_name = m.group(1), m.group(2), m.group(3) or "", m.group(4)
        else:
            # 兜底: 无法解析的保留原值, 归为未分类(正常数据不应出现)
            cat_no, cat_name, item_code, item_name = "", "未分类", "", raw
        out.append((cat_no, cat_name, item_code, item_name, raw))

    def sort_key(t):
        cat_no, _, item_code, item_name, _ = t
        return (
            int(cat_no) if cat_no.isdigit() else 999,
            int(item_code) if item_code.isdigit() else 999,
            item_name,
        )

    out.sort(key=sort_key)
    return out


def build_invoice_acct(rows, header):
    """收费票据分类 <-> 会计科目分类 逐行成对统计, 返回 (invoice_list, acct_list)。

    每个元素: (name, mapped_other, item_count)。对应关系取该分类下出现次数最多的搭档。
    """
    ii = col_idx(header, "invoice_class")
    ai = col_idx(header, "acct_class")

    inv_cnt = collections.Counter()
    acc_cnt = collections.Counter()
    pair = collections.Counter()  # (invoice, acct) -> count
    for row in rows:
        if len(row) <= max(ii, ai):
            continue
        inv = norm(row[ii])
        acc = norm(row[ai])
        if not inv or inv in HEADER_DUPS or not acc or acc in HEADER_DUPS:
            continue
        inv_cnt[inv] += 1
        acc_cnt[acc] += 1
        pair[(inv, acc)] += 1

    # invoice -> 最主要的 acct
    inv_to_acc = {}
    for inv in inv_cnt:
        best = max(
            [(c, acc) for (i, acc), c in pair.items() if i == inv],
            key=lambda x: x[0],
        )
        inv_to_acc[inv] = best[1]
    acc_to_inv = {}
    for acc in acc_cnt:
        best = max(
            [(c, inv) for (inv, a), c in pair.items() if a == acc],
            key=lambda x: x[0],
        )
        acc_to_inv[acc] = best[1]

    # 按预定义顺序排序, 未在列表中的追加(按名称)
    def order_names(names, priority):
        seen = set()
        ordered = [n for n in priority if n in names and not (n in seen or seen.add(n))]
        ordered += sorted(n for n in names if n not in seen)
        return ordered

    inv_names = order_names(set(inv_cnt), INVOICE_ORDER)
    inv_list = [(n, inv_to_acc.get(n, ""), inv_cnt[n]) for n in inv_names]

    # 会计科目分类沿用收费票据分类的顺序(1:1 对应), 保证两表编码一致
    acc_by_inv = {inv_to_acc.get(n, ""): n for n in inv_names}
    acc_names_ordered = [inv_to_acc.get(n, "") for n in inv_names if inv_to_acc.get(n)]
    acc_names_ordered += sorted(a for a in acc_cnt if a not in set(acc_names_ordered))
    acc_list = [(a, acc_to_inv.get(a, ""), acc_cnt[a]) for a in acc_names_ordered]

    return inv_list, acc_list


def write_tsv(path, header, rows):
    with open(path, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, delimiter="\t", lineterminator="\n")
        w.writerow(header)
        for r in rows:
            w.writerow(["" if c is None else str(c) for c in r])
    print("  写出 %s (%d 行)" % (os.path.basename(path), len(rows)))


def main():
    if not os.path.exists(SRC):
        raise SystemExit("源文件不存在: " + SRC)
    header, rows = load_rows()
    print("源: %s (%d 数据行)" % (os.path.basename(SRC), len(rows)))

    # 1) 病案首页费用分类(优先)
    mr = build_mr_cost_class(rows, header)
    write_tsv(
        os.path.join(SEED_DIR, "std_mr_cost_class.tsv"),
        ["cat_no", "cat_name", "item_code", "item_name", "raw_value"],
        mr,
    )

    # 2) 收费票据分类 / 3) 会计科目分类
    inv_list, acc_list = build_invoice_acct(rows, header)
    write_tsv(
        os.path.join(SEED_DIR, "std_invoice_class.tsv"),
        ["class_code", "class_name", "acct_class", "item_count"],
        [("%02d" % (i + 1), n, a, c) for i, (n, a, c) in enumerate(inv_list)],
    )
    write_tsv(
        os.path.join(SEED_DIR, "std_acct_class.tsv"),
        ["class_code", "class_name", "invoice_class", "item_count"],
        [("%02d" % (i + 1), n, iv, c) for i, (n, iv, c) in enumerate(acc_list)],
    )

    print("完成: 病案首页费用分类 %d 项, 收费票据分类 %d 项, 会计科目分类 %d 项"
          % (len(mr), len(inv_list), len(acc_list)))


if __name__ == "__main__":
    main()
