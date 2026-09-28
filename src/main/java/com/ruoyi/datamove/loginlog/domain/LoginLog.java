package com.ruoyi.datamove.loginlog.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 登录日志 sys_login_log
 *
 * 记录每一次登录尝试: 谁、什么时候、从哪个 IP、什么地点、用什么浏览器、成功还是失败。
 *
 * 设计要点:
 *   - 成功与失败都记: 失败日志是「有人在撞密码」的唯一线索, 只记成功等于没有安全审计
 *   - user_name / nick_name 是快照: 用户改名或删号后, 历史日志依然读得懂
 *   - 登录方式区分 PASSWORD / SMS / EMAIL (三种入口共用一张表)
 *   - 写入失败绝不影响登录主流程 (Service 层 try/catch 兜底)
 */
@Data
@TableName("sys_login_log")
public class LoginLog implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录账号 (失败时是用户填的那个账号, 可能并不存在) */
    private String userName;

    /** 用户昵称 (成功时的快照) */
    private String nickName;

    /** 登录方式 PASSWORD / SMS / EMAIL */
    private String loginType;

    /** 登录状态 0成功 1失败 */
    private String status;

    /** 提示信息 (成功文案 / 失败原因) */
    private String msg;

    /** 客户端IP (兼容 nginx X-Forwarded-For) */
    private String ip;

    /** 登录地点 (IP 归属地, 解析不出来为空) */
    private String loginLocation;

    /** 浏览器 */
    private String browser;

    /** 操作系统 */
    private String os;

    /** 客户端 UA 原文 */
    private String userAgent;

    /** 登录时间 */
    private Date loginTime;
}
