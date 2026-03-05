---
title: "异步编程深度分析：是否可以不带Async使用CompletableFuture？"
date: 2025-01-16
url: https://juejin.cn/post/7460437932454805544
views: 411
likes: 8
collects: 6
source: html2md
---

## 异步编程深度分析：是否可以不带Async使用CompletableFuture？

写文不易，本文禁止转载！

CompletableFuture 提供了3种回调方式，每个回调方法都进行了重载，以thenApply为例，方法签名分别是：

  0. 不带Async:

`public <U> CompletableFuture<U> thenApply(Function<? super T,? extends U> fn)`

  1. Async，使用默认执行器：

`public <U> CompletableFuture<U> thenApplyAsync(Function<? super T,? extends U> fn)`

  2. Async，使用执行器参数： `public <U> CompletableFuture<U> thenApplyAsync(Function<? super T,? extends U> fn, Executor executor)`




我在[《深入理解 Future, CompletableFuture, ListenableFuture，回调机制》](<https://juejin.cn/post/7388332481739882547> "https://juejin.cn/post/7388332481739882547")中谈到想要最大限度地提升异步能力，一般需要设置执行器，推荐使用第三种方法，当然也可以通过重写默认执行器实现，CFFU 提供了组合形式的实现。

不带Async的方法可能会导致异步任务阻塞，因为当执行thenApply时，如果CompletableFuture已经执行结束（有结果），此时添加的回调会在调用线程中执行，形成事实上的”阻塞“。另一种情况是，添加的回调在上一个回调执行后执行，且执行线程与上一个回调的执行线程一致，可以参考这篇[避坑文章](<https://juejin.cn/post/7411686792342274089> "https://juejin.cn/post/7411686792342274089")。

### ListenableFuture 的实现

ListenableFuture 及其相关类提供了一个巧妙的方法，解决了用户传执行器参数的问题，在添加回调时，执行器是必传参数：
    
    
    public interface ListenableFuture<V extends @Nullable Object> extends Future<V> {
        void addListener(Runnable listener, Executor executor);
    }
    

#### 支持直接执行器（DirectExecutor)

Guava 提供了直接执行器，支持了不带Async的CompletableFuture类似功能的实现。

其说明文档详实，可参考如下：

返回一个 `Executor`，该 `Executor` 在调用 `Executor#execute` 的线程中运行每个任务，类似于 `ThreadPoolExecutor.CallerRunsPolicy`。

此执行器适用于轻量级且不具有深层链式调用的任务。使用不当的 `directExecutor` 可能会导致问题，而且这些问题可能难以重现，因为它们依赖于时序。例如：

  * 当 `ListenableFuture` 的监听器被注册到 `directExecutor` 时，该监听器可以在三种可能的线程中执行：

    0. 当一个线程将监听器附加到已经完成的 `ListenableFuture` 时，监听器会立即在该线程中执行。
    1. 当一个线程将监听器附加到尚未完成的 `ListenableFuture`，并且该 `ListenableFuture` 后来正常完成时，监听器会在完成该 `ListenableFuture` 的线程中执行。
    2. 当监听器被附加到 `ListenableFuture` 上，而该 `ListenableFuture` 被取消时，监听器会立即在取消 `Future` 的线程中执行。



由于存在这些可能性，监听器经常可能会在 UI 线程、RPC 网络线程或其他对延迟敏感的线程中执行。在这些情况下，慢速的监听器可能会影响响应性、减慢整个系统的速度，甚至可能导致更严重的问题。（关于锁定的注释见下文。）

  * 如果多个任务将由同一事件触发，某个重量级任务可能会延迟其他任务——即使这些任务本身不是 `directExecutor` 任务。
  * 如果许多任务被串联在一起（例如通过 `future.transform(...).transform(...).transform(...)....`），它们可能会导致栈溢出。（在简单的情况下，调用者可以通过使用 `MoreExecutors#newSequentialExecutor` 包装器来避免此问题，该包装器将 `directExecutor()` 包裹起来。更复杂的情况可能需要使用线程池或者进行更深层次的修改。）
  * 如果异常从 `Runnable` 中传播出来，它不一定会被线程的任何 `UncaughtExceptionHandler` 捕获。例如，如果传递给 `Futures#addCallback` 的回调抛出异常，这个异常通常会被 `ListenableFuture` 实现记录下来，即使该线程被配置为执行不同的操作。在其他情况下，可能没有代码会捕获此异常，它可能会终止触发执行的线程。



##### 关于锁的特别警告：

执行用户提供的任务的代码（例如 `ListenableFuture` 监听器）应小心避免在持有锁时执行。此外，作为进一步的防线，最好不要在将要在 `directExecutor` 下执行的任务中进行任何锁定：不仅锁的等待可能很长，如果运行的线程正在持有锁，监听器可能会发生死锁或破坏锁的隔离性。

此实例等价于：
    
    
    final class DirectExecutor implements Executor {
      public void execute(Runnable r) {
        r.run();
      }
    }
    

### 结论

参考ListenableFuture的实现，我们可以得出结论：可以，但不推荐。

”可以“是因为当回调任务为轻量级方法时，我们可以获得一定的性能提升，减少上下文切换和锁的开销。

”不推荐“是因为既然回调任务是轻量级方法，其就应该和上一个任务进行合并，任务的回调不应过多，否则不易理解。 _在实际的生产项目中，CompletableFutue 的使用应该专注于任务编排_ 。链式调用和回调函数不应该作为使用CompletableFuture的主要追求。此外，基于异步的开发中常常涉及异常日志处理和链路追踪，如果想进行一些拓展（比如记录方法调用耗时），平时开发时“房间里的大象”——上下文就不得不考虑起来，相比于单线程编程，其处理难度急剧上升。

很遗憾，轻量级回调破坏了任务编排的整体逻辑，函数式的处理形式还是受限于 Java 语言的有限支持，比起开发时的爽感，我们更应关注实践中的拓展性。至于现有代码如何优化，可以使用笔者之前推荐的重构方法进行改造。

### 预告

之后想写以下相关话题，感兴趣的朋友可以关注下我：

  * 异步编程与上下文，TTL
  * CompletableFuture 另一大坑之吞异常


  * 如何从头开始学习 CompletableFuture
  * 如何快速学习 CFFU 类库
  * 一文总结 CompletableFuture 实战必备知识
  * CompletableFuture 源码分析