---
title: "Mybatis Plus 最佳实践总结与逻辑删除实现"
date: 2023-05-05
url: https://juejin.cn/post/7229536038715539512
views: 1277
likes: 4
collects: 12
source: html2md
---

本文想讨论两个方面的内容，第一部分是 MyBatis Plus（以下简称MP)的最佳实践， 第二部分讨论了从面向对象的角度抽取出可复用的业务逻辑，主要包括逻辑删除的实现、通用CRUD的封装、自动填充字段等。

## 一. MP最佳实践总结

  1. 不要使用IService。IService 封装了数据访问层的逻辑，实际上就应该属于业务访问层， 而且使用IService打破了表示层、业务逻辑层、数据访问层三层模型，将数据访问层的逻辑转移到业务逻辑层， 导致我们的Service对象变得异常臃肿。

  2. 尽量使用lambda链式处理。使用函数式参数避免了字符串拼写的问题，不过由于我们并不使用IService，Query并不能完全链式表示，比如还要显示调用 selectOne(query) 等方法。

  3. 不要使用联表查询。虽然MP支持了一定程度的联表查询，但是终究支持有限，不利用后续优化。使用 xml 即可，mybatis 对于 1对多、多对多的支持就很好。

  4. 不要使用MP提供的逻辑删除。在实际业务中我们常常使用逻辑删除，即把对数据的删除改为标记删除位。MP的逻辑删除不支持唯一索引的使用，我们需要自己实现逻辑删除。 对于一条用户信息，email字段是唯一的，UNIQUE INDEX unique_email (`email`) 这样设置索引是有问题的，因为我们可能有多条已经逻辑删除的邮箱。 解决方法是引入额外的字段保证唯一，常见的方法是引入uuid，对于并发量不大的业务也可以用时间戳， UNIQUE INDEX unique_email (`email`, `delete_flag`)。详见后续代码。

  5. 熟练使用条件构造器。官网提供了条件构造器的详细说明，熟练使用可以极大提高写代码的效率。

  6. 可以通过继承通用接口 BaseMapper 实现代码复用。也有人通过对于QueryMapper的封装实现复用；还可以通过组合对象的方式，由于Mapper对象由代理生成， 我们通过委托（Delegate) 实现对到具体的Mapper的调用。




## 二. 逻辑删除实现

以下为数据库表映射对象的实现：
    
    
    @Getter
    @Setter
    @ToString(callSuper = true)
    public class UserModel extends CommonModel<UserModel> {
       private String email;
       private String address;
    }
    
    // 此处仅为举例：继承Model可实现对象自主调用CRUD：ActiveRecord，因为会导致代码风格不一致，不建议使用
    // 逻辑删除支持对象
    @Data
    public abstract class CommonModel<T extends CommonModel<T>> extends Model<T> implements Cloneable {
    
       public static final int VALID = 1;
    
       public static final int INVALID = 2;
    
       /**
        * 主键ID
        */
       @TableId(value = "id", type = IdType.AUTO)
       private Long id;
    
       // 创建时间
       // 仅在第一次创建数据时手动添加，MP无需管理
       @TableField(select = false, insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
       @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
       @JsonDeserialize(using = LocalDateTimeDeserializer.class)
       @JsonSerialize(using = LocalDateTimeSerializer.class)
       private LocalDateTime createTime;
    
       // 创建人
       // 仅在第一次创建数据时数据库自动添加，MP无需管理
       @TableField(updateStrategy = FieldStrategy.NEVER)
       private Long createBy;
    
       // 保留字段，方便拓展
       // 此注解防止传空删除
       @TableField(insertStrategy = FieldStrategy.NOT_EMPTY, updateStrategy = FieldStrategy.NOT_EMPTY)
       private String remark;
    
       // 逻辑删除字段 (1: valid 2: invalid)
       private Integer valid;
    
    
       // 更新时间
       // 无需 MP/编程 管理，数据库设置为：on update CURRENT_TIMESTAMP
       @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
       @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
       @JsonDeserialize(using = LocalDateTimeDeserializer.class)
       @JsonSerialize(using = LocalDateTimeSerializer.class)
       private LocalDateTime updateTime;
    
       // 修改人
       // 配置自动填充
       @TableField(fill = FieldFill.INSERT_UPDATE)
       private Long modifiedBy;
    
       @TableField(fill = FieldFill.INSERT_UPDATE)
       private String modifier;
    
       // 删除时间（配合valid 实现唯一约束）
       // 仅在逻辑删除时设置值
       @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NOT_NULL)
       private Long deleteTime;
    
       // 序列化需要无参数构造器
       public CommonModel() {
       }
    
       // copy constructor 支持
       protected CommonModel(@NotNull CommonModelBuilder<T, ?, ?> b) {
          this.id = b.id;
          this.createTime = b.createTime;
          this.createBy = b.createBy;
          this.remark = b.remark;
          this.valid = b.isValid;
          this.updateTime = b.updateTime;
          this.modifiedBy = b.modifiedBy;
          this.deleteTime = b.deleteTime;
          this.modifier = b.modifier;
       }
    
       // 根据 id 重写 equals 和 hashcode
       @Override
       public boolean equals(@Nullable Object o) {
          if (this == o) return true;
          if (o == null || getClass() != o.getClass()) return false;
          CommonModel<?> commonModel = (CommonModel<?>) o;
          return Objects.equal(id, commonModel.id);
       }
    
       @Override
       public int hashCode() {
          return Objects.hashCode(id);
       }
    
       // 父类要求重写
       @Override
       public Serializable pkVal() {
          return getId();
       }
    
    
       @Override
       public String toString() {
          return "CommonModel{" +
                  "id=" + id +
                  '}';
       }
    
       // 虽然 Effective Java 并不推荐使用 Cloneable
       // 但是clone方法确实好用，而且提供了统一的拷贝方法
       // 需要注意的是这里的实现是浅拷贝，表的大部分字段为不可变类型，一般不会出现对象B修改字段影响对象A的情况
       @NotNull
       @Override
       @SuppressWarnings("unchecked")
       @SneakyThrows
       public final T clone() {
          return (T) super.clone();
       }
    
       // 方便使用的拷贝方法
       @NotNull
       public final T withId(Long id) {
          T copy = clone();
          copy.setId(id);
          return copy;
       }
    
       // 提示程序员物理删除已禁用
       @Deprecated
       @Override
       public final boolean deleteById() {
          throw new UnsupportedOperationException();
       }
    
       // 略去其他 @Deprecated方法
       // 复杂对象的创建推荐使用 builder 模式
       // SuperBuilder， 实现原理见我的另一篇文章：《深入理解Java泛型、协变逆变、泛型通配符、自限定》
       public static abstract class CommonModelBuilder<T extends CommonModel<T>, C extends CommonModel<T>, B extends CommonModelBuilder<T, C, B>> {
          private Long id;
          private LocalDateTime createTime;
          private Long createBy;
          private String remark;
          private Integer isValid;
          private LocalDateTime updateTime;
          private Long modifiedBy;
          private Long deleteTime;
          private String modifier;
    
          public @NotNull
          B id(Long id) {
             this.id = id;
             return self();
          }
    
          public @NotNull
          B createTime(LocalDateTime createTime) {
             this.createTime = createTime;
             return self();
          }
    
          public @NotNull
          B createBy(Long createBy) {
             this.createBy = createBy;
             return self();
          }
    
          public @NotNull
          B remark(String remark) {
             this.remark = remark;
             return self();
          }
    
          public @NotNull
          B isValid(Integer valid) {
             this.Valid = valid;
             return self();
          }
    
          public @NotNull
          B updateTime(LocalDateTime updateTime) {
             this.updateTime = updateTime;
             return self();
          }
    
          public @NotNull
          B modifiedBy(Long modifiedBy) {
             this.modifiedBy = modifiedBy;
             return self();
          }
    
          public @NotNull
          B deleteTime(Long deleteTime) {
             this.deleteTime = deleteTime;
             return self();
          }
    
          public @NotNull
          B modifier(String modifier) {
             this.modifier = modifier;
             return self();
          }
    
          protected abstract @NotNull
          B self();
    
          public abstract @NotNull
          C build();
    
          public @NotNull
          String toString() {
             return "CommonModel.CommonModelBuilder(" +
                     "super=" + super.toString() +
                     ", id=" + this.id +
                     ", createTime=" + this.createTime +
                     ", createBy=" + this.createBy +
                     ", remark=" + this.remark +
                     ", isValid=" + this.isValid +
                     ", updateTime=" + this.updateTime +
                     ", modifiedBy=" + this.modifiedBy +
                     ", deleteTime=" + this.deleteTime +
                     ", modifier=" + this.modifier + ")";
          }
       }
    }
    

以下为自动填充字段的实现：
    
    
    @Component
    public class OperatorMetaObjectHandler implements MetaObjectHandler {
    
        @Override
        public void insertFill(MetaObject metaObject) {
            User user = User.getCache();
            this.strictInsertFill(metaObject, "modifiedBy", Long.class, user.getUserId());
            this.strictInsertFill(metaObject, "modifier", String.class, user.getEmail());
        }
    
        @Override
        public void updateFill(MetaObject metaObject) {
            User user = User.getCache();
            this.strictUpdateFill(metaObject, "modifier", String.class, user.getEmail());
            this.strictUpdateFill(metaObject, "modifiedBy", Long.class, user.getUserId());
        }
    }
    

通过继承BaseMapper接口可以自定义公用接口逻辑：
    
    
    public enum FindAll {
        // 使用枚举作为方法参数值
        // 相比selectAll(true)有更好的可读性
        ENABLED, DISABLED
    }
    
    // 继承BaseMapper，可以通过default方法实现代码复用
    // 注意不要让 @MapperScan 扫描到此接口
    public interface LogicDeleteSupportMapper<T> extends BaseMapper<T> {
    
        // 由于我们支持了逻辑删除，原来的物理删除方法不使用了，但是父接口的方法不能删除
        // 使用Deprecated提示用户不要使用这个方法（可参考ImmutableList)
        @Deprecated
        @Override
        int deleteById(T entity);
    
        @Deprecated
        @Override
        int deleteBatchIds(Collection<?> idList);
    
        @Deprecated
        @Override
        int deleteByMap(Map<String, Object> columnMap);
    
        @Deprecated
        @Override
        int deleteById(Serializable id);
    
        @Deprecated
        @Override
        int delete(Wrapper<T> queryWrapper);
    
        // Optional 的唯一最佳实践，返回值返回 Optional，不用担心NPE
        default Optional<T> getValidById(Long id) {
            T result = selectOne(getQueryWrapper()
                    .eq("valid", 1)
                    .eq("id", id));
            return Optional.ofNullable(result);
        }
    
        default ImmutableSet<T> getValidListByIds(Collection<Long> ids) {
            return getValidListByIds(ids, FindAll.DISABLED);
        }
    
        default ImmutableSet<T> getAllValid() {
            return getValidListByIds(ImmutableSet.of(), FindAll.ENABLED);
        }
    
        // 入参为Collection, 出参为ImmutableSet
        // 保证宽接口，出参无需保证顺序且有唯一性要求，所以用 Set
        default ImmutableSet<T> getValidListByIds(Collection<Long> ids, FindAll findAllConfig) {
            if (findAllConfig == FindAll.DISABLED && ids.isEmpty()) {
                return ImmutableSet.of();
            }
            // 此处使用QueryWrapper仅作示例，推荐使用 LambdaWrapper
            QueryWrapper<T> queryWrapper = getQueryWrapper()
                    .in(CollectionUtils.isNotEmpty(ids), "id", ids)
                    .eq("valid", 1);
            List<T> queryResult = selectList(queryWrapper);
            // 集合类型天然具有空值
            return queryResult == null ? ImmutableSet.of() : ImmutableSet.copyOf(queryResult);
        }
    
       default int logicDelete(Long id) {
          LambdaUpdateWrapper<T> wrapper = Wrappers.<T>lambdaUpdate()
                  .set(CommonModel::getValid, CommonModel.INVALID)
                  .set(CommonModel::getDeleteTime, System.currentTimeMillis())
                  .eq(CommonModel::getId, id)
                  .eq(CommonModel::getValid, CommonModel.VALID);
          return update(null, wrapper);
       }
    
        // 每次 new QueryWrapper 需要传泛型参数,此方法方便调用
        // 参考 Lists.newArrayList()
        default QueryWrapper<T> getQueryWrapper() {
            return new QueryWrapper<>();
        }
    }
    
    
    
    // 子类通过继承，添加独立的实现
    @Repository
    public interface UserDao extends LogicDeleteSupportMapper<UserEntity> {
        // 由xml实现
        List<UserDetailDTO> getListByCondition(UserQueryRequest request);
    
        default Option<UserEntity> getUserByEmail(String email) {
            LambdaQueryWrapper<UserEntity> queryWrapper = new LambdaQueryWrapper<UserEntity>()
                    .eq(UserEntity::getEmail, email)
                    .eq(UserEntity::getIsValid, 1);
            return Optional.ofNullable(selectOne(queryWrapper));
        }
    
        default UserEntity getCheckedUserByEmail(String email) {
            return getUserByEmail(email).orElseThrow(() -> new BizException(ResultCode.USER_NOT_FOUND_FAIL));
        }
    }