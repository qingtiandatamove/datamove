package com.ruoyi.datamove.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.datamove.auth.domain.SysMenu;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysMenuMapper extends BaseMapper<SysMenu> {

    /**
     * 参与侧边栏渲染的菜单(目录 + 菜单), 按钮(F)不渲染
     */
    @Select("SELECT menu_id, parent_id, menu_name, path, icon, order_num, menu_type, perms " +
            "FROM sys_menu " +
            "WHERE status = '0' AND visible = '0' AND menu_type IN ('M', 'C') " +
            "ORDER BY order_num, menu_id")
    List<SysMenu> selectMenuTreeSource();

    /**
     * 全部菜单(含按钮 F) —— 角色授权的权限树用
     */
    @Select("SELECT menu_id, parent_id, menu_name, path, icon, order_num, menu_type, perms " +
            "FROM sys_menu " +
            "WHERE status = '0' " +
            "ORDER BY order_num, menu_id")
    List<SysMenu> selectAllMenuTreeSource();
}
