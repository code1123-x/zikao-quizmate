package com.quizmate;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.File;

@SpringBootApplication
@MapperScan("com.quizmate.mapper")
public class QuizMateApplication {

    public static void main(String[] args) {
        new File("data").mkdirs();
        new File("data/media").mkdirs();
        SpringApplication.run(QuizMateApplication.class, args);
    }
}
