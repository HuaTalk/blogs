---
title: "并发编程与视图——简单方法返回异步 Map<Key, Data>"
date: 2025-06-23
url: https://juejin.cn/post/7518450939427520523
views: 134
likes: 3
collects: 0
source: html2md
---

Future 的基本用法与Maps#transformValue

本文所指的Future 适用于 Future 类型或者其子类如 CompletableFuture或者其子接口 ListenableFuture。

## 需求描述

我们在业务开发中常常遇到这种场景，执行业务时需要获取一些异步数据，常见的优化是在请求尽量早的位置且再业务处理前进行数据预加载，这些预加载数据类型为Future。

这些数据可以组成集合类 `List<Future>`，更常见的集合类型是 `Map<String, Future<Data>>`。

实际上，对于后续使用方来说，其可能更愿意接受使用的是不带Future的版本。可以类比 Guava Cache 实现，获取的数据为真实类型而不是 Future 包装类型。

## Guava 与懒计算视图

对于Map来说，可以结合Guava提供的Map#transform方法，添加懒计算。这种实现稍微容易理解一点，可以不使用 monadic 方法，monadic 方法指的是

  1. map: CompletableFuture#thenApply, ListenableFuture##transform
  2. flatMap: Completable#thenCompose, ListenableFuture##transformAsync



以比价平台简单举例，需要加载某东、某宝等商品价格数据。
    
    
    // preload data
    Map<String, Future<ProductInfo>> loadingAllData = xxx;
    // 仅做简单举例，实际业务根据具体情况调整
    Map<String, ProductInfo> allProducts = Maps.transformValues(loadingAllData, Futures#getUnchecked);
    ​
    // 其他业务逻辑
    ​
    // process data
    var xxProduct = allProducts.get("xx");
    List<ProductDetail> processedProducts = new ArrayList<>();
    if (xxProduct != null) {
      processedProducts.add(processXxProduct(xxProduct));
    }
    processedProducts.stream().forEach(System.out::println);
    

同理，对于多值Map，可以使用 Mulitmaps#transformValues。

这里需要注意的是：

  1. 使用 allProducts 就像使用普通map一样，但是获取value值时实际上会执行 Futures#getUnchecked，后续使用方便。


  2. getUnchecked 可以改为带超时的get方法。一种是Future自带的get方法，另一种是 Guava Futures#withTimeout，这种超时可以保证超时返回超时异常，并且发送中断执行线程信号。


    
    
    Map<String, ListenableFuture<Integer>> futureMap = Map.of("1", Futures.immediateFuture(1));
    Map<String, Integer> simpleMap = Maps.transformValues(futureMap, f -> {
        FluentFuture<Integer> timedFuture = FluentFuture.from(Futures.withTimeout(f, Duration.ofMillis(250), timer))
                .catching(TimeoutException.class, e -> -1, MoreExecutors.directExecutor());
        return Futures.getUnchecked(timedFuture);
    });
    

  3. 考虑happy-path，这种实现可以简化代码使用，避免出错，便于后续使用方使用。类似于 LoadingCache 。
  4. 异常场景需要注意，由于集合Future可能存在多个异常，需要做好异常处理。一般来说是做好日志，fallback，熔断等功能。
  5. 懒计算可以尽量保证取 value 时，数据加载已经进行了尽可能足够长的时间。假设数据加载需要300ms，超时时间设置为250ms，在提交加载后100调用Map#get 方法可以成功。