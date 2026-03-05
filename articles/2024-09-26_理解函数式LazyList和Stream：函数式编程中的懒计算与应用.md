---
title: "理解函数式LazyList和Stream：函数式编程中的懒计算与应用"
date: 2024-09-26
url: https://juejin.cn/post/7418717006973763623
views: 238
likes: 6
collects: 1
source: html2md
---

## 理解函数式LazyList和Stream：函数式编程中的懒计算与应用

未经允许禁止转载！

### 前言

函数式编程中常见的技巧是懒计算（又叫惰性求值），`LazyList` 和 `Stream` 就是懒计算相关的数据结构。

`Stream` 和 `Stream` 相比，其所有节点都是懒计算的，而 `Stream` 头部是直接求值的。

这里 `Stream` 需要和 Java 标准库中的 `Stream` 相区别，函数式 `Stream` 是一种函数式数据结构，Java Stream 是进行流式编程的类。

Java 函数式类库 Vavr 中的 `Stream` 很大程度上借鉴了 Scala，如果你感兴趣可以学习一下 Scala。本文准备以 Vavr 中的 `Stream` 为例讲解其用法和使用场景。

### 1\. 三种方法实现裴波那契数列

#### 1\. 1 流可以引用自己
    
    
    import io.vavr.Tuple2;
    import io.vavr.collection.List;
    import io.vavr.collection.Stream;
    
    public class FibDemo {
      	// 传统实现，需要传入长度参数
        public static int[] fib(int n) {
            int[] nums = new int[n];
            if (n == 0) return nums;
            nums[0] = 0;
            if (n == 1) return nums;
            nums[1] = 1;
            for (int i = 2; i < n; i++) {
                nums[i] = nums[i - 1] + nums[i - 2];
            }
            return nums;
        }
    
        public static Stream<Integer> fib1() {
            Stream<Integer>[] fibs = new Stream[1];
            fibs[0] = Stream.cons(0, () ->
                Stream.cons(1, () ->
                    fibs[0].zip(fibs[0].tail()).map(n -> n._1 + n._2)));
            return fibs[0];
        }
    
        public static void main(String[] args) {
            List<Integer> fibs = fib1()
                .takeWhile(x -> x < 100)
                .toList();
            System.out.println(fibs);
        }
    }
    

输出结果为： List(0, 1, 1, 2, 3, 5, 8, 13, 21, 34, 55, 89)

Fib1 方法返回一个 `Stream`, 长度为无限，其具体值仅在需要时求解。cons 方法接受头部值和尾部（尚未计算的 `Stream`）,tail 方法返回去除头部后的 Stream。由于需要在lambda 计算时引用自己，Java 只能用一个容器类包起来，这里选择长度为1的数组，你也可以选择自己实现一个wrapper类。

算法思路是这样的：
    
    
    head1: 0 -> 1 -> xxx
    head2: 1 -> xxx
    head3: 1 -> xxxx
    

head1 为最初创建的 `Stream`, `head2` 为`head1.tail` ， 即去除头部后的 `Stream`, 实际上两者共用了内存，`head3` 为 `head1.tail.tail`，其值由前两项确定。

综合以上分析，`Stream` 的计算只要传入头部值和计算方法即可，数据结构 `Stream` 会在需要时进行计算。

#### 1.2 从任一起点开始计算
    
    
    public static Stream<Integer> fib2() {
        return fibFrom(0, 1);
    }
    
    private static Stream<Integer> fibFrom(int a, int b) {
        return Stream.cons(a, () -> fibFrom(b, a + b));
    }
    

这里方法fibFrom实际上实现的就是 裴波那契数列的基本算法 a, b, a + b。

#### 1.3 迭代器实现
    
    
    public static Stream<Integer> fib3() {
        return Stream.iterate(new Tuple2<>(0, 1), t -> new Tuple2<>(t._2, t._1 + t._2))
            .map(t -> t._1);
    }	
    

使用迭代器实现，将前一项值记为fib数列的前两项，这样每一项就都可以根据前一项进行计算了，然后进行 map 操作即可。

这个实现需要一定的技巧，使用 Java stream 也可以实现相同功能。

#### 1.4 函数式方法更好理解

实际上，使用 `Stream` 是比动态规划方法要更好理解的，因为函数式中很少考虑状态的维护，而在动态规划中状态转移方程是必不可少的，还需要考虑初始条件、边界条件。`Stream` 只需要指定好算法即可，而且 `Stream` 在未计算时就是可复用的。

### 2\. 什么时候使用 LazyList, Stream

#### 2.1. 处理大量数据或无限数据流

`LazyList`, `Stream` 允许处理潜在的无限列表，因为它不会尝试一次性将整个列表计算出来。例如，生成自然数序列、斐波那契数列等，你可能只关心前几个元素。

#### 2.2 避免不必要的计算，减少内存占用，提高性能

当计算每个元素的代价较高时，`LazyList`, `Stream` 可以提高效率。例如，你有一个昂贵的操作生成一个列表，而你只需要该列表中的部分元素，`LazyList`, `Stream` 可以避免预先计算整个列表。

#### 2.3 按需处理的场景

在某些应用中，数据可能是分阶段产生或需要的。例如，分页加载大量数据或逐步读取文件中的数据。LazyList 可以在需要时才加载下一部分数据。

#### 2.4 延迟初始化

有时候我们希望推迟一些对象的初始化直到它们被访问。`LazyList`, `Stream` 可以帮助我们在访问元素时才初始化它们，减少初始加载时间。

### 3\. 总结

使用`LazyList`, `Stream` 有诸多好处：

  1. 节省内存和提高性能。
  2. 支持无限长数据结构。
  3. 优化复杂计算，代码简洁易懂。



需要注意的问题：可能有内存泄漏的风险，如果不小心处理惰性列表中的未完成部分，可能会导致内存无法释放，特别是在无限数据流或长时间运行的应用中。

最佳实践：慎用