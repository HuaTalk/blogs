package com.example.parfun;

import java.util.Map;
import java.util.concurrent.*;

public class Main {
    public static void main(String[] args) {
//        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1,
//            0, TimeUnit.SECONDS, new SynchronousQueue<>(), new ThreadPoolExecutor.DiscardPolicy());
//        pool.submit(() -> {
//            while(!Thread.interrupted()) {
//
//            }
//            System.out.println("interrupted");
//        });
//        CompletableFuture<Void> cf = CompletableFuture.completedFuture(1)
//            .thenRunAsync(() -> {
//                System.out.println("Hello World!");
//            }, pool);
//        cf.join();
//        pool.shutdownNow();


        Map<String, Integer> map = new ConcurrentHashMap<>(16);
        map.computeIfAbsent(
            "AaAa",
            key -> {
                return map.computeIfAbsent(
                    "BBBB",
                    key2 -> 42);
            }
        );

//        TransmittableThreadLocal<Integer> transmittableThreadLocal = new TransmittableThreadLocal<>();
    }
}
