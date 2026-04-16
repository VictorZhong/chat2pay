package com.chat2pay.app;

import com.chat2pay.app.config.Chat2PayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(Chat2PayProperties.class)
public class Chat2PayApplication {

    public static void main(String[] args) {
        SpringApplication.run(Chat2PayApplication.class, args);
    }
}
