---
title: "深入解析CompletableFuture源码实现（2）———双源输入"
date: 2024-10-27
url: https://juejin.cn/post/7563914017632092202
source: html2md
---

## 前言

CompletableFuture（CF) 提供了一种灵活的方式来处理异步计算。通过其丰富的 API，开发者可以轻松地组合多个异步任务。然而，其内部实现涉及复杂的状态管理和线程安全机制。本文将通过源码解析，揭示 CompletableFuture 的内部工作原理。

上一篇文章[深入解析CompletableFuture源码实现](<https://juejin.cn/post/7522865190137708579> "https://juejin.cn/post/7522865190137708579") 中我们分析了thenApply的实现，这篇文章中将对双输入继续进行分析。

## 四、以双输入 BiApply 为例分析

### 回调数据类 BiApply 和执行回调方法 biApply
    
    
    abstract static class BiCompletion<T,U,V> extends UniCompletion<T,V> {
        // 双源实现的数据类，通过继承实现。实际上也可以通过组合实现。对于子类来说两种实现没有差别，因为字段都是透明的，可以直接访问字段。snd 是缩写，个人感觉不如直接命名为second。
        CompletableFuture<U> snd; // second source for action
        BiCompletion(Executor executor, CompletableFuture<V> dep,
                     CompletableFuture<T> src, CompletableFuture<U> snd) {
            super(executor, dep, src); this.snd = snd;
        }
    }
    

介绍一个简单的优化思路，字段单次读取：为了避免多次读取类中的同一个字段，可以通过声明 + 第一次读取时初始化临时变量实现，实现类似 if-let, switch-let 的效果。
    
    
    static final class BiApply<T,U,V> extends BiCompletion<T,U,V> {
        // 通过继承实现，
        BiFunction<? super T,? super U,? extends V> fn;
        BiApply(Executor executor, CompletableFuture<V> dep,
                CompletableFuture<T> src, CompletableFuture<U> snd,
                BiFunction<? super T,? super U,? extends V> fn) {
            // 封装了数据 + 回调
            super(executor, dep, src, snd); this.fn = fn;
        }
        // tryFire 方法触发回调
        final CompletableFuture<V> tryFire(int mode) {
            // 字段单次读取
            CompletableFuture<V> d;
            CompletableFuture<T> a;
            CompletableFuture<U> b;
            Object r, s; BiFunction<? super T,? super U,? extends V> f;
            // 已清理依赖，说明回调已经执行，不必继续执行，返回 NOOP，也就是 null
            if (   (a = src) == null || (r = a.result) == null
                || (b = snd) == null || (s = b.result) == null
                || (d = dep) == null || (f = fn) == null
                // 未清理依赖，执行biApply
                || !d.biApply(r, s, f, mode > 0 ? null : this))
            return null;
            src = null; snd = null; dep = null; fn = null;
            // 调用后续回调
            return d.postFire(a, b, mode);
        }
    }
    

以上代码有一个规律，所有的tryFire实现，都会调用一个对应的CompletableFuture内部方法，这里是biApply。

看下 biApply 的实现，这个方法在 CompletableFuture 中，主要用来执行回调：
    
    
    final <R,S> boolean biApply(Object r, Object s,
                                BiFunction<? super R,? super S,? extends T> f,
                                BiApply<R,S,T> c) {
        Throwable x;
        // 用了标签——业务中使用不算多的特性，可以跳出多层嵌套。
        tryComplete: if (result == null) {
            // 两个数据源输入如果任一一个失败，回调方法不必执行，直接跳出嵌套
            if (r instanceof AltResult) {
                if ((x = ((AltResult)r).ex) != null) {
                    // 异常传播到结果r
                    completeThrowable(x, r);
                    break tryComplete;
                }
                r = null;
            }
            // 同上
            if (s instanceof AltResult) {
                if ((x = ((AltResult)s).ex) != null) {
                    completeThrowable(x, s);
                    break tryComplete;
                }
                s = null;
            }
            try {
                // 仅当c不为空时，尝试获取执行许可
                // 也就是说：异步执行时（在执行器中执行时）mode = ASYNC = 1, 已经获得许可，执行回调
                if (c != null && !c.claim())
                    return false;
                @SuppressWarnings("unchecked") R rr = (R) r;
                @SuppressWarnings("unchecked") S ss = (S) s;
                // 执行回调
                completeValue(f.apply(rr, ss));
            } catch (Throwable ex) {
                completeThrowable(ex);
            }
        }
        return true;
    }
    

再来复习一下 Completion#claim 的实现，返回结果表示发放执行许可。
    
    
    final boolean claim() {
            Executor e = executor;
            if (compareAndSetForkJoinTaskTag((short)0, (short)1)) {
                if (e == null)
                    return true; // 就地执行，发放许可
                executor = null; // disable；及时清理
                e.execute(this); // 指定执行器执行，无需发放许可给后续执行，所以返回 false
            }
            return false; // if (false): cas失败：说明竞争失败，不发放许可
        }
    

### 触发过程
    
    
    // 就地执行
    public <U,V> CompletableFuture<V> thenCombine(
        CompletionStage<? extends U> other,
        BiFunction<? super T,? super U,? extends V> fn) {
        return biApplyStage(null, other, fn);
    }
    // 异步执行，默认执行器
    public <U,V> CompletableFuture<V> thenCombineAsync(
        CompletionStage<? extends U> other,
        BiFunction<? super T,? super U,? extends V> fn) {
        return biApplyStage(defaultExecutor(), other, fn);
    }
    // 异步执行，指定执行器
    public <U,V> CompletableFuture<V> thenCombineAsync(
        CompletionStage<? extends U> other,
        BiFunction<? super T,? super U,? extends V> fn, Executor executor) {
        return biApplyStage(screenExecutor(executor), other, fn);
    }
    

以上方法均调用 biApplyStage：
    
    
    private <U,V> CompletableFuture<V> biApplyStage(
        Executor e, CompletionStage<U> o,
        BiFunction<? super T,? super U,? extends V> f) {
        CompletableFuture<U> b; Object r, s;
        if (f == null || (b = o.toCompletableFuture()) == null)
            throw new NullPointerException();
        CompletableFuture<V> d = newIncompleteFuture();
        if ((r = result) == null || (s = b.result) == null)
            // 入栈，后续回调执行
            bipush(b, new BiApply<T,U,V>(e, d, this, b, f));
        else if (e == null)
            // 原地执行
            d.biApply(r, s, f, null);
        else
            // 异步执行
            try {
                // 也就是执行 run ==== tryFire(ASYNC)
                e.execute(new BiApply<T,U,V>(null, d, this, b, f));
            } catch (Throwable ex) {
                d.result = encodeThrowable(ex);
            }
        return d;
    }
    

#### 触发过程之入栈分析
    
    
    final void bipush(CompletableFuture<?> b, BiCompletion<?,?,?> c) {
        if (c != null) {
            while (result == null) {
                // 入栈（第一个CompletableFuture)
                if (tryPushStack(c)) {
                    if (b.result == null)
                        // 入栈（第二个CompletableFuture)
                        b.unipush(new CoCompletion(c));
                    else if (result != null)
                        // 不入栈，有结果时可以直接执行
                        c.tryFire(SYNC);
                    return;
                }
            }
            b.unipush(c);
        }
    }
    

入栈的逻辑是：尝试入栈；如果入栈的过程中结果已知，放弃入栈，直接触发回调（tryFire)。unipush内部执行相似逻辑，不赘述。

CoCompletion 封装了BiCompletion，委托模式。
    
    
    static final class CoCompletion extends Completion {
        BiCompletion<?,?,?> base;
        CoCompletion(BiCompletion<?,?,?> base) { this.base = base; }
        final CompletableFuture<?> tryFire(int mode) {
            BiCompletion<?,?,?> c; CompletableFuture<?> d;
            if ((c = base) == null || (d = c.tryFire(mode)) == null)
                return null;
            base = null; // detach
            return d;
        }
    

这里tryFire会调用两次，由第一个CF和第二个CF触发，只有第二次触发时会执行回调。入栈后的触发模式为嵌套模式，和前文对应不再赘述。

### 总结 BiApply 执行流程

a, b, c 表示两个输入和一个输出，最终结果 end 表示 c.postFire，后续的触发不在这次流程分析中。

#### 1\. 立即执行

a, b已知结果 ->> biApplyStage() ->> c.biApply(a, b, ...) ->> 执行回调函数 ->> end

#### 2\. 原地执行，指定执行器

回调函数在执行器 e 中执行，提交任务的动作在当前线程，这个任务是立即执行的。

t1: a, b 已知结果 ->> biApplyStage() ->> e.execute(new BiApply(...))

t2: BiApply#run, 也就是 BiApply#tryFire(ASYNC) ->> c.biApply(a, b, null: executor) ->> 执行回调函数 ->> end

#### 3\. 异步执行

回调函数在执行器 e 中执行，执行器e的提交动作由其他回调（也就是 Completion）完成。这里thisCompletion就是创建的BiApply。

t1: a,b 已知结果小于2 ->> biApplyStage() ->> 创建回调 Completion == BiApply ->> 入栈a,b  
t2: a 已知结果（postComplete, 深度优先搜索通知到c)  
t3: b 已知结果（postComplete, 深度优先搜索通知到c)  
t2/t3: a或b第二次通知到c == BiApply#tryFire(NESTED) ->> c.biApply(a, b, e, thisCompletion) ->> c.claim() ->> CAS成功，e.execute(thisCompletion)  
t4: BiApply#run, 也就是 BiApply#tryFire(ASYNC) ->> c.biApply(a, b, null: executor) ->> 执行回调函数 ->> end

### 异步执行流程可视化

**核心概念：**

  * **Completion (BiApply):** 一个回调对象，它等待两个前置 Completion (a, b) 完成，然后执行一个双参数函数。
  * **Executor (e):** 一个线程池或执行器，负责异步地执行提交给它的任务。
  * **postComplete:** 前置 Completion 完成后，通知其依赖者（这里是 BiApply）的机制。
  * **claim():** BiApply 内部的原子操作，确保在多个前置 Completion 同时完成时，只有一个线程负责将 BiApply 提交到 Executor。
  * **NESTED vs ASYNC:** `tryFire` 的两种调用模式。`NESTED` 表示在通知链中直接调用，`ASYNC` 表示在 Executor 线程中作为 Runnable 调用。



* * *

**参与者：**

  * **Completion A:** 第一个前置任务。
  * **Completion B:** 第二个前置任务。
  * **BiApply C (thisCompletion):** 我们的回调函数，依赖于 A 和 B。
  * **Executor e:** 异步执行器。
  * **Thread T_Notify (T_A / T_B):** 完成 A 或 B 的线程。
  * **Thread T_Executor (T_E):** Executor e 中的工作线程。



* * *

**流程图：**
    
    
    sequenceDiagram
        participant T_Notify_A as Thread T_Notify (Completes A)
        participant T_Notify_B as Thread T_Notify (Completes B)
        participant Completion_A as Completion A
        participant Completion_B as Completion B
        participant BiApply_C as BiApply C (thisCompletion)
        participant Executor_e as Executor e
        participant T_Executor as Thread T_Executor (from e)
    
        Note over T_Notify_A, T_Executor: **t1: 初始化阶段**
        T_Notify_A->>BiApply_C: biApplyStage()
        activate BiApply_C
        BiApply_C->>Completion_A: 注册为依赖 (入栈)
        BiApply_C->>Completion_B: 注册为依赖 (入栈)
        deactivate BiApply_C
        Note right of BiApply_C: BiApply C 等待 A 和 B 完成
    
        Note over T_Notify_A, T_Executor: **t2: Completion A 完成**
        T_Notify_A->>Completion_A: A.complete(resultA)
        activate Completion_A
        Completion_A->>BiApply_C: postComplete() -> tryFire(NESTED)
        activate BiApply_C
        Note right of BiApply_C: C 检查：A 已完成，B 未完成。<br/>不满足执行条件，不提交。
        deactivate BiApply_C
        deactivate Completion_A
        Note left of Completion_A: A 完成，通知 C，C 仍在等待 B
    
        Note over T_Notify_A, T_Executor: **t3: Completion B 完成 (触发提交)**
        T_Notify_B->>Completion_B: B.complete(resultB)
        activate Completion_B
        Completion_B->>BiApply_C: postComplete() -> tryFire(NESTED)
        activate BiApply_C
        Note right of BiApply_C: C 检查：A 已完成，B 已完成。<br/>满足执行条件。
        BiApply_C->>BiApply_C: c.claim() (CAS操作)
        alt CAS成功 (当前线程获得执行权)
            BiApply_C->>Executor_e: e.execute(thisCompletion)<br/>(提交 BiApply C 到执行器)
            activate Executor_e
            Note right of Executor_e: BiApply C (作为一个 Runnable) <br/>进入 Executor e 的任务队列。
        else CAS失败 (其他线程已提交)
            Note right of BiApply_C: 另一个线程已提交，当前线程退出。
        end
        deactivate BiApply_C
        deactivate Completion_B
        Note left of Completion_B: B 完成，通知 C，C 发现条件满足并提交自身到 Executor。
    
        Note over T_Notify_A, T_Executor: **t4: BiApply C 在 Executor 线程中执行**
        Executor_e->>T_Executor: 从队列中取出 BiApply C
        deactivate Executor_e
        activate T_Executor
        T_Executor->>BiApply_C: BiApply C.run()
        activate BiApply_C
        BiApply_C->>BiApply_C: tryFire(ASYNC)<br/>(内部调用 c.biApply(a, b, null))
        Note right of BiApply_C: 执行 BiApply 的回调函数<br/>(例如：(resA, resB) -> { /* 用户逻辑 */ })
        BiApply_C-->>T_Executor: 回调执行完成
        deactivate BiApply_C
        deactivate T_Executor
        Note over T_Notify_A, T_Executor: **流程结束**
    

**关键点总结：**

  * **异步性:** `BiApply` 的实际回调函数执行与触发它的 `Completion` 完成事件发生在不同的线程上（通过 `Executor e` 进行线程切换）。
  * **通知与执行分离:** `postComplete` 负责通知依赖者，实际的回调执行被推迟到 `Executor` 线程。
  * **`claim()` 的作用:** 确保在多个前置任务并发完成时，`BiApply` 的回调函数只被提交和执行一次。
  * **`tryFire(NESTED)` vs `tryFire(ASYNC)`:** `NESTED` 用于在通知链中检查条件和提交任务；`ASYNC` 用于在 `Executor` 线程中实际执行回调。`null: executor` 在 `ASYNC` 阶段表示不再需要进一步提交，直接执行。