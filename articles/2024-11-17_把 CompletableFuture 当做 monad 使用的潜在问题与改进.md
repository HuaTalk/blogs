---
title: "把 CompletableFuture 当做 monad 使用的潜在问题与改进"
date: 2024-11-17
url: https://juejin.cn/post/7438110650725646336
views: 295
likes: 4
collects: 2
source: html2md
---

## 把 CompletableFuture 当做 monad 使用的潜在问题与改进

笔者在上一篇文章中分析了单子(Monad) 的一些特点和使用场景。实际上，Java 中 CompletableFuture 也可以当做 monad 使用：忽略CompletableFuture 提供的额外写操作（如complete,obtrudeValue等） ，我们知道 CompletableFuture 支持 completedFuture, thenApply, thenCompose 操作，这类操作实际上对应于Future monad 中定义的方法 pure, map, flatMap，此时CompletableFuture可以等同于 Future monad 使用。

本文我们就来讨论一下当 CompletableFuture 视为 Future monad 使用时，可能有哪些代码问题。

### 1\. For-comprehension 与 Lambda 表达式调用

代码来自：[Virtual threads: Are futures a thing of the past?](<https://link.juejin.cn?target=https%3A%2F%2Fblogs.oracle.com%2Fjavamagazine%2Fpost%2Fvirtual-threads-futures> "https://blogs.oracle.com/javamagazine/post/virtual-threads-futures")

作者举了许多例子实现如下业务逻辑，返回结果需要组合天气、餐厅、电影院三种独立信息。我们来看看是否可以优化一下：
    
    
    public class Server {
      private final ServerSocket server = new ServerSocket(port);
    ​
      public void run() {
        while (!server.isClosed()) {
          var socket = server.accept();
          handleRequest(socket);
        }
      }
    ​
      void handleRequest(Socket socket) {
        var request = new Request(socket);              // parse a request
        var page = new Page(request);                   // create a base page
        page.setWeather(Weather.fetch(request))         // add weather info to the page
            .setRestaurants(Restaurants.fetch(request)) // add restaurant info to the page
            .setTheaters(Theaters.fetch(request)).      // add theater info to the page
            .send();                                    // send the page back as a response
      }
    }
    

以上代码为单线程实现的情况，其总耗时为三个阻塞请求的和。
    
    
    void handleRequest(Socket socket) {
      var request = new Request(socket);
      var futureWeather = CompletableFuture.supplyAsync(() -> Weather.fetch(request), exec2);
      var futureRestaurants = CompletableFuture.supplyAsync(() -> Restaurants.fetch(request), exec2);
      var futureTheaters = CompletableFuture.supplyAsync(() -> Theaters.fetch(request), exec2);
    ​
      new Page(request)
          .setWeather(futureWeather.join())
          .setRestaurants(futureRestaurants.join())
          .setTheaters(futureTheaters.join())
          .send();
    }
    

以上代码为异步任务的简单实现，join的多次调用相当于对于 Future monad 的多次解包。

我们来看下作者给出的使用 CompletableFuture 优化后的代码，主要使用了 CompletableFuture 的回调方法：
    
    
    void handleRequest(Socket socket) {
      var futureRequest = CompletableFuture.supplyAsync(() -> new Request(socket), exec);
    ​
      var futureWeather = futureRequest.thenApplyAsync(Weather::fetch, exec);
      var futureRestaurants = futureRequest.thenApplyAsync(Restaurants::fetch, exec);
      var futureTheaters = futureRequest.thenApplyAsync(Theaters::fetch, exec);
    ​
      futureRequest
          .thenApplyAsync(Page::new, exec)
          .thenCombine(futureWeather, Page::setWeather)
          .thenCombine(futureRestaurants, Page::setRestaurants)
          .thenCombine(futureTheaters, Page::setTheaters)
          .thenAccept(Page::send);
      }
    }
    

以上代码充分 CompletableFuture 的回调功能，其利用了thenCombine 实现了两个 Future monad 之间的解包，等效使用 flatMap、map的形式如下：
    
    
    // 简化后的代码，直接使用构造器创建 Page
    futureWeather.thenCompose(weather -> 
      futureRestaurants.thenCompose(res -> 
        futureTheaters.thenApply(ths -> 
          new Page(weather, res, ths))))
    .thenAccept(Page::send);
    

等价的 Scala for-comprehension 表示为：
    
    
    // 模拟外部异步操作
    val futureWeather: Future[String] = Future { /* 获取天气数据的逻辑 */ }
    val futureRestaurants: Future[String] = Future { /* 获取餐馆数据的逻辑 */ }
    val futureTheaters: Future[String] = Future { /* 获取剧院数据的逻辑 */ }
    ​
    // 创建Page类，模拟构造页面对象
    case class Page(weather: String, restaurants: String, theaters: String) {
      def send(): Unit = println("Page sent")
    }
    ​
    // 使用for-comprehension来实现
    val result: Future[Unit] = for {
      weather <- futureWeather  // 获取天气数据
      restaurants <- futureRestaurants  // 获取餐馆数据
      theaters <- futureTheaters  // 获取剧院数据
      page = Page(weather, restaurants, theaters)  // 创建Page对象
    } yield {
      page.send()  // 最终发送页面
    }
    ​
    // 执行并等待结果
    Await.result(result, 10.seconds)
    

for-comprehension实际上是flatMap, map调用的语法糖，对于 for-comprehension 可以简单理解为：反向箭头 <\- 表示解包，yield 返回最终结果，结果仍然被包在 Future monad 里。

### 2\. 模式匹配（JDK19+)

先简单讨论一下模式匹配，下一节将专注讨论 Monad 的问题：

Java 在 8 版本后对于函数式编程进行了进一步的支持，新增如模式匹配，record 类型，封装类等特性，但是Future接口积重难返，于是在 JDK19 中官方提出了一种“曲线救国”的方法。这种方法的缺点是需要开发者自己匹配状态与操作，Success <=> resultNow， FAILED <=> exceptionNow。同时由于Future提供的get方法和CompletableFuture提供的join方法，阻塞的同时可能会在获取结果时抛异常（如果有异常），这种不是函数式的处理方式，需要对于代码进行一定的 trick 处理：
    
    
    // 等待并记录结果
    static void waitResultAndLog(CompletableFuture<?> result) {
        result.copy().exceptionally(ex -> null).join();
        switch (result.state()) {
            case SUCCESS -> log.info("success: {}", result.resultNow());
            case FAILED -> log.error("error: ", result.exceptionNow());
            case CANCELLED, RUNNING -> throw new IllegalStateException();
        }
    }
    

### 3\. 它不是普通的单子,他是有副作用的单子

由于 Future monad 封装了异步调用副作用，实际上异步调用的时间容易被忽略，当遇到某个请求异常时，其调用不是立即结束的。对于最终的 Future 调用阻塞方法 join, 其效果等价于依次调用阻塞方法。
    
    
    @Slf4j
    class FuturePageDemo {
        record Page(String weather, String restaurants, String theaters) {
            public void send() {
                log.info("Page sent with weather: {}, restaurants: {}, theaters: {}", weather, restaurants, theaters);
            }
        }
    ​
        public static void main(String[] args) throws InterruptedException {
            // 模拟获取异步数据
            var futureWeather = CompletableFuture.supplyAsync(sleepAndGet(2, () -> "Sunny"));
            var futureRestaurants = CompletableFuture.supplyAsync(sleepAndGet(3,
                () -> { throw new RuntimeException("获取餐厅失败"); }));
            var futureTheaters = CompletableFuture.supplyAsync(sleepAndGet(4, () -> "Broadway Show"));
    ​
            sendPage(futureWeather, futureRestaurants, futureTheaters);
        }
    ​
        static void sendPage(CompletableFuture<String> futureWeather,
                             CompletableFuture<String> futureRestaurants,
                             CompletableFuture<String> futureTheaters) {
            // 使用thenCompose和thenApply实现异步流程
            var result = futureWeather.thenCompose(weather -> {
                    log.info("start1");
                    return futureRestaurants.thenCompose(restaurants -> {
                        log.info("start2");
                        return futureTheaters.thenApply(theaters -> {
                            log.info("start3");
                            return new Page(weather, restaurants, theaters);
                        });
                    });
                })
                .thenAccept(Page::send);  // 发送页面
    ​
            // 阻塞等待异步操作完成
            waitResultAndLog(result);
        }
    ​
        static Supplier<String> sleepAndGet(int seconds, Supplier<String> supplier) {
            return () -> {
                log.info("start");
                try {
                    Thread.sleep(seconds * 1000L);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                log.info("block end");
                return supplier.get();
            };
        }
    }
    

**case: 短路逻辑**

对于调用进行分析：调用获取餐厅时任务出现异常，Future monad 在 for-comprehension 式的运算中，**异常执行短路逻辑** ，出现失败后，后续操作不再执行，所以内部的两个lambda表达式，或者说`log.info("start2");`, `log.info("start3");`均不会执行。

建议读者可以自己先在脑海里演算一下结果和日志，再对比一下实际日志：
    
    
    22:32:06.359 [ForkJoinPool.commonPool-worker-1] INFO com.example.demo.cf.FuturePageDemo -- start
    22:32:06.359 [ForkJoinPool.commonPool-worker-2] INFO com.example.demo.cf.FuturePageDemo -- start
    22:32:06.359 [ForkJoinPool.commonPool-worker-3] INFO com.example.demo.cf.FuturePageDemo -- start
    22:32:08.366 [ForkJoinPool.commonPool-worker-1] INFO com.example.demo.cf.FuturePageDemo -- block end
    22:32:08.366 [ForkJoinPool.commonPool-worker-1] INFO com.example.demo.cf.FuturePageDemo -- start1
    22:32:09.366 [ForkJoinPool.commonPool-worker-2] INFO com.example.demo.cf.FuturePageDemo -- block end
    22:32:09.369 [main] ERROR com.example.demo.cf.FuturePageDemo -- error: 
    java.lang.RuntimeException: 获取餐厅失败
      at com.example.demo.cf.FuturePageDemo.lambda$main$1(FuturePageDemo.java:22)
      at com.example.demo.cf.FuturePageDemo.lambda$sleepAndGet$7(FuturePageDemo.java:66)
      at java.base/java.util.concurrent.CompletableFuture$AsyncSupply.run(CompletableFuture.java:1768)
      at java.base/java.util.concurrent.CompletableFuture$AsyncSupply.exec(CompletableFuture.java:1760)
      at java.base/java.util.concurrent.ForkJoinTask.doExec(ForkJoinTask.java:387)
      at java.base/java.util.concurrent.ForkJoinPool$WorkQueue.topLevelExec(ForkJoinPool.java:1312)
      at java.base/java.util.concurrent.ForkJoinPool.scan(ForkJoinPool.java:1843)
      at java.base/java.util.concurrent.ForkJoinPool.runWorker(ForkJoinPool.java:1808)
      at java.base/java.util.concurrent.ForkJoinWorkerThread.run(ForkJoinWorkerThread.java:188)
    

对于最终的结果，实际上无论 futureTheaters 是成功还是失败，其结果对于最终结果均没有影响，因为其计算已经被短路了。这里需要注意的是，futureTheaters 实际上还在执行，因为公共线程池是守护线程，当主线程执行结束后，commonPool-worker-3 被销毁了。CompletableFuture 有一个缺点是无法阻止或者中断正在执行线程中执行的任务。

**case: happy-path** 修改入参 Future 的实现：
    
    
    var futureWeather = CompletableFuture.supplyAsync(() -> "Sunny");
    var futureRestaurants = CompletableFuture.supplyAsync(() -> "Italian Restaurant");
    var futureTheaters = CompletableFuture.supplyAsync(() -> "Broadway Show");
    

控制台输出如下：
    
    
    22:46:47.052 [main] INFO com.example.demo.cf.FuturePageDemo -- start1
    22:46:47.053 [main] INFO com.example.demo.cf.FuturePageDemo -- start2
    22:46:47.053 [main] INFO com.example.demo.cf.FuturePageDemo -- start3
    22:46:47.053 [main] INFO com.example.demo.cf.FuturePageDemo -- Page sent with weather: Sunny, restaurants: Italian Restaurant, theaters: Broadway Show
    22:46:47.054 [main] INFO com.example.demo.cf.FuturePageDemo -- success: null
    

**case: 多余的等待**

修改任务执行时间为 4s, 3s, 2s:
    
    
    var futureWeather = CompletableFuture.supplyAsync(sleepAndGet(4, () -> "Sunny"));
    var futureRestaurants = CompletableFuture.supplyAsync(sleepAndGet(3,
        () -> { throw new RuntimeException("获取餐厅失败"); }));
    var futureTheaters = CompletableFuture.supplyAsync(sleepAndGet(2, () -> "Broadway Show"));
    

执行日志如下：
    
    
    22:41:57.466 [ForkJoinPool.commonPool-worker-2] INFO com.example.demo.cf.FuturePageDemo -- start
    22:41:57.466 [ForkJoinPool.commonPool-worker-1] INFO com.example.demo.cf.FuturePageDemo -- start
    22:41:57.466 [ForkJoinPool.commonPool-worker-3] INFO com.example.demo.cf.FuturePageDemo -- start
    22:41:59.472 [ForkJoinPool.commonPool-worker-3] INFO com.example.demo.cf.FuturePageDemo -- block end
    22:42:00.472 [ForkJoinPool.commonPool-worker-2] INFO com.example.demo.cf.FuturePageDemo -- block end
    22:42:01.472 [ForkJoinPool.commonPool-worker-1] INFO com.example.demo.cf.FuturePageDemo -- block end
    22:42:01.473 [ForkJoinPool.commonPool-worker-1] INFO com.example.demo.cf.FuturePageDemo -- start1
    22:42:01.476 [main] ERROR com.example.demo.cf.FuturePageDemo -- error: 
    java.lang.RuntimeException: 获取餐厅失败
      at com.example.demo.cf.FuturePageDemo.lambda$main$1(FuturePageDemo.java:22)
      at com.example.demo.cf.FuturePageDemo.lambda$sleepAndGet$7(FuturePageDemo.java:66)
      at java.base/java.util.concurrent.CompletableFuture$AsyncSupply.run(CompletableFuture.java:1768)
      at java.base/java.util.concurrent.CompletableFuture$AsyncSupply.exec(CompletableFuture.java:1760)
      at java.base/java.util.concurrent.ForkJoinTask.doExec(ForkJoinTask.java:387)
      at java.base/java.util.concurrent.ForkJoinPool$WorkQueue.topLevelExec(ForkJoinPool.java:1312)
      at java.base/java.util.concurrent.ForkJoinPool.scan(ForkJoinPool.java:1843)
      at java.base/java.util.concurrent.ForkJoinPool.runWorker(ForkJoinPool.java:1808)
      at java.base/java.util.concurrent.ForkJoinWorkerThread.run(ForkJoinWorkerThread.java:188)
    

从日志可以看出，最终的异常结果需要经过4秒才能获得，实际上我们在3s的时候已经可以知道任务已经失败。那么这种问题怎么解决呢，你可以使用 CFFU 中提供的 fail-fast 方法(Guava中也提供了相关方法: allAsList 用于处理 List, whenAllSucceed 用于处理 Tuple），其实现的基本原理是当获取到某个异常结果时调用回调方法，通知主程序或者某个对象任务已经失败。

**case: 使用 CFFU fail-fast 便利方法优化**

再修改计算结果方法如下：
    
    
    var result = CompletableFutureUtils.allTupleFailFastOf(futureWeather, futureRestaurants, futureTheaters)
            .thenApply(t -> new Page(t._1, t._2, t._3))
            .thenAccept(Page::send);  // 发送页面
    

执行日志如下：
    
    
    22:01:43.427 [ForkJoinPool.commonPool-worker-3] INFO com.example.blogdemo.futuremonad.FuturePageDemo -- start
    22:01:43.427 [ForkJoinPool.commonPool-worker-2] INFO com.example.blogdemo.futuremonad.FuturePageDemo -- start
    22:01:43.427 [ForkJoinPool.commonPool-worker-1] INFO com.example.blogdemo.futuremonad.FuturePageDemo -- start
    22:01:45.430 [ForkJoinPool.commonPool-worker-3] INFO com.example.blogdemo.futuremonad.FuturePageDemo -- block end
    22:01:46.433 [ForkJoinPool.commonPool-worker-2] INFO com.example.blogdemo.futuremonad.FuturePageDemo -- block end
    22:01:46.436 [main] ERROR com.example.blogdemo.futuremonad.FuturePageDemo -- error: 
    java.lang.RuntimeException: 获取餐厅失败
      at com.example.blogdemo.futuremonad.FuturePageDemo.lambda$main$1(FailFastPageSending.java:23)
      at com.example.blogdemo.futuremonad.FuturePageDemo.lambda$sleepAndGet$4(FailFastPageSending.java:50)
      at java.base/java.util.concurrent.CompletableFuture$AsyncSupply.run(CompletableFuture.java:1768)
      at java.base/java.util.concurrent.CompletableFuture$AsyncSupply.exec(CompletableFuture.java:1760)
      at java.base/java.util.concurrent.ForkJoinTask.doExec(ForkJoinTask.java:387)
      at java.base/java.util.concurrent.ForkJoinPool$WorkQueue.topLevelExec(ForkJoinPool.java:1312)
      at java.base/java.util.concurrent.ForkJoinPool.scan(ForkJoinPool.java:1843)
      at java.base/java.util.concurrent.ForkJoinPool.runWorker(ForkJoinPool.java:1808)
      at java.base/java.util.concurrent.ForkJoinWorkerThread.run(ForkJoinWorkerThread.java:188)
    

和上一个 case 相比，异常结果经过3秒就获得了，符合预期。

### 4\. sequence

Vavr 类库实现了Future monad，我们来分析一下 Vavr 函数式实现“遍历” futures 的代码：
    
    
    static <T> Future<Seq<T>> sequence(Executor executor, Iterable<? extends Future<? extends T>> futures) {
        Objects.requireNonNull(executor, "executor is null");
        Objects.requireNonNull(futures, "futures is null");
        final Future<Seq<T>> zero = successful(executor, Stream.empty());
        final BiFunction<Future<Seq<T>>, Future<? extends T>, Future<Seq<T>>> f =
                (result, future) -> result.flatMap(seq -> future.map(seq::append));
        return Iterator.ofAll(futures).foldLeft(zero, f);
    }
    

  1. Seq 可以理解为支持下标访问的顺序List数据结构，为不可变类型。Stream 是懒计算的实现，最终 sequence 方法时间复杂度为o(n)。如果底层使用Array，则o(n^2)。
  2. foldLeft 方法和 Java 流式编程中的reduce操作类似，每次取上一步运算的结果和当前遍历的值进行计算，返回结果供下次计算，若为最后一次运算，运算后直接返回结果。这段代码中的运算为列表拼接，为典型的monoid，单位元为 Stream.empty()，只不过使用Future monad 包装了副作用。
  3. 回调执行流程为迭代顺序。当一个result 计算结束（获得Future 的封装值）时，回调 flatMap 中的lambda表达式，等待下一个遍历节点结束，下一个遍历节点结束时回调seq::append方法，新的result计算结束。以上流程持续进行直至遍历结束。
  4. 这种实现和 for-comprehension 的实现一样，都会有多余等待的问题。



### 总结

最佳实践是使用快速失败的方式返回结果，如果你对性能或者时间上没有太多要求，for-comprehension 的方式也是可以满足需要的。