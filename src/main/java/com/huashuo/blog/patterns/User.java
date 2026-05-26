package com.huashuo.blog.patterns;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import org.immutables.value.Generated;
import org.immutables.value.Value;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

//@Builder(toBuilder = true)
//@Value
//@With
class User {
    String id;
    String name;
    Integer age;
}

@Value.Style(stagedBuilder = true)
@Value.Immutable
interface Person {
    String name();
    int age();
}

//record User(String id, String name, String email, List<String> friends) {
//    User {
//        name = nullToEmpty(name);
//        email = nullToEmpty(email);
//        friends = requireNonNullElse(friends, List.of());
//    }
//}
