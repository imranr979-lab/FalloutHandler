package com.nexatel.ssp.porting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Porting Service — manages TN porting lifecycle with ClearPort OMS.
 * Runs on port 8081 within the Kubernetes cluster.
 */
@SpringBootApplication
@EnableCaching
@EnableAsync
public class PortingServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PortingServiceApplication.class, args);
    }
}
