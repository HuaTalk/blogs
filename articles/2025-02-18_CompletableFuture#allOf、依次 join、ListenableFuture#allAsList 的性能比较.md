---
title: "CompletableFuture#allOf、依次 join、ListenableFuture#allAsList 的性能比较"
date: 2025-02-18
url: https://juejin.cn/post/7472626857293676596
views: 708
likes: 6
collects: 1
source: html2md
---

## 引子

之前写过一篇[《使用 CompletableFuture 最常见的错误（附实战代码）》](<https://juejin.cn/post/7415912390950797362> "https://juejin.cn/post/7415912390950797362")，读者 [闭关修炼啊哈](<https://juejin.cn/user/266561468510616> "https://juejin.cn/user/266561468510616") 指出依次join的性能问题。结论在文章末尾。

Talk is cheap, show me your code! 参考读者 [闭关修炼啊哈](<https://juejin.cn/user/266561468510616> "https://juejin.cn/user/266561468510616")的实现，编写jmh基准测试代码。代码主要测试了三种实现，分别是CompletableFuture#allOf、依次 join、ListenableFuture#allAsList，任务为CPU密集型，设置不同大小的子任务，最终比较总的耗时。

## 代码与结果分析
    
    
    @BenchmarkMode(Mode.AverageTime)
    @Warmup(iterations = 3, time = 5)
    @Measurement(iterations = 5, time = 5)
    @State(value = Scope.Benchmark)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public class ConcurrentDemoBenchmark {
    
        private List<Student> studentList;
        private ListeningExecutorService executor;
        @Param({"5", "10", "30"})
        private int numOfStudents;
    
        @Setup(Level.Iteration)
        public void setup() {
            studentList = IntStream.range(0, numOfStudents)
                    .mapToObj(i -> new Student("学生" + i, i + 1))
                    .collect(toList());
            int processors = Runtime.getRuntime().availableProcessors();
            executor = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(processors + 1));
        }
    
        @TearDown(Level.Iteration)
        public void tearDown() {
            executor.shutdownNow();
            System.out.println("shutdown");
        }
    
        @Benchmark
        public void testStreamJoin(Blackhole blackhole) {
            List<Integer> result = streamJoin(studentList, executor);
            blackhole.consume(result);
        }
    
        @Benchmark
        public void testCfAllOf(Blackhole blackhole) {
            List<Integer> result = cfAllOf(studentList, executor);
            blackhole.consume(result);
        }
    
        @Benchmark
        public void testLfAllOf(Blackhole blackhole) {
            List<Integer> result = lfAllOf(studentList, executor);
            blackhole.consume(result);
        }
    
        // 方法1：futureAllOf
        private static List<Integer> cfAllOf(List<Student> studentList, ListeningExecutorService executor) {
            @SuppressWarnings("unchecked")
            CompletableFuture<Integer>[] cfs = studentList.stream()
                    .map(student -> CompletableFuture.supplyAsync(student::study, executor))
                    .toArray(CompletableFuture[]::new);
            return CompletableFuture.allOf(cfs)
                    .thenApply(__ -> Arrays.stream(cfs)
                            .map(CompletableFuture::join).collect(toList()))
                    .join();
        }
    
        // 方法2：streamJoin
        private static List<Integer> streamJoin(List<Student> studentList, ListeningExecutorService executor) {
            return studentList.stream()
                    .map(student -> CompletableFuture.supplyAsync(student::study, executor))
                    .collect(toList())
                    .stream()
                    .map(CompletableFuture::join)
                    .collect(toList());
        }
    
        // 方法3：LF
        private static List<Integer> lfAllOf(List<Student> studentList, ListeningExecutorService executor) {
            List<ListenableFuture<Integer>> futures = studentList.stream()
                    .map(student -> executor.submit(student::study))
                    .collect(toList());
            return Futures.getUnchecked(Futures.allAsList(futures));
        }
    
        public static class Student {
            private final String name;
            private final int sleep;
    
            public Student(String name, int sleep) {
                this.name = name;
                this.sleep = sleep;
            }
    
            public int study() {
    //            long startTime = System.currentTimeMillis();
                double result = 0;
                for (int i = 0; i < 1_000_000 * sleep; i++) {
                    result += Math.sqrt(result) + Math.sin(i);
                }
    //            System.out.println(name + " study " + (System.currentTimeMillis() - startTime));
                return (int) result;
            }
        }
    }
    

基准测试结果：
    
    
    Benchmark                               (numOfStudents)  Mode  Cnt     Score    Error  Units
    ConcurrentDemoBenchmark.testCfAllOf                   5  avgt   25   147.354 ±  1.696  ms/op
    ConcurrentDemoBenchmark.testCfAllOf                  10  avgt   25   394.283 ±  3.566  ms/op
    ConcurrentDemoBenchmark.testCfAllOf                  30  avgt   25  3391.347 ± 29.419  ms/op
    ConcurrentDemoBenchmark.testLfAllOf                   5  avgt   25   146.789 ±  0.685  ms/op
    ConcurrentDemoBenchmark.testLfAllOf                  10  avgt   25   394.108 ±  4.139  ms/op
    ConcurrentDemoBenchmark.testLfAllOf                  30  avgt   25  3398.775 ± 39.469  ms/op
    ConcurrentDemoBenchmark.testStreamJoin                5  avgt   25   146.795 ±  0.132  ms/op
    ConcurrentDemoBenchmark.testStreamJoin               10  avgt   25   393.965 ±  4.783  ms/op
    ConcurrentDemoBenchmark.testStreamJoin               30  avgt   25  3390.928 ± 26.038  ms/op
    

## 结论

  1. 三种实现性能差别不大，依次join性能更好一点。
  2. 选择哪种方法更多地取决于代码的可读性和具体的业务需求，而不是性能差异。
  3. 对于性能问题不要想当然，笔者之前错误地认为allOf实现依赖于回调，必然比多次同步等待性能要好，事实上，性能与依次join的性能差别不大，甚至差一点。
  4. 推荐使用fail-fast实现，其在出现错误时可以快速响应，拓展性更好。
  5. 还可以使用ExecutorService#invokeAll实现相同的逻辑。