---
title: "CompletableFuture 异常处理常见陷阱——非预期的同步异常"
date: 2024-10-24
url: https://juejin.cn/post/7561632570703495178
source: html2md
---

### 前言

在Java中，当使用`CompletableFuture`处理异步代码时，有效地管理错误对于确保应用程序的健壮性和可预测性至关重要。一个常见的陷阱是混合同步和异步错误，这可能导致未处理的异常和不一致的异常处理策略。

本文将介绍在Java中使用`CompletableFuture`处理异步代码异常的最佳实践，重点是防止同步错误泄漏到函数之外（副作用）。本文参考了 [Dart 异步处理相关文档](<https://link.juejin.cn?target=https%3A%2F%2Fdart.dev%2Flibraries%2Fasync%2Ffutures-error-handling> "https://dart.dev/libraries/async/futures-error-handling")。

## 理解同步与异步异常

在Java中，返回`CompletableFuture`的函数应理想地以异步方式封装异常。这种方法允许调用者使用`exceptionally()`等机制统一处理错误。然而，如果一个函数抛出同步异常，它可能会绕过`CompletableFuture`的异常处理能力，导致未处理的异常。

考虑以下示例：
    
    
    CompletableFuture<Integer> parseAndRead(Map<String, Object> data) {
        String filename = obtainFilename(data); // 可能抛出异常。
        File file = new File(filename);
        return CompletableFuture.supplyAsync(() -> {
            try {
                String contents = new String(Files.readAllBytes(file.toPath()));
                return parseFileData(contents); // 可能抛出异常。
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }
    

在这段代码中，`obtainFilename()`可能抛出同步异常，这不会被`exceptionally()`捕获，需要调用者为同步异常实现单独的异常处理逻辑，这并不理想。

## 解决方案：使用`CompletableFuture.supplyAsync()`

为了确保所有错误，无论是同步还是异步，都能统一处理，可以将函数体包装在`CompletableFuture.supplyAsync()`调用中。这种模式确保任何同步错误都被捕获并转换为`CompletableFuture`封装的异常，从而可以使用`exceptionally()`进行处理。

以下是修复后的示例：
    
    
    CompletableFuture<Integer> parseAndRead(Map<String, Object> data) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String filename = obtainFilename(data); // 可能抛出异常。
                File file = new File(filename);
                String contents = new String(Files.readAllBytes(file.toPath()));
                return parseFileData(contents); // 可能抛出异常。
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
    

通过这种方法，`obtainFilename()`抛出的任何异常都将被`exceptionally()`捕获和处理：
    
    
    public static void main(String[] args) {
        parseAndRead(data).exceptionally(e -> {
            System.out.println("Inside exceptionally");
            e.printStackTrace();
            return -1;
        });
    }
    ​
    // 程序输出：
    //   Inside exceptionally
    //   <obtainFilename抛出的错误的堆栈跟踪>
    

### 使用`CompletableFuture.supplyAsync()`的好处

  0. **统一错误处理** ：通过将函数体包装在`CompletableFuture.supplyAsync()`中，可以确保所有异常，无论是同步还是异步，都以相同的方式处理。这简化了调用者的异常处理逻辑。
  1. **增强对未捕获异常的抵抗力** ：这种方法使代码更健壮，防止意外的同步错误泄漏出函数。这在可能意外发生错误的复杂函数中特别有用。
  2. **简化代码维护** ：通过一致的异常处理策略，代码更易于维护和调试，因为不需要为不同类型的异常实现单独的逻辑。



## CFFU 提供的便利方法：同步结果封装

`CompleatableFutureUtils` 提供了同步结果封装，如果同步任务执行失败，异常也会封装在结果里。实现相对简单，代码如下：
    
    
    public static <T> CompletableFuture<T> fromSyncCall(Callable<? extends T> callable) {
        requireNonNull(callable, "callable is null");
        try {
            return completedFuture(callable.call());
        } catch (Throwable ex) {
            return failedFuture(ex);
        }
    }
    

`fromSyncCall` 方法不异步执行，只是为了包 `CompletableFuture`外的逻辑到`CompletableFuture`，以归一异常处理路径。

这个方法非常实用，可以统一处理结果和异常，无论是同步还是异步操作，都可以通过 `CompletableFuture` 的方法来处理。

## 总结

**多数情况下，当函数返回`CompleatbleFuture` 时，不要直接抛出异常，应该包装到返回结果中。**

在Java中处理异步代码时，有效地管理错误对于确保应用程序的健壮性至关重要。通过使用`CompletableFuture.supplyAsync()`，可以防止同步错误泄漏出函数，从而实现一致和简化的异常处理策略。这种方法不仅使代码更具健壮，而且更易于维护和理解。