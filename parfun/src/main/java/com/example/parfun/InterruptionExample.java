package com.example.parfun;

import com.google.common.util.concurrent.Uninterruptibles;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

@Slf4j
public class InterruptionExample {
    public static void main(String[] args) {
        demo1();
    }

    private static void demo1() {
        Thread workerThread = new Thread(() -> {
            int i = 0;
            while (!Thread.interrupted()) {
                log.info("开始任务[{}]", i);
                try {
                    TimeUnit.SECONDS.sleep(1); // 可抛出中断异常
                } catch (InterruptedException e) {
                    log.info("线程 sleep 时中断");
                    // Re-interrupt the thread to propagate the interruption status
                    Thread.currentThread().interrupt();
                }
                log.info("结束任务[{}]", i);
                i++;
            }
            log.info("thread end!");
        });

        workerThread.start();

        try {
            TimeUnit.SECONDS.sleep(5); // Main thread waits for a bit
            workerThread.interrupt(); // Interrupt the worker thread
            log.info("Main thread interrupted worker thread.");
        } catch (InterruptedException e) {
            // 这个异常不会发生，但是受检异常不得不处理
            throw new IllegalStateException(e);
        }
    }


    static void demo2() {
        Thread workerThread = new Thread(() -> {
            int i = 0;
            while (!Thread.interrupted()) {
                log.info("开始任务[{}]", i);
                Uninterruptibles.sleepUninterruptibly(1, TimeUnit.SECONDS);
                log.info("结束任务[{}]", i);
                i++;
            }
            log.info("工作线程结束!");
        });
        workerThread.start();

        Uninterruptibles.sleepUninterruptibly(5, TimeUnit.SECONDS);// Main thread waits for a bit
        workerThread.interrupt(); // Interrupt the worker thread
        log.info("主线程中断工作线程");
    }
}