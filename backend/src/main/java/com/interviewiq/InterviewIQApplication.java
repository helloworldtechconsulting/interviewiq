package com.interviewiq;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@EnableAspectJAutoProxy
public class InterviewIQApplication {

    public static void main(String[] args) {
        SpringApplication.run(InterviewIQApplication.class, args);
    }
}
