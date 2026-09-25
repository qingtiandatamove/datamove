package com.ruoyi.datamove.auth.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

/**
 * 用户与角色关联表 sys_user_role (联合主键: user_id + role_id)
 */
@Data
@TableName("sys_user_role")
public class SysUserRole implements Serializable {

    private Long userId;
    private Long roleId;
}
