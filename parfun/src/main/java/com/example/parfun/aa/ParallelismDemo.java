package com.example.parfun.aa;


import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.*;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.util.concurrent.Futures.allAsList;
import static com.google.common.util.concurrent.Futures.getUnchecked;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static com.pivovarit.function.ThrowingRunnable.sneaky;

@Slf4j
public class ParallelismDemo {

    private static BlockingQueue<Callable<Integer>> getTasks() {
        IntFunction<Integer> square = x -> x * x;
        return IntStream.range(0, 10).<Callable<Integer>>mapToObj(num -> () -> {
            log.info("start {} ", num);
            Uninterruptibles.sleepUninterruptibly(num, TimeUnit.SECONDS);
            int result = square.apply(num);
            log.info("The square of {} is {}", num, result);
            return result;
        }).collect(Collectors.toCollection(() -> new ArrayBlockingQueue<>(10)));
    }



    static class ListenableFutureDemo {
        public static void main(String[] args) {
            ListeningExecutorService e = MoreExecutors.listeningDecorator(Executors.newCachedThreadPool());
            ListeningExecutorService submitter = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());

            int parallelism = 3; // 并发度
            BlockingQueue<Callable<Integer>> tasks = getTasks();
            int n = tasks.size();

            LinkedBlockingQueue<ListenableFuture<Integer>> completionQueue = new LinkedBlockingQueue<>(n);
            AtomicInteger unSubmittedCount = new AtomicInteger(n - parallelism);

            List<ListenableFuture<Integer>> futs = IntStream.range(0, parallelism)
                .mapToObj(i -> submitTaskWithCompletionQueue(e, tasks, unSubmittedCount, completionQueue))
                .collect(toImmutableList());
            List.of(CompletableFuture.completedFuture(1))
                .stream()
                .reduce((a, b) -> a.thenCombine(b, (x, y) -> null));

            ListenableFuture<List<ListenableFuture<Integer>>> submitterFut = submitter.submit(() -> {
                List<ListenableFuture<Integer>> laterFuts = new ArrayList<>(n - parallelism);
                while (unSubmittedCount.get() > 0) {
                    Uninterruptibles.takeUninterruptibly(completionQueue);
                    ListenableFuture<Integer> next = submitTaskWithCompletionQueue(e, tasks, unSubmittedCount, completionQueue);
                    laterFuts.add(next);
                }
                return laterFuts;
            });

            // wait for all tasks to complete
            FluentFuture<List<Integer>> otherFuts = FluentFuture.from(submitterFut).transformAsync(Futures::allAsList, directExecutor());
            Futures.getUnchecked(otherFuts);
            log.info("All tasks completed, result: " + getUnchecked(allAsList(futs)) + getUnchecked(otherFuts));
            e.shutdown();
            submitter.shutdown();
        }

        private static ListenableFuture<Integer> submitTaskWithCompletionQueue(
                ListeningExecutorService e, BlockingQueue<Callable<Integer>> tasks, 
                AtomicInteger unSubmittedCount, BlockingQueue<ListenableFuture<Integer>> completionQueue) {
            ListenableFuture<Integer> f = e.submit(() -> {
                Callable<Integer> r;
                if ((r = tasks.poll()) != null) {
                    return r.call();
                }
                throw new IllegalStateException();
            });
            f.addListener(() -> {
                if (unSubmittedCount.getAndDecrement() > 0) {
                    completionQueue.offer(f);
                }
            }, directExecutor());
            return f;
        }
    }

    public static void foo() {
        ListeningExecutorService pool = listeningDecorator(Executors.newCachedThreadPool());
        ExecutorCompletionService<Object> cs = new ExecutorCompletionService<>(pool);

    }

    @Value
    static class ConcurrentLimitExecutor<V> {
        ListeningExecutorService pool;
        int parallelism;
        BlockingQueue<Future<V>> q;
        ExecutorCompletionService<V> cs = new ExecutorCompletionService<>(pool, q);
        ListeningExecutorService submitter = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());

        public List<ListenableFuture<V>> submitAll(List<Callable<V>> tasks) {
            ImmutableList<ListenableFuture<V>> result = IntStream.range(0, parallelism)
                .mapToObj(__ -> SettableFuture.<V>create())
                .collect(toImmutableList());
            int start = Math.min(tasks.size(), parallelism);
            for (int i = 0; i < start; i++) {
                ListenableFuture<V> f = (ListenableFuture<V>) cs.submit(tasks.get(i));
                copyFuture(f, (SettableFuture<V>) result.get(i));
            }
            submitter.submit(sneaky(() -> {
                int i = start;
                while (i < tasks.size()) {
                    cs.take();
                    ListenableFuture<V> f = (ListenableFuture<V>) cs.submit(tasks.get(i++));
                    copyFuture(f, (SettableFuture<V>) result.get(i));
                }
            }));
            return result;
        }

        private void copyFuture(ListenableFuture<V> from, SettableFuture<V> to) {
            from.addListener(() -> to.setFuture(from), directExecutor());
        }
    }



}