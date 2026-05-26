plugins {
    java
    id("org.springframework.boot") version "3.5.4"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-web")
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // https://mvnrepository.com/artifact/com.github.ben-manes.caffeine/caffeine
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.2")
// https://mvnrepository.com/artifact/com.google.guava/guava
    implementation("com.google.guava:guava:33.4.8-jre")
    implementation("org.jooq:jool-java-8:0.9.15")
    implementation("org.jetbrains:annotations:24.1.0")
    // parallel collector
    implementation("com.pivovarit:parallel-collectors:3.3.0")
    // cffu2
    implementation("io.foldright:cffu2:2.0.2")
    implementation("com.alibaba:transmittable-thread-local:2.14.5")
    implementation("com.pivovarit:throwing-function:1.6.1")

}

tasks.withType<Test> {
    useJUnitPlatform()
}
