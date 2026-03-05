---
title: "滑动窗口限流器的演进之路：从调度器实现到 Packed CAS"
date: 2026-01-13
url: https://juejin.cn/post/7594642978695757887
views: 139
likes: 2
collects: 1
source: html2md
---

> 笔者注意到很多介绍限流算法的文章没有提供线程安全的实现，希望此文能达到抛砖引玉的作用。本文记录了一个滑动窗口限流器的完整演进过程。我们将看到每一次改进背后的动机、实现细节，以及在高并发场景下暴露的问题。这不是一个"正确答案"的展示，而是一个思考和迭代的过程。

## 问题背景

限流是分布式系统中的基础能力。滑动窗口算法相比固定窗口，能够更平滑地控制流量，避免窗口边界的突发问题。

我们的目标是实现一个滑动窗口限流器，支持：

  * 可配置的时间粒度（tickMillis）
  * 可配置的窗口大小（windowSize）
  * 可配置的容量上限（capacity）



* * *

## 第一版：Timer 驱动的实现

### 设计思路

最直观的想法是：用一个定时器周期性地推进窗口。每个 tick 到来时，保存当前计数，清理过期数据，重置计数器。业务线程只需要简单地递增计数并检查是否超限。
    
    
    ┌─────────────────────────────────────────────────────────┐
    │                    TimerSlidingWindowRateLimiter        │
    ├─────────────────────────────────────────────────────────┤
    │  ScheduledExecutorService  ──→  周期性调用 nextTick()   │
    │                                      │                  │
    │                                      ↓                  │
    │  WindowState { index, counter, preSum }                 │
    │                                      ↑                  │
    │  tryAcquire()  ──────────────────────┘                  │
    └─────────────────────────────────────────────────────────┘
    

### 实现
    
    
    @Value
    public class TimerSlidingWindowRateLimiter {
        int tickMillis;
        int windowSize;
        int capacity;
    
        @Getter
        @AllArgsConstructor
        static class WindowState {
            final long index;
            final AtomicInteger counter;
            final int preSum;
        }
    
        AtomicReference<WindowState> cur = new AtomicReference<>(
            new WindowState(0L, new AtomicInteger(0), 0));
        Map<Long, Integer> map = new HashMap<>();  // 单线程访问
        ScheduledExecutorService executorService = ...;
    
        void start() {
            executorService.scheduleAtFixedRate(
                () -> cur.updateAndGet(this::nextTick),
                tickMillis, tickMillis, TimeUnit.MILLISECONDS);
        }
    
        private WindowState nextTick(WindowState pre) {
            long index = pre.getIndex();
            int count = pre.getCounter().get();
            Integer toRemove = map.remove(index - windowSize);
            int newPre = count + pre.getPreSum() - (toRemove == null ? 0 : toRemove);
            map.put(index, count);
            return new WindowState(index + 1, new AtomicInteger(0), newPre);
        }
    
        public boolean tryAcquire() {
            WindowState curState = cur.get();
            AtomicInteger counter = curState.getCounter();
            int preSum = curState.getPreSum();
    
            if (counter.get() + preSum >= capacity) {
                return false;
            }
            counter.incrementAndGet();
            return true;
        }
    }
    

### 问题：Check-Then-Act 竞态条件

这个实现看起来合理，但在高并发下存在严重的 bug。问题出在 `tryAcquire()` 方法：
    
    
    // 线程 A 和 B 同时执行
    if (counter.get() + preSum >= capacity) {  // 两者都读到 99
        return false;
    }
    counter.incrementAndGet();  // 两者都执行，计数变成 101！
    return true;
    

这是典型的 **check-then-act** 竞态条件：检查和操作不是原子的，多个线程可以同时通过检查，导致超限。

* * *

## 第一次改进：使用 updateAndGet 实现原子性

### 修复思路

我们需要将"检查"和"递增"合并为一个原子操作。`AtomicInteger.updateAndGet()` 正好提供了这个能力：
    
    
    public boolean tryAcquire() {
        WindowState curState = cur.get();
        AtomicInteger counter = curState.getCounter();
        int preSum = curState.getPreSum();
    
        boolean[] acquired = {false};
        counter.updateAndGet(current -> {
            if (current + preSum >= capacity) {
                return current;  // 不修改
            }
            acquired[0] = true;
            return current + 1;  // 原子递增
        });
        return acquired[0];
    }
    

现在检查和递增是原子的，不会再出现超限问题。

### 遗留问题

Timer 实现有一个固有的问题：**需要管理线程生命周期** 。如果限流器是短生命周期的（比如每个请求创建一个），定时器线程会造成资源泄漏。我们能否避免使用定时器？

* * *

## 第二版：懒计算的实现

### 设计思路

定时器的作用是推进窗口，但我们真的需要它吗？窗口只在 `tryAcquire()` 时才需要是计算的。我们可以在每次调用时，根据当前时间**按需滑动** 窗口。
    
    
    ┌─────────────────────────────────────────────────────────┐
    │                 LazySlidingWindowRateLimiter            │
    ├─────────────────────────────────────────────────────────┤
    │  tryAcquire()                                           │
    │      │                                                  │
    │      ├──→ 计算 currentIndex = (now - start) / tick     │
    │      │                                                  │
    │      ├──→ 需要滑动? cur.updateAndGet(advanceToIndex)   │
    │      │                                                  │
    │      └──→ counter.updateAndGet(check-then-increment)   │
    └─────────────────────────────────────────────────────────┘
    

### 实现
    
    
    @Value
    public class LazySlidingWindowRateLimiter {
        int tickMillis;
        int windowSize;
        int capacity;
    
        long startTime = System.currentTimeMillis();
        AtomicReference<WindowState> cur = new AtomicReference<>(...);
        ConcurrentHashMap<Long, Integer> map = new ConcurrentHashMap<>();
    
        public boolean tryAcquire() {
            long currentIndex = (System.currentTimeMillis() - startTime) / tickMillis;
    
            // CAS 更新窗口状态
            WindowState curState = cur.updateAndGet(state -> {
                if (state.getIndex() >= currentIndex) {
                    return state;
                }
                return advanceToIndex(state, currentIndex);
            });
    
            // 原子化 check-then-increment
            AtomicInteger counter = curState.getCounter();
            int preSum = curState.getPreSum();
            boolean[] acquired = {false};
            counter.updateAndGet(current -> {
                if (current + preSum >= capacity) {
                    return current;
                }
                acquired[0] = true;
                return current + 1;
            });
            return acquired[0];
        }
    
        private WindowState advanceToIndex(WindowState state, long targetIndex) {
            map.put(state.getIndex(), state.getCounter().get());
            long minValidIndex = targetIndex - windowSize;
            map.keySet().removeIf(key -> key < minValidIndex);
            int preSum = map.values().stream().mapToInt(Integer::intValue).sum();
            return new WindowState(targetIndex, new AtomicInteger(0), preSum);
        }
    }
    

### 优势

  * **无后台线程** ：用完即弃，无资源泄漏风险
  * **资源友好** ：适合资源敏感场景



### 问题：窗口切换时的计数丢失

懒计算解决了线程管理问题，但引入了新的并发问题。考虑这个场景：
    
    
    时刻 T: 窗口即将从 index=5 切换到 index=6
    
    Thread A: 读取 state = {index=5, counter=10, preSum=80}
    Thread A: 准备执行 advanceToIndex()
                                        Thread B: counter.incrementAndGet() → 11
    Thread A: map.put(5, 10)  ← 丢失了 Thread B 的计数！
    Thread A: 返回新 state = {index=6, counter=0, preSum=90}
    

问题的根源在于：**`counter` 是可变的 `AtomicInteger`**。当我们读取它的值准备保存时，其他线程仍然可以修改它。

这个问题在 Timer 版本中不存在，因为 `nextTick()` 由单个定时器线程执行，不会与业务线程并发修改。

* * *

## 第三版：不可变状态的 CAS 实现

### 设计思路

要彻底解决计数丢失问题，我们需要让**整个状态不可变** 。任何修改都通过创建新状态 + CAS 更新来实现。

关键洞察：如果 `counter` 是不可变的 `int` 而非 `AtomicInteger`，那么在读取瞬间，状态就被"冻结"了。任何后续的修改都必须通过 CAS 整个 `WindowState`，失败的线程会重试并看到最新状态。
    
    
    ┌─────────────────────────────────────────────────────────┐
    │                 CasSlidingWindowRateLimiter             │
    ├─────────────────────────────────────────────────────────┤
    │  WindowState (IMMUTABLE)                                │
    │  ├── index: long                                        │
    │  ├── counter: int      ← 历史 tick 计数总和             │
    │  └── tickCounter: int  ← 当前 tick 计数                 │
    │                                                         │
    │  所有修改 = 创建新对象 + CAS 更新 AtomicReference       │
    └─────────────────────────────────────────────────────────┘
    

### 实现
    
    
    @Value
    public class CasSlidingWindowRateLimiter {
        int tickMillis;
        int windowSize;
        int capacity;
    
        @Getter
        @With
        @AllArgsConstructor
        static class WindowState {
            final long index;
            final int counter;      // 窗口内历史 tick 的计数总和
            final int tickCounter;  // 当前 tick 的计数
    
            int total() {
                return counter + tickCounter;
            }
    
            WindowState increment() {
                return withTickCounter(tickCounter + 1);
            }
        }
    
        long startTime = System.currentTimeMillis();
        AtomicReference<WindowState> cur = new AtomicReference<>(new WindowState(0L, 0, 0));
        ConcurrentHashMap<Long, Integer> map = new ConcurrentHashMap<>();
    
        public boolean tryAcquire() {
            long currentIndex = (System.currentTimeMillis() - startTime) / tickMillis;
    
            while (true) {
                WindowState state = cur.get();
    
                // 需要滑动窗口
                if (state.getIndex() < currentIndex) {
                    WindowState newState = advanceToIndex(state, currentIndex);
                    if (!cur.compareAndSet(state, newState)) {
                        continue;  // CAS 失败，重试
                    }
                    state = newState;
                }
    
                // 检查是否超限
                if (state.total() >= capacity) {
                    return false;
                }
    
                // CAS +1
                if (cur.compareAndSet(state, state.increment())) {
                    return true;
                }
                // CAS 失败，重试整个循环
            }
        }
    
        private WindowState advanceToIndex(WindowState state, long targetIndex) {
            int tickCounter = state.getTickCounter();
            if (tickCounter > 0) {
                map.put(state.getIndex(), tickCounter);
            }
    
            long minValidIndex = targetIndex - windowSize;
            int newCounter = 0;
            Iterator<Map.Entry<Long, Integer>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, Integer> entry = it.next();
                if (entry.getKey() < minValidIndex) {
                    it.remove();
                } else {
                    newCounter += entry.getValue();
                }
            }
    
            return new WindowState(targetIndex, newCounter, 0);
        }
    }
    

### 为什么这样能避免计数丢失？
    
    
    时刻 T: 窗口即将从 index=5 切换到 index=6
    
    Thread A: state = {index=5, counter=80, tickCounter=10}
    Thread A: 计算 newState = {index=6, counter=90, tickCounter=0}
                                        Thread B: 读取同一个 state
                                        Thread B: CAS(state → {5, 80, 11}) ✓
    Thread A: CAS(state → newState) ✗ 失败！state 已被 B 修改
    Thread A: 重试，读取新 state = {index=5, counter=80, tickCounter=11}
    Thread A: 重新计算 newState，包含 B 的计数
    

关键点：

  1. **状态不可变** ：一旦创建，永不修改
  2. **CAS 保证原子性** ：要么整体成功，要么整体失败
  3. **失败重试** ：使用最新状态重新计算



### 问题：频繁对象创建

每次 `increment()` 都创建新的 `WindowState` 对象。在高并发下，这会造成：

  * **GC 压力** ：大量短生命周期对象
  * **内存带宽** ：对象分配和复制的开销


    
    
    10 线程，100万次 tryAcquire
    ├── 100万次 WindowState 创建（成功的）
    ├── 可能数倍的失败重试（每次也创建对象）
    └── 频繁的 Young GC
    

* * *

## 第四版：Packed Long 实现

### 设计思路

`WindowState` 有三个字段：`index`（long）、`counter`（int）、`tickCounter`（int）。我们可以把它们**打包进一个 long** ：
    
    
    64 bits:
    ┌────────────────────────┬────────────┬────────────┐
    │     index (32 bits)    │counter(16) │tickCtr(16) │
    │       bits 32-63       │ bits 16-31 │ bits 0-15  │
    └────────────────────────┴────────────┴────────────┘
    

这样，`increment()` 操作变成简单的 `state + 1`（因为 tickCounter 在低 16 位），完全避免对象创建。

### 实现
    
    
    @Value
    public class PackedCasSlidingWindowRateLimiter {
        int tickMillis;
        int windowSize;
        int capacity;
    
        long startTime = System.currentTimeMillis();
        AtomicLong state = new AtomicLong(0L);
        AtomicLongArray ring;  // 环形数组
    
        // ==================== 位操作 ====================
    
        private static final int INDEX_SHIFT = 32;
        private static final int COUNTER_SHIFT = 16;
        private static final long COUNTER_MASK = 0xFFFF;
        private static final long TICK_COUNTER_MASK = 0xFFFF;
    
        static long getIndex(long state) {
            return state >>> INDEX_SHIFT;
        }
    
        static int getCounter(long state) {
            return (int) ((state >>> COUNTER_SHIFT) & COUNTER_MASK);
        }
    
        static int getTickCounter(long state) {
            return (int) (state & TICK_COUNTER_MASK);
        }
    
        static int getTotal(long state) {
            return getCounter(state) + getTickCounter(state);
        }
    
        static long pack(long index, int counter, int tickCounter) {
            return (index << INDEX_SHIFT) | ((long) counter << COUNTER_SHIFT) | tickCounter;
        }
    
        static long increment(long state) {
            return state + 1;  // tickCounter 在低位，直接 +1
        }
    
        // ==================== 核心逻辑 ====================
    
        public boolean tryAcquire() {
            long currentIndex = (System.currentTimeMillis() - startTime) / tickMillis;
    
            while (true) {
                long curState = state.get();
                long stateIndex = getIndex(curState);
    
                if (stateIndex < currentIndex) {
                    long newState = advanceToIndex(curState, currentIndex);
                    if (!state.compareAndSet(curState, newState)) {
                        continue;
                    }
                    curState = newState;
                }
    
                if (getTotal(curState) >= capacity) {
                    return false;
                }
    
                if (state.compareAndSet(curState, increment(curState))) {
                    return true;
                }
            }
        }
    }
    

### 存储优化：环形数组替代 HashMap

`ConcurrentHashMap` 有额外的内存开销（Entry 对象、哈希桶等），而且需要显式删除过期数据。

观察：我们的窗口大小是固定的，只需要存储最近 `windowSize` 个 tick 的数据。这正是**环形数组** 的完美场景：
    
    
    Ring Buffer (AtomicLongArray):
    ┌─────────┬─────────┬─────────┬─────────┬─────────┐
    │ slot[0] │ slot[1] │ slot[2] │  ...    │slot[n-1]│
    └─────────┴─────────┴─────────┴─────────┴─────────┘
         ↑
      index % windowSize
    
    每个槽位 (64 bits):
    ┌────────────────────────────────────┬────────────┐
    │         index (48 bits)            │ count (16) │
    └────────────────────────────────────┴────────────┘
    

关键优势：

  * **无需显式删除** ：新数据自动覆盖旧槽位
  * **固定内存** ：`windowSize * 8` 字节
  * **零 GC** ：没有对象分配


    
    
    private long advanceToIndex(long curState, long targetIndex) {
        // 存储当前 tick 的计数
        int tickCounter = getTickCounter(curState);
        long curIndex = getIndex(curState);
        if (tickCounter > 0) {
            int slot = (int) (curIndex % windowSize);
            ring.set(slot, packSlot(curIndex, tickCounter));
        }
    
        // 遍历环形数组，只计算有效范围内的数据
        long minValidIndex = targetIndex - windowSize;
        int newCounter = 0;
        for (int i = 0; i < windowSize; i++) {
            long slotValue = ring.get(i);
            long slotIndex = getSlotIndex(slotValue);
            if (slotIndex >= minValidIndex && slotIndex < targetIndex) {
                newCounter += getSlotCount(slotValue);
            }
        }
    
        return pack(targetIndex, newCounter, 0);
    }
    

* * *

## 最终对比

### 代码复杂度

版本| 核心数据结构| 并发控制| 代码行数  
---|---|---|---  
Timer| WindowState + HashMap| AtomicInteger.updateAndGet| ~80  
Lazy| WindowState + ConcurrentHashMap| AtomicReference.updateAndGet + AtomicInteger.updateAndGet| ~90  
CAS| WindowState (immutable) + ConcurrentHashMap| AtomicReference CAS loop| ~100  
Packed| long + AtomicLongArray| AtomicLong CAS loop| ~120  
  
### 并发正确性

版本| Check-Then-Act| 计数丢失| 状态一致性  
---|---|---|---  
Timer (原始)| ❌ 有问题| ✅ 无问题| ✅  
Timer (修复后)| ✅| ✅| ✅  
Lazy| ✅| ❌ 有问题| ✅  
CAS| ✅| ✅| ✅  
Packed| ✅| ✅| ✅  
  
### 性能特征

维度| Timer| Lazy| CAS| Packed  
---|---|---|---|---  
吞吐量| 高| 中| 中低| 高  
延迟稳定性| 好| 有毛刺| 有毛刺| 有毛刺  
GC 压力| 低| 中| 高| **零**  
内存占用| 动态| 动态| 动态| **固定**  
资源泄漏风险| 有| 无| 无| 无  
  
### 适用场景
    
    
    Timer 实现:
      ✓ 长时间运行的服务
      ✓ 对延迟稳定性要求高
      ✗ 需要管理线程生命周期
    
    Lazy 实现:
      ✓ 短生命周期限流器
      ✗ 高并发下有计数丢失风险（不推荐生产使用）
    
    CAS 实现:
      ✓ 正确性保证最强
      ✓ 代码可读性好
      ✗ GC 压力大
    
    Packed 实现:
      ✓ 零 GC
      ✓ 固定内存
      ✓ 高吞吐
      ✗ 代码可读性差（位操作）
      ✗ 容量限制（65535）
    

* * *

## 结语

这次演进展示了并发编程的几个核心教训：

  1. **Check-Then-Act 是并发 bug 的温床** 。始终问自己：检查和操作之间，状态会被其他线程修改吗？

  2. **可变共享状态是万恶之源** 。`AtomicInteger` 看起来是线程安全的，但当它作为复合状态的一部分时，仍然会出问题。

  3. **不可变性是并发的银弹** 。当状态不可变时，读取就是安全的，修改变成"创建新版本"，CAS 保证原子性。

  4. **性能优化有代价** 。Packed 实现虽然消除了 GC 压力，但牺牲了可读性。选择哪个版本，取决于你的具体场景。




最后，没有"最好"的实现，只有"最适合"的实现。理解每个版本的权衡，才能在实际工程中做出正确的选择。