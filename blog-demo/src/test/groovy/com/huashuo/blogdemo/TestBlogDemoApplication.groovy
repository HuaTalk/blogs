package com.huashuo.blogdemo

import org.springframework.boot.SpringApplication
import org.springframework.boot.test.context.TestConfiguration

@TestConfiguration(proxyBeanMethods = false)
class TestBlogDemoApplication {

    static void main(String[] args) {
        SpringApplication.from(BlogDemoApplication::main).with(TestBlogDemoApplication).run(args)
    }

}
