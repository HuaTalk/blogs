package com.huashuo.blog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;

@TestConfiguration(proxyBeanMethods = false)
public class TestBlogApplication {

    public static void main(String[] args) {
        SpringApplication.from(BlogApplication::main).with(TestBlogApplication.class).run(args);
    }

}
