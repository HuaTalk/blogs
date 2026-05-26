package com.example.parfun;

import com.google.common.reflect.TypeToken;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class A<T> {
    public final TypeToken<T> type = new TypeToken<>(getClass()) {};
}
class B<T> extends A<Collection<? extends String>> {
    public static void main(String[] args) {
        B<String> b = new B<>();
        Map<TypeToken<?>, Object> map = new HashMap<>();
        map.put(b.type, "Hello World!");
        map.put(new B<>() {
        }.type, "Hello World2!");
        System.out.println("map = " + map);
        var t =  b.type.getRawType();
        System.out.println("t = " + t);
        TypeToken<Collection<? extends String>>.TypeSet types = b.type.getTypes();
        System.out.println("b.type.getTypes() = " + types);
        TypeToken<? extends Collection<? extends String>> subtype = b.type.getSubtype(List.class);
        System.out.println("b.type.getSubtype(List.class) = " + subtype);
        System.out.println("types.rawTypes() = " + types.rawTypes());

        TypeToken<List<String>> t1 = new TypeToken<>() {
        };
        TypeToken<List<Integer>> t2 = new TypeToken<>() {
        };
        boolean equals = t1.equals(t2);
        System.out.println("equals = " + equals);
    }
}
