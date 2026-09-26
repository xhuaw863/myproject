# -*- coding: utf-8 -*-
"""侦察: 实体真实路径 + lombok 形态 + 待改文件确认(临时脚本)"""
import io
import os

BASE = r'D:\study\ybtest\yb-interface\src\main\java\com\yb\hi'

targets = ['HisStaff', 'HisDept', 'HisDrugCatalog', 'HisChargeItem', 'HisConsCatalog',
           'HisMedDict', 'SysOrg', 'HisPatient', 'HisRegistration', 'AreaCode',
           'MedServiceCatalog', 'ConsumableCatalog', 'DiseaseCatalog', 'DrugCatalog']

found = {}
for root, _dirs, files in os.walk(BASE):
    for fn in files:
        if not fn.endswith('.java'):
            continue
        name = fn[:-5]
        if name in targets:
            p = os.path.join(root, fn)
            text = io.open(p, encoding='utf-8', errors='ignore').read()
            head = [l.strip() for l in text.splitlines()[:40]
                    if l.strip().startswith('@') or 'class ' in l][:6]
            fields = [l.strip()[:80] for l in text.splitlines() if l.strip().startswith('private ')]
            found[name] = (p.replace(BASE, ''), head, len(fields))

for t in targets:
    if t in found:
        p, head, nf = found[t]
        print('==', t, p, 'fields:', nf)
        for h in head:
            print('   ', h)
    else:
        print('==', t, 'NOT FOUND')

# StdDictImportService 的导入完成挂点
p2 = os.path.join(BASE, 'stddict', 'StdDictImportService.java')
print('\nStdDictImportService exists:', os.path.exists(p2))
if os.path.exists(p2):
    s2 = io.open(p2, encoding='utf-8', errors='ignore').read()
    for i, l in enumerate(s2.splitlines(), 1):
        if 'void importByKey' in l or 'private void doImport' in l or 'importFromSeed' in l:
            print('   ', i, l.strip()[:100])

# StdDictQueryService / Maintain 实际路径
for root, _dirs, files in os.walk(BASE):
    for fn in files:
        if fn in ('StdDictQueryService.java', 'StdDictMaintainService.java', 'DictDataBackfill.java',
                  'CommunityDictImportService.java', 'SysOrgService.java'):
            print('LOC:', os.path.join(root, fn).replace(BASE, ''))
