package ru.itmo.advisor.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AdvisorApplication {
    public static void main(String[] args) {
        SpringApplication.run(AdvisorApplication.class, args);
    }
}
