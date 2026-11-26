package com.syncspace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // presence TTL-expiry sweep (PresenceSweeper)
public class SyncSpaceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SyncSpaceApplication.class, args);
    }
}
