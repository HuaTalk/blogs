package com.example.parfun;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.*;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.toList;

@Slf4j
public class ParallelismDemo {

    private static BlockingQueue<Runnable> getTasks() {
        IntFunction<Integer> square = x -> x * x;
        return IntStream.range(0, 10).<Runnable>mapToObj(num -> () -> {
            log.info("start {} ", num);
            Uninterruptibles.sleepUninterruptibly(num, TimeUnit.SECONDS);
            int result = square.apply(num);
            log.info("The square of {} is {}", num, result);
        }).collect(Collectors.toCollection(() -> new ArrayBlockingQueue<>(10)));
    }

    static class RaceQueueDemo {
        public static void main(String[] args) {
            ListeningExecutorService e = MoreExecutors.listeningDecorator(Executors.newCachedThreadPool());
            ListeningScheduledExecutorService scheduler = MoreExecutors.listeningDecorator(new ScheduledThreadPoolExecutor(1));
            int parallelism = 3; // 并发度
            BlockingQueue<Runnable> tasks = getTasks();
            int size = tasks.size();

            List<ListenableFuture<Void>> futs = IntStream.range(0, parallelism)
                .mapToObj(i -> e.<Void>submit(() -> {
                    Runnable r;
                    if ((r = tasks.poll()) != null) {
                        r.run();
                    }
                    return null;
                }))
                .collect(toList());

            ImmutableList<ListenableFuture<Void>> ordered = Futures.inCompletionOrder(futs);
            while ((futs.size()) != size) {
                // any task completed, submit a new task
                FluentFuture<Void> nextFut = FluentFuture.from(ordered.getFirst())
                    .transformAsync(__ -> e.submit(() -> {
                        Runnable r;
                        if ((r = tasks.poll()) != null) {
                            r.run();
                        }
                        return null;
                    }), scheduler);
                futs.add(nextFut);
                ordered = Futures.inCompletionOrder(
                    ImmutableList.<ListenableFuture<Void>>builder()
                        .addAll(ordered.subList(1, ordered.size()))
                        .add(nextFut)
                        .build()
                );
            }

            // wait for all tasks to complete
            ListenableFuture<?> allComplete = Futures.whenAllComplete(futs)
                .run(() -> log.info("All tasks completed"), e);
            Futures.getUnchecked(allComplete);
            e.shutdown();
            scheduler.shutdown();
        }
    }

    private static void submitNexts(List<ListenableFuture<Void>> futs, int size, ListeningExecutorService e, BlockingQueue<Runnable> tasks, ListeningScheduledExecutorService scheduler) {
        var ordered = Futures.inCompletionOrder(futs);
        while ((futs.size()) != size) {
            // any task completed, submit a new task
            FluentFuture<Void> nextFut = FluentFuture.from(ordered.getFirst())
                .transformAsync(__ -> e.submit(() -> {
                    Runnable r;
                    if ((r = tasks.poll()) != null) {
                        r.run();
                    }
                    return null;
                }), scheduler);
            futs.add(nextFut);
            ordered = Futures.inCompletionOrder(
                ImmutableList.<ListenableFuture<Void>>builder()
                    .addAll(ordered.subList(1, ordered.size()))
                    .add(nextFut)
                    .build()
            );
        }
    }

    private static void submitNextsV2(List<ListenableFuture<Void>> futs, int size, ListeningExecutorService e, BlockingQueue<Runnable> tasks, ListeningScheduledExecutorService scheduler) {
        var ordered = Futures.inCompletionOrder(futs);
        while (futs.size() < size) {
            // any task completed, submit a new task
            FluentFuture<Void> nextFut = FluentFuture.from(ordered.getFirst())
                .transformAsync(__ -> e.submit(() -> {
                    Runnable r;
                    if ((r = tasks.poll()) != null) {
                        r.run();
                    }
                    return null;
                }), scheduler);
            futs.add(nextFut);
            ordered = Futures.inCompletionOrder(
                ImmutableList.<ListenableFuture<Void>>builder()
                    .addAll(ordered.subList(1, ordered.size()))
                    .add(nextFut)
                    .build()
            );
        }
    }
}
