package com.example.parfun;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.CompletableFuture.supplyAsync;

public class DiscardDemo {
    public static void main(String[] args) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1,
                0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), new ThreadPoolExecutor.AbortPolicy());
        pool.submit(() -> {
            try {
                Thread.sleep(Duration.ofDays(1));
            } catch (InterruptedException e) {
                System.out.println("interrupted");
            }
        });
        CompletableFuture<Void> cf = supplyAsync(() -> 1, pool)
                .thenAccept(System.out::println);
        cf
//                .orTimeout(3, TimeUnit.SECONDS)
//                .exceptionally(ex -> {
//                    System.out.println("exception: " + ex);
//                    return null;
//                })
                .join();

        pool.shutdownNow();
    }
}