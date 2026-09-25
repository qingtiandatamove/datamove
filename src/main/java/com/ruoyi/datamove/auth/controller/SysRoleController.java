package com.ruoyi.datamove.auth.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ruoyi.common.core.domain.PageResult;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.datamove.auth.domain.SysRole;
import com.ruoyi.datamove.auth.domain.SysUserRole;
import com.ruoyi.datamove.auth.mapper.SysRoleMapper;
import com.ruoyi.datamove.auth.mapper.SysUserRoleMapper;
import com.ruoyi.datamove.auth.service.SysPermissionService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.List;

@Api(tags = "角色管理")
@RestController
@RequestMapping("/system/role")
@RequiredArgsConstructor
public class SysRoleController {

    private final SysPermissionService permissionService;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;

    @ApiOperation("分页查询角色")
    @GetMapping("/page")
    public R<PageResult<SysRole>> page(@RequestParam(defaultValue = "1") int pageNum,
                                        @RequestParam(defaultValue = "10") int pageSize,
                                        @RequestParam(required = false) String keyword) {
        Page<SysRole> page = new Page<>(pageNum, pageSize);
        QueryWrapper<SysRole> wrapper = new QueryWrapper<>();
        wrapper.eq("del_flag", "0");
        if (keyword != null && !keyword.isEmpty())
            wrapper.and(w -> w.like("role_name", keyword).or().like("role_key", keyword));
        wrapper.orderByAsc("role_sort").orderByAsc("role_id");
        Page<SysRole> result = roleMapper.selectPage(page, wrapper);
        return R.ok(PageResult.of(result.getRecords(), result.getTotal()));
    }

    @ApiOperation("全部可用角色 (用户授权弹窗用)")
    @GetMapping("/list")
    public R<List<SysRole>> list() {
        return R.ok(permissionService.listRoles());
    }

    @ApiOperation("新增角色")
    @PostMapping
    public R<Long> add(@RequestBody SysRole role) {
        if (role.getRoleName() == null || role.getRoleName().isEmpty()) throw new RuntimeException("角色名称不能为空");
        if (role.getRoleKey() == null || role.getRoleKey().isEmpty()) throw new RuntimeException("角色标识不能为空");
        long dup = roleMapper.selectCount(new QueryWrapper<SysRole>()
                .eq("del_flag", "0").eq("role_key", role.getRoleKey()));
        if (dup > 0) throw new RuntimeException("角色标识已存在: " + role.getRoleKey());
        if (role.getRoleSort() == null) role.setRoleSort(99);
        if (role.getStatus() == null) role.setStatus("0");
        role.setDelFlag("0");
        role.setCreateTime(new Date());
        roleMapper.insert(role);
        return R.ok(role.getRoleId());
    }

    @ApiOperation("修改角色")
    @PutMapping
    public R<Void> update(@RequestBody SysRole role) {
        SysRole old = roleMapper.selectById(role.getRoleId());
        if (old == null) throw new RuntimeException("角色不存在");
        // role_key 是权限判断依据(admin 靠它短路), 不允许改, 否则历史授权记录全部失效
        role.setRoleKey(null);
        role.setUpdateTime(new Date());
        roleMapper.updateById(role);
        return R.ok();
    }

    @ApiOperation("删除角色")
    @DeleteMapping("/{roleId}")
    public R<Void> remove(@PathVariable Long roleId) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) throw new RuntimeException("角色不存在");
        if ("admin".equals(role.getRoleKey())) throw new RuntimeException("内置超级管理员角色不可删除");
        long used = userRoleMapper.selectCount(new QueryWrapper<SysUserRole>().eq("role_id", roleId));
        if (used > 0) throw new RuntimeException("该角色已分配给 " + used + " 个用户, 请先取消授权");
        // RuoYi 约定: del_flag=2 表示删除
        role.setDelFlag("2");
        role.setUpdateTime(new Date());
        roleMapper.updateById(role);
        return R.ok();
    }

    @ApiOperation("启用/停用角色")
    @PostMapping("/{roleId}/status")
    public R<Void> changeStatus(@PathVariable Long roleId, @RequestParam String status) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) throw new RuntimeException("角色不存在");
        if ("1".equals(status) && "admin".equals(role.getRoleKey())) {
            throw new RuntimeException("内置超级管理员角色不可停用");
        }
        role.setStatus(status);
        role.setUpdateTime(new Date());
        roleMapper.updateById(role);
        return R.ok();
    }

    @ApiOperation("查询角色已授权的菜单ID (权限树回填)")
    @GetMapping("/{roleId}/menus")
    public R<List<Long>> listRoleMenus(@PathVariable Long roleId) {
        return R.ok(permissionService.listMenuIdsOfRole(roleId));
    }

    @ApiOperation("给角色授权菜单 (全量覆盖: 传空列表即取消全部权限)")
    @PutMapping("/{roleId}/menus")
    public R<Void> assignRoleMenus(@PathVariable Long roleId, @RequestBody(required = false) List<Long> menuIds) {
        permissionService.assignMenus(roleId, menuIds);
        return R.ok();
    }
}
