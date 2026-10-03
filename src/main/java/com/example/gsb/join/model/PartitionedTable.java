package com.example.gsb.join.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 按单个 long 分区键做范围分区的表。
 *
 * <p>分区区间互不相交、首尾相接，合起来完整覆盖分区键值域
 * （{@code [Long.MIN_VALUE, Long.MAX_VALUE)}），且两张参与连接的表
 * 必须采用完全相同的分区方案（同分区键、同边界）。
 */
public final class PartitionedTable {

    private final String name;
    private final String partitionKey;
    private final List<Partition> partitions;

    public PartitionedTable(String name, String partitionKey, List<Partition> partitions) {
        this.name = name;
        this.partitionKey = partitionKey;
        this.partitions = Collections.unmodifiableList(new ArrayList<>(partitions));
        validate();
    }

    private void validate() {
        if (partitions.isEmpty()) {
            throw new IllegalArgumentException("表至少要有一个分区");
        }
        for (int i = 0; i < partitions.size(); i++) {
            if (partitions.get(i).index() != i) {
                throw new IllegalArgumentException("分区下标必须连续且从 0 开始");
            }
            if (i > 0 && partitions.get(i - 1).upper() != partitions.get(i).lower()) {
                throw new IllegalArgumentException("分区必须首尾相接");
            }
        }
    }

    public String name() {
        return name;
    }

    public String partitionKey() {
        return partitionKey;
    }

    public List<Partition> partitions() {
        return partitions;
    }

    public int partitionCount() {
        return partitions.size();
    }

    /**
     * 构造等宽范围分区表。
     *
     * @param width 每个分区的宽度（正数）；最后一个分区右端取 {@link Long#MAX_VALUE}
     */
    public static PartitionedTable ranged(String name, String partitionKey, int partitionCount, long width,
                                         List<Row> rows) {
        if (partitionCount <= 0) {
            throw new IllegalArgumentException("分区数必须为正数");
        }
        if (width <= 0) {
            throw new IllegalArgumentException("分区宽度必须为正数");
        }
        List<Partition> partitions = new ArrayList<>(partitionCount);
        List<List<Row>> buckets = new ArrayList<>(partitionCount);
        for (int i = 0; i < partitionCount; i++) {
            long lo = Math.multiplyExact((long) i, width);
            long hi = (i == partitionCount - 1) ? Long.MAX_VALUE : Math.multiplyExact((long) (i + 1), width);
            partitions.add(new Partition(i, lo, hi, List.of()));
            buckets.add(new ArrayList<>());
        }
        for (Row row : rows) {
            long key = row.getLong(partitionKey);
            int idx = (int) Math.min(partitionCount - 1, Math.floorDiv(key, width));
            if (idx < 0) {
                throw new IllegalArgumentException("分区键小于 0，等宽分区从 0 开始: " + key);
            }
            buckets.get(idx).add(row);
        }
        List<Partition> filled = new ArrayList<>(partitionCount);
        for (int i = 0; i < partitionCount; i++) {
            filled.add(new Partition(i, partitions.get(i).lower(), partitions.get(i).upper(), buckets.get(i)));
        }
        return new PartitionedTable(name, partitionKey, filled);
    }
}
