package com.ruoyi.datamove.auth.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ruoyi.common.core.domain.PageResult;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.datamove.auth.domain.SysUser;
import com.ruoyi.datamove.auth.mapper.SysUserMapper;
import com.ruoyi.datamove.auth.service.IAuthService;
import com.ruoyi.datamove.auth.service.SysPermissionService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Api(tags = "用户管理")
@RestController
@RequestMapping("/system/user")
public class SysUserController {

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private IAuthService authService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SysPermissionService permissionService;

    @ApiOperation("分页查询")
    @GetMapping("/page")
    public R<PageResult<SysUser>> page(@RequestParam(defaultValue = "1") int pageNum,
                                        @RequestParam(defaultValue = "10") int pageSize,
                                        @RequestParam(required = false) String keyword) {
        Page<SysUser> page = new Page<>(pageNum, pageSize);
        QueryWrapper<SysUser> wrapper = new QueryWrapper<>();
        wrapper.eq("del_flag", "0");
        if (keyword != null && !keyword.isEmpty())
            wrapper.and(w -> w.like("user_name", keyword).or().like("nick_name", keyword));
        wrapper.orderByDesc("user_id");
        Page<SysUser> result = userMapper.selectPage(page, wrapper);
        // 不回显密码
        result.getRecords().forEach(u -> u.setPassword(null));
        // 角色: 一次批量查完再回填, 避免每行一次查询
        List<Long> ids = result.getRecords().stream().map(SysUser::getUserId).collect(Collectors.toList());
        Map<Long, List<String>> roleNames = permissionService.roleNamesOfUsers(ids);
        for (SysUser u : result.getRecords()) {
            u.setRoleNames(roleNames.getOrDefault(u.getUserId(), Collections.emptyList()));
        }
        return R.ok(PageResult.of(result.getRecords(), result.getTotal()));
    }

    @ApiOperation("新增用户")
    @PostMapping
    public R<Long> add(@RequestBody SysUser user) {
        Long exists = userMapper.selectCount(
                new QueryWrapper<SysUser>().eq("user_name", user.getUserName()).eq("del_flag", "0"));
        if (exists > 0) throw new RuntimeException("账号已存在");
        // 默认密码 123456
        user.setPassword(passwordEncoder.encode("123456"));
        if (user.getStatus() == null) user.setStatus("0");
        if (user.getUserType() == null) user.setUserType("00");
        user.setCreateTime(new Date());
        user.setUpdateTime(new Date());
        userMapper.insert(user);
        // roleIds 是前端跟着表单一起提交的角色选择, 不传表示不动授权
        if (user.getRoleIds() != null) permissionService.assignRoles(user.getUserId(), user.getRoleIds());
        return R.ok(user.getUserId());
    }

    @ApiOperation("修改用户")
    @PutMapping
    public R<Void> update(@RequestBody SysUser user) {
        if (user.getPassword() != null && !user.getPassword().isEmpty()) {
            user.setPassword(passwordEncoder.encode(user.getPassword()));
        } else {
            user.setPassword(null);
        }
        user.setUpdateTime(new Date());
        userMapper.updateById(user);
        if (user.getRoleIds() != null) permissionService.assignRoles(user.getUserId(), user.getRoleIds());
        return R.ok();
    }

    @ApiOperation("删除用户")
    @DeleteMapping("/{userId}")
    public R<Void> remove(@PathVariable Long userId) {
        if (userId == 1L) throw new RuntimeException("超级管理员不可删除");
        SysUser u = userMapper.selectById(userId);
        if (u != null) {
            u.setDelFlag("1");
            u.setUpdateTime(new Date());
            userMapper.updateById(u);
            // 删除的是逻辑删, 但角色关联必须物理清掉: 否则会留下孤儿关联,
            // 角色管理里会一直提示「该角色已分配给 N 个用户」而删不掉
            permissionService.assignRoles(userId, Collections.emptyList());
        }
        return R.ok();
    }

    @ApiOperation("重置密码")
    @PostMapping("/{userId}/reset")
    public R<Void> resetPassword(@PathVariable Long userId, @RequestBody(required = false) Map<String, String> body) {
        String pwd = body == null ? "123456" : body.getOrDefault("password", "123456");
        authService.resetPassword(userId, pwd);
        return R.ok();
    }

    @ApiOperation("查询用户已分配的角色ID (授权弹窗回填)")
    @GetMapping("/{userId}/roles")
    public R<List<Long>> listUserRoles(@PathVariable Long userId) {
        return R.ok(permissionService.listRoleIdsOfUser(userId));
    }

    @ApiOperation("给用户授权 (全量覆盖: 传空列表即取消全部角色)")
    @PutMapping("/{userId}/roles")
    public R<Void> assignUserRoles(@PathVariable Long userId, @RequestBody(required = false) List<Long> roleIds) {
        permissionService.assignRoles(userId, roleIds);
        return R.ok();
    }

    @ApiOperation("启用/停用")
    @PostMapping("/{userId}/status")
    public R<Void> changeStatus(@PathVariable Long userId, @RequestParam String status) {
        SysUser u = userMapper.selectById(userId);
        if (u != null) {
            u.setStatus(status);
            userMapper.updateById(u);
        }
        return R.ok();
    }
}
