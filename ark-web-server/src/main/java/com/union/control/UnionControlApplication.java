package com.union.control;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.union.control", "com.epcc.arkweb"})
@EnableScheduling
public class UnionControlApplication {
    public static void main(String[] args) {
        SpringApplication.run(UnionControlApplication.class, args);
    }
}
