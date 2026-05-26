package com.example.parfun;

import org.jooq.lambda.tuple.Tuple;
import org.jooq.lambda.tuple.Tuple2;

import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.maxBy;

public class JooqCollector {
    public static void main(String[] args) {
        Tuple2<Long, Optional<Integer>> r = Stream.of(1, 2, 3)
                .collect(Tuple.collectors(counting(), maxBy(Comparator.naturalOrder())));
        System.out.println("r = " + r);
    }
}
