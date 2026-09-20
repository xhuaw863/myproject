import json
import urllib.request
import urllib.error
import pymysql

BASE = 'http://localhost:8080'


def call(method, path, token=None, body=None):
    data = json.dumps(body).encode('utf-8') if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, json.loads(resp.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        raw = e.read().decode('utf-8')
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw


st, r = call('POST', '/api/auth/login', body={
    'tenantCode': 'H42010000000', 'username': 'admin', 'password': 'admin123'})
tok = r['data']['token']

# read 孝昌县 divisions from area_code_2021
conn = pymysql.connect(host='localhost', port=3306, user='root', password='bsoft',
                       database='yb_interface', charset='utf8mb4')
cur = conn.cursor()
XC = 420921000000
cur.execute("SELECT code,name FROM area_code_2021 WHERE level=4 AND pcode=%s ORDER BY code LIMIT 3", (XC,))
towns = cur.fetchall()
town_villages = {}
for t in towns:
    cur.execute("SELECT code,name FROM area_code_2021 WHERE level=5 AND pcode=%s ORDER BY code LIMIT 3", (t[0],))
    town_villages[t[0]] = cur.fetchall()
conn.close()


def village_org_name(raw):
    if raw.endswith('居委会'):
        return raw[:-3] + '卫生室'
    if raw.endswith('村委会'):
        return raw[:-3] + '卫生室'
    return raw + '卫生室'


def find_org(nodes, code):
    for n in nodes:
        if n.get('orgCode') == code:
            return n
        f = find_org(n.get('children') or [], code)
        if f:
            return f
    return None


# 1) rename existing county org (测试医院 -> 孝昌县人民医院)
st, r = call('GET', '/api/sys/org/tree', tok)
root = (r.get('data') or [{}])[0]
root_id = root.get('id')
rename = {
    'id': root_id, 'orgCode': root.get('orgCode'), 'orgName': '孝昌县人民医院',
    'orgLevel': 1, 'parentId': 0, 'orgType': '综合医院',
    'fixmedinsCode': root.get('fixmedinsCode'), 'admvsCode': '420921',
    'leader': root.get('leader'), 'phone': root.get('phone'), 'address': root.get('address'),
    'sortNo': root.get('sortNo') or 0, 'status': 1
}
st, r = call('PUT', '/api/sys/org', tok, rename)
print('rename 测试医院->孝昌县人民医院:', st, r.get('code'), r.get('msg'))

# 2) add other two county hospitals
for name, code, otype, fix in (
        ('孝昌县中医院', 'XC420921-ZYY', '中医医院', 'H42092100002'),
        ('孝昌县妇幼保健院', 'XC420921-FBY', '妇幼保健院', 'H42092100003')):
    st, r = call('POST', '/api/sys/org', tok, {
        'orgCode': code, 'orgName': name, 'orgLevel': 1, 'parentId': 0, 'orgType': otype,
        'fixmedinsCode': fix, 'admvsCode': '420921', 'sortNo': 0, 'status': 1})
    print('create county %s: %s %s %s' % (name, st, r.get('code'), r.get('msg')))

# 3) three 卫生院 under 孝昌县人民医院 + 3 卫生室 each
for tcode, tname in towns:
    st, r = call('POST', '/api/sys/org', tok, {
        'orgCode': str(tcode), 'orgName': tname + '卫生院', 'orgLevel': 2, 'parentId': root_id,
        'orgType': '乡镇卫生院', 'admvsCode': '420921', 'sortNo': 0, 'status': 1})
    print('create town %s卫生院: %s %s %s' % (tname, st, r.get('code'), r.get('msg')))
    # locate the created town org id
    st2, r2 = call('GET', '/api/sys/org/tree', tok)
    town_node = find_org(r2.get('data') or [], str(tcode))
    town_id = town_node.get('id') if town_node else None
    for vcode, vname in town_villages[tcode]:
        st3, r3 = call('POST', '/api/sys/org', tok, {
            'orgCode': str(vcode), 'orgName': village_org_name(vname), 'orgLevel': 3,
            'parentId': town_id, 'orgType': '村卫生室', 'admvsCode': '420921',
            'sortNo': 0, 'status': 1})
        print('   village %s: %s %s %s' % (village_org_name(vname), st3, r3.get('code'), r3.get('msg')))

# final tree
st, r = call('GET', '/api/sys/org/tree', tok)
def show(nodes, d=0):
    for n in nodes:
        print('  ' * d + '- [%s] %s (%s)' % (n.get('orgLevel'), n.get('orgName'), n.get('orgCode')))
        show(n.get('children') or [], d + 1)
print('\n=== final org tree ===')
show(r.get('data') or [])
