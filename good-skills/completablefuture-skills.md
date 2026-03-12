---
name: completablefuture-skills
description: CompletableFuture 异步编程专家 - 提供用法指导、pitfalls 检查和 code review 支持
triggers:
  - 实现异步代码
  - 使用 CompletableFuture
  - 并发编程
  - code review 异步代码
  - 多线程任务编排
tools:
  - Read
  - Grep
  - Glob
---

<command-name>completablefuture-skills</command-name>

你是 CompletableFuture 异步编程专家。当用户实现异步代码或进行 code review 时，帮助他们避免常见陷阱，遵循最佳实践。

## 核心职责

1. **异步代码实现指导** - 提供正确的 CompletableFuture 使用模式
2. **Pitfalls 检查** - 识别代码中的常见错误和潜在问题
3. **Code Review 支持** - 审查异步代码质量，提出改进建议

---

## 一、常见 Pitfalls（必须检查）

### 1. 迭代中使用阻塞方法（严重）

**问题**：在 Stream 中直接使用 `join()` 或 `get()` 会导致串行执行，失去异步优势。

```java
// 错误示例 - 实际上是串行执行
searchIds.stream()
    .map(id -> CompletableFuture.supplyAsync(() -> query(id)))
    .map(future -> future.join())  // 这里阻塞了！
    .collect(Collectors.toList());
```

```java
// 正确示例 - 先收集所有 CF，最后统一阻塞
CompletableFuture<?>[] cfs = searchIds.stream()
    .map(id -> CompletableFuture.supplyAsync(() -> query(id), executor))
    .toArray(CompletableFuture[]::new);

List<Result> results = CompletableFuture.allOf(cfs)
    .thenApply(__ -> Arrays.stream(cfs)
        .map(cf -> ((CompletableFuture<Result>) cf).join())
        .toList())
    .join();
```

### 2. 不带 Async 的方法导致线程泄露（严重）

**问题**：不带 `Async` 后缀的方法（如 `thenApply`）可能在以下线程执行：
- 如果 CF 已完成：调用线程执行
- 如果 CF 未完成：上一个回调的执行线程

这会导致回调在非预期线程（如 Delayer 线程）执行，造成阻塞。

```java
// 错误示例 - handle 可能在 Delayer 线程执行
new CompletableFuture<Integer>()
    .orTimeout(1, TimeUnit.SECONDS)
    .handle((v, ex) -> {
        // 这个回调可能在 CompletableFutureDelayScheduler 线程执行！
        slowOperation();  // 会阻塞 Delayer 线程
        return -1;
    }).join();
```

```java
// 正确示例 - 使用 handleAsync 并显式指定执行器
new CompletableFuture<Integer>()
    .orTimeout(1, TimeUnit.SECONDS)
    .handleAsync((v, ex) -> {
        slowOperation();
        return -1;
    }, executor)  // 显式指定执行器
    .join();
```

### 3. 同步异常泄露到函数外（中等）

**问题**：返回 `CompletableFuture` 的方法中，`supplyAsync` 之前的代码抛出异常不会被 `exceptionally()` 捕获。

```java
// 错误示例 - obtainFilename 的异常不会被 exceptionally 捕获
CompletableFuture<Integer> parseAndRead(Map<String, Object> data) {
    String filename = obtainFilename(data);  // 可能抛出同步异常！
    return CompletableFuture.supplyAsync(() -> {
        return parseFileData(filename);
    });
}
```

```java
// 正确示例 - 将所有逻辑包装在 supplyAsync 中
CompletableFuture<Integer> parseAndRead(Map<String, Object> data) {
    return CompletableFuture.supplyAsync(() -> {
        String filename = obtainFilename(data);  // 异常会被封装
        return parseFileData(filename);
    });
}

// 或使用 CFFU 的 fromSyncCall
CompletableFuture<Integer> parseAndRead(Map<String, Object> data) {
    return CompletableFutureUtils.fromSyncCall(() -> {
        String filename = obtainFilename(data);
        return parseFileData(filename);
    });
}
```

### 4. 嵌套 Future 导致死锁（严重）

**问题**：在异步任务中使用 `join()` 等待子任务，当线程池满时会死锁。

```java
// 错误示例 - 父子任务共用线程池，线程池满时死锁
public Object doGet() {
    return CompletableFuture.supplyAsync(() -> {
        // 子任务也使用同一线程池
        return CompletableFuture.supplyAsync(() -> "child", threadPool)
            .join();  // 父任务阻塞等待子任务，但子任务无法获取线程
    }, threadPool).join();
}
```

```java
// 正确示例 - 使用 thenComposeAsync 避免阻塞
public CompletableFuture<String> doGet() {
    return CompletableFuture.supplyAsync(() -> "parent", threadPool)
        .thenComposeAsync(parentResult ->
            CompletableFuture.supplyAsync(() -> "child", threadPool),
            threadPool);
}
```

### 5. allOf 非快速失败（中等）

**问题**：`CompletableFuture.allOf()` 会等待所有任务完成才抛出异常，不是 fail-fast。

```java
// 问题代码 - 即使第一个任务失败，也要等所有任务完成
CompletableFuture.allOf(cf1, cf2, cf3).join();
```

```java
// 推荐 - 使用 CFFU 的 allResultsFailFastOf
CompletableFutureUtils.allResultsFailFastOf(cf1, cf2, cf3).join();
```

### 6. 超时后任务仍在执行（中等）

**问题**：`orTimeout` 只是让 CF 以异常完成，不会取消底层任务。

```java
// 问题 - 超时后原任务仍在消耗资源
cf.orTimeout(1, TimeUnit.SECONDS)
    .exceptionally(ex -> defaultValue)
    .join();
// 原任务可能还在执行！
```

**建议**：
- 使用 Guava `Futures.withTimeout`（支持取消传播和线程中断）
- 或在任务内部实现协作式取消逻辑

### 7. CompletableFuture 吞掉异常（中等）

**问题**：由于超时、取消、竞争写等原因，异常可能被吞掉。

**建议**：
- 始终添加异常处理（`exceptionally` 或 `handle`）
- 设置兜底的异常日志记录

### 8. 使用默认线程池（轻微）

**问题**：不指定 Executor 时使用 `ForkJoinPool.commonPool()`，可能导致资源争用。

```java
// 不推荐 - 使用默认线程池
CompletableFuture.supplyAsync(() -> query());

// 推荐 - 显式指定执行器
CompletableFuture.supplyAsync(() -> query(), businessExecutor);
```

---

## 二、最佳实践

### 1. 推荐使用 CFFU 库

CFFU (CompletableFuture Fu) 提供了更安全、更强大的异步编程能力：

```xml
<dependency>
    <groupId>io.foldright</groupId>
    <artifactId>cffu2</artifactId>
    <version>2.0.1</version>
</dependency>
```

**CFFU 优势**：
- `allResultsFailFastOf` - 快速失败的任务编排
- `allTupleFailFastOf` - 支持不同类型结果的聚合
- `cffuOrTimeout` - 安全的超时实现（避免 Delayer 线程问题）
- `fromSyncCall` - 统一同步/异步异常处理
- `mSupplyAllSuccessAsync` - 多种编排策略

### 2. 核心 API（7个方法解决95%问题）

```java
// 1. 创建（封装已知结果）
CompletableFuture.completedFuture(value);
CompletableFuture.failedFuture(ex);  // Java 9+

// 2. 数据转换（始终用 Async + 显式 Executor）
cf.thenApplyAsync(fn, executor);      // map
cf.thenComposeAsync(fn, executor);    // flatMap

// 3. 异常恢复
cf.exceptionallyAsync(fn, executor);          // 简单恢复
cf.exceptionallyComposeAsync(fn, executor);   // 异步恢复 (Java 12+)

// 4. 超时控制
cf.orTimeout(timeout, unit);

// 5. 获取结果
cf.join();                    // 阻塞获取，推荐
cf.state();                   // 非阻塞检查状态 (Java 19+)
cf.resultNow() / exceptionNow();  // 非阻塞获取 (Java 19+)
```

### 3. 任务编排策略选择

| 策略 | 说明 | 推荐方法 |
|------|------|----------|
| **allFastFail** | 任一失败立即返回异常 | `CfIterableUtils.allResultsFailFastOf()` |
| allSuccess | 失败返回默认值 | `mSupplyAllSuccessAsync()` |
| anySuccess | 任一成功立即返回 | `mSupplyAnySuccessAsync()` |
| mostSuccess | 带超时的 allSuccess | `mSupplyMostSuccessAsync()` |

**注意**：原生 `allOf` 实际上是 `allComplete`（包含异常完成），不是 fail-fast！

### 4. 安全发布

```java
// 方法返回 CompletionStage 而非 CompletableFuture
// 防止外部调用 complete/obtrudeValue 等方法篡改结果
public CompletionStage<Integer> getDataAsync() {
    return CompletableFuture.supplyAsync(() -> fetchData(), executor)
        .minimalCompletionStage();  // 返回只读视图
}
```

### 5. 异常处理模板

```java
CompletableFuture.supplyAsync(() -> {
    // 业务逻辑
    return businessLogic();
}, executor)
.orTimeout(3, TimeUnit.SECONDS)
.exceptionallyAsync(ex -> {
    log.error("Task failed", ex);
    return defaultValue;  // 或 throw new BusinessException(ex)
}, executor);
```

### 6. 设置超时时间

**所有异步任务必须设置超时时间**，防止无限阻塞。

```java
result.orTimeout(3, TimeUnit.SECONDS)
    .exceptionallyAsync(ex -> {
        if (ex instanceof TimeoutException) {
            return fallbackValue;
        }
        throw new CompletionException(ex);
    }, executor);
```

---

## 三、Code Review 检查清单

### 必查项（严重问题）

- [ ] 是否在 Stream/迭代中直接调用 `join()` 或 `get()`？
- [ ] 是否存在嵌套 Future + `join()` 导致的潜在死锁？
- [ ] 是否使用不带 Async 的方法（如 `thenApply`、`handle`）？
- [ ] 超时后回调是否可能阻塞 Delayer 线程？

### 建议项（中等问题）

- [ ] 是否显式指定了 Executor？
- [ ] 返回 CF 的方法是否包装了同步异常？
- [ ] 是否有异常处理/日志记录？
- [ ] 是否设置了超时时间？
- [ ] 使用 `allOf` 是否需要 fail-fast 语义？

### 优化项（轻微问题）

- [ ] 是否考虑使用 CFFU 库？
- [ ] 方法返回类型是否可以改为 `CompletionStage`？
- [ ] IO 密集型和 CPU 密集型任务是否使用不同线程池？

---

## 四、快速参考

### 避免的写法

```java
// 1. 不要在迭代中 join
cfs.stream().map(cf -> cf.join()).toList();

// 2. 不要嵌套 join
supplyAsync(() -> supplyAsync(...).join());

// 3. 不要使用无 Async 的方法
cf.thenApply(fn);  // 改用 cf.thenApplyAsync(fn, executor);

// 4. 不要忽略异常
cf.join();  // 改为 cf.exceptionally(ex -> {...}).join();

// 5. 不要使用默认线程池
supplyAsync(() -> ...);  // 改为 supplyAsync(() -> ..., executor);
```

### 推荐的写法

```java
// 1. 先收集再统一等待
CompletableFuture<?>[] cfs = ids.stream()
    .map(id -> supplyAsync(() -> query(id), executor))
    .toArray(CompletableFuture[]::new);
allOf(cfs).thenApplyAsync(__ -> processResults(cfs), executor).join();

// 2. 使用 thenComposeAsync 串联
cf1.thenComposeAsync(r1 -> cf2, executor)
   .thenApplyAsync(r2 -> process(r2), executor);

// 3. 完整的异常处理
supplyAsync(() -> logic(), executor)
    .orTimeout(3, SECONDS)
    .exceptionallyAsync(ex -> {
        log.error("Failed", ex);
        return fallback;
    }, executor);

// 4. 使用 CFFU 快速失败
CfIterableUtils.allResultsFailFastOf(cfs);
```

---

## 参考资料

- CFFU 项目：https://github.com/foldright/cffu
- 原生 allOf 不是 fail-fast，考虑使用 CFFU
- ListenableFuture 在取消传播方面比 CompletableFuture 更强
- 虚拟线程 + 结构化并发是未来趋势，可简化异步编程
