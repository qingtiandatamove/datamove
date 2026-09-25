package com.ruoyi.datamove.auth.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 菜单树节点
 *
 * <p>两个用途:
 * <ol>
 *   <li>侧边栏渲染 —— 只用 menuId/menuName/path/icon/children</li>
 *   <li>角色授权的权限树 —— 额外用 menuType(M/C/F) 和 perms 区分「目录/菜单/按钮」</li>
 * </ol>
 */
@Data
public class MenuVO implements Serializable {

    private Long menuId;
    private Long parentId;
    private String menuName;
    /** 完整前端路由, 目录为空 */
    private String path;
    private String icon;
    private Integer orderNum;
    /** M=目录 C=菜单 F=按钮 */
    private String menuType;
    /** 权限标识, 按钮(F)才有实际鉴权意义 */
    private String perms;
    private List<MenuVO> children;
}
