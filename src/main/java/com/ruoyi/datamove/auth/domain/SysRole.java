package com.ruoyi.datamove.auth.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 角色表 sys_role (RuoYi 兼容)
 *
 * <p>本项目的授权模型: 用户 -> 角色 -> 菜单/按钮权限(sys_menu.perms)。
 * 角色本身不写死在代码里, 全部走 sys_role / sys_user_role / sys_role_menu 三张表。
 */
@Data
@TableName("sys_role")
public class SysRole implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long roleId;
    private String roleName;
    /** 角色权限字符串, 如 admin / operator; admin 视为超管(拥有 *:*:*) */
    private String roleKey;
    private Integer roleSort;
    private String dataScope;
    private String status;
    private String delFlag;
    private String createBy;
    private Date createTime;
    private String updateBy;
    private Date updateTime;
    private String remark;
}
