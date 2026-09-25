package com.ruoyi.datamove.auth.controller;

import com.ruoyi.common.core.domain.LoginUser;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.datamove.auth.domain.MenuVO;
import com.ruoyi.datamove.auth.service.IAuthService;
import com.ruoyi.datamove.auth.service.SysPermissionService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;

@Api(tags = "菜单权限")
@RestController
@RequestMapping("/system/menu")
@RequiredArgsConstructor
public class SysMenuController {

    private final SysPermissionService permissionService;
    private final IAuthService authService;

    @ApiOperation("当前登录用户的可见菜单树 (侧边栏动态渲染)")
    @GetMapping("/routers")
    public R<List<MenuVO>> routers() {
        LoginUser user = authService.currentUser();
        if (user == null) return R.ok(Collections.emptyList());
        return R.ok(permissionService.menusOfUser(user.getUserId(), user.getRoles()));
    }

    @ApiOperation("全部菜单树(含按钮), 角色授权弹窗用")
    @GetMapping("/tree")
    public R<List<MenuVO>> tree() {
        return R.ok(permissionService.allMenuTree());
    }
}
