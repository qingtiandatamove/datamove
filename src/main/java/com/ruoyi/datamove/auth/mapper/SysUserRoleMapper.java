package com.ruoyi.datamove.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.datamove.auth.domain.SysUserRole;
import com.ruoyi.datamove.auth.domain.UserRoleName;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysUserRoleMapper extends BaseMapper<SysUserRole> {

    /**
     * 用户的角色标识(role_key), 只统计启用且未删除的角色
     */
    @Select("SELECT r.role_key FROM sys_user_role ur " +
            "JOIN sys_role r ON ur.role_id = r.role_id " +
            "WHERE ur.user_id = #{userId} AND r.status = '0' AND r.del_flag = '0' " +
            "ORDER BY r.role_sort")
    List<String> selectRoleKeysByUserId(@Param("userId") Long userId);

    /**
     * 用户的按钮权限标识: 用户 -> 角色 -> 菜单(sys_menu.perms)
     * DISTINCT 是必须的: 多个角色授权同一个按钮时会出现重复
     */
    @Select("SELECT DISTINCT m.perms FROM sys_user_role ur " +
            "JOIN sys_role r ON ur.role_id = r.role_id " +
            "JOIN sys_role_menu rm ON r.role_id = rm.role_id " +
            "JOIN sys_menu m ON rm.menu_id = m.menu_id " +
            "WHERE ur.user_id = #{userId} AND r.status = '0' AND r.del_flag = '0' " +
            "AND m.status = '0' AND m.perms IS NOT NULL AND m.perms <> ''")
    List<String> selectPermsByUserId(@Param("userId") Long userId);

    /**
     * 批量查「用户 -> 角色名」, 列表页一次查完, 避免逐行回表
     */
    @Select("<script>SELECT ur.user_id AS user_id, r.role_name AS role_name " +
            "FROM sys_user_role ur JOIN sys_role r ON ur.role_id = r.role_id " +
            "WHERE ur.user_id IN " +
            "<foreach collection='userIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "ORDER BY r.role_sort</script>")
    List<UserRoleName> selectRoleNamesByUserIds(@Param("userIds") List<Long> userIds);
}
