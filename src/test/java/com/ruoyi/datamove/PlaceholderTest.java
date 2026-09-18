package com.ruoyi.datamove;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = DatamoveApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.autoconfigure.exclude=" +
                        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
        })
public class PlaceholderTest {

    @Test
    void contextLoads() {
        // 用例占位 - 此项目所有业务逻辑都在 Controller / Service 中手动验证
    }
}
