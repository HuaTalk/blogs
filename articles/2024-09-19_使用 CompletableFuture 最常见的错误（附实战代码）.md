---
title: "使用 CompletableFuture 最常见的错误（附实战代码）"
date: 2024-09-19
url: https://juejin.cn/post/7415912390950797362
views: 2480
likes: 29
collects: 55
source: html2md
---

# 使用 CompletableFuture 最常见的错误（附实战代码）

未经允许禁止转载！

本文将讨论 CompletableFuture 使用上的误区，内容涉及实战中代码重构。

使用CompletableFuture最常见的错误就是使用过早使用 join，错误的使用方法还常常会出现很多所谓的技巧，比如 Effective Java 中并不推荐使用的双重检查锁，不仅容易出现bug，还影响代码的阅读。

## 原代码实现

代码取自[使用CompletableFuture开启多线程实战教程](<https://juejin.cn/post/7413177840156229632?share_token=510835dc-068d-4945-a0ae-a9dfea689cdc> "https://juejin.cn/post/7413177840156229632?share_token=510835dc-068d-4945-a0ae-a9dfea689cdc")
    
    
    Integer size = req.getPageSize();
    Integer num = req.getCurrentPage();
    String redisKey = StringUtils.join(req.getSearchIds(), ",") + "--timeCycle:" + req.getTimeCycle();
    String redisCache = (String) redisUniteUtil.get(redisKey);
    if (Objects.nonNull(redisCache)) {
        List<String> cacheOptions = JSONObject.parseArray(redisCache, String.class);
        return getOptionsVO(cacheOptions, num, size);
    }
    List<String> options = req.getSearchIds().stream()
            .map(id -> new QueryOptionReq().setDataSource(req.getDataSource()).setFieldName(req.getFieldName())
                    .setSearchId(id).setTableName(req.getTableName())
                    .setTimeCycle(req.getTimeCycle()).setSearchIds(req.getSearchIds()))
            .map(newReq -> CompletableFuture.supplyAsync(() -> queryOneOptions(newReq), almQueryThreadPool))
      			// 作者已经意识到了阻塞等待的问题
            .collect(Collectors.toList())
            .stream()
            .map(CompletableFuture::join)
            .map(OptionsVO::getOptionList)
            .flatMap(Collection::stream)
            .distinct()
            .collect(Collectors.toList());
    
    redisUniteUtil.set(redisKey, JSON.toJSONString(options), 90L, TimeUnit.SECONDS);
    return getOptionsVO(options, num, size);
    

我们来分析下代码的问题，某些地方可能有点吹毛求疵。

  1. 参数 size，num 可以 inline，这里的技巧是 [Replace Temp with Query](<https://link.juejin.cn?target=https%3A%2F%2Frefactoring.guru%2Freplace-temp-with-query> "https://refactoring.guru/replace-temp-with-query")

  2. Redis 缓存处理有点硬编码，可以抽取出方法。比如 `CacheUtils#queryOptions:（ids, timeCycle) -> Optional<List<String>>` 使用Optional可以提示用户处理缓存为空的情况, CacheUtils#saveOptions 方便后续可能更改失效时间。

  3. Objects.nonNull 改成 xxx != null

  4. 流处理中间调用了 toList，可以新增一个临时变量 cfs 表示。这里必须要先终止流，因为要先创建所有的 CompletableFuture。

  5. 可以使用 builder 模式实现对象创建, Setter 可以改成 wither。




## 不要在迭代中使用阻塞方法

在迭代中使用阻塞方法（例如 `CompletableFuture::join`）会导致失去异步编程的优势，产生以下问题：

### 1\. **阻塞主线程，失去异步的好处**

`join()` 会阻塞当前线程，直到异步任务完成。如果你在迭代中使用 `join()`，即使你用的是异步方法，实际上仍然会一个接一个地等待每个任务完成，这与同步方法没有太大区别。
    
    
    searchIds.stream()
        .map(id -> CompletableFuture.supplyAsync(() -> query(id)))
        .map(future -> future.join()) // 这里阻塞了
        .collect(Collectors.toList());
    

这种做法的缺点是：每个 `CompletableFuture` 的结果都会在流操作中同步等待，因此即便 `supplyAsync()` 是异步执行的，主线程仍然会一个一个等待所有异步操作完成，而不是并行处理。

### 2\. **无法充分利用并行性**

异步操作的好处之一是它可以释放主线程，让它继续执行其他任务。使用 `join()` 或其他阻塞方法后，主线程就无法进行其他有意义的工作。相反，应该让所有的 `CompletableFuture` 在后台并行执行，最后一次性处理它们的结果。

如果我们使用 `CompletableFuture.allOf()` 等异步工具，我们可以并行运行多个异步操作，同时让主线程继续做其他事情，直到所有操作完成。如果每个异步任务在等待时阻塞当前线程，会导致线程池中的线程没有得到充分利用，从而可能拖慢整体的任务执行速度。例如，在高并发场景下，阻塞的线程会被浪费掉，无法服务其他任务。

正确的方式是让所有异步任务同时运行，只有在最终需要结果时才统一等待或处理。

### 3\. **死锁风险**

虽然使用 `join()` 在简单的异步操作中问题不大，但如果你在复杂的依赖链中使用它，可能会引发死锁。例如，两个线程可能在等待彼此的完成，导致程序陷入僵局。

### 正确的做法：非阻塞组合结果

可以通过 `CompletableFuture.allOf()` 等方式等待所有异步任务完成，并使用 `thenApply` 或 `thenCompose` 来处理结果，而不是在每个任务完成时同步等待。

## 任务编排策略

本文的重点还是 CompletableFuture 的使用:

先来看并发任务编排策略，Java 原生提供了两种，allOf 和 anyOf，实际上指的是allCompleteOf 和 anyCompleteOf，这里的完成包括正常完成和异常完成，异常完成指的是异步任务返回异常。异常也视为值，应用了函数式编程的思想。这两个方法的缺点是无法有效使用返回值，返回类型分别是 `CompletableFuture<Void>` , `CompletableFuture<Object>`。

我们在业务中常见的需求是只考虑正常完成结果，若考虑异常处理，以 [CFFU 开源类库](<https://link.juejin.cn?target=https%3A%2F%2Fgithub.com%2Ffoldright%2Fcffu> "https://github.com/foldright/cffu")中实现的方法为例，有 allSuccess, anySuccess, allFailFast, mostSuccess等几种形式，返回结果保证为正确的类型，类型为 List 或 Tuple。

allSuccess 指的是所有任务均正常返回结果，若其中一个任务异常完成，方法结果设置为默认值。

anySuccess 指的是任一任务返回正常结果时，返回其结果；若所有任务异常完成，则返回异常结果。

allFastFail 当任务异常时，方法返回结果为异常。

mostSuccess 同 allSuccess类似，加入了超时时间。
    
    
    // 方法签名
    public static <T> CompletableFuture<List<T>> mSupplyAllSuccessAsync(@Nullable T valueIfFailed, Supplier<? extends T>... suppliers);
    public static <T> CompletableFuture<List<T>> mSupplyFastFailAsync(Supplier<? extends T>... suppliers);
    public static <T> CompletableFuture<List<T>> mSupplyMostSuccessAsync(
                @Nullable T valueIfNotSuccess, long timeout, TimeUnit unit, Supplier<? extends T>... suppliers);
    public static <T> CompletableFuture<T> mSupplyAnySuccessAsync(Supplier<? extends T>... suppliers);
    

上文中原代码没有做异常处理，实现的异步任务编排策略为 allSuccess。如果有一个异步任务失败的话，这段代码所在的方法会异常返回。可以使用 Java 提供的 allOf 方法实现基本的处理。

## 重构代码

重构后的代码添加了异常处理，仅在最后需要时使用join。
    
    
    Optional<OptionsVO> result = CacheUtils.queryOptions(req.getSearchIds(), req.getTimeCycle())
      .map(options -> getOptionsVO(options, req.getNum(), req.getSize()));
    if (result.isPresent()) return result.get();
    
    CompletableFuture<List<String>>[] cfs = req.getSearchIds().stream()
      .map(id -> QueryOptionReq.builder()
           .dataSource(req.getDataSource())
           .fieldName(req.getFieldName())
           .searchId(id)
           .tableName(req.getTableName())
           .timeCycle(req.getTimeCycle())
           .searchIds(req.getSearchIds())
           .build())
      .map(newReq -> supplyAsync(() -> queryOneOptions(newReq), almQueryThreadPool)
           .exceptionally(ex -> {
             // 处理异常，打印日志或执行其他处理
             log.error("Error fetching options for searchId {}", newReq.getSearchId(), ex);
             return Collections.<String>emptyList(); // 这里处理成返回空列表, allSuccess 策略。需要根据业务要求处理异常。
           }))
      .toArray(CompletableFuture[]::new);
    
    List<String> options = CompletableFuture.allOf(cfs)
      .thenApplyAsync(__ -> Arrays.stream(cfs)
                      .map(CompletableFuture::join)
                      .map(OptionsVO::getOptionList)
                      .flatMap(Collection::stream)
                      .distinct()
                      .collect(toList()), almQueryThreadPool)
      .join();
    RedisUtils.saveOptionsCache(options);
    return getOptionsVO(options, req.getNum(), req.getSize());
    

还有一种实现，即在方法最后使用join：
    
    
    // implB: 最后使用join
    return CompletableFuture.allOf(cfs)
      .thenApplyAsync(__ -> Arrays.stream(cfs)
                      .map(CompletableFuture::join)
                      .map(OptionsVO::getOptionList)
                      .flatMap(Collection::stream)
                      .distinct()
                      .collect(toList()), almQueryThreadPool)
      .thenApplyAsync(options -> {
        RedisUtils.saveOptionsCache(options);
        return getOptionsVO(options, req.getNum(), req.getSize())
      }, almQueryThreadPool)
      .join(); 
    

## CFFU 实现
    
    
    Supplier<List<String>[] suppliers = req.getSearchIds().stream()
      .map(id -> QueryOptionReq.builder()
           .dataSource(req.getDataSource())
           .fieldName(req.getFieldName())
           .searchId(id)
           .tableName(req.getTableName())
           .timeCycle(req.getTimeCycle())
           .searchIds(req.getSearchIds())
           .build())
      // 这里注意需要指定类型
      .<Supplier<List<String>>>map(newReq -> () -> queryOneOptions(newReq))
      .toArray(Supplier[]::new);
    CompletableFuture<List<List<String>>> cfs = CompletableFutureUtils.mSupplyAllSuccessAsync(emptyList(), almQueryThreadPool, suppliers); // 这里为了演示返回类型创建了临时变量cfs，实践中不需要
    List<String> options = cfs
      .thenApplyAsync(list -> list.stream()
                      .flatMap(Collection::stream)
                      .distinct()
                      .collect(toList()), almQueryThreadPool)
      .join(); // 后续操作略
    

以上代码中，若想实现 快速失败策略，直接更改方法 CompletableFutureUtils#mSupplyAllSuccessAsync 为 mSupplyFastFailAsync 即可。

可见，CFFU 可以更高效灵活地执行并发策略。

## 实践建议

  * allFastFail 实际上是最常使用的任务编排策略，很多业务上的allOf,实际上都是allFastFail。推荐使用 CFFU 开源类库实现相关功能。
  * 使用异步任务拓展库CFFU 和原生实现组合多个异步操作，确保它们并行执行并非阻塞地等待所有操作完成。
  * 避免在迭代过程中调用阻塞方法(`join()`, `get`)。
  * 只有在最终处理结果时才进行必要的阻塞操作，例如调用 `join()` 以返回最终结果。