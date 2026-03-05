---
title: "实战分析 ConcurrentHashMap.computeIfAbsent 的锁冲突问题"
date: 2026-02-27
url: https://juejin.cn/post/7611069767186956294
views: 19
likes: 2
collects: 0
source: html2md
---

> 最近解决了一个线上性能问题，项目中大量使用自己实现的本地缓存，其锁冲突问题很常见，也容易被忽略。本文总结了这个问题，由AI辅助编写，我做了部分修改。实际上，如果没有使用CMH，而是使用 Caffeine 提供的相关抽象实现本地缓存，这类问题可以尽可能地避免，推荐大家使用成熟的轮子；否则很可能会重复踩坑。
> 
> 不禁感叹：多线程的坑是真的多！

大家好，我是桦说编程。

> 线上7个线程同时 BLOCKED 在同一个 ConcurrentHashMap 节点上，本文从一次真实的线程 dump 出发，深入分析 `computeIfAbsent` 内部执行阻塞操作引发的锁冲突问题，并给出解决方案。

## 问题背景

某次线上巡检发现锁竞争告警，锁对象是 `ConcurrentHashMap$ReservationNode`，7个线程被阻塞。初看以为是普通的并发冲突，但仔细分析堆栈后发现：**持锁线程并不是在做 CPU 密集型计算，而是在等待一个异步 Future 的结果** 。这就是典型的 `computeIfAbsent` 内部执行慢操作导致的锁放大问题。

## 线程 Dump 分析

### 持锁线程：TIMED_WAITING
    
    
    "worker-thread-83" id=431 TIMED_WAITING on TrustedListenableFutureTask@563177a1
    
    at  jdk.internal.misc.Unsafe.park(Native Method)
    at  java.util.concurrent.locks.LockSupport.parkNanos(LockSupport.java:269)
    at  com.google.common.util.concurrent.AbstractFuture.get(AbstractFuture.java:452)
    at  com.example.common.util.FutureUtil.getOrCatchInternal(FutureUtil.java:433)
    at  com.example.common.util.FutureUtil.getOrCatch(FutureUtil.java:348)
    at  com.example.business.utility.DataLoaderResultAsync.waitForCompletion(DataLoaderResultAsync.java:92)
    at  com.example.business.utility.DataLoaderResultAsync.load(DataLoaderResultAsync.java:50)
    at  com.example.business.redisbusiness.RemoteCacheBusiness.getDataFromCacheAsync(RemoteCacheBusiness.java:255)
    at  com.example.business.utility.CacheObjectHelper.lambda$getCachedData$0(CacheObjectHelper.java:175)
    at  java.util.concurrent.ConcurrentHashMap.computeIfAbsent(ConcurrentHashMap.java:1708)
    at  com.example.business.utility.CacheObjectHelper.getCachedData(CacheObjectHelper.java:174)
    at  com.example.business.filter.SubclassFilterProcessUnit.processSubclasses(SubclassFilterProcessUnit.java:988)
    

### 等锁线程（7个）：BLOCKED
    
    
    "worker-thread-32" id=379 BLOCKED on ConcurrentHashMap$ReservationNode@3ae90fc1
    
    at  java.util.concurrent.ConcurrentHashMap.computeIfAbsent(ConcurrentHashMap.java:1726)
      - waiting on java.util.concurrent.ConcurrentHashMap$ReservationNode@3ae90fc1
    at  com.example.business.utility.CacheObjectHelper.getCachedData(CacheObjectHelper.java:174)
    at  com.example.business.filter.SubclassFilterProcessUnit.processSubclasses(SubclassFilterProcessUnit.java:988)
    

两个关键信息：

  1. **持锁线程状态是`TIMED_WAITING`**，不是 `RUNNABLE`。它在 `future.get()` 上等待异步结果返回，最长 100ms。
  2. **等锁线程状态是`BLOCKED`**，被 `ConcurrentHashMap$ReservationNode` 的 `synchronized` 锁挡住。



也就是说：**一个线程在`computeIfAbsent` 的 lambda 里等 Redis，其余 7 个线程全部被堵在 ConcurrentHashMap 的桶锁上。**

## 根因分析

### ConcurrentHashMap.computeIfAbsent 的锁机制

很多人以为 `ConcurrentHashMap` 是"无锁"的，其实它在 `computeIfAbsent` 中使用了 `synchronized` 锁。看一下 JDK 源码（简化版）：
    
    
    // java.util.concurrent.ConcurrentHashMap
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        // ... 省略 hash 计算和查找逻辑
        Node<K,V> r = new ReservationNode<K,V>();
        synchronized (r) {           // 占位节点加锁
            // 将 ReservationNode 放入桶中
            tab[i] = r;
            V val = mappingFunction.apply(key);  // 执行 lambda
            // 替换为真正的节点
        }
        // ...
    }
    

关键点：**`mappingFunction.apply(key)` 是在 `synchronized` 块内执行的**。这意味着：

  * 同一个桶（hash 冲突）的其他 `computeIfAbsent` 调用会被阻塞
  * 不仅是相同 key，**任何落在同一个桶的 key** 都会被阻塞
  * lambda 执行时间越长，锁持有时间越长，冲突概率越大



### 问题代码模式
    
    
    // CacheObjectHelper.java
    public static Set<String> getCachedData(SearchContext context) {
        return (Set<String>) context.getLogicObjects()
                .computeIfAbsent(CacheKey.SOME_DATA, key ->
                        // 在 computeIfAbsent 的 lambda 内执行阻塞 I/O！
                        RemoteCacheBusiness.getDataFromCacheAsync(
                                context.getDepartCity(),
                                context.getArriveCity()));
    }
    

`getDataFromCacheAsync` 看似是异步方法，实际上内部提交任务到线程池后立即阻塞等待结果：
    
    
    // RemoteCacheBusiness.java
    public static Set<String> getDataFromCacheAsync(String dCity, String aCity) {
        int timeoutMs = ConfigManager.getInt("Cache.TimeoutMS", 100);
        String pool = ConfigManager.getString("ExecutorService.IOAccess", "io-pool");
        // 提交到线程池，然后同步等待结果，最长 100ms
        return DataLoaderResultAsync.load(pool, timeoutMs,
                () -> getDataFromCache(dCity, aCity), new HashSet<>());
    }
    
    
    
    // DataLoaderResultAsync.java
    public static <T> T load(String pool, int timeoutMs, Supplier<T> supplier, T defaultValue) {
        DataLoaderResultAsync<T> loader = new DataLoaderResultAsync<>(pool);
        loader.startNew(obj -> supplier.get(), null); // 提交到线程池
        T result = loader.waitForCompletion(timeoutMs); // 同步阻塞等待！
        return result != null ? result : defaultValue;
    }
    

整个调用链如下：
    
    
    CacheObjectHelper.getCachedData()
      -> ConcurrentHashMap.computeIfAbsent(key, lambda)  // 持有桶的 synchronized 锁
        -> RemoteCacheBusiness.getDataFromCacheAsync()     // lambda 内部
          -> DataLoaderResultAsync.load()                  // 提交线程池任务
            -> waitForCompletion(100ms)                    // 阻塞等待 future.get()
              -> FutureUtil.getOrCatch()
                -> future.get(100, MILLISECONDS)           // 最长阻塞 100ms
    

**锁持有时间 = Redis 网络往返时间（最长 100ms）** ，而不是一次内存操作的纳秒级别。

### 冲突概率估算

根据 ConcurrentHashMap 内部文档，两个随机 key 落在同一个桶的概率约为：
    
    
    P ≈ 1 / (8 * n)   // n 为桶数量
    

假设这个 ConcurrentHashMap 存储了少量逻辑对象（比如 16 个 key，初始容量 16），那么：
    
    
    P ≈ 1 / (8 * 16) = 1/128 ≈ 0.78%
    

看起来概率不高，但在高并发场景下（比如同时处理上百个请求，每个请求都要调用这个方法），加上每次锁持有 100ms，冲突就变得非常显著了。

## Caffeine 的建议

参考 [Caffeine FAQ](<https://link.juejin.cn?target=https%3A%2F%2Fgithub.com%2Fben-manes%2Fcaffeine%2Fwiki%2FFaq> "https://github.com/ben-manes/caffeine/wiki/Faq")，对于这类问题有明确的建议：

  1. **避免在`computeIfAbsent` 内部执行阻塞操作** —— mapping function 应该尽快返回
  2. **使用 AsyncCache** —— 存储 `CompletableFuture` 而非值本身，后续线程拿到 Future 后自行等待
  3. **增大初始容量** —— 减少 hash 冲突概率
  4. **优化 key 的 hashCode** —— 使用分布更均匀的 hash 函数



其中最核心的思路是：**将阻塞操作移到`computeIfAbsent` 外部**。

## 解决方案

### 方案一：先存 Future，再等结果（推荐）

将 `computeIfAbsent` 中存储的值从最终结果改为 `Future`，让 lambda 立即返回，锁快速释放：
    
    
    public static Set<String> getCachedData(SearchContext context) {
        Future<Set<String>> future = (Future<Set<String>>) context.getLogicObjects()
                .computeIfAbsent(CacheKey.SOME_DATA, key ->
                        // lambda 内只提交任务，立即返回 Future，不阻塞
                        RemoteCacheBusiness.submitCacheTask(
                                context.getDepartCity(),
                                context.getArriveCity()));
        // 在 computeIfAbsent 外部等待结果
        int timeoutMs = ConfigManager.getInt("Cache.TimeoutMS", 100);
        return FutureUtil.getOrCatch(future, timeoutMs, TimeUnit.MILLISECONDS)
                .getResultOrDefault(new HashSet<>());
    }
    

这样 `computeIfAbsent` 的 lambda 只做一次线程池 `submit`（微秒级），桶锁几乎立即释放。

### 方案二：提前计算，避免在 computeIfAbsent 内触发 I/O

在进入 `computeIfAbsent` 之前，先检查缓存是否存在，如果不存在则在外部完成数据加载：
    
    
    public static Set<String> getCachedData(SearchContext context) {
        ConcurrentHashMap<String, Object> logicObjects = context.getLogicObjects();
        Object cached = logicObjects.get(CacheKey.SOME_DATA);
        if (cached != null) {
            return (Set<String>) cached;
        }
        // 在 computeIfAbsent 外部完成 I/O
        Set<String> data = RemoteCacheBusiness.getDataFromCacheAsync(
                context.getDepartCity(), context.getArriveCity());
        // putIfAbsent 保证线程安全，不持有桶锁
        Set<String> existing = (Set<String>) logicObjects.putIfAbsent(CacheKey.SOME_DATA, data);
        return existing != null ? existing : data;
    }
    

注意：这种方式可能导致多个线程同时触发 Redis 查询（缓存击穿），但对于这类读操作，短暂的重复查询通常比锁等待更可接受。

### 方案三：增大 ConcurrentHashMap 初始容量

如果无法修改调用模式，至少可以通过增大容量来降低桶冲突概率：
    
    
    // 原来可能是默认容量 16
    private ConcurrentHashMap<String, Object> logicObjects = new ConcurrentHashMap<>();
    
    // 调大初始容量，减少 hash 冲突
    private ConcurrentHashMap<String, Object> logicObjects = new ConcurrentHashMap<>(256);
    

桶数量从 16 增加到 256，冲突概率降低 16 倍。但这只是缓解，不是根治。

## 总结

  * **`ConcurrentHashMap.computeIfAbsent` 的 lambda 在 `synchronized` 块内执行**，lambda 耗时越长，桶锁持有越久，冲突越严重
  * **尽量不要在`computeIfAbsent` 内部执行阻塞 I/O**（网络调用、Future.get、Thread.sleep 等）
  * **推荐方案：在`computeIfAbsent` 内只存 Future 对象，在外部等待结果**。lambda 快速返回，锁立即释放，后续线程拿到同一个 Future 共享等待
  * 作为辅助措施，可以**增大 ConcurrentHashMap 初始容量** 降低桶冲突概率，但这只是缓解，不解决根本问题



* * *

如果这篇文章对你有帮助，欢迎关注我，持续分享高质量技术干货，助你更快提升编程能力。