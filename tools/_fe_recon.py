# -*- coding: utf-8 -*-
import io, os, re
D = r'D:\study\ybtest\yb-interface\src\main\resources\static\js\views'
pats = {
    'filterable_select': re.compile(r'el-select[^>]*filterable'),
    'filter_method': re.compile(r'filter-method'),
    'placeholder': re.compile(r'placeholder=["\'][^"\']*搜索[^"\']*["\']|placeholder=["\'][^"\']*编码[^"\']*["\']|placeholder=["\'][^"\']*名称[^"\']*["\']|placeholder=["\'][^"\']*输入[^"\']*["\']'),
    'dept_kw': re.compile(r'filteredTree|filterText|filterNode|deptFilterOptions'),
}
for fn in sorted(os.listdir(D)):
    if not fn.endswith('.js'):
        continue
    L = io.open(os.path.join(D, fn), encoding='utf-8', errors='ignore').read().splitlines()
    hits = []
    for i, l in enumerate(L, 1):
        for name, p in pats.items():
            if p.search(l):
                hits.append((i, name, l.strip()[:120]))
    if hits:
        print('#####', fn, 'total', len(L))
        for i, name, s in hits:
            print('  ', i, name, s)
