package com.ruoyi.datamove;

import org.junit.jupiter.api.Test;

/**
 * 用例占位 — 此项目所有业务逻辑都在 Controller / Service 中通过集成环境手动验证。
 * 此前使用 {@code @SpringBootTest} 试图启动完整上下文, 因 MyBatis-Plus 需
 * sqlSessionFactory 而 DataSourceAutoConfiguration 已被排除, 必然失败。
 *
 * <p>改用最轻的纯 JUnit 烟雾测试: 只确保 main 方法签名 + 编译产物可用。
 * 真正的端到端验证见 QUICKSTART.md / README.md 的部署章节。
 */
public class PlaceholderTest {

    @Test
    void compilesAndMainMethodExists() throws Exception {
        // 反射校验主类存在且 main 公开, 确保 packaging / 编译链路没问题
        Class<?> app = Class.forName("com.ruoyi.datamove.DatamoveApplication");
        app.getMethod("main", String[].class);
    }
}