# -*- coding: utf-8 -*-
# 多角色 RBAC 改造 API 断言(临时验证脚本, 验证后删除)
import json
import sys
import urllib.request

BASE = "http://127.0.0.1:8080"
FAILS = []


def call(method, path, body=None, token=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode("utf-8") if body is not None else None
    try:
        with urllib.request.urlopen(req, data, timeout=15) as r:
            return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8"))
        except Exception:
            return e.code, {}


def check(name, cond, detail=""):
    print(("PASS " if cond else "FAIL ") + name + (("  | " + str(detail)) if detail else ""))
    if not cond:
        FAILS.append(name)


def keys_of(nodes, acc):
    for n in nodes or []:
        acc.add(n.get("menuKey"))
        keys_of(n.get("children"), acc)
    return acc


# 1) admin 登录(租户主账号)
st, r = call("POST", "/api/auth/login", {"tenantCode": "H42010000000", "username": "admin", "password": "admin123"})
check("admin login", st == 200 and r.get("code") == 0, r.get("msg"))
tok_a = r["data"]["token"]
check("admin resp.roles=[ADMIN]", r["data"].get("roles") == ["ADMIN"], r["data"].get("roles"))
check("admin roleName=系统管理员", r["data"].get("roleName") == "系统管理员", r["data"].get("roleName"))

# 2) admin 菜单回归: 全量医院菜单, 排除平台级 hospital-manage / std-dict-import
st, r = call("GET", "/api/auth/menus", token=tok_a)
ka = keys_of(r.get("data"), set())
check("admin menus: 含 dashboard/doctor-ws/dispense/org-manage",
      {"dashboard", "doctor-ws", "dispense", "org-manage"} <= ka, sorted(ka)[:10])
check("admin menus: 排除 hospital-manage/std-dict-import/area-code",
      not ({"hospital-manage", "std-dict-import", "area-code"} & ka))
check("admin menus: 牵头可见 community-dict", "community-dict" in ka)

# 3) 找 D002 并重置密码 + 配多角色 [DOCTOR(3), PHARMACIST(4), ADMIN(1)]
st, r = call("GET", "/api/sys/user/list", token=tok_a)
dr = [u for u in r["data"] if u["username"] == "D002"]
check("D002 exists", bool(dr), dr[0]["id"] if dr else "")
if not dr:
    sys.exit(1)
u0 = dr[0]
print("D002 before: roleId=%s role=%s roleIds=%s" % (u0.get("roleId"), u0.get("role"), u0.get("roleIds")))
st, r = call("POST", "/api/sys/user/%d/reset-password" % u0["id"], {"password": "Test@123456"}, token=tok_a)
check("reset D002 password", r.get("code") == 0, r.get("msg"))
form = {"id": u0["id"], "username": u0["username"], "realName": u0["realName"], "role": u0["role"],
        "roleId": u0["roleId"], "roleIds": [3, 4, 1], "orgId": u0["orgId"], "staffId": u0["staffId"],
        "deptId": u0["deptId"], "deptScope": u0.get("deptScope") or "", "phone": u0.get("phone"),
        "status": u0.get("status", 1), "loginOrgIds": u0.get("loginOrgIds") or []}
st, r = call("PUT", "/api/sys/user", form, token=tok_a)
check("save multi roles via API", r.get("code") == 0, r.get("msg"))
st, r = call("GET", "/api/sys/user/list", token=tok_a)
u1 = [u for u in r["data"] if u["username"] == "D002"][0]
check("list echo roleIds set={3,4,1}", sorted(u1.get("roleIds") or []) == [1, 3, 4], u1.get("roleIds"))
check("list echo primary roleId=3", u1.get("roleId") == 3 and u1.get("role") == "DOCTOR",
      (u1.get("roleId"), u1.get("role")))

# 4) D002 登录: 令牌 3 角色, 主角色置首, roleName 拼接
st, r = call("POST", "/api/auth/login", {"tenantCode": "H42010000000", "username": "D002", "password": "Test@123456"})
check("D002 login", r.get("code") == 0, r.get("msg"))
tok_d = r["data"]["token"]
check("token roles set & first DOCTOR", sorted(r["data"].get("roles") or []) == sorted(["DOCTOR", "ADMIN", "PHARMACIST"]) and r["data"].get("roles")[0] == "DOCTOR", r["data"].get("roles"))
check("roleName joined ' / '", " / " in (r["data"].get("roleName") or ""), r["data"].get("roleName"))

# 5) 菜单并集: 医生站(DOCTOR) + 药房(PHARMACIST) + 医共体管理(ADMIN)
st, r = call("GET", "/api/auth/menus", token=tok_d)
kd = keys_of(r.get("data"), set())
check("union: doctor-ws(DOCTOR)", "doctor-ws" in kd)
check("union: dispense(PHARMACIST)", "dispense" in kd)
check("union: org-manage & role-manage(ADMIN)", {"org-manage", "role-manage"} <= kd)
check("union: 仍排除 hospital-manage(各档位排除集)", "hospital-manage" not in kd)
check("union 菜单数 > admin单角色数-5(并集显著)", len(kd) >= len(ka) - 3, (len(kd), len(ka)))

# 6) 守卫档位: requireSuper 仍 403; requireAdmin 档放行
st, r = call("GET", "/api/sys/tenant/list", token=tok_d)
check("SUPER_ADMIN-only API -> 403", r.get("code") == 403 or st == 403, (st, r.get("code"), r.get("msg")))
st, r = call("GET", "/api/sys/role/list", token=tok_d)
check("requireAdmin API(role/list) 放行", r.get("code") == 0, r.get("msg"))
st, r = call("GET", "/api/sys/menu/tree", token=tok_d)
check("requireAdmin API(menu/tree) 放行", r.get("code") == 0, r.get("msg"))

# 7) switchOrg 后 roles 保留: 确保 D002 有一个成员机构可登录, 再切换
st, r = call("GET", "/api/sys/org/tree", token=tok_a)
flat = []
def walk(ns):
    for n in ns or []:
        flat.append(n.get("id") or n.get("orgId"))
        walk(n.get("children"))
walk(r.get("data"))
other = [x for x in flat if x and x != u1["orgId"]]
if other and len([o for o in (u1.get("loginOrgIds") or []) if o != u1["orgId"]]) == 0:
    form2 = dict(form)
    form2["loginOrgIds"] = [u1["orgId"], other[0]]
    st, r = call("PUT", "/api/sys/user", form2, token=tok_a)
    check("setup loginOrgIds for switch", r.get("code") == 0, r.get("msg"))
    st, r = call("POST", "/api/auth/login", {"tenantCode": "H42010000000", "username": "D002", "password": "Test@123456"})
    tok_d = r["data"]["token"]
    u1["loginOrgIds"] = [u1["orgId"], other[0]]
allowed = [o for o in (u1.get("loginOrgIds") or []) if o != u1["orgId"]]
if allowed:
    st, r = call("POST", "/api/auth/switch-org", {"orgId": allowed[0]}, token=tok_d)
    ok = r.get("code") == 0 and r.get("data")
    check("switchOrg ok", bool(ok), r.get("msg"))
    if ok:
        check("switchOrg roles kept", sorted(r["data"].get("roles") or []) == sorted(["DOCTOR", "ADMIN", "PHARMACIST"]), r["data"].get("roles"))
        check("switchOrg leadOrg present", "leadOrg" in r["data"], r["data"].get("leadOrg"))
        tok_d2 = r["data"]["token"]
        st, r2 = call("GET", "/api/auth/menus", token=tok_d2)
        kd2 = keys_of(r2.get("data"), set())
        check("switchOrg after non-lead: community-dict removed", ("community-dict" in kd2) == (r["data"].get("leadOrg") is True), (kd2 and "community-dict" in kd2, r["data"].get("leadOrg")))
else:
    print("SKIP switchOrg (D002 无可切换机构)")

# 8) 结果汇总
print("=" * 40)
print("FAILED: %s" % (FAILS if FAILS else "none"))
sys.exit(1 if FAILS else 0)

