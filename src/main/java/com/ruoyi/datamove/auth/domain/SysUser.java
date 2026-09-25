package com.ruoyi.datamove.auth.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * 用户表 sys_user (RuoYi 兼容)
 */
@Data
@TableName("sys_user")
public class SysUser implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long userId;
    private String userName;
    private String nickName;
    private String userType;
    private String email;
    private String phonenumber;
    private String sex;
    private String avatar;
    private String password;
    private String status;
    @TableField("del_flag")
    private String delFlag;
    private String loginIp;
    private Date loginDate;
    private String createBy;
    private Date createTime;
    private String updateBy;
    private Date updateTime;
    private String remark;

    /**
     * 非表字段: 该用户已分配的角色ID。
     * 查询时由 SysPermissionService 填充, 新增/修改时作为入参落 sys_user_role。
     */
    @TableField(exist = false)
    private List<Long> roleIds;

    /** 非表字段: 角色名(列表页展示用, 免得前端再拿ID反查) */
    @TableField(exist = false)
    private List<String> roleNames;
}
