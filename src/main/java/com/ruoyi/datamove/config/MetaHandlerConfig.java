package com.ruoyi.datamove.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.ruoyi.datamove.auth.OwnerContext;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * 自动填充 create_time / update_time / 归属用户 等字段
 *
 * <p>owner_id 是多用户数据隔离的落库依据: 谁创建的数据就归谁,
 * 查询时由 {@link DataOwnerHandler} 自动按 owner_id 过滤。
 *
 * <p>注意 strictInsertFill 只在字段为 null 时填充, 代码里显式 set 的值不会被覆盖 ——
 * 后台线程显式传入的归属优先级更高。
 */
@Component
public class MetaHandlerConfig implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "createTime", Date.class, new Date());
        this.strictInsertFill(metaObject, "updateTime", Date.class, new Date());
        this.strictInsertFill(metaObject, "createBy", String.class, operator());
        this.strictInsertFill(metaObject, "updateBy", String.class, operator());
        this.strictInsertFill(metaObject, "ownerId", Long.class, ownerId());
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updateTime", Date.class, new Date());
        this.strictUpdateFill(metaObject, "updateBy", String.class, operator());
    }

    /** 操作人: 后台线程没有登录态时记 system, 不再一律写成 admin (否则审计全是 admin) */
    private String operator() {
        String name = OwnerContext.currentUserName();
        return name == null ? "system" : name;
    }

    /** 归属用户: 取不到就记 0 (无人认领), 不会误挂到别人名下 */
    private Long ownerId() {
        Long userId = OwnerContext.currentUserId();
        return userId == null ? OwnerContext.UNKNOWN_OWNER : userId;
    }
}
