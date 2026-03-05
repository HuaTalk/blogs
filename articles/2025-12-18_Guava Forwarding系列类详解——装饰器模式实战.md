---
title: "Guava Forwarding系列类详解——装饰器模式实战"
date: 2025-12-18
url: https://juejin.cn/post/7585022810180714559
views: 99
likes: 5
collects: 4
source: html2md
---

大家好，我是桦说编程。

> 本文将以 ForwardingList 为例深入解析 Guava 的 ForwardingXXX 系列类，帮助你掌握装饰器模式在集合扩展中的优雅实现。Fowarding系列类在广义上涉及三种设计模式：装饰器模式、模版方法模式、委托模式。由于其可以避免通过继承形式实现的子类与父类的耦合，Forwarding 系列类已经成为笔者实现继承（功能拓展）的首选方式。

## 问题背景

在实际开发中，我们经常需要对标准集合类进行功能增强：

  * 在 List 添加元素时自动记录日志
  * 对 Map 的 put 操作进行参数校验
  * 在 Set 的读写操作上添加性能埋点



传统做法是直接继承 ArrayList、HashMap，但这会遇到几个问题：

  1. **需要重写大量方法** ：List 接口有 30+ 个方法，逐个重写工作量大
  2. **容易遗漏** ：某些方法忘记重写会导致增强逻辑失效
  3. **方法委托关系复杂** ：比如 `addAll` 内部可能调用 `add`，重写时需要理解内部实现



Guava 的 `ForwardingXXX` 系列正是为解决这类问题而设计。

## 核心原理

### 装饰器模式回顾
    
    
    ┌─────────────────────────────────────────────────┐
    │                   <<interface>>                 │
    │                       List                      │
    ├─────────────────────────────────────────────────┤
    │  + add(E): boolean                              │
    │  + get(int): E                                  │
    │  + size(): int                                  │
    │  ...                                            │
    └─────────────────────────────────────────────────┘
                            ▲
                            │ implements
              ┌─────────────┴─────────────┐
              │                           │
    ┌─────────────────┐         ┌─────────────────────┐
    │    ArrayList    │         │   ForwardingList    │
    ├─────────────────┤         ├─────────────────────┤
    │ 真正的实现        │◄────────│ - delegate: List<E> │
    └─────────────────┘   委托   │ + delegate(): List  │
                                │ + add(E): boolean   │
                                │   → delegate.add(e) │
                                └─────────────────────┘
                                          ▲
                                          │ extends
                                ┌─────────────────────┐
                                │   LoggingList<E>    │
                                ├─────────────────────┤
                                │ 只重写需要增强的方法 │
                                │ + add(E): boolean   │
                                │   → log + super.add │
                                └─────────────────────┘
    

### ForwardingList 源码剖析
    
    
    @GwtCompatible
    public abstract class ForwardingList<E> extends ForwardingCollection<E> implements List<E> {
    
        protected ForwardingList() {}
    
        // 子类必须实现：返回被装饰的 List
        @Override
        protected abstract List<E> delegate();
    
        // 所有方法都委托给 delegate()
        @Override
        public void add(int index, E element) {
            delegate().add(index, element);
        }
    
        @Override
        public boolean addAll(int index, Collection<? extends E> elements) {
            return delegate().addAll(index, elements);
        }
    
        @Override
        public E get(int index) {
            return delegate().get(index);
        }
    
        @Override
        public int indexOf(Object element) {
            return delegate().indexOf(element);
        }
    
        // ... 其他 List 方法同样委托
    
        // 标准方法实现：基于其他方法的默认实现
        protected boolean standardAdd(E element) {
            add(size(), element);
            return true;
        }
    
        protected boolean standardAddAll(int index, Iterable<? extends E> elements) {
            return Lists.addAllImpl(this, index, elements);
        }
    
        // ... 更多 standardXxx 方法
    }
    

**关键设计点** ：

设计元素| 说明  
---|---  
`abstract delegate()`| 强制子类指定被装饰对象，延迟绑定  
所有方法委托| 默认行为透传，子类按需重写  
`standardXxx` 方法| 提供基于基本操作的默认实现，避免委托循环  
  
### standardXxx 方法的作用

这是 ForwardingXXX 的精华所在。考虑这个场景：
    
    
    // 错误示范：addAll 内部可能直接调用 delegate 的 addAll
    public class LoggingList<E> extends ForwardingList<E> {
        @Override
        public boolean add(int index, E element) {
            log.info("Adding: {}", element);
            return delegate().add(index, element);
        }
    
        // addAll 没重写，直接委托给 delegate.addAll()
        // 结果：addAll 添加的元素不会被日志记录！
    }
    

正确做法是使用 `standardAddAll`：
    
    
    @Override
    public boolean addAll(int index, Collection<? extends E> elements) {
        return standardAddAll(index, elements); // 内部循环调用 this.add()
    }
    

## 实战示例

### 示例1：带日志的 List
    
    
    public class LoggingList<E> extends ForwardingList<E> {
        private static final Logger log = LoggerFactory.getLogger(LoggingList.class);
        private final List<E> delegate;
    
        public LoggingList(List<E> delegate) {
            this.delegate = delegate;
        }
    
        @Override
        protected List<E> delegate() {
            return delegate;
        }
    
        @Override
        public boolean add(E element) {
            log.info("[ADD] element={}", element);
            return standardAdd(element);
        }
    
        @Override
        public void add(int index, E element) {
            log.info("[ADD] index={}, element={}", index, element);
            delegate().add(index, element);
        }
    
        @Override
        public boolean addAll(int index, Collection<? extends E> elements) {
            log.info("[ADD_ALL] index={}, size={}", index, elements.size());
            return standardAddAll(index, elements); // 会逐个调用 add，每个都有日志
        }
    
        @Override
        public E remove(int index) {
            E removed = delegate().remove(index);
            log.info("[REMOVE] index={}, element={}", index, removed);
            return removed;
        }
    }
    

### 示例2：带容量限制的 List
    
    
    public class BoundedList<E> extends ForwardingList<E> {
        private final List<E> delegate;
        private final int maxSize;
    
        public BoundedList(List<E> delegate, int maxSize) {
            this.delegate = delegate;
            this.maxSize = maxSize;
        }
    
        @Override
        protected List<E> delegate() {
            return delegate;
        }
    
        private void checkCapacity(int additionalElements) {
            if (size() + additionalElements > maxSize) {
                throw new IllegalStateException(
                    "Exceeds max size: " + maxSize + ", current: " + size());
            }
        }
    
        @Override
        public boolean add(E element) {
            checkCapacity(1);
            return standardAdd(element);
        }
    
        @Override
        public void add(int index, E element) {
            checkCapacity(1);
            delegate().add(index, element);
        }
    
        @Override
        public boolean addAll(int index, Collection<? extends E> elements) {
            checkCapacity(elements.size());
            return delegate().addAll(index, elements);
        }
    }
    

### 示例3：性能埋点 List
    
    
    public class MetricsList<E> extends ForwardingList<E> {
        private final List<E> delegate;
        private final AtomicLong readCount = new AtomicLong();
        private final AtomicLong writeCount = new AtomicLong();
    
        public MetricsList(List<E> delegate) {
            this.delegate = delegate;
        }
    
        @Override
        protected List<E> delegate() {
            return delegate;
        }
    
        @Override
        public E get(int index) {
            readCount.incrementAndGet();
            return delegate().get(index);
        }
    
        @Override
        public boolean add(E element) {
            writeCount.incrementAndGet();
            return standardAdd(element);
        }
    
        @Override
        public void add(int index, E element) {
            writeCount.incrementAndGet();
            delegate().add(index, element);
        }
    
        public long getReadCount() {
            return readCount.get();
        }
    
        public long getWriteCount() {
            return writeCount.get();
        }
    }
    

## ForwardingXXX 家族

Guava 为常用集合都提供了 Forwarding 包装类：

类名| 对应接口| 典型应用场景  
---|---|---  
`ForwardingList`| List| 有序集合增强  
`ForwardingSet`| Set| 去重集合增强  
`ForwardingMap`| Map| K-V 映射增强  
`ForwardingQueue`| Queue| 队列增强  
`ForwardingMultimap`| Multimap| 一对多映射增强  
`ForwardingTable`| Table| 二维表增强  
`ForwardingIterator`| Iterator| 迭代器增强  
`ForwardingConcurrentMap`| ConcurrentMap| 并发 Map 增强  
  
## 与直接继承对比

方面| 直接继承 ArrayList| ForwardingList  
---|---|---  
代码量| 重写所有需要增强的方法| 只重写需要增强的方法  
灵活性| 绑定特定实现| 可装饰任意 List 实现  
组合能力| 单一继承| 可多层装饰叠加  
实现替换| 需修改代码| 构造时注入即可  
  
**装饰器叠加示例** ：
    
    
    // 一个 List 同时具备日志 + 容量限制 + 埋点
    List<String> base = new ArrayList<>();
    List<String> bounded = new BoundedList<>(base, 100);
    List<String> logged = new LoggingList<>(bounded);
    List<String> metered = new MetricsList<>(logged);
    
    // 调用顺序：metered → logged → bounded → base
    metered.add("item");
    

## 总结

  * **ForwardingXXX 是装饰器模式的标准实现** ：通过 `delegate()` 委托，只重写需要增强的方法
  * **standardXxx 方法避免委托循环** ：当增强逻辑依赖基础操作时，使用 standard 方法确保调用链正确
  * **支持多层装饰叠加** ：日志、校验、埋点等可以自由组合
  * **Guava 提供全系列 Forwarding 类** ：List、Set、Map、Queue、Multimap、Table 等都有对应实现



* * *

如果这篇文章对你有帮助，欢迎关注我，持续分享高质量技术干货，助你更快提升编程能力。