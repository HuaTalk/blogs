---
title: "CompletableFuture典型错误 —— 代码出自某大厂"
date: 2025-04-25
url: https://juejin.cn/post/7496876253865082932
views: 6147
likes: 82
collects: 130
source: html2md
---

## 一、错误理解

某团技术文章 [《CompletableFuture原理与实践-外卖商家端API的异步化》](<https://link.juejin.cn?target=https%3A%2F%2Ftech.meituan.com%2F2022%2F05%2F12%2Fprinciples-and-practices-of-completablefuture.html> "https://tech.meituan.com/2022/05/12/principles-and-practices-of-completablefuture.html") 文中存在对于CompletableFuture错误用法，我们先来看其表述：

**线程池循环引用会导致死锁** ：

  * 如果父任务和子任务使用同一个线程池，并且线程池已满，子任务可能无法获得线程，从而导致死锁。
  * 解决方案是为父任务和子任务使用不同的线程池，避免循环依赖。


    
    
    public Object doGet() {
      ExecutorService threadPool1 = new ThreadPoolExecutor(10, 10, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(100));
      CompletableFuture cf1 = CompletableFuture.supplyAsync(() -> {
      //do sth
        return CompletableFuture.supplyAsync(() -> {
            System.out.println("child");
            return "child";
          }, threadPool1).join();//子任务
        }, threadPool1);
      return cf1.join();
    }
    

如上代码块所示，doGet方法第三行通过supplyAsync向threadPool1请求线程，并且内部子任务又向threadPool1请求线程。threadPool1大小为10，当同一时刻有10个请求到达，则threadPool1被打满，子任务请求线程时进入阻塞队列排队，但是父任务的完成又依赖于子任务，这时由于子任务得不到线程，父任务无法完成。主线程执行cf1.join()进入阻塞状态，并且永远无法恢复。

为了修复该问题，需要将父任务与子任务做线程池隔离，两个任务请求不同的线程池，避免循环依赖导致的阻塞。

## 二、 正确实现

实际上，即使使用多个线程池，也可能[存在循环引用导致死锁的问题](<https://link.juejin.cn?target=https%3A%2F%2Fgithub.com%2Fdromara%2Fdynamic-tp%2Fissues%2F366> "https://github.com/dromara/dynamic-tp/issues/366")，出现了问题依然难以监控。

文中的问题可以通过 CompletableFuture 的函数式回调功能更好地实现：
    
    
    public class AsyncOperation {
        static final Executor threadPool1 = Executors.newFixedThreadPool(10);
        // 略去线程池定义
        public CompletableFuture<String> doGet() {
            return CompletableFuture.supplyAsync(() -> {
                // do sth
                return "parent";
            }, threadPool1).thenComposeAsync(parentResult -> {
                // 子任务
                return CompletableFuture.supplyAsync(() -> {
                    System.out.println("child");
                    return "child";
                }, threadPool1);
            }, threadPool1).thenApplyAsync(x -> {
                // ...
                return "end";
            }, threadPool1);
        }
    
        public static void main(String[] args) {
            AsyncOperation operation = new AsyncOperation();
            CompletableFuture<String> future = operation.doGet();
            // ...
            String result = future.orTimeout(200, TimeUnit.MILLISECONDS)
                    .join();
            // ...
        }
    }
    

### 代码说明：

#### 2.1 避免同步阻塞

原代码中通过 `.join()` **同步阻塞父任务线程** ，导致父任务必须等待子任务完成才能释放线程资源。当线程池满时，子任务无法获取线程，父任务也无法完成，形成死锁。正确的做法是 使用 `thenComposeAsync` 替代嵌套的 `join()`，将子任务与父任务异步串联，避免阻塞父任务线程。

#### 2.2 线程池资源释放

通过 `orTimeout` 添加超时控制，防止无限期阻塞。

## 三、进一步讨论

#### 3.1 避免嵌套的 Future

以下是Guava文档对于嵌套Future问题的讨论, CompletableFuture 同理：

在代码调用通用接口并返回 `Future` 的情况下，可能会产生嵌套的 `Future`。例如：
    
    
    executorService.submit(new Callable<ListenableFuture<Foo>>() {
      @Override
      public ListenableFuture<Foo> call() {
        return otherExecutorService.submit(otherCallable);
      }
    });
    

这段代码会返回一个 `ListenableFuture<ListenableFuture<Foo>>`。这种写法是错误的，因为如果外部 `Future` 的取消操作与外部 `Future` 的完成操作发生竞争，这种取消操作将无法传播到内部的 `Future`。此外，如果使用 `get()` 或监听器检查内部 `Future` 的失败，除非特别小心处理，否则 `otherCallable` 抛出的异常可能会被抑制。

为了避免这些问题，Guava 的所有 `Future` 处理方法（以及 JDK 中的部分方法）都提供了 `*Async` 版本，可以安全地解构这种嵌套。例如：

  * `transform(ListenableFuture<A>, Function<A, B>, Executor)` 和 `transformAsync(ListenableFuture<A>, AsyncFunction<A, B>, Executor)`
  * `ExecutorService.submit(Callable)` 和 `submitAsync(AsyncCallable<A>, Executor)` 等。



#### 3.2 如果线程池处理不了，应该在使用之前就拦截掉

CompletableFuture 没有直接支持取消（有折中方法，感兴趣可请参考 Spring 中 DelegatingCompletableFuture实现)，在资源不足时，会产生问题。对于时间敏感任务，通常会根据目标响应时间进行提前拒绝或者降级。

线程池虽然本身支持流量控制，但是在复杂场景中，也需要进行多维度的流量控制。

#### 3.3 取消了的任务可能还在任务队列中

对于普通线程池，取消了的任务可能还在任务队列中，线程池支持purge等方法进行清理。

#### 3.4 设置超时时间

目前很多公司都要求异步任务必须设置超时时间，这一规范提升了系统的容错能力。Guava 提供的超时机制支持取消传播和线程中断，遇到复杂问题时，可以考虑使用。

#### 3.5 关注线程池监控数据

比如 CPU 使用率、任务吞吐量、任务等待时间、拒绝任务数等指标，对于线程池参数进行合理配置。