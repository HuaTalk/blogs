---
title: "【源码解析】无锁并发栈 Treiber 栈详解，理解 CompletableFuture, FutureTask, ForkJoinTask 源码必备"
date: 2025-02-18
url: https://juejin.cn/post/7472346299737751592
views: 306
likes: 5
collects: 1
source: html2md
---

原创不易，禁止转载！

Treiber栈作为一种经典的无锁并发栈，凭借其简洁的设计和高效的性能，成为许多并发框架的核心组件。本文将从基本概念、实现原理、可能的问题等多个维度，全面解析Treiber栈，最后探讨了其在`CompletableFuture`中的具体应用。

* * *

### 一、简介

Treiber栈是一种基于CAS（Compare-And-Swap）操作实现的无锁并发栈，由Robert D. Treiber于1986年提出。其核心思想是通过原子操作避免传统锁机制带来的性能开销，从而在高并发场景下提供高效的线程安全保证。

* * *

### 二、数据结构与核心操作

#### 1\. 数据结构

Treiber栈的数据结构由两个部分组成：

  * 节点结构：每个节点包含一个值和一个指向下一个节点的引用。 
        
        class Node<T> {
            T value;
            Node<T> next;
            Node(T value) { this.value = value; }
        }
        



    
    
    - 栈结构：使用`AtomicReference`维护栈顶节点。
      ```java
      class TreiberStack<T> {
          AtomicReference<Node<T>> top = new AtomicReference<>();
      }
    

#### 2\. 关键操作

根本原理：并发环境下，对于栈的修改操作实际上只有一种，就是修改栈顶指针。

**Push操作**
    
    
    public void push(T item) {
        Node<T> newHead = new Node<>(item);
        Node<T> oldHead;
        do {
            oldHead = top.get();
            newHead.next = oldHead;
        } while (!top.compareAndSet(oldHead, newHead));
    }
    

  * 过程：线程尝试将新节点设置为栈顶。若CAS失败，则循环重试。
  * 优势：确保只有一个线程能够成功修改栈顶。



**Pop操作**
    
    
    public T pop() {
        Node<T> oldHead;
        Node<T> newHead;
        do {
            oldHead = top.get();
            if (oldHead == null) return null;
            newHead = oldHead.next;
        } while (!top.compareAndSet(oldHead, newHead));
        return oldHead.value;
    }
    

过程：线程尝试将栈顶节点弹出，并将下一个节点设置为新的栈顶。通过CAS确保操作的原子性。

* * *

### 三、并发场景下的问题与方案

  1. 注意空栈检查


    
    
    while ((d = stack) != null) {
        if (casStack(d, d.next)) return d;
    }
    

  2. ABA问题


  * 现象：线程1读取值A → 线程2修改为B → 线程2又改回A → 线程1的CAS误判未变化。
  * 解决方案： 
    * 在节点不复用时可不处理（Java 满足这个约束，新创建的对象引用不会和已删除的 Node 引用相同）
    * 使用带版本号的原子引用（如`AtomicStampedReference`）


  3. 内存管理


  * 问题：弹出节点可能仍被其他线程引用。
  * 解决方案：Java的垃圾回收机制自动处理（无需手动释放）。



* * *

### 四、在`CompletableFuture`中的应用

`CompletableFuture`通过`stack`字段维护依赖关系，形成一个完成链，记录所有回调。
    
    
    // CompletableFuture 源码片段
    volatile Completion stack;
    static abstract class Completion extends ForkJoinTask<Void> {
        volatile Completion next; // ...
    }
    

以下为处理回调的核心代码：
    
    
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
        // h 为栈顶
        while ((h = f.stack) != null ||
               (f != this && (h = (f = this).stack) != null)) {
            CompletableFuture<?> d; Completion t;
            // 出栈 + 处理回调
            if (STACK.compareAndSet(f, h, t = h.next)) {
                if (t != null) {
                    if (f != this) {
                        pushStack(h);
                        continue;
                    }
                    NEXT.compareAndSet(h, t, null); // try to detach
                }
                // 这里的嵌套实现以后再分析(修改f）
                // tryFire执行具体回调，不同的回调有不同的tryFire实现
                f = (d = h.tryFire(NESTED)) == null ? this : d;
            }
        }
    }