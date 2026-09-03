package com.union.control;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportResource;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.union.control.mapper")
@EnableScheduling
@ImportResource("classpath:dubbo-provider.xml")
public class ArkControlApplication {
    public static void main(String[] args) {
        SpringApplication.run(ArkControlApplication.class, args);
    }
}
