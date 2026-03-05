---
title: "深入解析CompletableFuture源码实现（3）———多源输入"
date: 2024-10-30
url: https://juejin.cn/post/7566465974406791222
source: html2md
---

## 前言

CompletableFuture（CF) 提供了一种灵活的方式来处理异步计算。通过其丰富的 API，开发者可以轻松地组合多个异步任务。然而，其内部实现涉及复杂的状态管理和线程安全机制。本文将通过源码解析，揭示 CompletableFuture 的内部工作原理。

上一篇文章[深入解析CompletableFuture源码实现(2)———双源输入](<https://juejin.cn/post/7563914017632092202> "https://juejin.cn/post/7563914017632092202") 中我们分析了thenCombine的实现，这篇文章中将继续对多源输入进行分析。

## 五、多输入 allOf 源码分析

### 回调数据类 BiRelay 和回调二叉树的构造

我们看下 allOf 专用的回调 Completion: BiRelay。其作用是：传递两个源CF结束回调到依赖CF，依赖CF只存 null 或者异常。虽然从技术角度也可以存所有结果，不过道哥似乎没有这方面的想法。
    
    
    static final class BiRelay<T,U> extends BiCompletion<T,U,Void> { // for And
        BiRelay(CompletableFuture<Void> dep,
                CompletableFuture<T> src, CompletableFuture<U> snd) {
            super(null, dep, src, snd);
        }
        final CompletableFuture<Void> tryFire(int mode) {
            CompletableFuture<Void> d;
            CompletableFuture<T> a;
            CompletableFuture<U> b;
            Object r, s, z; Throwable x;
            if (   (a = src) == null || (r = a.result) == null
                || (b = snd) == null || (s = b.result) == null
                || (d = dep) == null)
                // 只有一个源有结果时，必然返回null
                return null;
            if (d.result == null) {
                if ((r instanceof AltResult
                     && (x = ((AltResult)(z = r)).ex) != null) ||
                    (s instanceof AltResult
                     && (x = ((AltResult)(z = s)).ex) != null))
                     // 有异常，则存异常
                    d.completeThrowable(x, z);
                else
                    // 双源输入，无异常，则存null，表示两个源均已完成
                    d.completeNull();
            }
            // 清理资源。这里的思想是：如果已经得到计算结果，则清理回调数据类内数据（源、回调等）
            src = null; snd = null; dep = null;
            // 继续清理资源，清理a和b的回调栈
            return d.postFire(a, b, mode);
        }
    }
    

这里的清理实现可以先不关注，不影响对于整体实现的理解：
    
    
    final CompletableFuture<T> postFire(CompletableFuture<?> a,
                                        CompletableFuture<?> b, int mode) {
        // 1. 先清理第二个栈
        if (b != null && b.stack != null) { // clean second source
            Object r;
            if ((r = b.result) == null)
                // 需要注意，这里result == null表示未完成状态，此时必然存在多余的还未执行的BiRelay回调
                // 清理栈
                b.cleanStack();
            if (mode >= 0 && (r != null || b.result != null))
                b.postComplete();
        }
        // 2. 清理第一个栈
        return postFire(a, mode);
    }
    

#### 构建二叉树

之前我们说过BiCompletion保存了双源CF和依赖CF，可以想到一个最简单的实践是，反复使用 thenCombine，最终保存所有的输入，对应的代码是:
    
    
    cfs.stream().reduce((a, b) -> a.thenCombine(b, (x, y) -> null));
    

如果这样实现的话allOf仅仅是对原有功能的复用，但是效率很差。出现异常结束的结果时，时间复杂度为o(n)。如果最后一个cf得到异常结果，需要走完所有 n-1 个 BiApply(Completion)回调，才能得到最终结果。
    
    
    public static CompletableFuture<Void> allOf(CompletableFuture<?>... cfs) {
        return andTree(cfs, 0, cfs.length - 1);
    }
    

递归构建二叉树：
    
    
    static CompletableFuture<Void> andTree(CompletableFuture<?>[] cfs,
                                           int lo, int hi) {
        // 创建父结点
        CompletableFuture<Void> d = new CompletableFuture<Void>();
        if (lo > hi) // empty
            d.result = NIL;
        else {
            // a,b分别为左右节点
            CompletableFuture<?> a, b; Object r, s, z; Throwable x;
            int mid = (lo + hi) >>> 1;
            if ((a = (lo == mid ? cfs[lo] :
                      andTree(cfs, lo, mid))) == null ||
                (b = (lo == hi ? a : (hi == mid+1) ? cfs[hi] :
                      andTree(cfs, mid+1, hi))) == null)
                throw new NullPointerException();
            if ((r = a.result) == null || (s = b.result) == null)
                // a,b均未完成时，回调入栈，a和b都要入栈
                // a和b对于回调的调用共两次，没有异常的话，则第二次回调完成当前头结点
                a.bipush(b, new BiRelay<>(d, a, b));
            // 后面两个else分支分别处理了已知异常和已知无异常结果
            else if ((r instanceof AltResult
                      && (x = ((AltResult)(z = r)).ex) != null) ||
                     (s instanceof AltResult
                      && (x = ((AltResult)(z = s)).ex) != null))
                
                d.result = encodeThrowable(x, z);
            else
                d.result = NIL;
        }
        return d;
    }
    

这是一个递归实现，左右节点的构造依然调用当前函数，只不过参数范围缩小了。 关于递归实现一个重要的技巧是：先看方法输出，理解输出是什么，然后不要进入“深度优先搜索”的陷阱，要先理解当前函数的实现。这里输出是二叉树的父节点，左节点和右节点通过 Completion 回调通知父节点。

再来看下参数范围，lo和hi表示左右区间坐标。 由于 mid = (lo + hi) >>> 1，所以其指向的坐标偏左，也就是说，如果有两个元素（lo + 1 = hi)，mid 指向lo。

如果坐标的长度为0，父节点可以直接返回完成值，即 `CF(null)`，其可以作为其他节点的子节点，满足 identity 性质。  
如果坐标的长度为1，左节点对应于cfs[lo]，右节点对应于上一个例子。  
如果坐标的长度为2，左右节点分别对应cfs[lo],cfs[hi]，可以构建隐形的链接BiApply。  
如果坐标的长度为3，左节点对应于上一个例子，右节点对应于cfs[hi]。  
以此类推，最终构造了一个完全二叉树。

### 图解

当 n = 4时，构造如图：

说明：d0_3表示构造的节点，区间为[0,3]。单个CFi实际应该表示为左节点cfs[i]和右节点CF(null)，为便于理解没有完全画出。
    
    
                     d0_3 (allOf 结果)
                     /      \
                    /        \
        d0_1 (CF0,CF1 的组合) d2_3 (CF2,CF3 的组合)
              /    \        /    \
             /      \      /      \
           CF0      CF1    CF2      CF3
                   
    

n = 5时，构造如图：
    
    
                                  CF_0_4
                                 /      \
                                /        \
                         CF_0_2          CF_3_4
                        /      \        /      \
                       /        \      /        \
                  CF_0_1        CF2    CF3        CF4
                 /      \
                /        \
              CF0        CF1
    
    

显然，当任意一个叶子节点以异常完成时，allOf计算的时间复杂度为o(logn）。