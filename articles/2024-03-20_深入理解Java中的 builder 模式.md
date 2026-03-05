---
title: "深入理解Java中的 builder 模式"
date: 2024-03-20
url: https://juejin.cn/post/7348245676890587186
views: 1290
likes: 3
collects: 3
source: html2md
---

# Java中的BuilderPattern与变形有哪些？ — Builder模式大总结

建造者模式是一种创建复杂对象的对象创建模式，其有多种变形，有时候我们常常忽略建造者模式的某些要素（比如可变与不变、可选参数、默认参数、传参顺序、继承等）。这篇文章中笔者尽量将建造者模式中的概念一网打尽，为今后的代码设计、重构提供参考。

* * *

## 初见

先简单罗列一些常见的使用Builder模式的代码：
    
    
    // 创建JavaBean对象
    var user = User.builder().id("xxx").name("HuaShuo").roles(List.of()).build();
    
    // 复杂对象的配置/创建，用以实现序列化和反序列化
    var objectMapper = JsonMapper.builder()
                    .disable(MapperFeature.DEFAULT_VIEW_INCLUSION)
                    .enable(SerializationFeature.INDENT_OUTPUT)
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
                    .serializationInclusion(JsonInclude.Include.NON_NULL)
                    .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                    .addModules(new Jdk8Module(), new JavaTimeModule(), new ParameterNamesModule())
                    .build();
    
    // 一个对象同时具有可变和不可变两种形式:
    var sb = new StringBuilder();
    sb.append("123").reverse();
    // ...
    var msg = sb.toString();
    
    // 创建集合
    var usersBuilder = ImmutableList.<User>builder();
    if (validate(user)) {
    	usersBuilder.add(user);
    }
    //...
    var validUsers = usersBuilder.build();
    
    // 创建不可变对象，其中tags创建为不可变集合，with**表示创建新对象
    var item = new ItemBuilder()
        .setName("Nameless")
        .addTags("important", "relevant")
        .setDescription("Description provided")
        .build();
    var item2 = item.withName("name2");
    

## 目的与优点

### 1\. 创建复杂对象，避免错误传参

复杂对象常常包含多种属性，对于具有类型系统的语言，虽然可以避免类型不匹配，但是对于代码中基本类型表示不同含义的情况无法区分。
    
    
    // implA
    var user = new User("HuaShuo", "xxx");
    var user2 = new User(name, id);
    
    // implB
    var user = User.builder()
    		.id("xxx")
    		.name("HuaShuo")
    		.build();
    var user2 = User.builder()
    		.id(id)
    		.name(name)
    		.build();
    
    // implC
    var user = new User(new Name("Huashuo"), new Id("xxx"));
    

如上代码中第一种实现出现了错误传参的问题，编译器无法检查出来。在大型项目中，特别是历史代码、大方法中存在多种变量，同时有时候这些代码的含义在不断变化时，传参错误时有发生。当需要创建对象时，这种实现需要特别注意构造器传参顺序，如果使用了lombok，更不利于正确传参。

第二种实现需要显示传参，减少了参数错传的可能性，可读性好。代码遵循命名规范时，错误容易发现。

第三种实现指定的实现类型，不会出现错误传参的问题，可以实现编译器静态检查，便于对于某一熟悉进行单独拓展（重构）。不过Java目前不支持值类型，性能一般同时开发繁琐。若使用，应该确保项目代码遵循统一规范，不失为一种方案。

* * *

### 2\. 有利于拓展与重构

复杂对象常常需要新增、删除、修改属性，不同属性间关系可能发生变更。
    
    
    class AddUserRequest {
    		// ...
    		// 新增用户请求来源
    		String origin;
    }
    
    // implA
    // var user = new User(id, name);
    var user = new User(id, name, origin);
    
    // implB
    var user = User.builder()
    		.id(id)
    		.name(name)
    //		.origin(origin)
    		.build();	
    

以如上代码为例，User等相关对象新增属性: 账号请求来源，比如来源为手机号用户、邮箱用户等。

case1: 账号来源必填。此时需要修改所有对象的创建过程，使用构造器方法需要在对应参数位置修改（依据位置传参），而创建者模式只需要新增对应属性传参即可（依据名称传参）。

注意：这里有个散弹效应问题，所有涉及builder对象的地方都需要指定来源，不传的话编译器不会报错，简单的解决方法是使用lombok下@NonNull进行fail-fast处理，回归测试/单元测试时有问题会暴露，还有一种方法是构造器的使用强制参数实现，后文会详细讨论。

case2: 账号来源可以为空，指定默认值。此时需要修改最终的创建结果，实现如下：使用建造者模式可以通过设置创建建造者模式的初始值实现，或者在最终创建时实现参数赋值，使用Lombok仅需新增字段及Default注解。使用构造器方法需要将默认值在创建时赋值。
    
    
    User(String id, String name) {
    		// 略去其他实现
        User(String id, String name, String origin) {
            if (origin == null) {
    						this.origin = DEFAULT_ORIGIN;
    				} else {
    						this.origin = origin;
    				}
    				// 略去其他赋值
        }
    
    		User(String id, String name) {
    				this(id, name, null);
    		}
    }
    
    // lombok 辅助实现，可指定默认值
    @Builder
    @Value
    class User {
        String id;
        String name;
        @Builder.Default
        String originType = "email";
    }
    
    // Lombok具体实现
    public static class UserBuilder {
        private String id;
        private String name;
        private boolean originType$set;
        private String originType$value;
    
        UserBuilder() {
        }
    
        public UserBuilder id(String id) {
            this.id = id;
            return this;
        }
    
        public UserBuilder name(String name) {
            this.name = name;
            return this;
        }
    
        public UserBuilder originType(String originType) {
            this.originType$value = originType;
            this.originType$set = true;
            return this;
        }
    
        public User build() {
            String originType$value = this.originType$value;
            if (!this.originType$set) {
                originType$value = User.$default$originType();
            }
    
            return new User(this.id, this.name, originType$value);
        }
    }
    

两种实现的拓展性分析：

  1. 使用构造器方法需要重写构造器方法，会存在多个构造器方法，有时可能会有冲突，比如新增可为空的参数（电话: String)，此时构造器方法无法重载，需要使用静态工厂方法实现；同时原有的每一个构造器都要处理新增参数的情况，数量再次乘以2，最终难以维护。
  2. 使用建造者默认需要新增属性传参方法，修改build方法。每次增加参数保证了builder对象同时以o(1)复杂度新增字段，同时保证了目标对象存在唯一一份构造器。



综合以上两个case, 在对象拓展和重构方面，使用builder模式均优于使用构造器。以上例子中构造器方法都是有参构造器，还有一种实现是使用无参构造器，也就是常见的JavaBean创建方式，笔者特意忽略了这种实现，因为这是一种常见的反模式，其造成了散弹效应，将代码正确性的责任都落到开发者肩上（简单类比动态语言和静态语言，静态语言具有类型系统，可以避免很多问题），每一处对象的创建都需要开发者保证某些属性为必填或默认值。心理学上有个专业术语叫“过度自信”，我们经常高估自己判断与选择，对未来保持过分乐观。赌场里的赌徒认为自己下注的筹码更有机会赢，程序员们编译部署代码时认为自己写的代码是bugfree的。实际上，程序中的错误经常发生，且看某些大厂服务又崩了，某些基础组件又出现安全漏洞了。我们需要对于错误情况做好预案，提高代码的可维护性和韧性。回到Builder模式的主题上来，如同空指针异常一样，这样的bug总是不期而遇，而实际上我们可以通过简单的实现减少其发生的概率。

> Afterthought: 这个例子实际上不太好，origin使用枚举实现，可以避免歧义，改成用户昵称更为合适。

* * *

### 3\. 解耦对象的创建与使用（创建型模式的共同优点）

很容易让人联想到Spring容器基于的设计思想：将对象的创建与销毁权交付给容器，使用对象时从容器中获取，那么Spring中可能就提供了某种创建对象的形式，这种形式和建造者模式的思想密不可分，后文再作详细分析。

对于可变的JavaBean来说，后续可以继续修改某些属性，比如有时需要对数据库中查出的数据进行加敏脱密的情况。

对于不可变对象来说，其可以作为数据载体，在不同线程、消息队列中传播，参与基于数据的计算，同时其便于编写纯函数，实现计算与数据的解耦。

### 4\. 创建流程控制

可以实现复杂的创建流程控制，比如参数冲突检查、默认参数、传参顺序、嵌套、错误提前返回（卫模式）、传参次数控制（覆盖、新增、清空）、复用等，后文详细分析。

Java8中的stream流式编程实现可以看成一种特殊的建造者模式（forEach等特殊情况除外），流的创建、中间操作、最终操作分别对应builder对象的创建、设置属性或参数、最终build操作。流式编程的基本思想是函数式操作，两个有状态操作（流的创建和最终操作）+若干函数变换组合。如果从建造者模式的角度来看，每次的参数设置对应了函数操作（严格来说，应该为纯函数），此时函数也是数据，终端操作对于最终对象的创建，结果常常为集合对象、reduce计算结果等。一次流式操作可以改写为纯函数，入参为流的初始状态，返回结果为流的最终计算结果，函数体为实现若干函数变换。最后，我们可以将建造者模式的优点套用到流式计算上：

Constructor| BuilderPattern| LoopOperation| Stream  
---|---|---|---  
根据位置传递参数| 创建复杂对象| 计算耦合在循环内部和外部（如修改result)| 组合复杂计算  
无法区分不同含义的原始类型| 避免参数错传| 有状态计算，耦合，容易出错，错误不易发现| 无状态，使用封装好的中间操作（map, filter…), 避免遍历代码中计算的耦合  
需要重写，最差o(n^2)| 拓展与重构| 耦合，需要增删临时变量| 简单，仅需增删排序中间操作  
  
## 最简单的Builder需要什么信息？

可以从builder对象的最终操作确定builder需要什么信息：构造方法。这里的构造方法不仅仅指构造器方法，还可以是静态工厂方法，只要其能返回创建的对象即可。构造方法包含参数名及对应的参数类型，返回对象类型，构造方法名字可以决定builder的相关名称。

常见的编译期代码生成类库，如Lombok，AutoValue, Immutables，均支持根据简单的构造器或者方法生成 Builder 类。

以Jackson为例：对于序列化后的对象，其仅包含原始类型信息，不包含其他类型信息，ObjectMapper需要指定类型，可以将JSON转换为具体的类型对象，若不指定或者指定为Object，类型为LinkedHashMap，等同于JSON对象。以下为Lombok结合Jackson的官方示例：
    
    
    @Value @Builder
    @JsonDeserialize(builder = JacksonExample.JacksonExampleBuilder.class)
    public class JacksonExample {
    	@Singular(nullBehavior = NullCollectionBehavior.IGNORE) private List<Foo> foos;
    	
    	@JsonPOJOBuilder(withPrefix = "")
    	public static class JacksonExampleBuilder implements JacksonExampleBuilderMeta {
    	}
    	
    	private interface JacksonExampleBuilderMeta {
    		@JsonDeserialize(contentAs = FooImpl.class) JacksonExampleBuilder foos(List<? extends Foo> foos)
    	}
    }
    

简单总结一下，序列化时无需指定类型，或者可以认为类型丢失，反序列化时需要指定类型。对象创建如果支持Builder模式，则序列化时尽量使用。

## 特性与变形

### 1\. GoF 版本

最初的设计模式版本下Builder模式中包含抽象的建造者，Java中表示为接口类型的builder，存在多个子类实现。

不过在实际应用中大多数情况下并不需要这一层抽象，builder模式的使用者仅需要获取获得builder对象的入口即可，如直接调用builder方法，new XxxBuilder()对象等。

以下是builder模式的UML逻辑图：

![Builder_UML_class_diagram.svg](https://p9-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/ad13e8ac409740fc90169e9660485d18~tplv-k3u1fbpfcp-image.image#?w=1400&h=480&s=3672&e=svg&a=1&b=000000)

实际上，多数情况下，更简单的做法是：Builder类删掉，直接使用builder对象。

* * *

之后将介绍几种builder模式的特性，会对比3中代码生成类库。

### 2\. 默认值

默认值可以通过修改build的获取入口或build方法实现。上文中分析builder模式的重构优势时已分析，以下仅列举一下类库的代码编写方式：
    
    
    // 1. record， 可以实现默认值，空指针处理，formalized等处理
    @Builder
    record User(String id, String name, String email, List<String> friends) {
        User {
            name = nullToEmpty(name);
            email = nullToEmpty(email);
            friends = requireNonNullElse(friends, List.of());
        }
    }
    
    // 2. lombok @Default注解
    @Builder
    @Value
    class User {
        String id;
        String name;
        @Builder.Default
        String originType = "email";
    }
    
    // 3. AutoValue 重写builder方法实现
    @AutoValue
    abstract class Animal {
      abstract String name();
      abstract int numberOfLegs();
    
      static Builder builder() {
        return new AutoValue_Animal.Builder()
            .setNumberOfLegs(4);
      }
    
      @AutoValue.Builder
      abstract static class Builder {
        abstract Builder setName(String value);
        abstract Builder setNumberOfLegs(int value);
        abstract Animal build();
      }
    }
    
    // 4. Immutables 类库支持
    @Value.Immutable
    public abstract class PlayerInfo {
    
      @Value.Parameter
      public abstract long id();
    
      @Value.Default
      public String name() {
        return "Anonymous_" + id();
      }
    
      @Value.Default
      public int gamesPlayed() {
        return 0;
      }
    }
    ...
    
    PlayerInfo veteran = ImmutablePlayerInfo.builder()
        .id(1)
        .name("Fiddler")
        .gamesPlayed(99)
        .build();
    
    PlayerInfo anonymous44 = ImmutablePlayerInfo.of(44);
    
    String name = anonymous44.name(); // Anonymous_44
    

如果可以的话，使用record是最简单有效的。其他的实现都有一定的学习成本。

### 3\. 参数检查与正则化

常见的参数检查为空指针检查：

  1. Java语言和lombok中通过@Builder创建类都不限制空指针。
  2. AutoValue、Immutables提供空指针检查，默认非空。



正则化（Nomalization）指的是将不符合条件的参数进行修改。

实现时重写build方法或者AllArgsConstructor即可。

以下是Immutables的实现，使用了@Value.Check注解，其拦截了最终对象创建流程，但是这个类库的学习成本有点大。
    
    
    @Value.Immutable
    public interface Normalized {
      int value();
    
      @Value.Check
      default Normalized normalize() {
        if (value() == Integer.MIN_VALUE) {
          return ImmutableNormalized.builder()
              .value(0)
              .build();
        }
        if (value() < 0) {
          return ImmutableNormalized.builder()
              .value(-value())
              .build();
        }
        return this;
      }
    }
    
    int shouldBePositive2 = ImmutableNormalized.builder()
        .value(-2)
        .build()
        .value()
    

### 4\. Copiable

复制指的是从被创建对象拷贝属性到builder对象，如果builder对象只能使用一次，还包括从builder对象自己的复制。以下是一些类库的实现：
    
    
    // 1. lombok 提供toBuilder实现
    @Builder(toBuilder = true)
    @Value
    class User {
        String id;
        String name;
        Integer age;
    }
    
    class User {
    		// 略去其他,新增了toBuilder方法
        public UserBuilder toBuilder() {
            return (new UserBuilder()).id(this.id).name(this.name).age(this.age);
        }
    }
    
    // 2. Immutables提供from方法
    ImmutableValue.builder()
        .from(otherValue) // merges attribute value into builder
        .addBuz("b")
        .build();
    

此外，对于不可变对象属性的修改常常使用叫做 Wither 的特性:
    
    
    user = user.withAge(user.age() + 1);
    

### 5\. 嵌套

嵌套创建对象指的是构建对象的某些属性本身的构建也是builder模式，其包含在最终对象创建的链式调用中。
    
    
    var item = new ItemBuilder()
        .setName("Nameless")
        .addTags("important", "relevant")
        .setDescription("Description provided")
        .build();
    

如上代码中的addTags可以多次调用，tags集合进行多次新增。

Lombok提供了@Singular支持集合对象的多次新增、清空操作。AutoValue需要自己编写相关代码。Immutables支持分步构建模式，同时还支持其他的类型的嵌套。
    
    
    @Value.Immutable
    @Value.Style(deepImmutablesDetection = true, depluralize = true)
    public interface Line {
      List<Point> points();
    }
    
    @Value.Immutable
    @Value.Style(allParameters = true)
    public interface Point {
      int x();
      int y();
    }
    
    ImmutableLine line = ImmutableLine.builder()
      .addPoint(1, 2) // implicit addPoint(ImmutablePoint.of(1, 2))
      .addPoint(4, 5)
      .build();
    }
    

### 6\. 流程控制—StrictBuilder

对于builder需要创建的对象来说，严格限制每个属性只赋值一次。一般在实际代码中属性都是赋值一次的，如果出现多次赋值，则意味着可能有bug。错误调用和简单实现代码如下：
    
    
    // 同一属性多次赋值
    var user = User.builder().id(id).name(name).name("HuaShuo").build();
    
    // 每次调用检查是否已经赋值
    class UserBuilder {
    		boolean nameSetted;
    		String name;
    		UserBuilder(String name) {
    				if (nameSetted) throw new IllegalStateException("Strict user builder: name allready set");
    				this.name = Objects.requireNonNull(name, "name");
    				return this;
    		}
    		
    		User build() {
    				if (!allProperiesSet()) throw new IllegalStateException("参数不足，无法创建 user 对象");
    				// 略
    		}
    		// 略去无关代码
    }
    

### 7\. 流程控制—StepBuilder

又叫StagedBuilder，指的是builder对象执行严格的创建步骤。比如user对象的创建，必须先传id参数，在传name参数，最后执行build。这样做的好处是通过静态代码检查防止参数多传、错传（类型不匹配）或漏传，缺点是代码量较大，对性能有少量影响。

具体该如何实现呢？你可能想到了对于每一步创建相应的类，限制每次参数传递返回的对象类型。不过这样做显然代码量巨大，同时新增属性时，代码改动也很多。更好的方式是使用唯一实现类xxxBuilder，其实现每步指定的接口方法，如以下代码所示：
    
    
    final class UserBuilder implements NameBuildStage, AgeBuildStage, BuildFinal {
    		// 略
    }
    
    interface NameBuildStage {
        AgeBuildStage name(String name);
    }
    
    interface AgeBuildStage {
        BuildFinal age(int age);
    }
    
    interface BuildFinal {
        ImmutablePerson build();
    }
    

### 8\. 继承

lombok支持了继承链上对象通过builder进行创建，使用@SupperBuilder注解即可实现。其基本思路是添加自限定泛型参数。具体分析详见我之前写的一篇文章—《**【Final】深入理解Java泛型、协变逆变、泛型通配符、自限定》**末尾。

举一个常见的继承实现案例，很多公司都要求持久层对象或数据记录支持逻辑删除，使用自增主键，记录修改信息。代码如下：
    
    
    @AllArgsConstructor
    @NoArgsConstructor
    @SuperBuilder(toBuilder = true)
    @Data
    class BasePO {
        Long id;
        Long deletedAt;
        Long createdAt;
        String creator;
        Long updatedAt;
        String operator;
    }
    
    @EqualsAndHashCode(callSuper = true)
    @AllArgsConstructor
    @NoArgsConstructor
    @SuperBuilder(toBuilder = true)
    @Data
    class UserPO extends BasePO{
        String name;
        Integer age;
    }
    

如果公司限制不能使用lombok.experimental（官方解释这个功能还处于实验期的主要原因是代码过于复杂，估计以后也不可能作为正式功能），可以使用delombok功能生成具体代码实现。

先看父类builder实现：
    
    
    class BasePO {
        Long id;
        Long deletedAt;
        Long createdAt;
        String creator;
        Long updatedAt;
        String operator;
    
        public BasePO(Long id, Long deletedAt, Long createdAt, String creator, Long updatedAt, String operator) {
            this.id = id;
            this.deletedAt = deletedAt;
            this.createdAt = createdAt;
            this.creator = creator;
            this.updatedAt = updatedAt;
            this.operator = operator;
        }
    
        public BasePO() {
        }
    
        protected BasePO(BasePOBuilder<?, ?> b) {
            this.id = b.id;
            this.deletedAt = b.deletedAt;
            this.createdAt = b.createdAt;
            this.creator = b.creator;
            this.updatedAt = b.updatedAt;
            this.operator = b.operator;
        }
    
        public static BasePOBuilder<?, ?> builder() {
            return new BasePOBuilderImpl();
        }
        
        // ignore setters getters
    
        public BasePOBuilder<?, ?> toBuilder() {
            return new BasePOBuilderImpl().$fillValuesFrom(this);
        }
    
        public static abstract class BasePOBuilder<C extends BasePO, B extends BasePOBuilder<C, B>> {
            private Long id;
            private Long deletedAt;
            private Long createdAt;
            private String creator;
            private Long updatedAt;
            private String operator;
    
            private static void $fillValuesFromInstanceIntoBuilder(BasePO instance, BasePOBuilder<?, ?> b) {
                b.id(instance.id);
                b.deletedAt(instance.deletedAt);
                b.createdAt(instance.createdAt);
                b.creator(instance.creator);
                b.updatedAt(instance.updatedAt);
                b.operator(instance.operator);
            }
    
            public B id(Long id) {
                this.id = id;
                return self();
            }
    
            public B deletedAt(Long deletedAt) {
                this.deletedAt = deletedAt;
                return self();
            }
    
            public B createdAt(Long createdAt) {
                this.createdAt = createdAt;
                return self();
            }
    
            public B creator(String creator) {
                this.creator = creator;
                return self();
            }
    
            public B updatedAt(Long updatedAt) {
                this.updatedAt = updatedAt;
                return self();
            }
    
            public B operator(String operator) {
                this.operator = operator;
                return self();
            }
    
            protected B $fillValuesFrom(C instance) {
                BasePOBuilder.$fillValuesFromInstanceIntoBuilder(instance, this);
                return self();
            }
    
            protected abstract B self();
    
            public abstract C build();
    
            public String toString() {
                return "BasePO.BasePOBuilder(id=" + this.id + ", deletedAt=" + this.deletedAt + ", createdAt=" + this.createdAt + ", creator=" + this.creator + ", updatedAt=" + this.updatedAt + ", operator=" + this.operator + ")";
            }
        }
    
        private static final class BasePOBuilderImpl extends BasePOBuilder<BasePO, BasePOBuilderImpl> {
            private BasePOBuilderImpl() {
            }
    
            protected BasePOBuilderImpl self() {
                return this;
            }
    
            public BasePO build() {
                return new BasePO(this);
            }
        }
    }
    

由于builder对象的返回类型为继承链上对应的类型，BasePoBuilder提供了供子类继承的self方法，build方法。在继承链上的某个builder类实现需要同时包括提供继承功能的类和自己的实现。UserPO的实现如下：
    
    
    class UserPO extends BasePO {
        String name;
        Integer age;
    
        public UserPO(String name, Integer age) {
            this.name = name;
            this.age = age;
        }
    
        public UserPO() {
        }
    
        protected UserPO(UserPOBuilder<?, ?> b) {
            super(b);
            this.name = b.name;
            this.age = b.age;
        }
    
        public static UserPOBuilder<?, ?> builder() {
            return new UserPOBuilderImpl();
        }
    
        public UserPOBuilder<?, ?> toBuilder() {
            return new UserPOBuilderImpl().$fillValuesFrom(this);
        }
    
        public static abstract class UserPOBuilder<C extends UserPO, B extends UserPOBuilder<C, B>> extends BasePOBuilder<C, B> {
            private String name;
            private Integer age;
    
            private static void $fillValuesFromInstanceIntoBuilder(UserPO instance, UserPOBuilder<?, ?> b) {
                b.name(instance.name);
                b.age(instance.age);
            }
    
            public B name(String name) {
                this.name = name;
                return self();
            }
    
            public B age(Integer age) {
                this.age = age;
                return self();
            }
    
            protected B $fillValuesFrom(C instance) {
                super.$fillValuesFrom(instance);
                UserPOBuilder.$fillValuesFromInstanceIntoBuilder(instance, this);
                return self();
            }
    
            protected abstract B self();
    
            public abstract C build();
    
            public String toString() {
                return "UserPO.UserPOBuilder(super=" + super.toString() + ", name=" + this.name + ", age=" + this.age + ")";
            }
        }
    
        private static final class UserPOBuilderImpl extends UserPOBuilder<UserPO, UserPOBuilderImpl> {
            private UserPOBuilderImpl() {
            }
    
            protected UserPOBuilderImpl self() {
                return this;
            }
    
            public UserPO build() {
                return new UserPO(this);
            }
        }
    }
    

可以看出，其结构和父类类似。

## Java语言缺失的特性— Named Parameters

Java中方法调用根据的是类型和排序匹配参数，参数的顺序必须与方法签名保持一致。 如果Java支持根据名称匹配参数，大部分builder模式不需要存在，因为builder模式的主要目的就是为了防止参数错传。如果没有根据名称匹配参数特性是Java的缺陷的话，那么builder模式可以说就是为了弥补这种缺陷而存在的。

一般支持Named Parameter特性后，还会支持方法参数设置默认值。

其他语言如Kotlin, PHP, Python都支持参数名称匹配，Rust不支持。
    
    
    // Python 函数调用
    window.NewControl(title="Title",
                         xPosition=20,
                         yPosition=50,
                         width=100,
                         height=50,
                         drawingNow=True)
    
    
    
    // Kotlin 
    // 函数定义与调用
    fun foo(
        bar: Int = 0,
        baz: Int,
    ) { /*...*/ }
    
    foo(baz = 1) // The default value bar = 0 is used
    
    // 类定义与实例化对象
    data class Computer(var speed: Float = 0F,
                        var screenSize: Float = 0F,
                        var hardDisk: HeadDisk = HeadDisk())
    
    val computer = Computer()
    

一个好消息是，JEP468(Preview)提供了record wither 特性支持，with作用域内支持 Named Parameters特性。具体可参考官方文档，期待在Java25中可以使用。
    
    
    Point nextLoc = oldLoc with {
        x *= 2;
        y *= 2;
        z *= 2;
    };
    
    record Complex(double re, double im) {
        Complex conjugate() { return this with { im = -im; }; }
        Complex realOnly()  { return this with { im = 0; }; }
        Complex imOnly()    { return this with { re = 0; }; }
    }
    

## Spring是如何支持builder模式的？

我们知道Spring管理的对象叫做bean，在旧时代（XML配置时期），还存在一类`FactoryBean`, 这种bean支持复杂对象的配置同时支持bean生命周期管理，比如支持创建对象前检查（使用`@PostConstruct`或实现`InitializingBean` 接口)、支持默认配置。通过xml配置可以实现单例或原型bean，bean的创建支持延迟或立即创建。FactoryBean的缺点是无法和注解实现有效结合，这反而是个好事，如果你使用的注解驱动开发的话，就不要使用FactoryBean，避免了不同模式的混杂，同时避免出现问题后排查的困难。
    
    
    public interface FactoryBean<T> {
      T getObject() throws Exception;
      Class<T> getObjectType();
      boolean isSingleton();
    }
    

由于是xml配置，同时Spring很多时候使用的JavaBean形式的配置测量（多次调用setter），FactoryBean是旧时代最接近Builder模式的实现了(更像工厂模式）。

Spring中依赖注入有多种形式，构造器注入，setter注入，field反射注入（官方不推荐）等。在注解驱动开发过程中，我们可以直接使用builder创建对象了，builder需要的参数可以通过方法参数实现。在支持自动配置后，builder可以支持多次配置，还可以有默认配置，灵活性大大提高。

以下以ObjectMapper为例说明：
    
    
    // 旧时代的ObjectMapperFatoryBean
    public class Jackson2ObjectMapperFactoryBean implements FactoryBean<ObjectMapper>, BeanClassLoaderAware,
    		ApplicationContextAware, InitializingBean {
    
    	private final Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
    
    	@Nullable
    	private ObjectMapper objectMapper;
    
    	public void setObjectMapper(ObjectMapper objectMapper) {
    		this.objectMapper = objectMapper;
    	}
    
    	public void setCreateXmlMapper(boolean createXmlMapper) {
    		this.builder.createXmlMapper(createXmlMapper);
    	}
    
    	public void setFactory(JsonFactory factory) {
    		this.builder.factory(factory);
    	}
    
    	public void setDateFormat(DateFormat dateFormat) {
    		this.builder.dateFormat(dateFormat);
    	}
    
    	public void setSimpleDateFormat(String format) {
    		this.builder.simpleDateFormat(format);
    	}
    
    	public void setLocale(Locale locale) {
    		this.builder.locale(locale);
    	}
    
    	public void setTimeZone(TimeZone timeZone) {
    		this.builder.timeZone(timeZone);
    	}
    
    	public void setAnnotationIntrospector(AnnotationIntrospector annotationIntrospector) {
    		this.builder.annotationIntrospector(annotationIntrospector);
    	}
    	
    	@Override
    	public void afterPropertiesSet() {
    		if (this.objectMapper != null) {
    			this.builder.configure(this.objectMapper);
    		}
    		else {
    			this.objectMapper = this.builder.build();
    		}
    	}
    	// 略去其他	
    }
    
    
    
      // 配置
      <bean class="org.springframework.http.converter.json.Jackson2ObjectMapperFactoryBean">
        <property name="modulesToInstall" value="myapp.jackson.MySampleModule,myapp.jackson.MyOtherModule"/>
      </bean>
    

从代码中可以看出，旧时代的FactoryBean支持InitializingBean，xxxAware等接口，可以进行复杂配置，不过配置比较繁琐。根据其文档，其默认配置如下：

  * MapperFeature.DEFAULT_VIEW_INCLUSION is disabled
  * DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES is disabled
  * jackson-datatype-jdk7 : support for Java 7 types like java.nio.file.Path
  * jackson-datatype-jdk8 : support for other Java 8 types like java.util.Optional
  * jackson-datatype-jsr310 : support for Java 8 Date & Time API types
  * jackson-module-kotlin : support for Kotlin classes and data classes



再看新的Spring提供的ObjectMapperBuilder类：
    
    
    public class Jackson2ObjectMapperBuilder {
    	// 略去其他
    
    	/**
    	 * Build a new {@link ObjectMapper} instance.
    	 * <p>Each build operation produces an independent {@link ObjectMapper} instance.
    	 * The builder's settings can get modified, with a subsequent build operation
    	 * then producing a new {@link ObjectMapper} based on the most recent settings.
    	 * @return the newly built ObjectMapper
    	 */
    	@SuppressWarnings("unchecked")
    	public <T extends ObjectMapper> T build() {
    		ObjectMapper mapper;
    		if (this.createXmlMapper) {
    			mapper = (this.defaultUseWrapper != null ?
    					new XmlObjectMapperInitializer().create(this.defaultUseWrapper, this.factory) :
    					new XmlObjectMapperInitializer().create(this.factory));
    		}
    		else {
    			mapper = (this.factory != null ? new ObjectMapper(this.factory) : new ObjectMapper());
    		}
    		configure(mapper);
    		return (T) mapper;
    	}
    
    	/**
    	 * Configure an existing {@link ObjectMapper} instance with this builder's
    	 * settings. This can be applied to any number of {@code ObjectMappers}.
    	 * @param objectMapper the ObjectMapper to configure
    	 */
    	public void configure(ObjectMapper objectMapper) {
    		Assert.notNull(objectMapper, "ObjectMapper must not be null");
    
    		MultiValueMap<Object, Module> modulesToRegister = new LinkedMultiValueMap<>();
    		if (this.findModulesViaServiceLoader) {
    			ObjectMapper.findModules(this.moduleClassLoader).forEach(module -> registerModule(module, modulesToRegister));
    		}
    		else if (this.findWellKnownModules) {
    			registerWellKnownModulesIfAvailable(modulesToRegister);
    		}
    
    		if (this.modules != null) {
    			this.modules.forEach(module -> registerModule(module, modulesToRegister));
    		}
    		if (this.moduleClasses != null) {
    			for (Class<? extends Module> moduleClass : this.moduleClasses) {
    				registerModule(BeanUtils.instantiateClass(moduleClass), modulesToRegister);
    			}
    		}
    		List<Module> modules = new ArrayList<>();
    		for (List<Module> nestedModules : modulesToRegister.values()) {
    			modules.addAll(nestedModules);
    		}
    		objectMapper.registerModules(modules);
    
    		if (this.dateFormat != null) {
    			objectMapper.setDateFormat(this.dateFormat);
    		}
    		if (this.locale != null) {
    			objectMapper.setLocale(this.locale);
    		}
    		if (this.timeZone != null) {
    			objectMapper.setTimeZone(this.timeZone);
    		}
    
    		if (this.annotationIntrospector != null) {
    			objectMapper.setAnnotationIntrospector(this.annotationIntrospector);
    		}
    		if (this.propertyNamingStrategy != null) {
    			objectMapper.setPropertyNamingStrategy(this.propertyNamingStrategy);
    		}
    		if (this.defaultTyping != null) {
    			objectMapper.setDefaultTyping(this.defaultTyping);
    		}
    		if (this.serializationInclusion != null) {
    			objectMapper.setDefaultPropertyInclusion(this.serializationInclusion);
    		}
    
    		if (this.filters != null) {
    			objectMapper.setFilterProvider(this.filters);
    		}
    
    		if (jackson2XmlPresent) {
    			objectMapper.addMixIn(ProblemDetail.class, ProblemDetailJacksonXmlMixin.class);
    		}
    		else {
    			objectMapper.addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class);
    		}
    		this.mixIns.forEach(objectMapper::addMixIn);
    
    		if (!this.serializers.isEmpty() || !this.deserializers.isEmpty()) {
    			SimpleModule module = new SimpleModule();
    			addSerializers(module);
    			addDeserializers(module);
    			objectMapper.registerModule(module);
    		}
    
    		this.visibilities.forEach(objectMapper::setVisibility);
    
    		customizeDefaultFeatures(objectMapper);
    		this.features.forEach((feature, enabled) -> configureFeature(objectMapper, feature, enabled));
    
    		if (this.handlerInstantiator != null) {
    			objectMapper.setHandlerInstantiator(this.handlerInstantiator);
    		}
    		else if (this.applicationContext != null) {
    			objectMapper.setHandlerInstantiator(
    					new SpringHandlerInstantiator(this.applicationContext.getAutowireCapableBeanFactory()));
    		}
    
    		if (this.configurer != null) {
    			this.configurer.accept(objectMapper);
    		}
    	}
    
    	private void registerModule(Module module, MultiValueMap<Object, Module> modulesToRegister) {
    		if (module.getTypeId() == null) {
    			modulesToRegister.add(SimpleModule.class.getName(), module);
    		}
    		else {
    			modulesToRegister.set(module.getTypeId(), module);
    		}
    	}
    
    	// Any change to this method should be also applied to spring-jms and spring-messaging
    	// MappingJackson2MessageConverter default constructors
    	private void customizeDefaultFeatures(ObjectMapper objectMapper) {
    		if (!this.features.containsKey(MapperFeature.DEFAULT_VIEW_INCLUSION)) {
    			configureFeature(objectMapper, MapperFeature.DEFAULT_VIEW_INCLUSION, false);
    		}
    		if (!this.features.containsKey(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)) {
    			configureFeature(objectMapper, DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    		}
    	}
    
    	@SuppressWarnings("unchecked")
    	private <T> void addSerializers(SimpleModule module) {
    		this.serializers.forEach((type, serializer) ->
    				module.addSerializer((Class<? extends T>) type, (JsonSerializer<T>) serializer));
    	}
    
    	@SuppressWarnings("unchecked")
    	private <T> void addDeserializers(SimpleModule module) {
    		this.deserializers.forEach((type, deserializer) ->
    				module.addDeserializer((Class<T>) type, (JsonDeserializer<? extends T>) deserializer));
    	}
    

通过代码可以看出，builder对象最终build时，会进行默认值的设置，包括模块配置、序列化/反序列化配置、MixIn配置、日期+时间配置等。只是代码量多，具体逻辑并不复杂。

再来看下自动配置进行的拓展：
    
    
    // 对builder可进行多次配置
    @FunctionalInterface
    public interface Jackson2ObjectMapperBuilderCustomizer {
    	void customize(Jackson2ObjectMapperBuilder jacksonObjectMapperBuilder);
    }
    

自动配置支持多次配置代码如下：
    
    
    @AutoConfiguration
    @ConditionalOnClass(ObjectMapper.class)
    public class JacksonAutoConfiguration {
    		// 略去其他自动配置实现
    		
    		@Configuration(proxyBeanMethods = false)
    		@ConditionalOnClass(Jackson2ObjectMapperBuilder.class)
    		static class JacksonObjectMapperBuilderConfiguration {
    	
    			@Bean
    			@Scope("prototype")
    			@ConditionalOnMissingBean
    			Jackson2ObjectMapperBuilder jacksonObjectMapperBuilder(ApplicationContext applicationContext,
    					List<Jackson2ObjectMapperBuilderCustomizer> customizers) {
    				Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
    				builder.applicationContext(applicationContext);
    				customize(builder, customizers);
    				return builder;
    			}
    	
    			private void customize(Jackson2ObjectMapperBuilder builder,
    					List<Jackson2ObjectMapperBuilderCustomizer> customizers) {
    				for (Jackson2ObjectMapperBuilderCustomizer customizer : customizers) {
    					customizer.customize(builder);
    				}
    			}
    		}
    	}
    

代码分析：ObjectMapperBuilder当容器中没有对应bean时创建，为原型对象，遍历配置器（Jackson2ObjectMapperBuilderCustomizer）配置，这里的所有配置器可以指定顺序。

为支持通过yaml等外部配置文件进行配置，自动配置类提供了如下默认Customizer默认实现：
    
    
    	@Configuration(proxyBeanMethods = false)
    	@ConditionalOnClass(Jackson2ObjectMapperBuilder.class)
    	@EnableConfigurationProperties(JacksonProperties.class)
    	static class Jackson2ObjectMapperBuilderCustomizerConfiguration {
    
    		@Bean
    		StandardJackson2ObjectMapperBuilderCustomizer standardJacksonObjectMapperBuilderCustomizer(
    				JacksonProperties jacksonProperties, ObjectProvider<Module> modules) {
    			return new StandardJackson2ObjectMapperBuilderCustomizer(jacksonProperties, modules.stream().toList());
    		}
    
    		static final class StandardJackson2ObjectMapperBuilderCustomizer
    				implements Jackson2ObjectMapperBuilderCustomizer, Ordered {
    
    			private final JacksonProperties jacksonProperties;
    
    			private final Collection<Module> modules;
    
    			StandardJackson2ObjectMapperBuilderCustomizer(JacksonProperties jacksonProperties,
    					Collection<Module> modules) {
    				this.jacksonProperties = jacksonProperties;
    				this.modules = modules;
    			}
    
    			@Override
    			public int getOrder() {
    				return 0;
    			}
    
    			@Override
    			public void customize(Jackson2ObjectMapperBuilder builder) {
    				if (this.jacksonProperties.getDefaultPropertyInclusion() != null) {
    					builder.serializationInclusion(this.jacksonProperties.getDefaultPropertyInclusion());
    				}
    				if (this.jacksonProperties.getTimeZone() != null) {
    					builder.timeZone(this.jacksonProperties.getTimeZone());
    				}
    				configureFeatures(builder, FEATURE_DEFAULTS);
    				configureVisibility(builder, this.jacksonProperties.getVisibility());
    				configureFeatures(builder, this.jacksonProperties.getDeserialization());
    				configureFeatures(builder, this.jacksonProperties.getSerialization());
    				configureFeatures(builder, this.jacksonProperties.getMapper());
    				configureFeatures(builder, this.jacksonProperties.getParser());
    				configureFeatures(builder, this.jacksonProperties.getGenerator());
    				configureFeatures(builder, this.jacksonProperties.getDatatype().getEnum());
    				configureFeatures(builder, this.jacksonProperties.getDatatype().getJsonNode());
    				configureDateFormat(builder);
    				configurePropertyNamingStrategy(builder);
    				configureModules(builder);
    				configureLocale(builder);
    				configureDefaultLeniency(builder);
    				configureConstructorDetector(builder);
    			}
    			// 略去其他方法
    		}
    

代码分析：默认配置器（标准配置器）读取配置数据类文件（JacskonProperties)和Spring管理的Jaskson模块对象bean进行创建。默认配置器实现了排序接口。

以上代码简化了ObjectMapper的配置操作，多数情况下，开发者仅仅需要配置配置文件或添加所需Module即可满足需求。

总之，在使用了注解驱动开发后，可以更容易地使用builder模式创建bean，也可以通过自定义配置器、结合外部配置类、生命周期管理等方法对builder模式进行拓展。通过自动配置结合builder模式，开发者可以避免繁琐的配置操作。

虽然自动配置为开发者做了很多脏活累活，但是开发者仍然需要在开发前认真读取自动配置类等说明文档。否则，如果开发者自己注入ObjectMapper bean，就可能会造成修改前后的ObjectMapper行为不一致的问题。

## 总结

  1. 对于复杂对象的构建，使用builder模式具有诸多好处
  2. builder模式具有多种实现，开发时需根据实际情况权衡取舍。可以借助Lombok等类库辅助代码编写。builder创建的对象若为不可变对象（值类型对象），应尽量使用record关键字或者AutoValue等类库实现。
  3. 不论是Spring还是其他类库，基本上都支持JavaBean和builder模式的对象创建方式。