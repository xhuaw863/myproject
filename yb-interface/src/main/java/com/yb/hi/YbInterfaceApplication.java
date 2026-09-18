package com.yb.hi;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.yb.hi.**.mapper")
@EnableScheduling
public class YbInterfaceApplication {
    public static void main(String[] args) {
        SpringApplication.run(YbInterfaceApplication.class, args);
    }
}
