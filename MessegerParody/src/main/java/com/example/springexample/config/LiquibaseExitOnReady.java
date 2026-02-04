package com.example.springexample.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Profile("liquibase")
public class LiquibaseExitOnReady {

    private final ApplicationContext context;

    public LiquibaseExitOnReady(ApplicationContext context) {
        this.context = context;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void exitAfterMigration() {
        int code = SpringApplication.exit(context, () -> 0);
        System.exit(code);
    }
}
