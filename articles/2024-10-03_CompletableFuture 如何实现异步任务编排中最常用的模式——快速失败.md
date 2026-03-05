---
title: "CompletableFuture 如何实现异步任务编排中最常用的模式——快速失败"
date: 2024-10-03
url: https://juejin.cn/post/7420597224546091059
views: 968
likes: 9
collects: 11
source: html2md
---

## CompletableFuture 如何实现异步任务编排中最常用的模式——快速失败

未经允许禁止转载！

之前文章[《使用 CompletableFuture 最常见的错误（附实战代码）》](<https://juejin.cn/post/7415912390950797362> "https://juejin.cn/post/7415912390950797362")中，我们提到：对于并发任务编排，CompletableFuture 原生提供的两种策略 `anyOf` 和 `allOf` 并不能满足实际开发需要，最常使用的策略是 failFastOf：当任务异常时，返回结果为异常。本文将讨论快速失败实现的方法，内容涉及CFFU实现源码以及其他实现方式，希望对你理解并发编程有所帮助。

### CFFU 实现

快速失败实际上是一个 any 逻辑, 其短路终止逻辑为

  1. 当任一个任务执行失败
  2. 或者所有任务执行成功



为了可以复用 anyOf 逻辑，可以将不触发终止逻辑的 CompletableFuture 转换为 未完成状态的 CompletableFuture。第一种情况需要把执行成功的任务转换成未完成，会触发终止逻辑；第二种情况需要所有任务成功的逻辑，否则，处理成未完成任务。

CFFU 使用了这样的处理，为便于理解，保留了原始注释，代码如下：
    
    
    @Contract(pure = true)
    @SafeVarargs
    public static <T> CompletableFuture<List<T>> allResultsFailFastOf(CompletionStage<? extends T>... cfs) {
        return allResultsFailFastOf0(requireCfsAndEleNonNull(cfs));
    }
    
    // 具体实现
    private static <T> CompletableFuture<List<T>> allResultsFailFastOf0(CompletionStage<? extends T>[] cfs) {
        final int len = cfs.length;
        if (len == 0) return completedFuture(arrayList());
        // convert input cf to non-minimal-stage CF instance for SINGLE input in order to
        // ensure that the returned cf is not minimal-stage instance(UnsupportedOperationException)
      
      	// 这里 toNonMinCf0 安全地将 CompletionStage 转换成了 CompletableFuture
        if (len == 1) return toNonMinCf0(cfs[0]).thenApply(CompletableFutureUtils::arrayList);
      
        final CompletableFuture<?>[] successOrBeIncomplete = new CompletableFuture[len];
        // NOTE: fill ONE MORE element of failedOrBeIncomplete LATER
        final CompletableFuture<?>[] failedOrBeIncomplete = new CompletableFuture[len + 1];
        fill0(cfs, successOrBeIncomplete, failedOrBeIncomplete);
    
        // NOTE: fill the ONE MORE element of failedOrBeIncomplete HERE:
        //       a cf that is successful when all given cfs success, otherwise be incomplete
      	// 第二种情况
        failedOrBeIncomplete[len] = allResultsOf0(successOrBeIncomplete);
    		
      	// 复用 anyOf 逻辑
      	// 这里anyOf执行完直接强转类型即可，因为如果成功，结果类型必为 List<T>。
        return f_cast(CompletableFuture.anyOf(failedOrBeIncomplete));
    }
    
    // 这个方法提取出来是为了代码复用
    private static <T> void fill0(CompletionStage<? extends T>[] stages,
                                  CompletableFuture<? extends T>[] successOrBeIncomplete,
                                  CompletableFuture<? extends T>[] failedOrBeIncomplete) {
        for (int i = 0; i < stages.length; i++) {
            final CompletableFuture<T> f = f_toCf0(stages[i]);
          	// 注意这里必须转换：异常结束 -> 未完成状态的 CompletableFuture，否则，会有竞争存在，结果会错误
            successOrBeIncomplete[i] = exceptionallyCompose(f, ex -> new CompletableFuture<>());
          	// 第一种情况
            failedOrBeIncomplete[i] = f.thenCompose(v -> new CompletableFuture<>());
        }
    }
    

注意 fill0 这个方法独立出来是为了复用，在 `allSuccessOf` 和 `anySuccessOf`中会复用相关逻辑。

以 `anySuccessOf` 为例，其终止逻辑为：

  1. 任意一个任务执行成功
  2. 或者所有任务执行失败



这里的逻辑和快速失败是相反的，不再赘述相关代码。

### 手动实现

你也可以使用 CompletableFuture 的写方法，通过 `complete` 和 `completeExceptionally` 实现原子写和单次写，写入的条件也就是以上的终止逻辑。

所有任务执行成功的逻辑可以使用 CountDownLatch 等同步工具类实现。在笔者之前写的《[深入理解 Future, CompletableFuture, ListenableFuture，回调机制](<https://juejin.cn/post/7388332481739882547> "https://juejin.cn/post/7388332481739882547")》文中没有考虑这种逻辑，使用了超时时间进行兜底。
    
    
    // 使用 JDK21
    public static CompletableFuture<List<Integer>> failFastOf(Iterable<CompletableFuture<Integer>> cfs) {
        int size = Iterables.size(cfs);
        if (size == 0) return CompletableFuture.completedFuture(List.of());
        CompletableFuture<List<Integer>> result = new CompletableFuture<>();
        LongAdder success = new LongAdder();
        CountDownLatch latch = new CountDownLatch(size);
        cfs.forEach(cf -> cf.whenComplete((v, ex) -> {
                if (ex != null) {
                    result.completeExceptionally(ex);
                }
                success.increment();
                latch.countDown();
            }
        ));
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> {
                try {
                    latch.wait();
                } catch (InterruptedException ignored) {}
                if (success.intValue() == size) {
                    List<Integer> list = Streams.stream(cfs)
                        .map(CompletableFuture::join)
                        .toList();
                    result.complete(list);
                }
            });
        }
        return result;
    }
    

此外，如果你关心取消功能的话，对于其他未完成的任务，获取结果后可以进行批量取消。由于 CompletableFuture 并不能取消提交到线程池中的任务，对于已提交到线程池的任务，其实际意义可能不大。对于未提交的任务，可以置为取消状态（CancellationException)。如果你想实现相关功能的话，可以使用 Guava 的 ListenableFuture。