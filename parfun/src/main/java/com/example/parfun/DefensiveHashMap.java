package com.example.parfun;

import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

public class DefensiveHashMap {

//    @NonBlockingExecutor
    private static final ExecutorService executorService  = Executors.newCachedThreadPool();

    public static void main(String[] args) {
        var map = new HashMap<>();
        IntStream.range(0, 10000).parallel()
            .forEach(i -> {
                System.out.println("thread = " + Thread.currentThread().getName());
                map.put(i, i);
            });
        System.out.println("map.size() = " + map.size());
        executorService.execute(DefensiveHashMap::task);
    }

//    @Blocking
    public static void task() {
        System.out.println("task = " + Thread.currentThread().getName());
    }
}
