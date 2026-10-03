package com.example.gsb.join.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一个范围分区：覆盖分区键区间 {@code [lower, upper)}，内含若干行。
 *
 * <p>{@code scanned} 仅统计“行数据被真正读取”的次数：剪枝排除的分区
 * 该计数必须为 0，用于校验“无关分区不得参与扫描”。
 */
public final class Partition {

    private final int index;
    private final long lower;
    private final long upper;
    private final List<Row> rows;

    private volatile int scanned;

    public Partition(int index, long lower, long upper, List<Row> rows) {
        if (lower >= upper) {
            throw new IllegalArgumentException("分区边界必须满足 lower < upper: " + lower + " >= " + upper);
        }
        this.index = index;
        this.lower = lower;
        this.upper = upper;
        this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
    }

    public int index() {
        return index;
    }

    public long lower() {
        return lower;
    }

    public long upper() {
        return upper;
    }

    /** 读取本分区的行数据，扫描计数 +1。 */
    public List<Row> scan() {
        scanned++;
        return rows;
    }

    public int rowCount() {
        return rows.size();
    }

    public int scanCount() {
        return scanned;
    }

    public boolean containsKey(long key) {
        return key >= lower && key < upper;
    }

    @Override
    public String toString() {
        return "P" + index + "[" + lower + "," + upper + "),rows=" + rows.size();
    }
}
