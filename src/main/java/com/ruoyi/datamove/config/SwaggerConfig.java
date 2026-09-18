package com.ruoyi.datamove.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 文档 (springdoc-openapi 1.7)
 * 兼容 SpringBoot 2.7
 */
@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI datamoveOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("DataMove 轻量 MySQL 数据同步工具 API")
                .description("对应产品需求文档 4 章节核心模块 - 数据源 / 同步任务 / 日志 / 授权 / 用户管理")
                .contact(new Contact().name("RuoYi-DataMove").email("support@datamove.com"))
                .version("4.8.1"));
    }
}