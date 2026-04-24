package com.chat2pay.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class Chat2PayApplication {

    public static void main(String[] args) {
        SpringApplication.run(Chat2PayApplication.class, args);
    }
}
