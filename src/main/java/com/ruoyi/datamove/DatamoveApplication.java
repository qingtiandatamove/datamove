package com.ruoyi.datamove;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 轻量MySQL数据同步工具 启动入口
 *
 * @author ruoyi
 */
@EnableAsync
@EnableScheduling
@MapperScan({"com.ruoyi.**.mapper", "com.ruoyi.datamove.**.mapper"})
@SpringBootApplication(scanBasePackages = {"com.ruoyi.datamove", "com.ruoyi.common"})
public class DatamoveApplication {

    public static void main(String[] args) {
        SpringApplication.run(DatamoveApplication.class, args);
        System.out.println("================ Datamove started ================");
        System.out.println("(♥◠‿◠)ﾉ  datamove 启动成功   ");
    }
}
