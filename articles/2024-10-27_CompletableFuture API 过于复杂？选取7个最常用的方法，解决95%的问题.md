---
title: "CompletableFuture API 过于复杂？选取7个最常用的方法，解决95%的问题"
date: 2024-10-27
url: https://juejin.cn/post/7563301071173320742
source: html2md
---

## 前言

截止 JDK25，`CompletableFuture` 已经支持68个实例方法，12个静态方法。这些方法虽然方便了使用者，但对于初学者来说无疑是过于复杂和难以记忆的。本文中，我将秉持极简和实用的原则，选取最核心、最常用的API方法，同时分析为什么其他方法没有被选择进来。

**注意，本文的观点可能相当激进，可能挑战固有认知，希望大家聚焦真正能解决问题的核心API。**

如果你使用的JDK1.8版本，可以使用类库CFFU2，其提供了 `CompletableFuture` 在新版JDK中的所有方法支持（backport)。CFFU2 不仅提供了新版本JDK的API回溯支持，更以其增强的编排能力，弥补了原生API的一些不足，完美契合了本文的观点。使用方法：所有的JDK9+方法都可以通过 `CompletableFutureUtils` 中对应名称的静态方法实现。
    
    
    <dependency>
      <groupId>io.foldright</groupId>
      <artifactId>cffu2</artifactId>
      <version>2.0.1</version>
    </dependency>
    

## 核心API精选

这里选择了7个最常用的方法，组合起来使用，基本可以解决大多数异步编程问题。所有带 `Async` 后缀的方法，都推荐显式传入 `Executor` 参数，以确保任务在预期的线程池中执行，避免资源争用和不可预测的性能问题。

### 1\. 创建（封装固定值或者异常）（2个）

  * **`static<U> CompletableFuture<U> completedFuture(U value)`** : 返回一个已经以给定值正常完成的 `CompletableFuture`。用于将一个已知结果封装为 `CompletableFuture`。
  * **`static<U> CompletableFuture<U> failedFuture(Throwable ex)` (Since 9)** : 返回一个已经以给定异常完成的 `CompletableFuture`。用于将一个已知异常封装为 `CompletableFuture`。



### 2\. 数据转换与异常恢复（4个）

  * **`thenApplyAsync(Function<? super T, ? extends U> fn, Executor executor)`** : 当前阶段正常完成时，将结果作为输入，通过 `fn` 转换并返回一个新的 `CompletableFuture`。类似于 `Optional.map`。 **（推荐显式指定执行器）**
  * **`thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn, Executor executor)`** : 当前阶段正常完成时，将结果作为输入，通过 `fn` 返回一个新的 `CompletionStage`。用于扁平化嵌套的异步操作，类似于 `Optional.flatMap`。 **（推荐显式指定执行器）**
  * **`exceptionallyAsync(Function<Throwable, ? extends T> fn, Executor executor)`** : 当当前阶段异常完成时，执行 `fn` 来恢复，并返回一个正常完成的新 `CompletableFuture`。 **（推荐显式指定执行器）**
  * **`exceptionallyComposeAsync(Function<Throwable, ? extends CompletionStage<T>> fn, Executor executor)` (Since 12)** : 类似于 `exceptionallyAsync`，但 `fn` 返回一个 `CompletionStage`，用于更复杂的异常恢复逻辑，例如在异常时启动另一个异步任务。 **（推荐显式指定执行器）**



### 3\. 超时控制（1个）

  * **`CompletableFuture<T> orTimeout(long timeout, TimeUnit unit)`** : 如果在指定时间内未完成，则此 `CompletableFuture` 会以 `TimeoutException` 异常完成。这是实现健壮异步服务不可或缺的方法。



* * *

## 获取结果（阻塞与非阻塞）

  * **`T join()`** : 阻塞等待任务完成，并返回结果。如果异常完成，则抛出**非受检的`CompletionException`**。通常比 `get()` 更适合链式调用，因为它避免了繁琐的受检异常处理。

  * **`State state()`** : 非阻塞，返回当前状态，分别是 RUNNING（实际上指的是未完成状态，这个名称是设计上的缺陷，而且不能亡羊补牢了）, SUCCESS, CANCELLED, FAILED。更简洁，可以和之后两个方法结合使用，避免了传统获取结果方法（get, join) 需要封装异常的问题。

  * **`T resultNow()` (Since 19)** : 非阻塞。如果已成功完成，直接返回结果。这是对 `CompletableFuture` 状态进行“模式匹配”的更优雅方式。

  * **`Throwable exceptionNow()` (Since 19)** : 非阻塞。如果已异常完成，直接返回异常。与 `resultNow()` 配合，可以清晰地判断并处理最终结果或异常。



    
    
    public class SimpleCFApiDemo {
        public static void main(String[] args) {
            // allocate thread pool
            ExecutorService e = Executors.newCachedThreadPool();
    
            String userName = "123";
            CompletableFuture<String> result = CompletableFuture.completedFuture(userName)
                    .thenApplyAsync(u -> {
                        // fetch user info from db
                        Uninterruptibles.sleepUninterruptibly(5, TimeUnit.SECONDS);
                        return "UserInfo for " + u;
                    }, e);
            // do other ops
    
            // 超时操作可以放在获取结果时
            result.orTimeout(3, TimeUnit.SECONDS);
            // 阻塞等待
            result.exceptionallyAsync(ex -> null, e).join();
            switch (result.state()) {
                case SUCCESS -> System.out.println(result.resultNow());
                case FAILED -> System.out.println("Failed with exception: " + result.exceptionNow().getMessage());
                case RUNNING, CANCELLED -> throw new IllegalStateException();
            }
    
            // release resources
            e.shutdownNow();
        }
    }
    

* * *

## 任务编排（强烈推荐CFFU2）

原生API自带的编排方法（如 `allOf`、`anyOf`）能力有限且并不常用，尤其在错误处理方面存在不足。这里我们**强烈推荐使用CFFU2提供的编排方法** ，它们提供了更强大、更符合实际生产需求的编排能力。
    
    
    // CfIterableUtils
    // 聚合所有任务的结果，任何一个任务失败，整个编排立即失败（fail-fast）
    public static <T> CompletableFuture<List<T>> allResultsFailFastOf(Iterable<? extends CompletionStage<? extends T>> cfs)
    
    // CfTupleUtils, 支持多参数，聚合不同类型的任务结果，任何一个任务失败，整个编排立即失败（fail-fast）
    public static <T1, T2> CompletableFuture<Tuple2<T1, T2>> allTupleFailFastOf(
        CompletionStage<? extends T1> cf1, CompletionStage<? extends T2> cf2)
    // ... 还有allTupleFailFastOf(cf1, cf2, cf3) 等更多重载
    

**`failFast` 的含义是：**  
当任何一个子任务失败时，整个编排任务（例如 `allResultsFailFastOf` 或 `allTupleFailFastOf`）会**立即以该失败告终** ，而不是像原生 `allOf` 那样等待所有任务都完成后才抛出异常。这种行为在很多实际场景中更为合理和高效，可以避免不必要的计算和资源消耗。

* * *

## 那些用处不大的方法

以下方法，在笔者实践和理解中，要么可以通过之前推荐的方法组合实现，要么使用场景过于特殊，要么存在设计缺陷，不应成为初学者学习的重点，应该谨慎使用，有些甚至应该尽量避免使用。

  1. **就地执行、使用默认执行器的异步执行相关方法：**

     * **不带`Async` 后缀的方法**：如 `thenApply(Function)`。它们在当前线程同步执行，可能阻塞调用者，导致性能问题或死锁。
     * **带`Async` 后缀但未传入 `Executor` 参数的方法**：如 `thenApplyAsync(Function)`。它们使用 `ForkJoinPool.commonPool()` 作为默认执行器，可能导致任务队列饱和、线程耗尽，影响整个应用的性能。
     * **替代方案** ：所有带`Async`后缀的方法，都**推荐显式传入`Executor` 参数**。如果确实需要就地执行，可以通过传入 `MoreExecutors.directExecutor()` (Guava) 或 `Runnable::run` (Java 9+) 来实现，但应清楚其潜在风险。
  2. **`Runnable`、`Supplier`、`Consumer` 相关方法：**

     * 如 `thenRun`、`thenAccept`、`supplyAsync`。这些方法虽然提供了便利，但从函数式编程的角度看，它们都可以通过 `Function` 的变体（输入或输出为 `Void`）来表达。例如，`thenRun` 等同于 `thenApply(v -> null)`，`thenAccept` 等同于 `thenApply(t -> { consumer.accept(t); return null; })`。
     * **替代方案** ：统一使用 `thenApplyAsync` 和 `thenComposeAsync` 有助于简化概念模型，减少需要记忆的方法数量，并鼓励更纯粹的函数式编程风格。
  3. **两个元素关系的相关方法：`or`、`either`、`both`：**

     * 如 `acceptEither`、`runAfterBoth`。这些方法命名复杂且难以记忆，只适用于两个 `CompletableFuture` 的简单组合。
     * **替代方案** ：可以通过 CFFU2 提供的支持元组、列表的编排方法实现更灵活、更可扩展的组合逻辑。
  4. **同时处理值和异常的相关方法：`handle`、`whenComplete`：**

     * 这两个方法的名字就挺奇怪且难记。`handle` 相当于 `thenApply` 结合 `exceptionally` 的组合，而 `whenComplete` 则是 `thenAccept` 结合 `exceptionally`。
     * **替代方案** ：通过组合前文推荐的 `thenApplyAsync` / `thenComposeAsync` 和 `exceptionallyAsync` / `exceptionallyComposeAsync`，可以更清晰地分离正常流程与异常恢复逻辑，提高代码可读性。
  5. **`anyOf`、`allOf` (原生API)：**

     * `anyOf`：个人经验中用的不多，虽然看上去很有用，但实际场景中通常需要更复杂的逻辑来处理多个成功结果或错误。
     * `allOf`：原生 `allOf` 方法是**非 fail-fast** 的，即一个任务失败后，它会等待所有任务完成才抛出异常，这在需要快速响应失败的场景下是低效的。
     * **替代方案** ：对于 `anyOf`，通常可以用 `orTimeout` 和 `exceptionally` 组合实现更精细的控制。对于 `allOf`，**强烈推荐使用 CFFU2 提供的`allResultsFailFastOf` 或 `allTupleFailFastOf`**，它们提供了更健壮、更符合实际需求的 fail-fast 编排能力。
  6. **一些获取结果相关方法：`isDone`、`isCanceled`、`isCompletedExceptionally`：**

     * 这些方法提供了任务状态的布尔判断。
     * **替代方案** ：由于新版 `CompletableFuture` 提供了 `resultNow()` 和 `exceptionNow()` 这种“模式匹配”类似的方法，结合 `try-catch` 或 `Optional` 包装，可以更直接、更清晰地获取结果或异常，而无需先判断状态。`get()` 方法由于处理受检异常过于麻烦，更推荐使用 `join()` 或 `resultNow()` / `exceptionNow()` 组合处理结果。
  7. **提供给子类实现的相关方法：`newIncompletableFuture`、`defaultExecutor`、`toCompletableFuture` 等：**

     * 这些方法主要用于 `CompletableFuture` 的内部扩展或与其他 `CompletionStage` 实现的互操作，对于日常使用者来说，真正使用到的机会很少。
  8. **一些“差强人意”的实现：`cancel`：**

     * 原生 `cancel` 方法只设置任务状态为 `CANCELLED`，并不会中断正在执行的底层任务，也无法向下游传播取消信号，其行为常常不符合预期，导致取消逻辑复杂且不可靠。
     * **替代方案** ：更推荐通过 `orTimeout` 等机制进行超时控制，或者在任务内部实现协作式取消逻辑。
  9. **为了实现“只读”或者说防止篡改的实现：`completedStage`、`failedStage`、`minimalCompletionStage`、`copy` 等：**

     * 这些方法的核心思想是防御性编程，旨在返回一个不可修改的 `CompletionStage`。当你需要严格的防御性编程时用处很大，但考虑到实际开发效率，每次都写这种代码更像是在防御自己，而不是解决业务问题。
     * **替代方案** ：在大多数业务场景中，直接返回 `CompletableFuture` 即可，通过良好的代码规范和团队协作来避免误用。
  10. **支持竞争写、篡改的实现：`complete`、`completeExceptionally`、`obtrudeValue`、`obtrudeException`：**

     * 这些方法允许外部强制设置 `CompletableFuture` 的结果或异常，甚至覆盖已有的结果。它们打破了 `CompletableFuture` 通常的单次完成语义。
     * **建议** ：应该**尽量避免使用** 这些方法，除非你非常清楚其副作用，且用于将非 `CompletableFuture` 的异步源桥接到 `CompletableFuture` 生态系统。滥用会导致难以追踪的并发问题。
  11. **监控、调试相关方法：`getNumberOfDependents`：**

     * 这类方法提供了 `CompletableFuture` 内部状态的快照，但通常不用于业务逻辑。
     * **建议** ：更推荐使用专业的监控工具或日志系统来调试和观察异步任务。
  12. **延迟执行相关方法：`delayedExecutor`：**

     * `delayedExecutor` 使用到了 `CompletableFuture` 内部维护的 `delayer` 线程。
     * **建议** ：建议理解任务调度器 `ScheduledThreadPoolExecutor` 的核心思想后，全局使用自己维护的唯一全局任务调度器来管理延迟任务，以便更好地控制资源和生命周期。



* * *

通过这套精简的API，你将能够更高效、更清晰地编写 `CompletableFuture` 相关的异步代码，避免陷入API的海洋而不知所措。正所谓少即是多，聚焦核心才能真正掌握异步编程的精髓。