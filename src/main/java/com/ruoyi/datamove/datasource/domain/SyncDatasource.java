package com.ruoyi.datamove.datasource.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ruoyi.common.core.domain.BaseEntity;
import com.ruoyi.common.utils.AesUtils;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * 数据源对象 sync_datasource
 *
 * 对应文档 4.1
 */
@Data
@TableName("sync_datasource")
public class SyncDatasource extends BaseEntity implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    @NotBlank(message = "数据源名称不能为空")
    private String datasourceName;

    @NotBlank(message = "数据库IP不能为空")
    private String host;

    @NotNull(message = "端口号不能为空")
    private Integer port;

    @NotBlank(message = "数据库名称不能为空")
    private String dbName;

    @NotBlank(message = "登录账号不能为空")
    private String username;

    /**
     * 密码字段 - 写入加密,读取解密
     * WRITE_ONLY 注解使 Jackson 反序列化时接收 JSON 中的 password 字段,
     * 但响应序列化时不输出该字段(避免明文泄露)
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    /**
     * setter 自动加密(明文写入自动转密文)
     * 这样前端无论传明文还是密文,均可统一处理
     */
    public void setPassword(String raw) {
        if (raw == null || raw.isEmpty()) {
            this.password = null;
            return;
        }
        // 已是密文(BASE64 长度>=16,含字母数字+/=)
        if (looksCipher(raw)) {
            this.password = raw;
        } else {
            this.password = AesUtils.encrypt(raw);
        }
    }

    private static boolean looksCipher(String s) {
        // 简单判断:长度>=24 且 不全是纯字母数字
        return s.length() >= 24 && s.matches("^[A-Za-z0-9+/=]+$");
    }

    @TableField("del_flag")
    private String delFlag;

    /**
     * 兼容前端传入明文密码(同上,响应时不输出)
     * 由于 setter 内部会加密, 因此读写都安全
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    public void setPlainTextPassword(String plain) {
        if (plain != null && !plain.isEmpty()) {
            this.password = AesUtils.encrypt(plain);
        }
    }

    @JsonIgnore
    public String getPlainPassword() {
        if (password == null || password.isEmpty()) return password;
        try {
            return AesUtils.decrypt(this.password);
        } catch (Exception e) {
            // 解密失败说明不是密文(可能是前端传入的明文),直接返回
            return password;
        }
    }

    /** 前端密文回显(脱敏中间部分) */
    public String getShowPassword() {
        if (password == null) return "";
        String p = getPlainPassword();
        if (p == null || p.length() <= 4) return "****";
        return p.substring(0, 2) + "****" + p.substring(p.length() - 2);
    }

    /** 不影响主 setter 行为, 仅添加明文 set */
    @JsonIgnore
    public String getPassword() {
        return password;
    }
}