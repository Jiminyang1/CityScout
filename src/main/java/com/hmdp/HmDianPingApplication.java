package com.hmdp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;


@EnableScheduling
@EnableAspectJAutoProxy(exposeProxy = true)
@MapperScan("com.hmdp.mapper")
@SpringBootApplication
public class HmDianPingApplication {

    public static void main(String[] args) {
        // 强制 JVM 时区与 MySQL 容器 (UTC) 一致，避免 LocalDateTime 与 DB 自动填充的
        // CURRENT_TIMESTAMP 在 WHERE 比较时出现 8 小时偏差导致超时任务误删订单
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(HmDianPingApplication.class, args);
    }

}
