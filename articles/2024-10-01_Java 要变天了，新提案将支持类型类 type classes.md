---
title: "Java 要变天了，新提案将支持类型类 type classes"
date: 2024-10-01
url: https://juejin.cn/post/7541436591899344922
source: html2md
---

## 前言

继管道编程、lambda表达式、record、密封类、模式匹配等特性之后，Java似乎想在函数式编程范式上更进一步。2025 JVM 语言峰会上，Java 架构师 Brian Goetz 提出了Java支持类型类[新提案](<https://link.juejin.cn?target=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3DGz7Or9C0TpM%26t%3D3880s> "https://www.youtube.com/watch?v=Gz7Or9C0TpM&t=3880s")，旨在提升 Java 的拓展性。可预见的未来，这个特性和值类型(Project Valhalla)一样，将作为Java语言的核弹级更新，显著提升Java语言的竞争力。

### 初识类型类

你可能没有接触过类型类这个词，接下来我们一步步拆解“类型类”这个概念。

首先，想象一下 Java 现有的**接口（Interfaces）** 。接口定义了**对象能做什么** 。比如，`Comparable` 接口定义了对象如何与另一个同类型对象进行比较，`List` 接口定义了列表如何添加、删除元素等。当你有一个 `String` 对象，它可以调用 `length()` 方法，因为它是一个字符串的实例。Java 现有的接口非常擅长描述“实例的行为”。

但是，有时候我们想要描述的是“类型本身的行为”，而不是某个具体对象的行为。还有一些 Java 接口无法做到的事情，这就是类型类想要解决的问题。

**类型类** 是一种强大的抽象机制，它允许你在不使用子类型（即不修改现有类的继承层级）的情况下，为任何封闭数据类型添加新行为。

### 什么是 Java 里的“类型类”？

在 Java 的新设计中，“类型类”本质上就是一个**普通接口** 。

  * 它定义了**某种类型应该具备的某个行为** ，通常会用**泛型** （比如 `<T>`）来表示它适用于哪种类型。
  * 但关键在于，**它不要求被该类型直接“实现”** 。



这听起来有点抽象，我们用一个类比来理解：

想象 `Comparable` 和 `Comparator`。

  * `Comparable` 接口通常由类自己实现（比如 `String implements Comparable<String>`），它定义了“a如何与b比较”。
  * `Comparator` 接口则不同。一个 `Comparator<Integer>` 对象可以比较两个 `Integer`，但 `Integer` 类本身并不直接实现 `Comparator` 接口。`Comparator` 是一个**独立的“比较器”** ，由外部代码提供给需要比较的场景（比如 `Collections.sort` 方法可以接受一个 `Comparator`）。



Java 的“类型类”就像 `Comparator` 一样，是一个描述类型行为的接口，而不是让类型本身去实现它。

使用举例：
    
    
    // sort方法需要传入比较器
    // 使用类型类后不需要传入比较器实例，比较器实例可以理解为只和类型相关
    // witness 可以理解成已知比较器参数类型，相当于使用时没有泛型擦除
    sort(list, Comparator<String>.witness);
    // 更标准的用法
    result = MySort<String>.witness.sort(list);
    
    // 当前Java求和需要使用Stream#reduce等方法
    // 使用类型类后可以定义一个统一的求和方法
    List<BigDecimal> list = ...
    // ...
    sum(list, List<BigDecimal>.witness);
    List<Optional<BigDecimal>> list2 = ...
    sum(list2, List<Optional<BigDecimal>>.witness);
    

### 什么是“见证”（Witness）？

如果类型类是一个接口，那么“见证”就是这个类型类的**普通实例** 。

  * 你可以把它想象成一个“凭证”或“证明”，证明某个类型确实具备了类型类所定义的行为。
  * 比如，一个 `Comparator<Integer>` 的实例，就是 `Integer` 类型具有“可比较性”的**见证** 。



### 为什么 Java 需要类型类？它解决了哪些问题？

Java 引入类型类主要是为了增强语言的**可增长性（growable）和可扩展性（extensible）** ，解决现有接口的几个局限性。

  1. **为现有类型添加新行为（即表达式问题 Expression Problem）**

     * 设想你有一个 `String` 类（字符串），它是 Java 内置的，你无法修改它的源代码。现在你想要给 `String` 添加一个新的行为，比如让它支持某种新的格式化操作（`Formattable`）。
     * 使用传统接口，你必须让 `String` 类去 `implements Formattable`。但你无法修改 `String` 类的代码！
     * 有了类型类，你可以在**外部** 定义一个 `Formattable` 类型类，然后创建一个 `Formattable<String>` 的**见证** 。这个见证就证明了 `String` 类型支持这种格式化行为，而无需改动 `String` 本身的代码。
  2. **抽象“类型本身的行为”，而非“实例行为”**

     * 假设你想要计算一个数字列表的总和。如果列表是空的，总和是 `0`。如果列表包含 `String`，那么总和是“空字符串 `""`”（字符串拼接的“零值”）。
     * 你不能问一个空列表中的任何元素“你的零值是什么？”因为根本没有元素。你需要的是**类型** `Integer` 的零值是 `0`，**类型** `String` 的零值是 `""`。
     * 传统接口定义的是**实例方法** （比如 `myString.length()`），它们需要一个实例才能调用。但有时我们需要一个**静态（static）** **类型本身** 的行为（比如 `Monoid<T>` 类型类可以定义 `zero()` 方法来获取类型的零值）。
  3. **为同一类型提供多种行为实例**

     * 假设你有一个 `short` 类型。它可以被**拓宽转换** （widening conversion）成 `int`，也可以转换成 `long`，甚至可以转换成 `float`。
     * 如果用传统接口 `ConvertibleTo<T>`，Java 规定一个类只能实现一个 `ConvertibleTo<T>`（比如 `ConvertibleTo<Int>`），而不能同时实现 `ConvertibleTo<Long>` 和 `ConvertibleTo<Float>`。
     * 类型类则没有这个限制。你可以为 `short` 类型提供多个“见证”，比如 `ConversionWitness<Short, Int>`、`ConversionWitness<Short, Long>` 等，每个都作为 `short` 可以转换成不同类型的“凭证”。
  4. **避免命名冲突**

     * 有时接口中的方法名可能与实现它的类中已有的方法名冲突。
     * 通过类型类引入的这层间接性，可以更好地隔离接口方法的通用命名与特定类中的命名，减少冲突的可能性。



### Java 将如何实现这些“见证”的发布和查找？

这是类型类机制的核心：

  1. **发布见证（Publishing Witnesses）**

     * 你可以通过将一个 public static final 字段（就像一个静态常量）标记为见证来宣布一个类型具备某种行为。
    
    public static final witness Comparator<Integer> CANONICAL_COMPARATOR = ...
    

     * 你也可以将一个 **static** **方法** 标记为见证。这些方法可以根据已有的其他见证来**推导出（derive）**新的见证。例如，如果你有一个 `Monoid<T>` 的见证（知道如何“相加”类型 `T`），你可以推导出一个 `Monoid<Optional<T>>` 的见证（知道如何“相加”`Optional<T>`）。
    
    public static <T> witness Monoid<Box<T>> list(Monoid<T> w) {
        return new Monoid<>() {
            Box<T> zero() {
                return new Box(w.zero)); 
            }
            Box<T> plus(Box<T> a, Box<T> b) {
                return new Box(w.plus(a.unbox(), b.unbox()));
            }
        }
    }
    

  2. **查找见证（Finding Witnesses）**

     * **编译器将在代码编译时** 自动执行一个“证明搜索”过程来查找合适的见证。
     * 为了确保结果是明确和可预测的，**只有与你正在查找的见证的类型参数直接相关的类** 才会被考虑提供见证（例如，查找 `Monoid<Box<String>>` 的见证时，只会考虑 `Monoid`、`Box` 和 `String` 类型，而不会去问不相关的 `Integer` 或其他类）。
     * 当有多个潜在见证时，会有一套冲突解决规则，类似于 Java 现有的方法覆盖规则。
     * 由于这些见证在编译时就已经确定，并且被视为“符号常量”，这对于 Java 虚拟机（JVM）的即时编译器（JIT）来说非常有利，可以进行**积极的内联和优化** ，甚至可能将复杂操作简化为单个机器指令，从而提高性能。



### 类型类带来的实际好处和应用场景：

  1. **隐式宽类型转换（Implicit Widening Conversions）**

     * 未来，用户可以定义新的数值类型（比如 `float16`），并通过提供见证，让它们像内置的 `int` 自动转换为 `long` 或 `float` 自动转换为 `double` 一样，**自动进行拓宽转换** ，而无需在 Java 语言规范中硬编码一大堆转换表。
  2. **运算符重载（Operator Overloading）**

     * 允许为**值类型** （尤其是数值类型）重载运算符，比如让 `float16` 类型也能使用 `+` 运算符进行加法运算，而不是写成 `a.add(b)`。
     * 但这会有非常严格的限制，以防止滥用：**不能引入新的运算符** ，只能重载 Java 已有的运算符；**仅限于值类型，尤其是数值类型** ；并且必须实现一套完整的代数结构操作（如加、减、乘、除），以确保运算符的数学含义不变。
  3. **集合字面量（Collection Literals）**

     * 你可以用更简洁的语法创建集合，比如 `[a, b, c]` 直接表示一个列表。
     * 编译器会在编译时查找一个 `SequenceBuildable` 类型的见证，这个见证会告诉编译器如何从这些元素构建出对应的集合。这意味着**任何提供了相应见证的集合类型** 都可以使用这种简洁的字面量语法，而不再局限于 Java 内置的少数几个集合。
  4. **区分创建表达式（Distinguished Creation Expressions）**

     * 允许为某些**值类型** （通常是那些有“自然默认值”的类型，比如数字类型的 `0`）提供一个默认实例的见证。
     * 例如，在创建数组时，如果类型有默认实例，语言可以自动填充这些有效默认值，而不是像现在一样总是填充 `0` 或 `null`。



与 Scala 相比，Scala 3 已经通过 `given` 实例和 `using` 子句提供了类似类型类的功能。Java 的方法与 Scala 的 `given` 实例有异曲同工之妙，但 Java 的设计在见证查找和冲突解决方面将更加严格和可预测，以支持优化和清晰性。

总而言之，Java 的类型类机制，通过**普通的接口定义行为（类型类）** ，**接口的普通实例作为凭证（见证）** ，并结合编译器智能的查找机制，旨在让 Java 变得更加灵活、可扩展，能够以更优雅的方式处理那些目前通过内置功能或笨拙的模式才能实现的行为。这会使得用户代码能更好地与新的语言功能互动和扩展。