---
title: "【性能优化】经常被忽视但是很有用的并发执行策略(CompletionService)"
date: 2024-12-26
url: https://juejin.cn/post/7452325631465652276
views: 270
likes: 3
collects: 0
source: html2md
---

### 本文禁止转载！

CFFU 中介绍了4种常见的执行策略，其实，Java 中还提供了第五种并发执行策略，CompletionService 常常被很多人所忽略，其可以实现异步任务生产与消费的解耦。并发编程抽象的集大成者是响应式编程，其具有很多特性，比如反压（back pressure)、声明式编程、懒计算、事件驱动、发布订阅模式等等，本文不会讨论复杂的响应式编程，但是如果你对其有一定的了解，你肯定会发现响应式编程的影子。

## 1\. 回顾四种并发执行策略

以下内容引用自[ CFFU 官方文档](<https://link.juejin.cn?target=https%3A%2F%2Fgithub.com%2Ffoldright%2Fcffu> "https://github.com/foldright/cffu")：

> 📔 关于多个`CF`的并发执行策略，可以看看`JavaScript`规范[`Promise Concurrency`](<https://link.juejin.cn?target=https%3A%2F%2Fdeveloper.mozilla.org%2Fen-US%2Fdocs%2FWeb%2FJavaScript%2FReference%2FGlobal_Objects%2FPromise%23promise_concurrency> "https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Promise#promise_concurrency")；在`JavaScript`中，`Promise`即对应`CompletableFuture`。
> 
> `JavaScript Promise`提供了4个并发执行方法：
> 
>   * [`Promise.all()`](<https://link.juejin.cn?target=https%3A%2F%2Fdeveloper.mozilla.org%2Fen-US%2Fdocs%2FWeb%2FJavaScript%2FReference%2FGlobal_Objects%2FPromise%2Fall> "https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Promise/all")：等待所有`Promise`运行成功，只要有一个失败就立即返回失败（对应`cffu`的`allResultsFailFastOf`方法）
>   * [`Promise.allSettled()`](<https://link.juejin.cn?target=https%3A%2F%2Fdeveloper.mozilla.org%2Fen-US%2Fdocs%2FWeb%2FJavaScript%2FReference%2FGlobal_Objects%2FPromise%2FallSettled> "https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Promise/allSettled")：等待所有`Promise`运行完成，不管成功失败（对应`cffu`的`allResultsOf`方法）
>   * [`Promise.any()`](<https://link.juejin.cn?target=https%3A%2F%2Fdeveloper.mozilla.org%2Fen-US%2Fdocs%2FWeb%2FJavaScript%2FReference%2FGlobal_Objects%2FPromise%2Fany> "https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Promise/any")：赛马模式，立即返回首个成功的`Promise`（对应`cffu`的`anySuccessOf`方法）
>   * [`Promise.race()`](<https://link.juejin.cn?target=https%3A%2F%2Fdeveloper.mozilla.org%2Fen-US%2Fdocs%2FWeb%2FJavaScript%2FReference%2FGlobal_Objects%2FPromise%2Frace> "https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Promise/race")：赛马模式，立即返回首个完成的`Promise`（对应`cffu`的`anyOf`方法）
> 


这四种并发执行策略都涉及到等待，有的是等待第一个任务结束，有的是等待第一个异常结果，有的是等待所有结果执行完成。想象存在一个等待时间轴，完成的任务不断放到这个时间轴上，我们可以对轴上的结果进行进一步的分析。如果我们把任务处理和分析进行解耦，即使用阻塞队列作为媒介，就是第五种并发执行策略。

我们可以将4种并发策略从阻塞队列的角度理解，看看实际上做了哪种逻辑。

方法| 从消息队列消费的角度分析  
---|---  
`Promise.all()`| 第一个异常结果 || 最后一个结果  
`Promise.allSettled()`| 最后一个结果  
`Promise.any()`| 第一个成功结果  
`Promise.race()`| 第一个结果  
  
## 2\. CompletionService

CompletionService是Java中的一个接口，它定义了一组用于管理异步任务执行结果的方法。以下是CompletionService接口的所有方法签名：
    
    
    public interface CompletionService<V> {
        Future<V> submit(Callable<V> task);
        Future<V> submit(Runnable task, V result);
        Future<V> take() throws InterruptedException;
        Future<V> poll();
        Future<V> poll(long timeout, TimeUnit unit) throws InterruptedException;
    }
    

这些方法用于向CompletionService提交任务、获取已完成任务的结果，并提供了不同的方式来获取结果，包括阻塞和非阻塞的方式。

以下是 ExecutorCompletionService(接口默认实现）注释中提出的经典例子：

  1. 依次消费结果，直到所有任务消费完成


    
    
    void solve(Executor e, 
               Collection<Callable<Result>> solvers) throws InterruptedException, ExecutionException {
        CompletionService<Result> cs = new ExecutorCompletionService<>(e);
        solvers.forEach(cs::submit);
        for (int i = solvers.size(); i > 0; i--) {
            Result r = cs.take().get();
            if (r != null)
                use(r);
        }
    }
    

  2. 消费第一个非空正常结果，同时取消其他任务


    
    
    void solve(Executor e, Collection<Callable<Result>> solvers) throws InterruptedException {
        CompletionService<Result> cs = new ExecutorCompletionService<>(e);
        int n = solvers.size();
        List<Future<Result>> futures = new ArrayList<>(n);
        Result result = null;
        try {
            solvers.forEach(solver -> futures.add(cs.submit(solver)));
            for (int i = n; i > 0; i--) {
                try {
                    Result r = cs.take().get();
                    if (r != null) {
                        result = r;
                        break;
                    }
                } catch (ExecutionException ignore) {}
            }
        } finally {
            futures.forEach(future -> future.cancel(true));
        }
        if (result != null)
            use(result);
    }
    

## 3\. 结合 Guava 使用

ListenableFuture 和 ListeningExecutorService 是 Guava 提供的异步增强实现，其可以和 ExecutorCompletionService 结合使用。
    
    
    class CompletionServiceDemo {
        public static void main(String[] args) {
            var executor = MoreExecutors.listeningDecorator(Executors.newCachedThreadPool());
            var completionService = new ExecutorCompletionService<Integer>(executor);
            Future<Integer> future = completionService.submit(() -> 1);
            boolean result = future instanceof ListenableFuture<?>;
            System.out.println("result = " + result);
          	// 输出： result = true
        }
    }
    

源码分析如下：

ExecutorCompletionService 接收 Executor 参数，如果 executor 是 AbstractExecutorService 的子类，可以使用其newTaskFor 方法创建 RunnableFuture 对象，guava 通过继承重写了 newTaskFor 方法。
    
    
        public ExecutorCompletionService(Executor executor) {
            if (executor == null)
                throw new NullPointerException();
            this.executor = executor;
            this.aes = (executor instanceof AbstractExecutorService) ?
                (AbstractExecutorService) executor : null;
            this.completionQueue = new LinkedBlockingQueue<Future<V>>();
        }
    
        private RunnableFuture<V> newTaskFor(Runnable task, V result) {
            if (aes == null)
                return new FutureTask<V>(task, result);
            else
                return aes.newTaskFor(task, result);
        }
    

Guava 实现分析:
    
    
    // 创建线程池，一般情况下入参为线程池，返回类型为 ListeningDecorator
    public static ListeningExecutorService listeningDecorator(ExecutorService delegate) {
      return (delegate instanceof ListeningExecutorService)
          ? (ListeningExecutorService) delegate
          : (delegate instanceof ScheduledExecutorService)
              ? new ScheduledListeningDecorator((ScheduledExecutorService) delegate)
              : new ListeningDecorator(delegate);
    }
    

ListeningDecorator 继承自 AbstractListeningExecutorService，而 AbstractListeningExecutorService 又继承自 AbstractExecutorService。AbstractListeningExecutorService 重写了newTaskFor 方法，JDK 中 newTaskFor 使用 FutureTask 实现。
    
    
    /** @since 19.0 (present with return type {@code ListenableFutureTask} since 14.0) */
    @Override
    protected final <T extends @Nullable Object> RunnableFuture<T> newTaskFor(Callable<T> callable) {
      return TrustedListenableFutureTask.create(callable);
    }
    

## 4\. 结合CompletableFuture 使用

实际上，我们可以在异步回调方法中将结果放到阻塞队列中，其实现和 ExecutorCompletionService 使用阻塞队列的方式是一致的。
    
    
    public static void main(String[] args) throws InterruptedException {
        var blockingQueue = new LinkedBlockingQueue<CompletableFuture<Integer>>();
        var cf = CompletableFuture.supplyAsync(() -> 1);
        cf.whenComplete((v, e) -> blockingQueue.offer(cf));
        CompletableFuture<Integer> result = blockingQueue.take();
        System.out.println("result = " + result.join());
    }
    

## 5\. 总结

总之，生产-消费模式是一种更一般的并发执行策略，其特点总结：

  1. 生产者-消费者模式
  2. 更灵活的并发控制
  3. 相比于等待全部结果（allOf) 其实现具有更好的并发性



不过，我们应该在发现其可以简单直接地解决问题时再使用。

笔者见过某些业务需要异步进行多次规约计算（CPU密集型） ，其实现逻辑是 allOf, 返回多个候选 Future，最后通过某些规则筛选出最优结果。实际上，这种场景下使用 CompletionService 或者说生产-消费模式再合适不过了，每次有完成的结果返回时，我们可以在主线程中进行规约计算，充分提高并发度。

当你学了一个新知识后（特别是多线程相关知识），不要生搬硬套，生产-消费模式是一把锤子，不要把什么都看成钉子；不要把熟悉当做简单，你写的代码应该易于他人理解。