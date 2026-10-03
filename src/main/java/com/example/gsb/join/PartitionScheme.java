package com.example.gsb.join;

import java.util.Arrays;

/**
 * 范围分区方案。
 *
 * <p>分区 0 覆盖 [lowerBounds[0], lowerBounds[1])，……，最后一个分区覆盖
 * [lowerBounds[n-1], +inf)。即每个分区 i 的范围为 [lowerBounds[i], lowerBounds[i+1])，
 * 最后一个分区的上界用 {@link Long#MAX_VALUE} 作为 +inf 哨兵（左闭右开）。
 * 因此可分区的最大键值为 {@code Long.MAX_VALUE - 1}。
 * 下界数组必须严格递增。
 */
public record PartitionScheme(String keyColumn, long[] lowerBounds) {

    public PartitionScheme {
        if (keyColumn == null || keyColumn.isBlank()) {
            throw new IllegalArgumentException("分区键列名不能为空");
        }
        if (lowerBounds == null || lowerBounds.length == 0) {
            throw new IllegalArgumentException("至少需要一个分区下界");
        }
        for (int i = 1; i < lowerBounds.length; i++) {
            if (lowerBounds[i] <= lowerBounds[i - 1]) {
                throw new IllegalArgumentException("分区下界必须严格递增");
            }
        }
    }

    /** 直接按给定下界构造（首个下界通常为 0）。 */
    public static PartitionScheme of(String keyColumn, long... lowerBounds) {
        return new PartitionScheme(keyColumn, lowerBounds);
    }

    /**
     * 构造 n 个等宽分区：[base + i*width, base + (i+1)*width)，
     * 最后一个分区为 [base + (n-1)*width, +inf)。
     */
    public static PartitionScheme equalWidth(String keyColumn, long base, long width, int n) {
        if (n <= 0 || width <= 0) {
            throw new IllegalArgumentException("分区数与宽度必须为正");
        }
        long[] bounds = new long[n];
        for (int i = 0; i < n; i++) {
            bounds[i] = Math.addExact(base, Math.multiplyExact((long) i, width));
        }
        return new PartitionScheme(keyColumn, bounds);
    }

    public int partitionCount() {
        return lowerBounds.length;
    }

    public long lowerBound(int partitionId) {
        return lowerBounds[partitionId];
    }

    /** 分区上界；最后一个分区返回 {@link Long#MAX_VALUE} 表示 +inf（左闭右开）。 */
    public long upperBound(int partitionId) {
        return partitionId + 1 < lowerBounds.length ? lowerBounds[partitionId + 1] : Long.MAX_VALUE;
    }

    /** 二分查找键值所属分区 id。 */
    public int partitionOf(long key) {
        int lo = 0;
        int hi = lowerBounds.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (key >= lowerBounds[mid]) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /** 收集与某个区间域 [domainLo, domainHi) 有交集的所有分区。 */
    public int[] partitionsIntersecting(long domainLo, long domainHi) {
        int[] out = new int[lowerBounds.length];
        int cnt = 0;
        for (int p = 0; p < lowerBounds.length; p++) {
            long pLo = lowerBounds[p];
            long pHi = upperBound(p);
            if (domainLo < pHi && pLo < domainHi) {
                out[cnt++] = p;
            }
        }
        return Arrays.copyOf(out, cnt);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PartitionScheme ps
                && keyColumn.equals(ps.keyColumn)
                && Arrays.equals(lowerBounds, ps.lowerBounds);
    }

    @Override
    public int hashCode() {
        return 31 * keyColumn.hashCode() + Arrays.hashCode(lowerBounds);
    }

    @Override
    public String toString() {
        return "PartitionScheme[" + keyColumn + ", bounds=" + Arrays.toString(lowerBounds) + "]";
    }
}
