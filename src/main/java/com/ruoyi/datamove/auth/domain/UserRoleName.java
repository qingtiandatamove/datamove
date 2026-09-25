package com.ruoyi.datamove.auth.domain;

import lombok.Data;

import java.io.Serializable;

/**
 * 用户 -> 角色名 的投影 (列表页展示用, 避免为展示再去 N 次查角色表)
 */
@Data
public class UserRoleName implements Serializable {

    private Long userId;
    private String roleName;
}
