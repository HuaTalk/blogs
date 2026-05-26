package com.huashuo.blog.patterns;

record PositiveInteger(int value) {
    PositiveInteger {
        if (value <= 0) throw new IllegalStateException("invalid value");
    }

    public static void main(String[] args) {
        var a = new PositiveInteger(1);
        var b = new PositiveInteger(1);
        System.out.println("a == b = " + (a == b));
        System.out.println("a.equals(b) = " + a.equals(b));
    }
}
