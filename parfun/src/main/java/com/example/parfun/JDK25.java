package com.example.parfun;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
public class JDK25 {
    public static void main(String[] args) {
        log.info("start");
        CompletableFuture<Object> cf = new CompletableFuture<>();
        cf.orTimeout(5, TimeUnit.SECONDS);

        CompletableFuture<Object> cf2 = new CompletableFuture<>();
        cf2.orTimeout(7, TimeUnit.SECONDS);

        cf.exceptionally(e -> null).thenRun(() -> {
            System.out.println("timeout");
            Thread t = Thread.currentThread();
            System.out.println("t = " + t);
//            Uninterruptibles.sleepUninterruptibly(5, TimeUnit.SECONDS);
        });

        cf2.exceptionally(x -> null).join();
        log.info("cf2 is done: {}", cf2.isDone());
    }
}
