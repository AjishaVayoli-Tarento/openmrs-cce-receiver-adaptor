package org.openphc.cce.receiver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.retry.annotation.EnableRetry;

@SpringBootApplication
@EnableRetry
@EnableConfigurationProperties
public class CceReceiverAdaptorApplication {

    public static void main(String[] args) {
        SpringApplication.run(CceReceiverAdaptorApplication.class, args);
    }
}
