package com.ruoyi.datamove.auth.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 菜单表 sys_menu (RuoYi 兼容)
 *
 * <p>menu_type: M=目录 C=菜单 F=按钮。
 * 目录与菜单参与侧边栏渲染(见 SysPermissionService#menusOfUser),
 * 按钮(F)只提供 perms 权限标识, 不渲染。
 */
@Data
@TableName("sys_menu")
public class SysMenu implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long menuId;
    private String menuName;
    private Long parentId;
    private Integer orderNum;
    /** 前端路由完整路径, 如 /sync/task; 目录(M)不用 */
    private String path;
    private String component;
    private String isFrame;
    private String menuType;
    private String visible;
    private String status;
    /** 权限标识; 为空表示「登录即可见」 */
    private String perms;
    private String icon;
    private String createBy;
    private Date createTime;
    private String updateBy;
    private Date updateTime;
    private String remark;
}
