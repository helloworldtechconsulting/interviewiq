package com.interviewengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * InterviewEngine Backend — application entry point.
 *
 * <p>Virtual threads are enabled via {@code spring.threads.virtual.enabled=true}
 * in application.yml, which instructs Spring Boot to use Tomcat's virtual-thread
 * executor and the virtual-thread-based task executor for {@code @Async} work.
 *
 * <p>Component scan root is {@code com.interviewengine}; every module sub-package
 * (auth, company, job, candidate, session, ai, billing, webhook, storage, email,
 * shared) is discovered automatically.
 *
 * <p>{@code @ConfigurationPropertiesScan} registers all {@code @ConfigurationProperties}
 * classes under {@code com.interviewengine} without requiring per-class
 * {@code @EnableConfigurationProperties} declarations.
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
@ConfigurationPropertiesScan
public class InterviewEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(InterviewEngineApplication.class, args);
    }
}
