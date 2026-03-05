---
title: "告别强制转换：使用设计模式实现 Guava ListenableFuture 与 TTL 优雅融合"
date: 2025-04-21
url: https://juejin.cn/post/7495005551308324927
views: 298
likes: 6
collects: 3
source: html2md
---

“为什么每次提交任务后都要手动强转？这代码太不优雅了！”

当你在异步任务中同时使用 Guava 的 ListenableFuture 和 TransmittableThreadLocal（TTL）时，是否也曾被类型转换的冗余代码困扰？

* * *

## 1\. 问题背景：异步编程的“接口断层”

在 Java 生态中，Guava 的 `ListenableFuture` 是异步编程的重要工具，它允许开发者通过回调机制处理任务结果。阿里开源的 TransmittableThreadLocal（TTL） 则解决了线程池场景下的上下文传递问题。二者的结合本应如虎添翼，但现实却存在一个尴尬的断层：
    
    
    // 使用 TtlExecutors 包装线程池
    ExecutorService executor = TtlExecutors.getTtlExecutorService(originalExecutor);
    
    // 提交任务后需手动强转为 ListenableFuture
    ListenableFuture<?> future = (ListenableFuture<?>) executor.submit(task);
    

**问题分析** ：

  1. TTL 作为上下文传递使用，应该放在所有包装器的最外边
  2. 类型不安全：强制转换可能引发 `ClassCastException`
  3. 代码冗余：每次提交任务都需重复强转
  4. 设计割裂：装饰器模式导致接口类型丢失



* * *

## 2\. 解决方案：接口兼容性设计

### 2.1 使用包装类原生支持 Guava 接口

现状：当前 TTL 不支持第三方类库 Guava，可能在 TTL3.x 进行支持。 目标：让 `TtlExecutors.getTtlExecutorService()` 直接返回 `ListeningExecutorService` 实例。  
实现关键：通过包装类保留原始接口类型。
    
    
    class Demo {
        public static ListeningExecutorService getTtlListeningExecutorService(ListeningExecutorService delegate) {
            return new ListeningExecutorServiceTtlWrapper(delegate, false);
        }
    
        @Value
        static class ListeningExecutorServiceTtlWrapper extends ForwardingListeningExecutorService implements TtlWrapper<ListeningExecutorService> {
            @NonNull
            ListeningExecutorService executor;
            boolean idempotent;
    
            // 避免耦合
            @Override
            protected ListeningExecutorService delegate() {
                return executor;
            }
    
            @Override
            public @NonNull ListeningExecutorService unwrap() {
                return executor;
            }
    
            @Override
            public <T> ListenableFuture<T> submit(Callable<T> task) {
                task = TtlCallable.get(task, false, idempotent);
                return super.submit(task);
            }
    
            // 略去其他
        }
    }
    

修改后可实现：

  * 类型安全：直接返回 `ListenableFuture`
  * 无缝迁移：原有 Guava 用户无需修改代码习惯



* * *

### 2.2 通用适配器：应对多层装饰场景

当执行器被其他装饰器（如日志、监控）包装时，接口类型可能被隐藏。此时需要一个智能适配器：
    
    
    public class ListenableHelper {
        /**
         * Adapts an ExecutorService to a {@link ListeningExecutorService}.
         * <p>This handles cases where the underlying ExecutorService can produce {@link ListenableFuture} instances
         * but does not directly implement the {@code ListeningExecutorService} interface. Such scenarios typically
         * occur when a {@code ListeningExecutorService} is wrapped by another decorator object (e.g., via the
         * decorator pattern), causing the original interface type to be obscured.
         */
        public static ListeningExecutorService adaptWrappedListeningExecutor(ExecutorService executorService) {
            if (executorService instanceof ListeningExecutorService) {
                return (ListeningExecutorService) executorService;
            }
    
            // optional check
            // if (executorService instanceof TtlWrapper) {
            //     TtlWrapper<?> ttlWrapper = (TtlWrapper<?>) executorService;
            //     Object inner = ttlWrapper.unwrap();
            //     if (inner instanceof ListeningExecutorService) {
            //         return new ListeningExecutorServiceAdapter(executorService);
            //     }
            // } else {
            //     throw new IllegalArgumentException("executorService must be a ListeningExecutorService in TtlWrapper");
            // }
            return new ListeningExecutorServiceAdapter(executorService);
        }
    }
    
    @AllArgsConstructor(access = AccessLevel.PACKAGE)
    class ListeningExecutorServiceAdapter extends ForwardingExecutorService implements ListeningExecutorService {
    
        private final ExecutorService delegate;
    
        @Override
        protected ExecutorService delegate() {
            return delegate;
        }
    
        @Override
        public <T extends @Nullable Object> ListenableFuture<T> submit(Callable<T> task) {
            Future<T> result = super.submit(task);
            if (!(result instanceof ListenableFuture<T>)) {
                throw new IllegalStateException("executor service must return ListenableFuture");
            }
            return (ListenableFuture<T>) result;
        }
    
        @Override
        public ListenableFuture<?> submit(Runnable task) {
            Future<?> result = super.submit(task);
            if (!(result instanceof ListenableFuture<?>)) {
                throw new IllegalStateException("executor service must return ListenableFuture");
            }
            return (ListenableFuture<?>) result;
        }
    
        @Override
        public <T extends @Nullable Object> ListenableFuture<T> submit(Runnable task, T result) {
            Future<T> r = super.submit(task, result);
            if (!(r instanceof ListenableFuture<T>)) {
                throw new IllegalStateException("executor service must return ListenableFuture");
            }
            return (ListenableFuture<T>) r;
        }
    }
    

当执行器被装饰器多层包装，并且底层实际支持 `ListenableFuture` 但接口类型丢失时，使用这个实现更通用。

* * *

## 3\. 修改后效果
    
    
    // 原始执行器（可能被多层装饰器包装） 
    ExecutorService wrappedExecutor = ExecutorService wrappedExecutor = TtlExecutors.getTtlExecutorService(guavaExecutor); 
    // 通过适配器恢复 ListeningExecutorService 接口 
    ListeningExecutorService adaptedExecutor = ListenableHelper.adapt(wrappedExecutor); 
    // 直接使用 ListenableFuture 接口 
    ListenableFuture<String> future = adaptedExecutor.submit(() -> { 
        // 业务逻辑 
        return "Hello TTL"; 
    });
    

* * *

## 4\. 知识点与总结

**Guava Forwarding 类**  
`ForwardingListeningExecutorService` 的运用成为解决+简化问题的关键。该抽象类通过委托机制，将装饰器模式的复杂度降至最低：开发者只需重写需要增强的方法（如 `submit`），其余方法自动委托给原始对象，同时实现了父类方法间的解耦。这种设计消除了传统装饰器模式中必须重写所有接口方法的负担，同时通过强制类型约束（`delegate()` 方法返回 `ListeningExecutorService`），确保包装后的对象始终符合接口规范。例如，在 `ListeningExecutorServiceTtlWrapper` 的实现中，仅需对任务提交方法进行 TTL 包装，其他方法如 `shutdown` 则自动继承默认实现，显著降低了代码维护成本。

**适配器模式的灵活运用**  
面对多层装饰器导致的接口类型丢失问题，我们引入 `ListenableHelper` 适配器。该组件通过运行时类型检查，将任意 `ExecutorService` 重新适配为 `ListeningExecutorService`。其核心逻辑在于验证底层执行器是否实际生成 `ListenableFuture` 实例，若不符合预期则抛出明确异常。这种设计既保证了灵活性（兼容任意层数的装饰器包装），又通过防御性编程规避了潜在的类型错误风险。典型应用场景中，即使执行器被日志、监控等装饰器多层包裹，用户仍可通过一行 `adapt()` 调用恢复接口能力。