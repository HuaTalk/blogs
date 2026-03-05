---
title: "Spring Security 难用到怀疑人生？你可能不是一个人！"
date: 2025-02-05
url: https://juejin.cn/post/7467503654695026722
views: 4736
likes: 35
collects: 52
source: html2md
---

## Spring Security 难用到怀疑人生？你可能不是一个人！

Spring Security 之所以非常复杂，很大程度上在于它“独特”的 API 设计，“独特”并不是什么褒义词。对于初学者来说，很难快速上手；对于老手来说，容易犯错。DSL 的配置像是解谜，为了解决 Bug，很可能需要看源码，而源码写得如同天书，不同版本的 API 变化又相当频繁。本文将深入分析其难用的原因，并给出实践建议。

### 1\. **多种功能的支持与配置**

Spring Security 的灵活性和强大功能使得配置变得复杂且繁琐。它提供的功能涉及认证、授权、会话管理、CSRF 防护、CORS、跨站请求伪造攻击防护等多个方面。为了实现这些功能，开发者需要在项目中配置大量的内容，包括：

  * **认证方式** ：如表单登录、HTTP 基本认证、JWT 等。
  * **授权策略** ：如基于角色的访问控制（RBAC）和基于权限的访问控制。
  * **会话管理** ：包括会话固定攻击防护、会话超时、并发控制等。
  * **跨站请求伪造（CSRF）防护** ：如何管理 CSRF Token 和启用相关的保护机制。



对于初学者或没有安全基础的开发者来说，理解并配置这些选项通常会感到非常困惑。Spring Security 的默认配置，尤其是在基于 XML 的配置方式中，经常被认为是冗长、复杂且不直观的。虽然 Spring Boot 可以通过自动配置来简化某些配置，但仍然需要理解配置细节。

### 2\. **DSL 配置系统**

Spring Security 定义了自己的领域专属语言（DSL）配置系统，基于 Java 的语法构建。这种 DSL 配置系统的初衷是为了让开发者能够简洁地配置安全相关的内容，但实际上其设计晦涩难懂且不易扩展，常常让开发者感到迷惑。

这种配置系统受到了流畅 API 设计思想的影响，然而，就像很多流畅 API 一样，面临着可读性、拓展性等问题。比如初次接触 Java 8 集合类库的 Stream 编程时，对于多数人或其他语言迁移过来的程序员，很难做到快速上手和理解。可读性是一个非常主观的评价标准，对于熟练使用的程序员来说，可能认可某些流畅 API 的可读性。

以下是一个典型配置：
    
    
    // 配置类
    @Configuration
    @EnableWebSecurity
    public class SecurityConfig extends WebSecurityConfigurerAdapter {
        @Override
        public void configure(AuthenticationManagerBuilder auth) throws Exception {
            auth.ldapAuthentication()
                .contextSource() // 又进入了一个 configurer
                    .url(ldapProperties.getUrls()[0] + StrUtil.SLASH + ldapProperties.getBase())
                    .managerDn(ldapProperties.getUsername())
                    .managerPassword(ldapProperties.getPassword())
                .and() // 这个 and 返回到了 LDAP configurer
                .userDetailsContextMapper(customLdapUserDetailsMapper)
                .userSearchBase(extendLdapProperties.getSearchBase())
                .userSearchFilter(extendLdapProperties.getSearchFilter())
                .and()
                .userDetailsService(userDetailsService())
                .passwordEncoder(new BCryptPasswordEncoder());
        }
    }
    
    

#### 2.1 不直观的配置顺序

Spring Security 的配置逻辑有时显得不直观，配置的顺序可能影响最终的行为。尤其是在进行多个安全组件的集成时，开发者需要特别小心这些组件之间的顺序问题。

#### 2.2 泛型和继承的复杂性

Spring Security 使用泛型和继承来实现一些抽象类，这对于不熟悉这种设计模式的开发者来说，可能会产生很大的困惑。

如下是官方文档的后处理实现示例：
    
    
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeRequests(authorize -> authorize
                .anyRequest().authenticated()
                .withObjectPostProcessor(new ObjectPostProcessor<FilterSecurityInterceptor>() {
                    public <O extends FilterSecurityInterceptor> O postProcess(O fsi) {
                        fsi.setPublishAuthorizationSuccess(true);
                        return fsi;
                    }
                })
            );
        return http.build();
    }
    

这种泛型和继承结合的设计方式对于大部分开发者，尤其是对于没有使用过这些特性的开发者，可能是一个不小的障碍。

#### 2.3 舍弃强类型

Spring Security 的某些 API 返回 `Object` 类型，开发者往往需要强制转换成更具体的类型。这种做法违背了强类型语言的设计初衷，增加了代码出错的概率和调试的难度。

HTTP 配置链进行配置时很难进行追踪。我在[之前的文章](<https://juejin.cn/post/7222921573623201851#heading-11> "https://juejin.cn/post/7222921573623201851#heading-11")中提到过便于理解的方式：可以将配置链的构建看成是一条绳子上的绳结，每次配置都是对于某个绳结进行配置，通过调用 `and` 方法回到绳子上。

### 3\. **高层次的抽象与复杂的内部机制**

Spring Security 的高层次抽象是它的一个重要特性，但这也使得其学习曲线异常陡峭。Spring Security 的许多核心概念都依赖于抽象，例如：

  * **过滤器链（Filter Chain）** ：Spring Security 内部使用了过滤器链模式，所有的安全控制都通过不同的过滤器进行处理。理解过滤器的顺序和行为非常重要，尤其是在处理认证和授权请求时，开发者需要知道过滤器是如何一层层地处理请求的。
  * **认证提供者（Authentication Provider）** ：Spring Security 提供了不同的认证方式，如 LDAP、OAuth2、JDBC、JWT 等。每种认证方式都需要实现一个特定的认证提供者接口，这要求开发者理解这些认证提供者如何工作，并根据需求进行配置。尤其是在处理自定义认证逻辑时，开发者往往需要对 Spring Security 内部的实现有深入的了解。
  * **授权管理器（Authorization Manager）** ：授权管理是 Spring Security 的一个核心概念，它决定了用户是否可以访问特定的资源。为了灵活控制权限，Spring Security 提供了多个层次的授权控制（如基于角色的访问控制、基于权限的访问控制等），这些都需要开发者去学习和掌握。



### 4\. **调试与错误处理的难度**

由于 Spring Security 的抽象层次较高，调试和排查问题往往需要相当的经验。配置不当或者安全策略设计错误可能导致非常难以捕捉和定位的异常。例如，错误的过滤器顺序、缺失的认证提供者、权限配置错误等，都可能导致认证失败或者授权错误，而这些错误往往无法通过常规的堆栈跟踪信息清晰地了解问题的根本原因。

### 5\. **技术债**

Spring Security 很难使用，这基本已经成为[共识](<https://link.juejin.cn?target=https%3A%2F%2Fgithub.com%2Fspring-projects%2Fspring-security%2Fissues> "https://github.com/spring-projects/spring-security/issues")。

在最新版本 Spring Security 7 中提供了新的配置方式，使用 Lambda 表达式进行配置，但这种实现依然摆脱不了其难用的现状，甚至导致用户的学习成本进一步提高：
    
    
    @Configuration
    @EnableWebSecurity
    public class SecurityConfig {
    
        @Bean
        public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
            http
                .authorizeHttpRequests(authorize -> authorize
                    .requestMatchers("/blog/**").permitAll()
                    .anyRequest().authenticated()
                )
                .formLogin(formLogin -> formLogin
                    .loginPage("/login")
                    .permitAll()
                )
                .rememberMe(Customizer.withDefaults());
    
            return http.build();
        }
    }
    

笔者认为，受限于 Spring Security API 设计的历史问题，这种技术债只会越积越多。不如完全抛弃 DSL 这种配置方式，启动新版本，改为全面支持 Spring Boot 形式的配置。

除了这种配置的问题，Spring Security 还存在多种 API 设计的问题，比如大部分功能需要由用户自己实现的权限管理功能、深度依赖 Spring EL 表达式的设计。作为一个专门学习和研究过 Spring Security 设计思想和源码，以及排查过让人费解的配置 Bug 的开发者，我了解的越多，就越是反感这种错误的 API 设计。这种感觉似乎就是和臭棋篓子一起下棋，这么说似乎有点过，因为 Spring Security 在网络安全防护方面做得还不错。退而求其次地说，Spring Security 就是臭豆腐，甚至是臭鳜鱼，它的臭会让你敬而远之，它的香又让你少有其他选择。

### **结论与实践建议**

如果项目中还没有使用 Spring Security，不要把 Spring Security 作为技术选型的首选。

如果项目中已经使用了 Spring Security：

  1. 应该尽量减少代码与 Security 框架的耦合。
  2. 通过调试、单元测试等形式检查 DSL 配置，因为很多情况下 DSL 最终生成的过滤器链可能和你的预想不一致。
  3. Spring Security 的设计并不是良好的[深模块（Deep Module）](<https://link.juejin.cn?target=https%3A%2F%2Fprograils.com%2Fmodular-design-deep-vs-shallow-modules> "https://prograils.com/modular-design-deep-vs-shallow-modules")，做好研究其底层实现的准备。