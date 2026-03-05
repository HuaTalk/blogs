---
title: "Vavr(Javaslang) 从入门到跑路（附使用指南）"
date: 2024-09-16
url: https://juejin.cn/post/7414734288632381479
views: 769
likes: 4
collects: 9
source: html2md
---

# Vavr 从入门到跑路

本文未经允许禁止转载！

Vavr（原名为Javaslang）是一个为 Java 提供**函数式编程** （Functional Programming）特性的库。Vavr旨在扩展Java的标准类库，弥补Java语言自身在函数式编程支持上的不足。它通过添加不可变的数据结构、模式匹配、函数式控制结构等功能，帮助Java开发者编写更简洁、可维护且更具表达力的代码。

## 怎么评价 Vavr

Vavr 可以实现尽最大限度地在Java中使用函数式编程，其提供了很多已用的特性，其不支持的特性大多由Java语言所限制（不完全的模式匹配、Monad、For Comprehension等）。在项目中使用 Vavr，可以体会函数式编程的优雅和乐趣。

实际上，现实代码中 Vavr 类库比较小众（和非函数式类库相比），同时不利于团队合作，对于大型项目如果依赖于某个特定类库又有一定的风险。话虽这么讲，如果你独立负责项目同时又具有一定的话语权的话，使用 Vavr 可以作为一种所谓的”防御性编程“手段，这里指的是防止被”优化“，当然即使被裁员了，其他人上手也有一定的门槛。

## 主要特性

Vavr 的核心特性涵盖了许多函数式编程概念，包括不可变数据结构、函数式接口、Try/Option等容器类型和模式匹配。为限制篇幅，某些特性的细节分析我后续会持续写相关文章，欢迎关注专栏：[函数式思想](<https://juejin.cn/column/7217795738692648997> "https://juejin.cn/column/7217795738692648997")。下面介绍一些主要功能：

### 1\. **不可变数据结构**

Vavr 提供了一组不可变的集合（例如 `List`、`Map`、`Set` 等），与 Java 原生的集合类类似，但它们是不可变的，并且支持函数式操作（如 map、filter、flatMap 等）。这些集合在并发环境下尤其有用，因为它们本身是线程安全的。
    
    
    import io.vavr.collection.List;
    
    // 注意需要导入 vavr 集合类型
    class VavrCollectionDemo {
        public static void main(String[] args) {
            List<Integer> numbers = List.of(1, 2, 3);
            List<Integer> doubled = numbers.map(n -> n * 2);
            System.out.println(doubled);  // 输出: List(2, 4, 6)
        }
    }
    

### 2\. [面向轨道编程类型](<https://link.juejin.cn?target=https%3A%2F%2Ffsharpforfunandprofit.com%2Frop%2F> "https://fsharpforfunandprofit.com/rop/")

#### 2.1. **`Option`类型**

Vavr提供了`Option`类型，用来优雅地处理可能为`null`的值。`Option`类似于Java 8中的`Optional`，但更加丰富和简洁。

  * `Option.Some` 表示有值的情况
  * `Option.None` 表示无值的情况


    
    
    class OptionDemo {
        public static void main(String[] args) {
            Option<String> maybeName = Option.of("Vavr");
    
            String name = maybeName.getOrElse("Unknown");
            System.out.println(name);  // 输出: Vavr
    
            Option<String> noName = Option.none();
            System.out.println(noName.getOrElse("Unknown"));  // 输出: Unknown
        }
    }
    

#### 2.2 **`Try`类型**

`Try`用于处理可能会抛出异常的操作，它提供了一种函数式的方式来处理异常，而不是使用传统的`try-catch`块。

使用 Try 可以实现

它有两种可能的状态：

  * `Success` 表示成功的结果
  * `Failure` 表示异常


    
    
    class TryDemo {
        public static void main(String[] args) {
            Try<Integer> result = Try.of(() -> 1 / 0);  // 可能抛出异常
    
            result
                .onFailure(ex -> System.out.println("Error: " + ex.getMessage()))  // 处理异常
                .onSuccess(res -> System.out.println("Success: " + res));  // 处理成功结果
        }
    }
    

#### 2.3 **`Either`类型**

`Either` 表示两个可能的值，其中一个是 "Right"（成功），另一个是 "Left"（错误）。与 `Try` 类似，它可以用来处理操作的结果，但更适合需要明确表示两种不同情况的场景。
    
    
    class EitherDemo {
        public static void main(String[] args) {
            Either<String, Integer> right = Either.right(42);
            Either<String, Integer> left = Either.left("Error occurred");
    
            System.out.println(right.isRight());  // 输出: true
            System.out.println(left.isLeft());    // 输出: true
        }
    }
    

### 3\. **模式匹配**

Vavr 提供了类似 Scala 中的模式匹配机制，通过 `Match` 类实现。它允许你基于数据的类型或结构来进行分支选择，使代码更加简洁、易读。
    
    
    class PatternMatchingDemo {
        public static void main(String[] args) {
            int number = 2;
    
            String result = Match(number).of(
                Case($(1), "One"),
                Case($(2), "Two"),
                Case($(), "Unknown")
            );
    
            System.out.println(result);  // 输出: Two
        }
    }
    

### 4\. **Tuple（元组）**

Vavr 提供了 `Tuple` 类型，允许你将多个值组合在一起返回。与 Java 中只能返回单一对象不同，`Tuple` 可以方便地组合多个不同类型的值。元组提供了很多便利方法，如 map（类似Optional的map功能）, append（返回新的元组）。
    
    
    class TupleDemo {
        public static void main(String[] args) {
            Tuple2<String, Integer> person = Tuple.of("John", 25);
            System.out.println(person._1);  // 输出: John
            System.out.println(person._2);  // 输出: 25
        }
    }
    

### 5\. **函数式特性与柯里化（Currying）**

Vavr支持函数式编程的核心特性之一——函数作为一等公民。Vavr 提供了多种函数式接口，例如 `Function1`、`Function2` 等，允许你创建高阶函数和支持柯里化。
    
    
    class FunctionDemo {
        public static void main(String[] args) {
            Function2<Integer, Integer, Integer> sum = (a, b) -> a + b;
    
            // 柯里化
            Function1<Integer, Integer> addFive = sum.curried().apply(5);
            System.out.println(addFive.apply(10));  // 输出: 15
        }
    }
    

### 6\. **懒加载（Lazy Evaluation）**

Vavr 支持延迟计算（Lazy evaluation），即值在需要的时候才会被计算。这种惰性求值的模式在性能优化中非常有用。
    
    
    class LazyDemo {
        public static void main(String[] args) {
            Lazy<Double> lazyValue = Lazy.of(Math::random);
            System.out.println(lazyValue.isEvaluated());  // 输出: false
            // 现在计算值
            System.out.println(lazyValue.get());
            System.out.println(lazyValue.isEvaluated());  // 输出: true
        }
    }
    

### 7\. **Stream（流式处理）**

Vavr 的 `Stream` 类提供了类似于 Java 8 的流式 API，但支持惰性求值和无限流的生成。
    
    
    class StreamDemo {
        public static void main(String[] args) {
            Stream<Integer> evenNumbers = Stream.iterate(0, n -> n + 2);
            System.out.println(evenNumbers.take(5).toList());  // 输出: List(0, 2, 4, 6, 8)
        }
    }
    

## 总结

Vavr 是一个强大且实用的库，它为 Java 引入了许多函数式编程的核心概念和工具。通过使用不可变数据结构、容器类型（如 `Option`、`Try`、`Either`）、模式匹配和懒加载等特性，Vavr 使得 Java 开发者能够更方便地编写函数式风格的代码，从而提高代码的可读性、简洁性和可维护性。

Vavr 在 Java 项目中非常适合用来处理复杂的数据流、避免空指针异常以及优化异常处理逻辑，是Java函数式编程的极佳选择。

## 使用指南

  1. 使用时尽量减少和原生集合类库混用，一方面因为两者基于不同的编程范式，容易理解混乱；另一方面因为两者很多类名重复，混用起来相当不便。
  2. 需要特别注意某些坑点：比如不可变对象的时间空间复杂度，懒计算等问题。
  3. 配合其他类库，如：Spring Data 支持 vavr 集合类型，支持 Jackson 序列化。
  4. 结合你当前的 JDK 版本选择更合适的实现，相同功能实现下优先选择Java语言提供的方法，比如 Java17+ 支持部分功能的模式匹配，record 类型可以替换 Tuple 实现，使用密封类/接口。