---
title: "深入解析CompletableFuture源码实现"
date: 2024-09-09
url: https://juejin.cn/post/7522865190137708579
source: html2md
---

## 前言

CompletableFuture（CF) 提供了一种灵活的方式来处理异步计算。通过其丰富的 API，开发者可以轻松地组合多个异步任务。然而，其内部实现涉及复杂的状态管理和线程安全机制。本文将通过源码解析，揭示 CompletableFuture 的内部工作原理。

## 前置知识

在深入源码之前，我们需要了解一些基本概念：

  1. CF有三种执行模式，就地执行、指定执行器执行、默认执行器执行，后两种在Completion中会显式保存需要使用的执行器，就地执行时执行器字段为 null。

  2. CF只有两个字段：
         
         volatile Object result;       // Either the result or boxed AltResult
         volatile Completion stack;    // Top of Treiber stack of dependent actions
         

  3. 计算结果封装




由于直接使用Object类型字段无法区分结果未计算还是null，使用封装类，支持 单例 null 对象，支持封装异常 Throwable。根据Future#get、CompletableFuture#join 等方法签名要求，需要对结果进行封装或者转化（抛异常）。

  4. 使用 Treiber 栈，每层栈内类型为 Completion，记录回调；若栈为空，则没有回调或者回调执行完成。

  5. Completion 执行的回调有3种模式，同步 SYNC、异步 ASYNC、嵌套 NESTED。这里需要着重理解，因为其是理解源码复杂度的窍门。

  6. CF 单元操作、多元操作（Bi、Or、any等）最终都对应某个Completion的具体实现类。回调/Action 分类：

     * 单输入（`UniCompletion`），
     * 双输入（`BiCompletion`），
     * 投影（使用两个输入中的一个`BiCompletions`），
     * 共享（`CoCompletion`，由两个源中的第二个使用），
     * 源动作（零输入）
     * 信号器 Singallers (解除 waiters 阻塞)
  7. 回调需要原子执行，大多数实现底层为CAS操作，比如 result 设置的竞争、Treiber 栈的竞争、Completion 实现依赖 ForkJoinTask#tag字段的竞争。

  8. Completion 通常保存源与目标CF，方便回调计算，并及时释放源与目标，避免泄露。

  9. 完成的字段（结果）不需要声明为 final 或 volatile，因为它们只在安全发布时对其他线程可见。这是非常高级的技巧，建议不要轻易模仿尝试。

  10. 源码的复杂度在于多种状态的维护、线程安全要求；内部维护的多个类型需要相互协作，这是一种协作类模式，是一种常见的反模式，在不能保证封装不泄露的情况下，不要东施效颦。




## 一、触发回调

CF获得结果（isDone）后会触发所有回调，即处理所有的回调栈。这种触发模式为嵌套模式，此外还有回调模式分成就地执行和异步执行。具体方法为 postComplete：
    
    
        /**
         * Pops and tries to trigger all reachable dependents.  Call only
         * when known to be done.
         */
        final void postComplete() {
                /*
                 * On each step, variable f holds current dependents to pop
                 * and run.  It is extended along only one path at a time,
                 * pushing others to avoid unbounded recursion.
                 */
                CompletableFuture<?> f = this; Completion h;
                while ((h = f.stack) != null ||
                       (f != this && (h = (f = this).stack) != null)) {
                    CompletableFuture<?> d; Completion t;
                    if (STACK.compareAndSet(f, h, t = h.next)) {
                        if (t != null) {
                            if (f != this) {
                                pushStack(h);
                                continue;
                            }
                            NEXT.compareAndSet(h, t, null); // try to detach
                        }
                        f = (d = h.tryFire(NESTED)) == null ? this : d;
                    }
                }
            }
    

注意 f = this为读操作，并发编程中常常使用这种技巧，保证只读取一次，此后只使用临时变量 f 即可。与之对应的是技巧是 replace-temp-with-query，其在重构、代码优化中常常使用。

while 中 h 为栈，其或为 f 的 Completion，或为 this 的 Completion。第二个`或`可以保证当前CF的回调全部得到执行。

`if (STACK.compareAndSet(f, h, t = h.next))` 是一个常用的原子操作技巧，之前笔者在介绍 Treiber 栈时曾有详细讨论。

`pushStack(h);` 这里将其他CF上的Completion添加到this上了，Completion 实际上不需要绑定某一个 CF，其保存了源CF、目标CF、和回调操作等内容，可以相对独立地执行。

`NEXT.compareAndSet(h, t, null);` 这里将待执行的 Completion 解除其游离指针，至此我们拿到了需要执行的 Completion h。

`f = (d = h.tryFire(NESTED)) == null ? this : d;` 执行回调模式，回调返回其他已完成CF时，继续下一轮 while 循环；没有其他已完成CF时，继续以this执行。

当所有 Completion 执行完毕后结束循环。

Completion 的执行类似于深度优先搜索算法（DFS)。

## 二、Completion 抽象类解析
    
    
        @SuppressWarnings("serial")
        abstract static class Completion extends ForkJoinTask<Void>
            implements Runnable, AsynchronousCompletionTask {
            volatile Completion next;      // Treiber stack link
    
            /**
             * Performs completion action if triggered, returning a
             * dependent that may need propagation, if one exists.
             *
             * @param mode SYNC, ASYNC, or NESTED
             */
            abstract CompletableFuture<?> tryFire(int mode);
    
            /** Returns true if possibly still triggerable. Used by cleanStack. */
            abstract boolean isLive();
    
            public final void run()                { tryFire(ASYNC); }
            public final boolean exec()            { tryFire(ASYNC); return false; }
            public final Void getRawResult()       { return null; }
            public final void setRawResult(Void v) {}
        }
    

  1. 通常来说，抽象类一般用来支持模版方法模式，抽象方法提供一系列钩子，final 方法定义了模版。
  2. Completion 可组成 Treiber 栈，通过 next 指针实现。
  3. 继承实现了 ForkJoinTask，ForkJoinTask是FutureTask的轻量化实现，其提供 status 字段钩子，可以构建DAG依赖的task集合。
  4. 实现了Runnable，委托给`tryFire(ASYNC)`实现。
  5. AsynchronousCompletionTask 是一个标签接口。我们通常可以通过类型检查确定当前 Runnable 具有哪些特性，常用于监控。
  6. getRawResult 没啥用，不得不实现。
  7. isLive 给清理栈使用
  8. 最重要的实现 tryFire，提供三种模式。如果有，则返回目标CF,也就是 dependent。请记住这三种状态的值，后面分析会用到。


    
    
    // Modes for Completion.tryFire. Signedness matters.
    static final int SYNC   =  0;
    static final int ASYNC  =  1;
    static final int NESTED = -1;
    

  9. 子类实现举例



一共21个实现，这里只简单分析一部分： ![image.png](https://p6-xtjj-sign.byteimg.com/tos-cn-i-73owjymdk6/c5eeb80a8f4342af875e5f150466245a~tplv-73owjymdk6-jj-mark-v1:0:0:0:0:5o6Y6YeR5oqA5pyv56S-5Yy6IEAg5qGm6K-057yW56iL:q75.awebp?rk3s=f64ab15b&x-expires=1772455770&x-signature=34uK%2FD%2FKCJf18xACgx07X%2BAxon4%3D)
    
    
    // 单输入实现
    /** A Completion with a source, dependent, and executor. */
    @SuppressWarnings("serial")
    abstract static class UniCompletion<T,V> extends Completion {
        Executor executor;                 // executor to use (null if none)
        CompletableFuture<V> dep;          // the dependent to complete
        CompletableFuture<T> src;          // source for action
    }
    
    
    
    // 双输入实现
    abstract static class BiCompletion<T,U,V> extends UniCompletion<T,V> {
        CompletableFuture<U> snd; // second source for action
    }
    

单输入实现和双输入实现可以看作data类，贫血数据容器。此外还有委托模式实现。
    
    
    // 委托双输入实现
    static final class CoCompletion extends Completion {
        BiCompletion<?,?,?> base;
    

Signaller 尝试避免ForkJoinPool 饥饿阻塞，实现更为复杂，涉及join实现，随着我们分析的深入，也会详细解读，敬请期待。
    
    
    /**
     * Completion for recording and releasing a waiting thread.  This
     * class implements ManagedBlocker to avoid starvation when
     * blocking actions pile up in ForkJoinPools.
     */
    @SuppressWarnings("serial")
    static final class Signaller extends Completion
        implements ForkJoinPool.ManagedBlocker {
        long nanos;                    // remaining wait time if timed
        final long deadline;           // non-zero if timed
        final boolean interruptible;
        boolean interrupted;
        volatile Thread thread;
    }
    

anyOf 的实现使用了srcs，可以帮助进行资源释放，后续再分析。
    
    
    /** Completion for an anyOf input future. */
    @SuppressWarnings("serial")
    static class AnyOf extends Completion {
        CompletableFuture<Object> dep; CompletableFuture<?> src;
        CompletableFuture<?>[] srcs;
    }
    

  10. 不存在零输入Completion，因为Completion是回调，零输入不是回调。但是存在零输入的ForkJoinTask, 如 AsyncSupply对应于CF::supplyAsync


    
    
    static final class AsyncSupply<T> extends ForkJoinTask<Void>
        implements Runnable, AsynchronousCompletionTask {
        CompletableFuture<T> dep; Supplier<? extends T> fn;
        AsyncSupply(CompletableFuture<T> dep, Supplier<? extends T> fn) {
            this.dep = dep; this.fn = fn;
        }
        // 适配 ForkJoinTask
        public final Void getRawResult() { return null; }
        public final void setRawResult(Void v) {}
        public final boolean exec() { run(); return false; }
    
        public void run() {
            CompletableFuture<T> d; Supplier<? extends T> f;
            if ((d = dep) != null && (f = fn) != null) {
                // 依赖释放最早时间
                dep = null; fn = null;
                if (d.result == null) {
                    try {
                        d.completeValue(f.get());
                    } catch (Throwable ex) {
                        d.completeThrowable(ex);
                    }
                }
                // 触发回调
                d.postComplete();
            }
        }
    }
    

## 三、以单输入 UniApply 为例分析

UniApply 对应CF::thenApply、CF::thenApplyAsync 方法，也就是 map 语义。 我们先大概看一下其实现:
    
    
    static final class UniApply<T,V> extends UniCompletion<T,V> {
        Function<? super T,? extends V> fn;
        UniApply(Executor executor, CompletableFuture<V> dep,
                 CompletableFuture<T> src,
                 Function<? super T,? extends V> fn) {
            super(executor, dep, src); this.fn = fn;
        }
        final CompletableFuture<V> tryFire(int mode) {
            // ...
        }
    }
    

fn 是回调函数。看到这里，你可能已经绕晕了，我们可以借助JOL插件查看对象布局，顺便回顾一下继承链：

![image.png](https://p6-xtjj-sign.byteimg.com/tos-cn-i-73owjymdk6/2913f9423e1c45608f0c002cf0197cce~tplv-73owjymdk6-jj-mark-v1:0:0:0:0:5o6Y6YeR5oqA5pyv56S-5Yy6IEAg5qGm6K-057yW56iL:q75.awebp?rk3s=f64ab15b&x-expires=1772455770&x-signature=7gtlDmc%2FCBh5Gv84fCgEhLzTLgU%3D) 从图中可以看到继承链，从上到下沿着父类到子类的方向。每个字段都有其职责分离的职责，这里不再赘述。

### 原子化执行语义实现

UniCompletion claim 方法利用ForkJoinTask提供的CAS操作，实现原子化操作，即保证回调的执行只有一次。根据返回结果确定是否执行，实现了类似于 mutex#acquire 的功能。返回值为false，表示无需执行，已经有其他执行器或者线程执行；返回值为true，表示可以执行。
    
    
    /** A Completion with a source, dependent, and executor. */
    @SuppressWarnings("serial")
    abstract static class UniCompletion<T,V> extends Completion {
        Executor executor;                 // executor to use (null if none)
        CompletableFuture<V> dep;          // the dependent to complete
        CompletableFuture<T> src;          // source for action
        
        /**
         * Returns true if action can be run. Call only when known to
         * be triggerable. Uses FJ tag bit to ensure that only one
         * thread claims ownership.  If async, starts as task -- a
         * later call to tryFire will run action.
         */
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
        // 辅助清理
        final boolean isLive() { return dep != null; }
    }
    

### tryFire

我们来看最重要的实现 `tryFire`,笔者觉得这里的代码最为有趣，本文也算是为了这段代码包的饺子。

基本实现思路与概览：

  1. 如果相关资源已经释放，说明无需额外计算，返回null，表示没有额外回调。
  2. 异常传播，依赖的CF 保存 exception 时，取相同异常。
  3. 三种执行模式的实现。


    
    
    static final class UniApply<T,V> extends UniCompletion<T,V> {
        Function<? super T,? extends V> fn;
        UniApply(Executor executor, CompletableFuture<V> dep,
                 CompletableFuture<T> src,
                 Function<? super T,? extends V> fn) {
            super(executor, dep, src); this.fn = fn;
        }
        final CompletableFuture<V> tryFire(int mode) {
            CompletableFuture<V> d; CompletableFuture<T> a;
            Object r; Throwable x; Function<? super T,? extends V> f;
            if ((a = src) == null || (r = a.result) == null
                || (d = dep) == null || (f = fn) == null)
                return null;
            tryComplete: if (d.result == null) {
                if (r instanceof AltResult) {
                    if ((x = ((AltResult)r).ex) != null) {
                        d.completeThrowable(x, r);
                        break tryComplete;
                    }
                    r = null;
                }
                try {
                    if (mode <= 0 && !claim())
                        return null;
                    else {
                        @SuppressWarnings("unchecked") T t = (T) r;
                        d.completeValue(f.apply(t));
                    }
                } catch (Throwable ex) {
                    d.completeThrowable(ex);
                }
            }
            src = null; dep = null; fn = null;
            return d.postFire(a, mode);
        }
    }
    

### tryFire 嵌套模式（mode = -1)

为便于理解，其他短路条件跳过（考虑待执行回调状态），化简代码如下：
    
    
    final CompletableFuture<V> tryFire(int mode) {
        CompletableFuture<V> d = dep; 
        CompletableFuture<T> a = src;
        Object r = a.result;
        Function<? super T,? extends V> f = fn；
        
        try {
            if (!claim())
                return null;
            else {
                @SuppressWarnings("unchecked") T t = (T) r;
                d.completeValue(f.apply(t));
            }
        } catch (Throwable ex) {
            d.completeThrowable(ex);
        }
        
        src = null; dep = null; fn = null; // 资源及时释放
        return d.postFire(a, mode);
    }
    

此时代码就非常清晰了：

  1. claim 失败，也就是竞争失败，返回null, 同时null表示中断之后的操作。

  2. claim 成功，会执行else。这里反直觉的一点是 if 表达式判断时，实际上执行了 claim 逻辑。可以算是副作用，对于复杂源码的学习需要特别注意。之前 claim 的分析已经说明：claim 成功时，调用方负责执行，此时也是”就地执行“，只不过执行的上下文在tryFire(NESTED)中，正好对应我们最开始提到的postComplete方法。




如果回调阻塞或者比较耗时，就会“阻塞/延迟”后续回调的执行。所以最佳实践是：只对轻量级任务就地执行，此时避免上下文切换（切换线程），性能更好一点。

  3. 回调执行失败时，以异常结果完成dep。这里的try-catch实现考虑了 claim 异常的情况，如果claim中提交任务失败，也会以此异常完成dep。

  4. 执行后处理，后处理应该返回已完成的 dep。




### tryFire 同步模式（mode = 0)

这里有一个可能比较迷惑的问题，回调为什么同步执行？答案是非常巧，回调刚刚加入 Treiber 栈后，src 就有结果了，此时会触发同步模式执行回调。这里的同步模式和就地执行、执行器执行没有关系。以下举一个触发tryFire模式的代码例子，调用链是 thenApplyAsync -> uniApplyStage -> unipush -> tryFire(0)
    
    
    public <U> CompletableFuture<U> thenApplyAsync(
        Function<? super T,? extends U> fn) {
        return uniApplyStage(defaultExecutor(), fn);
    }
    
    private <V> CompletableFuture<V> uniApplyStage(
        Executor e, Function<? super T,? extends V> f) {
        if (f == null) throw new NullPointerException();
        Object r;
        if ((r = result) != null)
            return uniApplyNow(r, e, f);
        CompletableFuture<V> d = newIncompleteFuture();
        unipush(new UniApply<T,V>(e, d, this, f));
        return d;
    }
    
    /**
     * Pushes the given completion unless it completes while trying.
     * Caller should first check that result is null.
     */
    final void unipush(Completion c) {
        if (c != null) {
            while (!tryPushStack(c)) {
                if (result != null) {
                    NEXT.set(c, null);
                    break;
                }
            }
            if (result != null)
                c.tryFire(SYNC); // 刚入栈，src 就有结果了，说曹操，曹操就到
        }
    }
    

后续的执行和嵌套模式完全一致。

### tryFire 异步模式（mode = 1)

异步模式实际上我们已经见过了，只要任务在执行器中执行就是异步模式，Completion 自己就是任务（Runnable)，所以其执行就是异步模式，请回想Completion模版方法实现：
    
    
    public final void run()                { tryFire(ASYNC); }
    

化简后的tryFire代码如下:
    
    
    final CompletableFuture<V> tryFire(int mode) {
        CompletableFuture<V> d = dep; 
        Object r = a.result;
        Function<? super T,? extends V> f = fn；
        
        try {
            @SuppressWarnings("unchecked") T t = (T) r;
            d.completeValue(f.apply(t));
        } catch (Throwable ex) {
            d.completeThrowable(ex);
        }
        
        src = null; dep = null; fn = null; // 资源及时释放
        return d.postFire(a, mode);
    }
    

代码比较简单，封装了回调执行结果。

下一篇文章我们再来看看多源回调实现。