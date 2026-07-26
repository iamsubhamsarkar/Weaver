package com.weaver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.weaver.config.WeaverConfigProperties;

@SpringBootApplication
@EnableConfigurationProperties(WeaverConfigProperties.class)
public class WeaverApplication {

    public static void main(String[] args) {
        SpringApplication.run(WeaverApplication.class, args);
    }
}
