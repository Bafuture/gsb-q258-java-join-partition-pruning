package com.example.gsb.join;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 分区键的取值域：由若干半开区间 [lo, hi) 的并集表示，{@link Long#MAX_VALUE} 作为 +inf 哨兵。
 *
 * <p>推导规则（对应谓词 -> 域）：
 * <ul>
 *   <li>{@code k = v}  -> [v, v+1)</li>
 *   <li>{@code k > v}  -> [v+1, +inf)</li>
 *   <li>{@code k >= v} -> [v, +inf)</li>
 *   <li>{@code k < v}  -> (-inf, v)</li>
 *   <li>{@code k <= v} -> (-inf, v+1)</li>
 *   <li>{@code k != v} -> (-inf, v) ∪ [v+1, +inf)</li>
 *   <li>{@code k IN (v1..vn)} -> 每个 vi 的单点区间 [vi, vi+1) 的并集</li>
 * </ul>
 * 多个谓词之间取交集（AND 语义）。域与分区范围求交得到命中的分区集合。
 */
public final class KeyDomain {

    /** 半开区间 [lo, hi)。 */
    public record Interval(long lo, long hi) {
        boolean overlaps(long otherLo, long otherHi) {
            return lo < otherHi && otherLo < hi;
        }
    }

    private static final KeyDomain FULL = new KeyDomain(List.of(new Interval(Long.MIN_VALUE, Long.MAX_VALUE)));
    private static final KeyDomain EMPTY = new KeyDomain(List.of());

    private final List<Interval> intervals;

    private KeyDomain(List<Interval> intervals) {
        this.intervals = intervals;
    }

    public static KeyDomain full() {
        return FULL;
    }

    public static KeyDomain empty() {
        return EMPTY;
    }

    public static KeyDomain ofIntervals(List<Interval> intervals) {
        return new KeyDomain(normalize(intervals));
    }

    public static KeyDomain singleton(long v) {
        if (v == Long.MAX_VALUE) {
            return EMPTY;
        }
        return new KeyDomain(List.of(new Interval(v, v + 1)));
    }

    public static KeyDomain from(long lo, long hi) {
        if (lo >= hi) {
            return EMPTY;
        }
        return new KeyDomain(List.of(new Interval(lo, hi)));
    }

    /** 从键-值谓词推导域。 */
    public static KeyDomain fromPredicate(Predicate.Op op, long value) {
        return switch (op) {
            case EQ -> singleton(value);
            case NE -> value == Long.MAX_VALUE ? FULL : union(from(Long.MIN_VALUE, value), from(safeInc(value), Long.MAX_VALUE));
            case LT -> from(Long.MIN_VALUE, value);
            case LE -> value == Long.MAX_VALUE ? FULL : from(Long.MIN_VALUE, value + 1);
            case GT -> value == Long.MAX_VALUE ? EMPTY : from(value + 1, Long.MAX_VALUE);
            case GE -> from(value, Long.MAX_VALUE);
        };
    }

    /** 从 IN 谓词推导域：各单点区间的并集。 */
    public static KeyDomain fromIn(List<Long> values) {
        List<Interval> parts = new ArrayList<>(values.size());
        for (long v : values) {
            if (v != Long.MAX_VALUE) {
                parts.add(new Interval(v, v + 1));
            }
        }
        return new KeyDomain(normalize(parts));
    }

    private static long safeInc(long v) {
        return v == Long.MAX_VALUE ? Long.MAX_VALUE : v + 1;
    }

    public static KeyDomain union(KeyDomain a, KeyDomain b) {
        List<Interval> all = new ArrayList<>(a.intervals.size() + b.intervals.size());
        all.addAll(a.intervals);
        all.addAll(b.intervals);
        return new KeyDomain(normalize(all));
    }

    /** AND 语义：两域求交。 */
    public KeyDomain intersect(KeyDomain other) {
        List<Interval> out = new ArrayList<>();
        for (Interval x : intervals) {
            for (Interval y : other.intervals) {
                long lo = Math.max(x.lo(), y.lo());
                long hi = Math.min(x.hi(), y.hi());
                if (lo < hi) {
                    out.add(new Interval(lo, hi));
                }
            }
        }
        return new KeyDomain(normalize(out));
    }

    public boolean isEmpty() {
        return intervals.isEmpty();
    }

    public boolean isFull() {
        return intervals.size() == 1
                && intervals.get(0).lo() == Long.MIN_VALUE
                && intervals.get(0).hi() == Long.MAX_VALUE;
    }

    public List<Interval> intervals() {
        return intervals;
    }

    /** 域与分区范围 [lo, hi) 是否有交集。 */
    public boolean overlaps(long lo, long hi) {
        for (Interval iv : intervals) {
            if (iv.overlaps(lo, hi)) {
                return true;
            }
        }
        return false;
    }

    /** 排序并合并相邻/重叠区间，保证内部表示规范化。 */
    private static List<Interval> normalize(List<Interval> raw) {
        List<Interval> sorted = new ArrayList<>(raw.size());
        for (Interval iv : raw) {
            if (iv.lo() < iv.hi()) {
                sorted.add(iv);
            }
        }
        sorted.sort((a, b) -> a.lo() != b.lo() ? Long.compare(a.lo(), b.lo()) : Long.compare(a.hi(), b.hi()));
        List<Interval> merged = new ArrayList<>(sorted.size());
        for (Interval iv : sorted) {
            if (!merged.isEmpty()) {
                Interval last = merged.get(merged.size() - 1);
                if (iv.lo() <= last.hi()) {
                    merged.set(merged.size() - 1, new Interval(last.lo(), Math.max(last.hi(), iv.hi())));
                    continue;
                }
            }
            merged.add(iv);
        }
        return List.copyOf(merged);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof KeyDomain kd && intervals.equals(kd.intervals);
    }

    @Override
    public int hashCode() {
        return intervals.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < intervals.size(); i++) {
            if (i > 0) {
                sb.append(" ∪ ");
            }
            Interval iv = intervals.get(i);
            sb.append('[')
                    .append(iv.lo() == Long.MIN_VALUE ? "-inf" : iv.lo())
                    .append(", ")
                    .append(iv.hi() == Long.MAX_VALUE ? "+inf" : iv.hi())
                    .append(')');
        }
        return intervals.isEmpty() ? "∅" : sb.toString();
    }

    static KeyDomain fromValues(long... values) {
        return fromIn(Arrays.stream(values).boxed().toList());
    }
}
