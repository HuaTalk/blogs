package com.example.parfun;

import com.google.common.base.Throwables;

import java.io.IOException;
import java.util.concurrent.CancellationException;

public class T {
    public static void main(String[] args) {
//        ArrayDeque<Integer> deque = new ArrayDeque<>();
//        for (int i = 0; i < 100; i++) deque.add(i);
//        Stream<Integer> s = deque.stream()
//            .skip(13);
//        Integer i = s
//            .findFirst()
//            .orElseThrow();
//        System.out.println("i = " + i);
//        Stopwatch stopwatch = Stopwatch.createStarted();
//
//        new Thread(() -> {}, "234").start();
//        stopwatch.stop();
//        System.out.println(stopwatch.elapsed(TimeUnit.MILLISECONDS));
        Throwable canceled = new CancellationException("canceled");
        Throwables.throwIfInstanceOf(canceled, CancellationException.class);
        Throwables.throwIfInstanceOf(canceled, IOException.class);
    }
}
