package com.ruoyi.datamove.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ruoyi.datamove.auth.domain.MenuVO;
import com.ruoyi.datamove.auth.domain.SysMenu;
import com.ruoyi.datamove.auth.domain.SysRole;
import com.ruoyi.datamove.auth.domain.SysRoleMenu;
import com.ruoyi.datamove.auth.domain.SysUser;
import com.ruoyi.datamove.auth.domain.SysUserRole;
import com.ruoyi.datamove.auth.domain.UserRoleName;
import com.ruoyi.datamove.auth.mapper.SysMenuMapper;
import com.ruoyi.datamove.auth.mapper.SysRoleMapper;
import com.ruoyi.datamove.auth.mapper.SysRoleMenuMapper;
import com.ruoyi.datamove.auth.mapper.SysUserMapper;
import com.ruoyi.datamove.auth.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 授权服务: 用户 <-> 角色 的分配, 以及「用户有哪些角色/权限」的查询
 *
 * <p>设计要点:
 * <ol>
 *   <li>权限不写死在代码里: 权限标识来自 sys_menu.perms, 通过 sys_role_menu 授予角色,
 *       再通过 sys_user_role 授予用户 —— 改授权不用改代码。</li>
 *   <li>超管短路: role_key=admin 直接视为 *:*:* (与 RuoYi 一致),
 *       否则每加一个按钮权限都要给 admin 补一条 role_menu, 很容易漏。</li>
 *   <li>授权立即生效: JWT 里只放 userId/userName, 角色权限在 JwtAuthenticationFilter 里每次查库,
 *       所以「改完授权」下一个请求就生效, 不需要用户重新登录 (前端按钮要重新登录才刷新)。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class SysPermissionService {

    /** 超管角色标识, 拥有全部权限 */
    public static final String SUPER_ADMIN_ROLE = "admin";
    /** 超管的权限通配符 */
    public static final String ALL_PERMISSION = "*:*:*";

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysMenuMapper menuMapper;
    private final SysRoleMenuMapper roleMenuMapper;

    /** 全部可用角色 (授权弹窗用) */
    public List<SysRole> listRoles() {
        return roleMapper.selectList(new QueryWrapper<SysRole>()
                .eq("status", "0")
                .eq("del_flag", "0")
                .orderByAsc("role_sort", "role_id"));
    }

    /** 用户已分配的角色ID */
    public List<Long> listRoleIdsOfUser(Long userId) {
        return userRoleMapper.selectList(new QueryWrapper<SysUserRole>().eq("user_id", userId))
                .stream().map(SysUserRole::getRoleId).collect(Collectors.toList());
    }

    /** 用户的角色标识(role_key) */
    public Set<String> roleKeysOfUser(Long userId) {
        return new LinkedHashSet<>(userRoleMapper.selectRoleKeysByUserId(userId));
    }

    /** 用户的权限标识(perms) */
    public Set<String> permissionsOfUser(Long userId) {
        return permissionsOfUser(userId, roleKeysOfUser(userId));
    }

    /** 用户的权限标识(perms) —— 已拿到角色时避免重复查一次角色表 */
    public Set<String> permissionsOfUser(Long userId, Set<String> roleKeys) {
        if (roleKeys != null && roleKeys.contains(SUPER_ADMIN_ROLE)) {
            return Collections.singleton(ALL_PERMISSION);
        }
        return new LinkedHashSet<>(userRoleMapper.selectPermsByUserId(userId));
    }

    /**
     * 当前用户可见的侧边栏菜单树
     *
     * <p>可见规则:
     * <ul>
     *   <li>超管(admin) 短路: 返回全部菜单。</li>
     *   <li>菜单 perms 为空 —— 登录即可见(首页、目录), 不需要授权。</li>
     *   <li>菜单 perms 非空 —— 必须出现在用户的权限集合里才可见。</li>
     *   <li>目录(M)下没有任何可见子菜单时, 目录本身也不显示 —— 否则会留下空的父级分组。</li>
     * </ul>
     */
    public List<MenuVO> menusOfUser(Long userId, Set<String> roleKeys) {
        boolean admin = roleKeys != null && roleKeys.contains(SUPER_ADMIN_ROLE);
        Set<String> perms = admin ? Collections.emptySet() : permissionsOfUser(userId, roleKeys);
        List<SysMenu> visible = new ArrayList<>();
        for (SysMenu m : menuMapper.selectMenuTreeSource()) {
            if (admin || isMenuVisible(m, perms)) visible.add(m);
        }
        return buildMenuTree(visible);
    }

    private boolean isMenuVisible(SysMenu m, Set<String> perms) {
        String p = m.getPerms();
        return p == null || p.trim().isEmpty() || perms.contains(p);
    }

    private List<MenuVO> buildMenuTree(List<SysMenu> list) {
        Map<Long, List<SysMenu>> byParent = list.stream()
                .collect(Collectors.groupingBy(m -> m.getParentId() == null ? 0L : m.getParentId(),
                        LinkedHashMap::new, Collectors.toList()));
        return buildMenuNodes(0L, byParent);
    }

    private List<MenuVO> buildMenuNodes(Long parentId, Map<Long, List<SysMenu>> byParent) {
        List<MenuVO> nodes = new ArrayList<>();
        for (SysMenu m : byParent.getOrDefault(parentId, Collections.emptyList())) {
            List<MenuVO> children = buildMenuNodes(m.getMenuId(), byParent);
            if ("M".equals(m.getMenuType()) && children.isEmpty()) continue;
            MenuVO vo = new MenuVO();
            vo.setMenuId(m.getMenuId());
            vo.setParentId(m.getParentId());
            vo.setMenuName(m.getMenuName());
            vo.setPath(m.getPath());
            vo.setIcon(m.getIcon());
            vo.setOrderNum(m.getOrderNum());
            vo.setMenuType(m.getMenuType());
            vo.setPerms(m.getPerms());
            vo.setChildren(children);
            nodes.add(vo);
        }
        return nodes;
    }

    /** 批量: 用户ID -> 角色名列表 (列表页展示) */
    public Map<Long, List<String>> roleNamesOfUsers(List<Long> userIds) {
        if (CollectionUtils.isEmpty(userIds)) return Collections.emptyMap();
        Map<Long, List<String>> map = new HashMap<>();
        for (UserRoleName vo : userRoleMapper.selectRoleNamesByUserIds(userIds)) {
            map.computeIfAbsent(vo.getUserId(), k -> new ArrayList<>()).add(vo.getRoleName());
        }
        return map;
    }

    /**
     * 全部菜单树(含按钮), 角色授权弹窗用
     */
    public List<MenuVO> allMenuTree() {
        return buildMenuTree(menuMapper.selectAllMenuTreeSource());
    }

    /** 角色已授权的菜单ID */
    public List<Long> listMenuIdsOfRole(Long roleId) {
        return roleMenuMapper.selectList(new QueryWrapper<SysRoleMenu>().eq("role_id", roleId))
                .stream().map(SysRoleMenu::getMenuId).collect(Collectors.toList());
    }

    /**
     * 给角色授权菜单 —— 全量覆盖: 先清空该角色的菜单, 再按传入列表重建
     *
     * <p>内置 admin 角色不在这里特殊保护: 超管本身走 *:*:* 短路,
     * 即使 sys_role_menu 被清空也仍是全权限, 改它只会让「显式授权记录」与实际不一致。
     */
    @Transactional(rollbackFor = Exception.class)
    public void assignMenus(Long roleId, List<Long> menuIds) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) throw new RuntimeException("角色不存在");

        List<Long> target = menuIds == null
                ? Collections.emptyList()
                : menuIds.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());

        if (!target.isEmpty()) {
            long exists = menuMapper.selectCount(new QueryWrapper<SysMenu>()
                    .eq("status", "0").in("menu_id", target));
            if (exists != target.size()) throw new RuntimeException("存在无效菜单");
        }

        roleMenuMapper.delete(new QueryWrapper<SysRoleMenu>().eq("role_id", roleId));
        for (Long menuId : target) {
            SysRoleMenu rm = new SysRoleMenu();
            rm.setRoleId(roleId);
            rm.setMenuId(menuId);
            roleMenuMapper.insert(rm);
        }
    }

    /**
     * 给用户授权 —— 全量覆盖: 先清空该用户的角色, 再按传入列表重建
     *
     * @param userId  用户ID
     * @param roleIds 目标角色ID列表, 传空列表表示取消全部角色
     */
    @Transactional(rollbackFor = Exception.class)
    public void assignRoles(Long userId, List<Long> roleIds) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) throw new RuntimeException("用户不存在");

        List<Long> target = roleIds == null
                ? Collections.emptyList()
                : roleIds.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());

        // 角色必须真实存在且未删除, 否则会写进脏关联, 后面算权限时静默丢权限
        if (!target.isEmpty()) {
            long exists = roleMapper.selectCount(new QueryWrapper<SysRole>()
                    .eq("del_flag", "0").in("role_id", target));
            if (exists != target.size()) throw new RuntimeException("存在无效角色");
        }

        // 内置 admin(userId=1) 必须保留 admin 角色: 否则系统会失去唯一可管理账号, 谁都进不去用户管理
        if (userId != null && userId == 1L && !target.isEmpty()) {
            boolean keepAdmin = target.stream().anyMatch(this::isAdminRole);
            if (!keepAdmin) throw new RuntimeException("内置超级管理员必须保留 admin 角色");
        }

        userRoleMapper.delete(new QueryWrapper<SysUserRole>().eq("user_id", userId));
        for (Long roleId : target) {
            SysUserRole ur = new SysUserRole();
            ur.setUserId(userId);
            ur.setRoleId(roleId);
            userRoleMapper.insert(ur);
        }
    }

    private boolean isAdminRole(Long roleId) {
        SysRole role = roleMapper.selectById(roleId);
        return role != null && SUPER_ADMIN_ROLE.equals(role.getRoleKey());
    }
}
