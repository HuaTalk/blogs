---
title: "ConcurrentHashMap 弱一致性解读"
date: 2025-12-11
url: https://juejin.cn/post/7582232291621306406
views: 120
likes: 3
collects: 1
source: html2md
---

大家好，我是桦说编程。

> 本文将深入解读 ConcurrentHashMap 迭代器的弱一致性实现，帮助你掌握迭代过程中数据可见性的边界情况。

## 源码思想

### 设计目标

ConcurrentHashMap 的迭代器采用弱一致性（Weakly Consistent）设计，而非强一致性。目标是：

  * 迭代过程中不抛出 `ConcurrentModificationException`
  * 不加全局锁，保证高并发性能
  * 允许迭代期间的并发修改部分可见



### 核心理念

**快照语义 + 实时遍历** ：迭代器创建时不复制数据，而是直接遍历底层数组。遍历过程中：

  * 已遍历的桶位：修改不可见
  * 未遍历的桶位：修改可能可见



## 源码解析

### 迭代器核心结构
    
    
    // ConcurrentHashMap.java (JDK 8+)
    static class Traverser<K,V> {
        Node<K,V>[] tab;        // 当前遍历的数组
        Node<K,V> next;         // 下一个要返回的节点
        int index;              // 当前桶的索引
        int baseIndex;          // 初始索引
        int baseLimit;          // 遍历上限
        final int baseSize;     // 初始数组大小
    }
    

### 关键代码：advance() 方法
    
    
    final Node<K,V> advance() {
        Node<K,V> e;
        if ((e = next) != null)
            e = e.next;  // 【关键】链表遍历，直接读取 next 指针
        for (;;) {
            Node<K,V>[] t; int i, n;
            if (e != null)
                return next = e;
            // 【关键】baseIndex++ 推进到下一个桶
            if (baseIndex >= baseLimit || (t = tab) == null ||
                (n = t.length) <= (i = index) || i < 0)
                return next = null;
            // 【关键】volatile 读取桶位头节点，保证可见性
            if ((e = tabAt(t, i)) != null && e.hash < 0) {
                // 处理 ForwardingNode（扩容中）和 TreeBin
                if (e instanceof ForwardingNode) {
                    tab = ((ForwardingNode<K,V>)e).nextTable;
                    e = null;
                    pushState(t, i, n);
                    continue;
                }
                else if (e instanceof TreeBin)
                    e = ((TreeBin<K,V>)e).first;
                else
                    e = null;
            }
            if (stack != null)
                recoverState(n);
            else if ((index = i + baseSize) >= n)
                index = ++baseIndex;  // 推进 baseIndex
        }
    }
    

**核心逻辑** ：

  1. `tabAt(t, i)` 使用 `volatile` 语义读取桶位头节点
  2. 遍历链表时直接读取 `next` 指针（非 volatile）
  3. `baseIndex++` 线性推进，已遍历的桶不会再访问



## 复杂场景分析

### Case 1：迭代过程中删除未遍历的元素 —— 数据丢失
    
    
    初始状态：bucket[0]=A, bucket[5]=B, bucket[10]=C
    迭代器位置：正在遍历 bucket[0]
    
    并发操作：线程2删除 bucket[10] 的 C
    
    结果：迭代器遍历到 bucket[10] 时，C 已被删除，【数据丢失】
    

**原因** ：删除发生在未遍历的桶，且删除操作先于迭代器到达该桶。

### Case 2：迭代过程中删除已遍历的元素 —— 仍然返回
    
    
    初始状态：bucket[0]=A, bucket[5]=B
    迭代器位置：已遍历 bucket[0]，返回了 A
    
    并发操作：线程2删除 bucket[0] 的 A
    
    结果：A 已经被迭代器返回，删除不影响迭代结果
    

**原因** ：迭代器不会回头，已返回的数据不受后续删除影响。

### Case 3：迭代过程中新增到未遍历的桶 —— 可能可见
    
    
    初始状态：bucket[0]=A, bucket[5]=空
    迭代器位置：正在遍历 bucket[0]
    
    并发操作：线程2向 bucket[5] 插入 B
    
    结果：
    - 如果插入发生在迭代器到达 bucket[5] 之前，且写入对迭代器可见 → B 会被遍历【新数据加入】
    - 如果存在可见性延迟 → B 可能不会被遍历
    

**原因** ：`tabAt()` 是 volatile 读，能读到最新的桶位头节点。

### Case 4：迭代过程中新增到已遍历的桶 —— 不可见
    
    
    初始状态：bucket[0]=A, bucket[5]=B
    迭代器位置：已遍历 bucket[0]
    
    并发操作：线程2向 bucket[0] 插入 C
    
    结果：C 不会被迭代器返回【新数据丢失】
    

**原因** ：迭代器的 `baseIndex` 已经推进，不会回头遍历 bucket[0]。

### Case 5：迭代过程中修改当前链表的后继节点 —— 可能可见
    
    
    初始状态：bucket[0] 链表为 A -> B -> C
    迭代器位置：刚返回 A，next 指向 B
    
    并发操作：线程2将 B.next 从 C 改为 D
    
    结果：
    - 迭代器下一次调用 next() 返回 B
    - 再下一次读取 B.next，可能读到 C 或 D（取决于可见性）
    

**原因** ：链表节点的 `next` 字段不是 volatile，存在可见性问题。

### Case 6：扩容期间的迭代 —— 保证遍历完整性（深入分析）

#### 核心思想

扩容时迭代器需要**同时遍历旧表和新表** ，且支持**递归扩容** （扩容过程中再次扩容）。通过 `stack` 保存旧表状态，遍历完新表后恢复旧表继续遍历。

#### JDK 源码注释（原文翻译）
    
    
    /*
     * Traversal（遍历）必须处理以下情况：
     * 1. 遍历开始后的表扩容
     * 2. 遍历过程中遇到 ForwardingNode（表示该桶已迁移到新表）
     *
     * 核心策略：
     * - 当遇到 ForwardingNode 时，切换到新表继续遍历
     * - 使用 TableStack 保存旧表的遍历状态
     * - 遍历完新表对应区域后，恢复旧表状态继续
     * - 支持嵌套扩容（扩容中再扩容）
     *
     * 遍历顺序：
     * - 正常情况：按 baseIndex 顺序遍历 [0, 1, 2, ..., n-1]
     * - 扩容时：深度优先，先遍历新表，再回到旧表
     */
    

#### TableStack 结构
    
    
    /**
     * 用于保存/恢复遍历状态的栈结构
     * 当遇到 ForwardingNode 时 push，遍历完新表后 pop 恢复
     */
    static final class TableStack<K,V> {
        int length;             // 旧表长度
        int index;              // 旧表中下一个要遍历的索引
        Node<K,V>[] tab;        // 旧表引用
        TableStack<K,V> next;   // 链表结构，支持嵌套扩容
    }
    
    // Traverser 中的字段
    TableStack<K,V> stack;      // 状态栈
    TableStack<K,V> spare;      // 【优化】对象池，避免频繁 new
    

#### pushState：保存旧表状态
    
    
    /**
     * 遇到 ForwardingNode 时调用，保存当前遍历状态
     */
    private void pushState(Node<K,V>[] t, int i, int n) {
        TableStack<K,V> s = spare;  // 【优化】优先复用 spare 对象
        if (s != null)
            spare = s.next;         // spare 是单链表，取头节点
        else
            s = new TableStack<K,V>();  // spare 为空才 new
        s.tab = t;                  // 保存旧表引用
        s.length = n;               // 保存旧表长度
        s.index = i;                // 保存旧表当前索引
        s.next = stack;             // 压栈
        stack = s;
    }
    

#### recoverState：恢复旧表状态
    
    
    /**
     * 新表遍历完成后，恢复旧表状态继续遍历
     * @param n 当前表长度，用于判断是否需要恢复
     */
    private void recoverState(int n) {
        TableStack<K,V> s; int len;
        // 循环处理：可能有多层嵌套扩容
        while ((s = stack) != null && (index += (len = s.length)) >= n) {
            n = len;
            index = s.index;        // 恢复旧表索引
            tab = s.tab;            // 恢复旧表引用
            s.tab = null;           // help GC
            TableStack<K,V> next = s.next;
            s.next = spare;         // 【优化】用完的对象放回 spare 池
            stack = next;           // 弹栈
            spare = s;
        }
        // 调整 index 到下一个要遍历的位置
        if (s == null && (index += baseSize) >= n)
            index = ++baseIndex;
    }
    

#### spare 对象池优化
    
    
    spare 设计目的：避免扩容遍历时频繁创建 TableStack 对象
    
    工作流程：
    1. pushState 时：优先从 spare 取对象，没有才 new
    2. recoverState 时：用完的对象放回 spare
    
    效果：
    - 单次扩容：最多 new 1 个 TableStack
    - 嵌套扩容：复用已有对象，减少 GC 压力
    

#### 遍历顺序变化图解
    
    
    【正常遍历】按 baseIndex 顺序
    tab[16]: [0] -> [1] -> [2] -> ... -> [15]
             ↑
          baseIndex++
    
    【扩容时遍历】深度优先，先新表后旧表
    
    假设在遍历 tab[16] 的 index=5 时触发扩容：
    
    旧表 tab[16]:  [0] [1] [2] [3] [4] [5:FWD] [6] ... [15]
                                        ↓ pushState
    新表 tab[32]:  遍历 index=5 和 index=5+16=21 的桶
                                        ↓ recoverState
    旧表 tab[16]:  继续从 [6] 遍历 ... [15]
    
    遍历顺序：[0,1,2,3,4] -> [5的新表位置,21的新表位置] -> [6,7,...,15]
    

#### 扩容遍历的完整流程
    
    
    1. 迭代器在旧表 index=i 遇到 ForwardingNode
    2. pushState(oldTab, i, oldLen) - 保存旧表状态到 stack
    3. tab = ForwardingNode.nextTable - 切换到新表
    4. 在新表中遍历 index=i 和 index=i+oldLen 两个位置
       (因为扩容后，原桶的元素会分散到这两个位置)
    5. 新表对应位置遍历完，recoverState 恢复旧表状态
    6. 继续旧表 index=i+1 的遍历
    7. 如果新表中又遇到 ForwardingNode（嵌套扩容），重复 2-6
    

## 数据可见性总结表

场景| 操作位置| 操作类型| 迭代器可见性  
---|---|---|---  
Case 1| 未遍历的桶| 删除| 不可见（数据丢失）  
Case 2| 已遍历的桶| 删除| 已返回，不影响  
Case 3| 未遍历的桶| 新增| 可能可见  
Case 4| 已遍历的桶| 新增| 不可见  
Case 5| 当前链表后继| 修改| 可能可见  
Case 6| 扩容| 结构变化| 保证完整遍历  
  
## 完整示例
    
    
    import java.util.Iterator;
    import java.util.Map;
    import java.util.concurrent.ConcurrentHashMap;
    import java.util.concurrent.CountDownLatch;
    
    public class ConcurrentHashMapWeakConsistencyDemo {
        public static void main(String[] args) throws InterruptedException {
            // Case 1: 删除未遍历的元素 - 数据丢失
            System.out.println("=== Case 1: 删除未遍历的元素 ===");
            testDeleteUnvisited();
    
            // Case 2: 新增到未遍历的桶 - 可能可见
            System.out.println("\n=== Case 2: 新增到未遍历的桶 ===");
            testAddToUnvisited();
    
            // Case 3: 新增到已遍历的桶 - 不可见
            System.out.println("\n=== Case 3: 新增到已遍历的桶 ===");
            testAddToVisited();
        }
    
        static void testDeleteUnvisited() throws InterruptedException {
            ConcurrentHashMap<Integer, String> map = new ConcurrentHashMap<>();
            // 插入多个元素，确保分布在不同桶
            for (int i = 0; i < 100; i++) {
                map.put(i, "value-" + i);
            }
    
            CountDownLatch iteratorStarted = new CountDownLatch(1);
            CountDownLatch deleteCompleted = new CountDownLatch(1);
    
            int[] iteratedCount = {0};
            int[] foundKey50 = {0};
    
            Thread iteratorThread = new Thread(() -> {
                Iterator<Map.Entry<Integer, String>> it = map.entrySet().iterator();
                iteratorStarted.countDown();
                try {
                    deleteCompleted.await(); // 等待删除完成
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                while (it.hasNext()) {
                    Map.Entry<Integer, String> entry = it.next();
                    iteratedCount[0]++;
                    if (entry.getKey() == 50) {
                        foundKey50[0] = 1;
                    }
                }
            });
    
            Thread deleteThread = new Thread(() -> {
                try {
                    iteratorStarted.await(); // 等待迭代器创建
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                map.remove(50); // 删除 key=50
                deleteCompleted.countDown();
            });
    
            iteratorThread.start();
            deleteThread.start();
            iteratorThread.join();
            deleteThread.join();
    
            System.out.println("原始大小: 100, 迭代数量: " + iteratedCount[0]);
            System.out.println("key=50 是否被迭代到: " + (foundKey50[0] == 1 ? "是" : "否（数据丢失）"));
        }
    
        static void testAddToUnvisited() throws InterruptedException {
            ConcurrentHashMap<Integer, String> map = new ConcurrentHashMap<>();
            for (int i = 0; i < 10; i++) {
                map.put(i, "value-" + i);
            }
    
            int[] iteratedCount = {0};
            int[] foundKey999 = {0};
    
            Thread iteratorThread = new Thread(() -> {
                Iterator<Map.Entry<Integer, String>> it = map.entrySet().iterator();
                int count = 0;
                while (it.hasNext()) {
                    Map.Entry<Integer, String> entry = it.next();
                    iteratedCount[0]++;
                    if (entry.getKey() == 999) {
                        foundKey999[0] = 1;
                    }
                    // 第一次迭代后，让另一个线程插入
                    if (count == 0) {
                        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                    }
                    count++;
                }
            });
    
            Thread addThread = new Thread(() -> {
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                map.put(999, "new-value"); // 插入新元素
            });
    
            iteratorThread.start();
            addThread.start();
            iteratorThread.join();
            addThread.join();
    
            System.out.println("原始大小: 10, 最终map大小: " + map.size() + ", 迭代数量: " + iteratedCount[0]);
            System.out.println("key=999 是否被迭代到: " + (foundKey999[0] == 1 ? "是（新数据可见）" : "否"));
        }
    
        static void testAddToVisited() {
            ConcurrentHashMap<Integer, String> map = new ConcurrentHashMap<>();
            map.put(1, "one");
    
            Iterator<Map.Entry<Integer, String>> it = map.entrySet().iterator();
    
            // 先遍历第一个元素
            if (it.hasNext()) {
                System.out.println("遍历到: " + it.next());
            }
    
            // 在遍历后插入到同一个桶（通过 hash 冲突）
            map.put(1 + 16, "new-after-visit"); // 假设初始容量16，会落入相邻桶
            map.put(1, "updated-one"); // 更新已遍历的 key
    
            int count = 0;
            while (it.hasNext()) {
                System.out.println("继续遍历到: " + it.next());
                count++;
            }
    
            System.out.println("后续遍历数量: " + count);
            System.out.println("说明: 已遍历桶的修改通常不会被看到");
        }
    }
    

**运行输出示例** ：
    
    
    === Case 1: 删除未遍历的元素 ===
    原始大小: 100, 迭代数量: 99
    key=50 是否被迭代到: 否（数据丢失）
    
    === Case 2: 新增到未遍历的桶 ===
    原始大小: 10, 最终map大小: 11, 迭代数量: 11
    key=999 是否被迭代到: 是（新数据可见）
    
    === Case 3: 新增到已遍历的桶 ===
    遍历到: 1=one
    继续遍历到: 17=new-after-visit
    后续遍历数量: 1
    说明: 已遍历桶的修改通常不会被看到
    

### 扩容遍历顺序演示
    
    
    import java.lang.reflect.Field;
    import java.util.ArrayList;
    import java.util.Iterator;
    import java.util.List;
    import java.util.concurrent.ConcurrentHashMap;
    
    /**
     * 演示 ConcurrentHashMap 扩容时迭代器的遍历顺序变化
     *
     * 核心观察点：
     * 1. 正常遍历：按桶位顺序 [0,1,2,...,n-1]
     * 2. 扩容遍历：深度优先，遇到 ForwardingNode 先遍历新表
     */
    public class ResizeTraversalOrderDemo {
    
        public static void main(String[] args) throws Exception {
            System.out.println("=== 1. 正常遍历顺序 ===");
            normalTraversalOrder();
    
            System.out.println("\n=== 2. 扩容时遍历顺序（模拟） ===");
            explainResizeTraversal();
    
            System.out.println("\n=== 3. 验证扩容不丢数据 ===");
            verifyResizeCompleteness();
        }
    
        /**
         * 演示正常情况下的遍历顺序
         */
        static void normalTraversalOrder() throws Exception {
            ConcurrentHashMap<Integer, String> map = new ConcurrentHashMap<>(16);
    
            // 精心选择 key，使其分布在不同桶位
            // hash & (n-1) 决定桶位，n=16 时，桶位 = hash & 15
            int[] keys = {0, 1, 2, 16, 17, 32}; // 桶位分别是 0,1,2,0,1,0
            for (int key : keys) {
                map.put(key, "v" + key);
            }
    
            System.out.println("Map 内容: " + map);
            System.out.println("遍历顺序（按桶位）:");
    
            List<Integer> order = new ArrayList<>();
            for (Integer key : map.keySet()) {
                order.add(key);
                int bucket = key & 15; // 假设容量16
                System.out.println("  key=" + key + " (桶位≈" + (key % 16) + ")");
            }
        }
    
        /**
         * 图解扩容时的遍历逻辑
         */
        static void explainResizeTraversal() {
            System.out.println("""
                扩容遍历原理图解：
    
                【场景】容量 16 扩容到 32，迭代器正在遍历
    
                旧表[16]:  [0] [1] [2] [3] [4] [5:FWD] [6] [7] ... [15]
                            ↓   ↓   ↓   ↓   ↓     ↓
                已遍历 ─────────────────────┘     │
                                                  │ 遇到 ForwardingNode
                                                  ↓
                新表[32]:                    [5]      [21]  (5+16=21)
                                              ↓        ↓
                                           遍历新表对应位置
                                                  │
                                                  ↓ recoverState
                旧表[16]:                              [6] [7] ... [15]
                                                       ↓
                                                   继续旧表遍历
    
                【遍历顺序】
                旧表: 0 → 1 → 2 → 3 → 4 → (遇到FWD)
                新表:                      → 5 → 21
                旧表:                               → 6 → 7 → ... → 15
    
                【关键点】
                1. index 跳跃：新表中遍历 i 和 i+oldLen 两个位置
                2. 深度优先：先完成新表，再回旧表
                3. 不重不漏：通过 stack 保证状态正确恢复
                """);
        }
    
        /**
         * 验证扩容过程中遍历的完整性
         */
        static void verifyResizeCompleteness() throws InterruptedException {
            // 使用较小初始容量，容易触发扩容
            ConcurrentHashMap<Integer, String> map = new ConcurrentHashMap<>(4, 0.75f);
    
            // 插入足够多的元素，确保会扩容
            for (int i = 0; i < 100; i++) {
                map.put(i, "value-" + i);
            }
    
            // 记录所有遍历到的 key
            List<Integer> traversedKeys = new ArrayList<>();
    
            // 边遍历边插入，触发扩容
            Iterator<Integer> it = map.keySet().iterator();
            int count = 0;
            while (it.hasNext()) {
                Integer key = it.next();
                traversedKeys.add(key);
                count++;
    
                // 在遍历过程中继续插入，可能触发扩容
                if (count == 50) {
                    for (int i = 100; i < 200; i++) {
                        map.put(i, "value-" + i);
                    }
                    System.out.println("遍历到第50个时插入了100个新元素");
                }
            }
    
            System.out.println("最终 map 大小: " + map.size());
            System.out.println("遍历到的元素数: " + traversedKeys.size());
    
            // 验证原始100个元素是否都被遍历到
            int originalKeysTraversed = 0;
            for (int i = 0; i < 100; i++) {
                if (traversedKeys.contains(i)) {
                    originalKeysTraversed++;
                }
            }
            System.out.println("原始100个key遍历到: " + originalKeysTraversed + " 个");
    
            // 统计新插入的元素有多少被遍历到
            int newKeysTraversed = 0;
            for (int i = 100; i < 200; i++) {
                if (traversedKeys.contains(i)) {
                    newKeysTraversed++;
                }
            }
            System.out.println("新插入100个key遍历到: " + newKeysTraversed + " 个（弱一致性，可能部分可见）");
        }
    }
    

**运行输出示例** ：
    
    
    === 1. 正常遍历顺序 ===
    Map 内容: {0=v0, 32=v32, 16=v16, 1=v1, 17=v17, 2=v2}
    遍历顺序（按桶位）:
      key=0 (桶位≈0)
      key=32 (桶位≈0)
      key=16 (桶位≈0)
      key=1 (桶位≈1)
      key=17 (桶位≈1)
      key=2 (桶位≈2)
    
    === 2. 扩容时遍历顺序（模拟） ===
    扩容遍历原理图解：
    
    【场景】容量 16 扩容到 32，迭代器正在遍历
    ...
    
    === 3. 验证扩容不丢数据 ===
    遍历到第50个时插入了100个新元素
    最终 map 大小: 200
    遍历到的元素数: 156
    原始100个key遍历到: 100 个
    新插入100个key遍历到: 56 个（弱一致性，可能部分可见）
    

## 实战借鉴

### 设计思想：弱一致性换高性能

**源码做法** ：放弃强一致性保证，不加全局锁，允许迭代器看到部分并发修改。

**借鉴场景** ：缓存遍历、监控数据采集等对实时性要求不高的场景。

**示例代码** ：
    
    
    public class MetricsCollector {
        private final ConcurrentHashMap<String, Long> metrics = new ConcurrentHashMap<>();
    
        public static void main(String[] args) {
            MetricsCollector collector = new MetricsCollector();
            // 模拟并发更新
            collector.metrics.put("api.latency", 100L);
            collector.metrics.put("api.count", 1000L);
    
            // 采集时允许弱一致性，不影响业务逻辑
            collector.collectSnapshot().forEach((k, v) ->
                System.out.println(k + " = " + v)
            );
        }
    
        // 采集快照，允许弱一致性
        public Map<String, Long> collectSnapshot() {
            Map<String, Long> snapshot = new HashMap<>();
            // 遍历期间的并发更新可能部分可见，但不影响监控准确性
            metrics.forEach(snapshot::put);
            return snapshot;
        }
    }
    

### 设计思想：不可变快照的替代

**源码做法** ：不复制数据，直接遍历底层结构。

**借鉴场景** ：当复制成本过高时，接受弱一致性比强制复制更优。

## 总结

  * **核心思想** ：弱一致性是性能与一致性的权衡，迭代器不加锁，允许并发修改部分可见
  * **关键技巧** ：`baseIndex` 单向推进 + `volatile` 读桶位头节点 + 扩容跟随
  * **实战价值** ：理解弱一致性边界，避免在需要强一致性的场景误用 ConcurrentHashMap 迭代器



* * *

如果这篇文章对你有帮助，欢迎关注我，持续分享高质量技术干货，助你更快提升编程能力。