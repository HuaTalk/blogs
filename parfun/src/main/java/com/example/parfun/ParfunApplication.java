package com.example.parfun;

import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.*;

import static java.util.stream.Collectors.toList;
import static java.util.stream.IntStream.range;

@Slf4j
@SpringBootApplication
public class ParfunApplication {

    public static void main(String[] args) {
        readThroughDemo();
//        SpringApplication.run(ParfunApplication.class, args);
    }

    public static void readThroughDemo() {
//        AsyncLoadingCache<Integer, String> cache = Caffeine.newBuilder()
//                .maximumSize(1)
//                .<Integer, String>evictionListener((k, v, cause) ->
//                        log.info("Evicted key={} cause={}", k, cause))
//                .buildAsync(x -> {
//                    log.info("loading from redis");
//                    return Objects.toString(x);
//                });
//        String v1 = cache.get(1).join();
//        System.out.println("v1 = " + v1);
//        String v2 = cache.get(2).join();
//        System.out.println("v2 = " + v2);
//        Uninterruptibles.sleepUninterruptibly(1000, TimeUnit.MILLISECONDS);
//        record User(String name, int age) {}
//        Stream.of(new User("a", 1), new User("b", 1))
//                .map(Equivalence.equals().onResultOf(User::age)::wrap)
//                .distinct()
//                .map(Equivalence.Wrapper::get)
//                .collect(toList());
        Map<String, List<Integer>> map = new HashMap<>();
        map.put("a", new ArrayList<>(Arrays.asList(1, 2, 3)));
        map.put("b", new ArrayList<>(Arrays.asList(5, 6, 7)));
        map.put("c", range(0, 10).boxed().collect(toList()));
        map.put("d", Lists.newArrayList(2, 4, 6));
        map.entrySet().removeIf(e -> {
            List<Integer> list = e.getValue();
            list.removeIf(x -> x % 2 == 0);
            return list.isEmpty();
        });
        System.out.println("map = " + map);

//        Iterable<Iterable<Integer>> rev = Iterables.transform(map.values(), Lists::reverse);
//        var iter = Iterables.concat(rev).iterator();
//        while(iter.hasNext()) {
//            int x = iter.next();
//            if (x % 2 == 0) {
//                iter.remove();
//            }
//        }
//        System.out.println("map = " + map);

    }

}
