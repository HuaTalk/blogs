package com.huashuo.blog.patterns;

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

    public Long getId() {
        return this.id;
    }

    public Long getDeletedAt() {
        return this.deletedAt;
    }

    public Long getCreatedAt() {
        return this.createdAt;
    }

    public String getCreator() {
        return this.creator;
    }

    public Long getUpdatedAt() {
        return this.updatedAt;
    }

    public String getOperator() {
        return this.operator;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setDeletedAt(Long deletedAt) {
        this.deletedAt = deletedAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public void setCreator(String creator) {
        this.creator = creator;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public boolean equals(final Object o) {
        if (o == this) return true;
        if (!(o instanceof BasePO)) return false;
        final BasePO other = (BasePO) o;
        if (!other.canEqual((Object) this)) return false;
        final Object this$id = this.getId();
        final Object other$id = other.getId();
        if (this$id == null ? other$id != null : !this$id.equals(other$id)) return false;
        final Object this$deletedAt = this.getDeletedAt();
        final Object other$deletedAt = other.getDeletedAt();
        if (this$deletedAt == null ? other$deletedAt != null : !this$deletedAt.equals(other$deletedAt)) return false;
        final Object this$createdAt = this.getCreatedAt();
        final Object other$createdAt = other.getCreatedAt();
        if (this$createdAt == null ? other$createdAt != null : !this$createdAt.equals(other$createdAt)) return false;
        final Object this$creator = this.getCreator();
        final Object other$creator = other.getCreator();
        if (this$creator == null ? other$creator != null : !this$creator.equals(other$creator)) return false;
        final Object this$updatedAt = this.getUpdatedAt();
        final Object other$updatedAt = other.getUpdatedAt();
        if (this$updatedAt == null ? other$updatedAt != null : !this$updatedAt.equals(other$updatedAt)) return false;
        final Object this$operator = this.getOperator();
        final Object other$operator = other.getOperator();
        if (this$operator == null ? other$operator != null : !this$operator.equals(other$operator)) return false;
        return true;
    }

    protected boolean canEqual(final Object other) {
        return other instanceof BasePO;
    }

    public int hashCode() {
        final int PRIME = 59;
        int result = 1;
        final Object $id = this.getId();
        result = result * PRIME + ($id == null ? 43 : $id.hashCode());
        final Object $deletedAt = this.getDeletedAt();
        result = result * PRIME + ($deletedAt == null ? 43 : $deletedAt.hashCode());
        final Object $createdAt = this.getCreatedAt();
        result = result * PRIME + ($createdAt == null ? 43 : $createdAt.hashCode());
        final Object $creator = this.getCreator();
        result = result * PRIME + ($creator == null ? 43 : $creator.hashCode());
        final Object $updatedAt = this.getUpdatedAt();
        result = result * PRIME + ($updatedAt == null ? 43 : $updatedAt.hashCode());
        final Object $operator = this.getOperator();
        result = result * PRIME + ($operator == null ? 43 : $operator.hashCode());
        return result;
    }

    public String toString() {
        return "BasePO(id=" + this.getId() + ", deletedAt=" + this.getDeletedAt() + ", createdAt=" + this.getCreatedAt() + ", creator=" + this.getCreator() + ", updatedAt=" + this.getUpdatedAt() + ", operator=" + this.getOperator() + ")";
    }

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

    public String getName() {
        return this.name;
    }

    public Integer getAge() {
        return this.age;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setAge(Integer age) {
        this.age = age;
    }

    public String toString() {
        return "UserPO(name=" + this.getName() + ", age=" + this.getAge() + ")";
    }

    public boolean equals(final Object o) {
        if (o == this) return true;
        if (!(o instanceof UserPO)) return false;
        final UserPO other = (UserPO) o;
        if (!other.canEqual((Object) this)) return false;
        if (!super.equals(o)) return false;
        final Object this$name = this.getName();
        final Object other$name = other.getName();
        if (this$name == null ? other$name != null : !this$name.equals(other$name)) return false;
        final Object this$age = this.getAge();
        final Object other$age = other.getAge();
        if (this$age == null ? other$age != null : !this$age.equals(other$age)) return false;
        return true;
    }

    protected boolean canEqual(final Object other) {
        return other instanceof UserPO;
    }

    public int hashCode() {
        final int PRIME = 59;
        int result = super.hashCode();
        final Object $name = this.getName();
        result = result * PRIME + ($name == null ? 43 : $name.hashCode());
        final Object $age = this.getAge();
        result = result * PRIME + ($age == null ? 43 : $age.hashCode());
        return result;
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
