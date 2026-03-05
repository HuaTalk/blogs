---
title: "CompletableFuture 的第四种调用模式"
date: 2025-08-06
url: https://juejin.cn/post/7534891542233727019
views: 597
likes: 5
collects: 13
source: html2md
---

## 三种调用模式

CompletableFuture（以下简称CF）提供了三种调用模式，分别是就地执行、异步使用默认执行器执行、异步指定执行器执行。

就地执行指的是回调在当前线程中执行，调用thenApply、thenCompose等方法时，如果当前 CF 已经执行完成，会立即执行回调，称为当前执行，执行时刻为当前（now）；如果未完成，则由完成 CF 的线程执行。

如下分别是立即执行和异步执行的例子：
    
    
    var cf = CompletableFuture.completed(1);
    var result = cf.thenApply(x -> x + 1)
      .thenRun(x -> System.out.println(x))
      .resultNow();
      // .join();
    

这里可以不使用 `join`，因为立即执行使用的就是当前线程，调用 `join` 时不会等待结果而是直接获得已经计算出来的结果；最好使用 `resultNow` 方法，表示结果已经知道并且不用等待的语义。 以上代码全程同步。
    
    
    var cf = new CompletableFuture<Integer>();
    cf.thenApply(x -> x + 1)
      .thenRun(x -> System.out.println(x));
    new Thread(() -> cf.complete(1)).start();
    Uninterruptible.sleep(1, TimeUnit.SECONDS);
    

thenApply、thenRun在调用时，cf未完成，无法立刻执行，其执行在完成cf的线程，也就是新创建的线程中。

异步执行指的是回调任务的执行必定在执行器中执行，默认执行器为Java提供的commonPool线程池，当然也可以通过重写defaultExecutor实现调用指定的线程池。
    
    
    var cf = CompletableFuture.completed(1);
    cf.thenApplyAsync(x -> x + 1)
      .thenRunAsync(x -> System.out.println(x))
      .join();
    Uninterruptible.sleep(1, TimeUnit.SECONDS);
    

以上代码中打印操作在公共线程池中执行。

## 比较

就地执行性能最好，可以完全避免线程上下文切换，适合执行一些轻量级任务。缺点是使用不当时，会阻塞当前线程；可能会造成“线程泄露”，导致线程池中的线程没有及时归还。

异步执行反之。

## 第 4 种调用模式

线程池中任务执行有一条原则：尽最大努力交付。意思是如果任务提交时没有拒绝，没有抛出拒绝执行等异常，通常来说通过了信号量、限流器、执行时间、线程数等诸多限制，后续的执行应该不作额外限制，且努力完成；而不是等执行过程中再抛出类似拒绝服务等异常。反过来说，如果当前任务提交时，任务不能执行，就应该拒绝执行。这条简单的原则可以避免考虑复杂的问题，比如反压、取消机制等，也能够应对大多数的业务场景。

对于非轻量级任务，例如 A -> B，表示任务A执行完成后执行任务B，常规的线程池实现有一个问题，B任务的提交不一定立即执行，可能遇到排队（进入阻塞队列）甚至超时等情况，最终导致整个任务的滞后。此时如果能就地执行最好。

如果选择就地执行策略，解决了以上问题，但是可能会导致CF已完成后执行的当前线程阻塞。这时最好有执行器执行任务，而不是占用当前线程。

最近CFFU类库提供LLCF#relayAsync0,完美解决了以上痛点。LL表示low level，对于其的正确使用要求开发人员对CompletableFuture有着充分的理解。relay的含义是接力，这里指的是

  * relay Async 接力异步
  * Async 词尾，保证一定是异步（和CF命名表义 一样）



异步时（不阻塞调用逻辑），用前个computation的线程接力执行，不使用新线程，避免了上下文切换开销。

## 例子

relayAsync0 签名如下：
    
    
    public static <T, F extends CompletionStage<?>> F relayAsync0(
            CompletionStage<? extends T> cfThis,
            Function<CompletableFuture<T>, F> relayComputations, Executor executor)
    

需要注意传入的回调任务不是普通的Function，而是入参CF,出参 CompletionStage，也就是说我们需要传入对CF的回调。比如：
    
    
    cf -> cf.thenApply(...)
    cf -> cf.thenCompose(...)
    cf -> cf.thenRun(...)
    

该方法使用时和thenApplyAsync很像，只不过由实例方法调用改成了静态方法调用，回调参数为对CF的回调。

以下代码引用自CFFU作者 [李鼎 | Jerry Lee](<https://link.juejin.cn?target=https%3A%2F%2Fgithub.com%2Ffoldright%2Fcffu%2Fcommits%3Fauthor%3Doldratlee> "https://github.com/foldright/cffu/commits?author=oldratlee")，详细说明四种调用模式的用法：
    
    
    public class RelayAsyncDescriptionAndExample {
        static void executeComputationsOfNewStage(CompletableFuture<String> cf) {
    
            // ================================================================================
            // Default execution
            // ================================================================================
    
            cf.thenApply(s -> {
                // a simulating long-running computation...
                sleep(1000);
                // if input cf is COMPLETED when computations execute,
                // executes the long time computation SYNCHRONOUSLY (aka. in the caller thread);
                // this SYNCHRONIZED execution leads to BLOCKing sequential codes of caller... ⚠️
    
                return s + s;
            });
    
            // ================================================================================
            // Asynchronous execution of CompletableFuture(default executor or custom executor)
            // ================================================================================
    
            cf.thenApplyAsync(s -> {
                // a simulating long-running computation...
                sleep(1000);
                // always executes via an executor(guarantees not to block sequential code of caller).
                // if input cf is INCOMPLETE when computations execute,
                // the execution via an executor leads to ONE MORE thread switching. ⚠️
    
                return s + s;
            });
    
            // ================================================================================
            // How about the fourth way to arrange execution of a new stage's computations?
            // ================================================================================
            //
            // - if input cf is COMPLETED when computations execute, use "asynchronous execution" (via supplied Executor),
            //   won't block sequential code of caller ✅
            // - otherwise, use "default execution", save one thread switching ✅
            //
            // Let's call this way as "relay async".
    
            LLCF.relayAsync0(cf, f -> f.thenApply(s -> {
                // a simulating long-running computation...
                sleep(1000);
                // if input cf is COMPLETED, executes via supplied executor
                // if input cf is INCOMPLETE, use "default execution"
    
                return s + s;
            }), ForkJoinPool.commonPool());
        }
    }
    

## 实现分析
    
    
    public static <T, F extends CompletionStage<?>> F relayAsync0(
            CompletionStage<? extends T> cfThis,
            Function<CompletableFuture<T>, F> relayComputations, Executor executor) {
        final CompletableFuture<T> promise = new CompletableFuture<>();
        final F ret = relayComputations.apply(promise);
    
        final Thread callerThread = currentThread();
        final boolean[] returnedFromPeek0 = {false};
    
        LLCF.peek0(cfThis, (v, ex) -> {
            if (currentThread().equals(callerThread) && !returnedFromPeek0[0]) {
                // If the action is running in the caller thread(single same thread) and `peek0` invocation does not
                // return to caller(flag returnedFromPeek0 is false), the action is being executed synchronously.
                // To prevent blocking the caller's sequential code, use the supplied executor to complete the promise.
                executor.execute(() -> completeCf0(promise, v, ex));
            } else {
                // Otherwise, complete the promise directly, avoiding one thread switching.
                completeCf0(promise, v, ex);
            }
        }, "relayAsync0");
    
        returnedFromPeek0[0] = true;
        return ret;
    }
    

说明：

  1. completeCf0方法可以将结果v或者异常ex设置到promise中
  2. peek0 近似等效于 whenComplete



分析：

  1. 可以通过引入新的CF，也就是 promise 实现线程传递，其他线程“完成”promise时，这个线程隐式传到了promise中，可以理解成隐式上下文。任何一个CF都带有一个隐式上下文。
  2. returnedFromPeek0 避免了异步调用但是恰好是同线程的问题，此时也应该实现relay语义，因为我们的目的是避免对当前线程的阻塞。returnedFromPeek0 天然线程安全，因为其访问总是在一个确定的线程内。
  3. else 代码块：就地执行，避免线程切换。



## 总结