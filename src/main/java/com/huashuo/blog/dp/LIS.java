package com.huashuo.blog.dp;

import java.util.Arrays;
import java.util.Random;

public class LIS {
    public static void main(String[] args) {
        int[] arr = new int[10];
        var r = new Random();
        Arrays.setAll(arr, x -> r.nextInt(100));
        System.out.println(Arrays.toString(arr));

        int result = Arrays.binarySearch(arr, 0, 0, 10);
        System.out.println("result = " + result);
    }
}
