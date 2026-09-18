package com.ruoyi.common.core.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.Set;

/**
 * 当前登录用户上下文
 */
@Data
public class LoginUser implements Serializable {

    private Long userId;
    private String userName;
    private String nickName;
    private Set<String> roles;
    private Set<String> permissions;

    public boolean isAdmin() {
        return roles != null && roles.contains("admin");
    }
}
