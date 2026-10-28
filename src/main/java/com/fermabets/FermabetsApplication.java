package com.fermabets;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
@ConfigurationPropertiesScan
public class FermabetsApplication {
    public static void main(String[] args) {
        // Form values such as "28.5" must parse the same on every machine, whatever the OS locale.
        java.util.Locale.setDefault(java.util.Locale.US);
        SpringApplication.run(FermabetsApplication.class, args);
    }
}
