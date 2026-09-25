package com.yb.hi.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.platform.dto.MenuNode;
import com.yb.hi.platform.dto.MenuSaveReq;
import com.yb.hi.platform.entity.SysMenu;
import com.yb.hi.platform.mapper.SysMenuMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 菜单服务(全局真源, sys_menu 已入 IGNORE_TABLES, 无租户隔离)。
 * 负责菜单 CRUD 与树形组装; 供角色授权与登录后动态菜单下发使用。
 */
@Service
public class SysMenuService {

    private final SysMenuMapper menuMapper;

    public SysMenuService(SysMenuMapper menuMapper) {
        this.menuMapper = menuMapper;
    }

    /** 全量菜单(按 sort_no,id 升序) */
    public List<SysMenu> listAll() {
        return menuMapper.selectList(new QueryWrapper<SysMenu>().orderByAsc("sort_no", "id"));
    }

    public SysMenu getById(Long id) {
        return menuMapper.selectById(id);
    }

    /** 全量目录树 */
    public List<MenuNode> tree() {
        return buildTree(listAll(), null);
    }

    /** 导出菜单列表(xlsx 行集): 树 DFS 摊平, 与页面查询同口径(名称/键/组件关键字 + 类型 1目录/2菜单 + 状态) */
    public Map<String, Object> exportRows(String keyword, Integer menuType, Integer status) {
        List<List<String>> head = new ArrayList<>();
        for (String h : new String[]{"菜单名称", "菜单键", "上级菜单", "类型", "组件", "阶段", "图标", "排序号", "显示", "状态"}) {
            head.add(Collections.singletonList(h));
        }
        String kw = StringUtils.hasText(keyword) ? keyword.trim().toLowerCase() : null;
        List<List<Object>> rows = new ArrayList<>();
        flattenForExport(tree(), null, rows, kw, menuType, status);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("head", head);
        out.put("rows", rows);
        out.put("total", rows.size());
        return out;
    }

    /** DFS 摊平导出行: 命中才写入但仍递归子级(与前端树过滤保留层级上下文同口径) */
    private void flattenForExport(List<MenuNode> nodes, String parentName, List<List<Object>> rows,
                                  String kw, Integer menuType, Integer status) {
        for (MenuNode n : nodes) {
            if (matchExport(n, kw, menuType, status)) {
                rows.add(Arrays.asList(
                        nz(n.getMenuName()), nz(n.getMenuKey()), parentName == null ? "" : parentName,
                        n.getMenuType() != null && n.getMenuType() == 1 ? "目录" : "菜单",
                        nz(n.getComp()), nz(n.getPhase()), nz(n.getIcon()), n.getSortNo(),
                        n.getVisible() != null && n.getVisible() == 1 ? "是" : "否",
                        n.getStatus() != null && n.getStatus() == 1 ? "启用" : "停用"));
            }
            if (n.getChildren() != null && !n.getChildren().isEmpty()) {
                flattenForExport(n.getChildren(), n.getMenuName(), rows, kw, menuType, status);
            }
        }
    }

    private static boolean matchExport(MenuNode n, String kw, Integer menuType, Integer status) {
        if (kw != null && !nz(n.getMenuName()).toLowerCase().contains(kw)
                && !nz(n.getMenuKey()).toLowerCase().contains(kw)
                && !nz(n.getComp()).toLowerCase().contains(kw)) {
            return false;
        }
        if (menuType != null && !menuType.equals(n.getMenuType())) {
            return false;
        }
        return status == null || status.equals(n.getStatus());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 仅保留指定 menuKey 的顶级节点(及其子树); 用于平台超管精简菜单 */
    public List<MenuNode> treeOnlyTopKeys(Collection<String> topKeys) {
        List<MenuNode> roots = tree();
        if (topKeys == null || topKeys.isEmpty()) {
            return new ArrayList<>();
        }
        Set<String> keep = new HashSet<>(topKeys);
        roots.removeIf(n -> !keep.contains(n.getMenuKey()));
        return roots;
    }

    /** 全量树, 但剔除指定 menuKey 的顶级节点(及其子树); 用于医院端排除平台级菜单 */
    public List<MenuNode> treeExcludingTopKeys(Collection<String> topKeys) {
        List<MenuNode> roots = tree();
        if (topKeys == null || topKeys.isEmpty()) {
            return roots;
        }
        Set<String> ex = new HashSet<>(topKeys);
        roots.removeIf(n -> ex.contains(n.getMenuKey()));
        return roots;
    }

    /**
     * 全量树, 递归剔除指定 menuKey 的节点(任意层级, 连同其子树)。
     * 用于医院端既排除平台级顶级菜单(如 hospital-manage), 又排除某个目录下的维护类子菜单
     * (如 std-dict-import 标准字典提取入库), 只保留可浏览的子菜单。
     */
    public List<MenuNode> treeExcludingKeys(Collection<String> keys) {
        List<MenuNode> roots = tree();
        if (keys == null || keys.isEmpty()) {
            return roots;
        }
        Set<String> ex = new HashSet<>(keys);
        removeRecursive(roots, ex);
        return roots;
    }

    private void removeRecursive(List<MenuNode> nodes, Set<String> ex) {
        nodes.removeIf(n -> ex.contains(n.getMenuKey()));
        for (MenuNode n : nodes) {
            if (n.getChildren() != null && !n.getChildren().isEmpty()) {
                removeRecursive(n.getChildren(), ex);
            }
        }
    }

    /**
     * 按菜单 id 集合过滤成树: 命中集合的菜单, 及其祖先目录(保证树可渲染)保留;
     * 未被命中且无命中后代的目录会被剔除。ids 为空返回空树。
     */
    public List<MenuNode> treeByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }
        Set<Long> idSet = new HashSet<>(ids);
        List<SysMenu> all = listAll();
        // 计算需保留的节点: 命中 + 命中的所有祖先
        Set<Long> keep = new HashSet<>(idSet);
        for (SysMenu m : all) {
            if (idSet.contains(m.getId())) {
                Long p = m.getParentId();
                while (p != null && p != 0L) {
                    if (!keep.add(p)) {
                        break;
                    }
                    SysMenu pm = findById(all, p);
                    p = pm == null ? null : pm.getParentId();
                }
            }
        }
        List<SysMenu> filtered = new ArrayList<>();
        for (SysMenu m : all) {
            if (keep.contains(m.getId())) {
                filtered.add(m);
            }
        }
        return buildTree(filtered, keep);
    }

    /** 组装菜单树; keepIds 非空时仅挂载父节点在 keepIds 中的节点(用于过滤树) */
    private List<MenuNode> buildTree(List<SysMenu> menus, Set<Long> keepIds) {
        List<MenuNode> roots = new ArrayList<>();
        List<MenuNode> nodes = new ArrayList<>();
        for (SysMenu m : menus) {
            MenuNode n = toNode(m);
            nodes.add(n);
        }
        for (MenuNode n : nodes) {
            Long pid = n.getParentId();
            if (pid == null || pid == 0L) {
                roots.add(n);
                continue;
            }
            MenuNode parent = findNode(nodes, pid);
            if (parent != null) {
                parent.getChildren().add(n);
            } else if (keepIds == null) {
                // 全量树时父节点缺失(数据异常)按根挂载, 避免节点丢失
                roots.add(n);
            }
        }
        return roots;
    }

    private MenuNode toNode(SysMenu m) {
        MenuNode n = new MenuNode();
        n.setId(m.getId());
        n.setParentId(m.getParentId());
        n.setMenuKey(m.getMenuKey());
        n.setMenuName(m.getMenuName());
        n.setMenuType(m.getMenuType());
        n.setComp(m.getComp());
        n.setPhase(m.getPhase());
        n.setIcon(m.getIcon());
        n.setSortNo(m.getSortNo());
        n.setVisible(m.getVisible());
        n.setStatus(m.getStatus());
        return n;
    }

    private MenuNode findNode(List<MenuNode> nodes, Long id) {
        for (MenuNode n : nodes) {
            if (n.getId().equals(id)) {
                return n;
            }
        }
        return null;
    }

    private SysMenu findById(List<SysMenu> menus, Long id) {
        for (SysMenu m : menus) {
            if (m.getId().equals(id)) {
                return m;
            }
        }
        return null;
    }

    /** 新增菜单 */
    public Long create(MenuSaveReq req) {
        validate(req, true);
        SysMenu m = new SysMenu();
        applyReq(m, req);
        menuMapper.insert(m);
        return m.getId();
    }

    /** 修改菜单 */
    public void update(MenuSaveReq req) {
        if (req.getId() == null) {
            throw new BizException(400, "菜单ID不能为空");
        }
        validate(req, false);
        SysMenu m = menuMapper.selectById(req.getId());
        if (m == null) {
            throw new BizException("菜单不存在");
        }
        applyReq(m, req);
        menuMapper.updateById(m);
    }

    /** 删除菜单: 存在子菜单则拒绝 */
    public void delete(Long id) {
        long children = menuMapper.selectCount(new QueryWrapper<SysMenu>().eq("parent_id", id));
        if (children > 0) {
            throw new BizException("存在下级菜单, 不可删除");
        }
        menuMapper.deleteById(id);
    }

    private void applyReq(SysMenu m, MenuSaveReq req) {
        m.setParentId(req.getParentId() == null ? 0L : req.getParentId());
        m.setMenuKey(req.getMenuKey());
        m.setMenuName(req.getMenuName());
        m.setMenuType(req.getMenuType() == null ? 2 : req.getMenuType());
        m.setComp(req.getComp());
        m.setPhase(req.getPhase());
        m.setIcon(req.getIcon());
        m.setSortNo(req.getSortNo() == null ? 0 : req.getSortNo());
        m.setVisible(req.getVisible() == null ? 1 : req.getVisible());
        m.setStatus(req.getStatus() == null ? 1 : req.getStatus());
    }

    private void validate(MenuSaveReq req, boolean isCreate) {
        if (!StringUtils.hasText(req.getMenuKey()) || !StringUtils.hasText(req.getMenuName())) {
            throw new BizException(400, "菜单键与名称不能为空");
        }
        QueryWrapper<SysMenu> q = new QueryWrapper<SysMenu>().eq("menu_key", req.getMenuKey());
        if (!isCreate && req.getId() != null) {
            q.ne("id", req.getId());
        }
        if (menuMapper.selectCount(q) > 0) {
            throw new BizException("菜单键已存在: " + req.getMenuKey());
        }
    }
}
