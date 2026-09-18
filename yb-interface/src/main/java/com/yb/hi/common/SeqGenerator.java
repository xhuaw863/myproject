package com.yb.hi.common;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 报文顺序号生成器(4位循环)
 */
public class SeqGenerator {

    private static final AtomicInteger SEQ = new AtomicInteger(0);

    public static String next() {
        int val = SEQ.incrementAndGet();
        if (val > 9999) {
            SEQ.set(1);
            val = 1;
        }
        return String.format("%04d", val);
    }
}
