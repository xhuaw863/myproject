# -*- coding: utf-8 -*-
# 核对 static/js 源文件与 target/classes 副本是否一致(哈希), 不一致则提示需 Copy-Item
import io, os, hashlib
SRC = r'D:\study\ybtest\yb-interface\src\main\resources\static\js'
DST = r'D:\study\ybtest\yb-interface\target\classes\static\js'
def h(p):
    return hashlib.md5(io.open(p, 'rb').read()).hexdigest()
diff = []
for dp, _, fns in os.walk(SRC):
    for fn in fns:
        if not fn.endswith('.js'):
            continue
        s = os.path.join(dp, fn)
        d = os.path.join(DST, os.path.relpath(s, SRC))
        if not os.path.exists(d):
            diff.append(('MISSING', s))
        elif h(s) != h(d):
            diff.append(('DIFF', s))
for k, p in diff:
    print(k, os.path.relpath(p, SRC))
print('total need-sync:', len(diff))
