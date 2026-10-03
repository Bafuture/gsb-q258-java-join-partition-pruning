package com.example.gsb.join.domain;

import com.example.gsb.join.model.Partition;
import com.example.gsb.join.predicate.Op;
import java.util.ArrayList;
import java.util.List;

/**
 * 分区键的取值域：由一组互不相交、首尾不相接、升序排列的闭区间表示。
 *
 * <p>谓词到取值域的推导规则（均为闭区间语义）：
 * <ul>
 *   <li>{@code pk = v}  → 单点域 {@code [v, v]}</li>
 *   <li>{@code pk > v}  → {@code [v+1, MAX]}；{@code pk >= v} → {@code [v, MAX]}</li>
 *   <li>{@code pk < v}  → {@code [MIN, v-1]}；{@code pk <= v} → {@code [MIN, v]}</li>
 *   <li>{@code pk IN (v1..vn)} → 各单点域的并集；空 IN 列表 → 空域</li>
 *   <li>{@code pk <> v}  → 无法剪枝（值域几乎不变），记为不可推导</li>
 * </ul>
 * 多个谓词之间是 AND 关系，对应取值域求交集；空域表示没有任何分区能产出行。
 */
public final class KeyDomain {

    public static final KeyDomain ALL = new KeyDomain(List.of(new Interval(Long.MIN_VALUE, Long.MAX_VALUE)));
    public static final KeyDomain EMPTY = new KeyDomain(List.of());

    private final List<Interval> intervals;

    private KeyDomain(List<Interval> intervals) {
        this.intervals = intervals;
    }

    public static KeyDomain of(Interval interval) {
        return new KeyDomain(List.of(interval));
    }

    /** 从单个谓词推导取值域；无法推导时返回 {@code null}。 */
    public static KeyDomain fromComparison(Op op, long value) {
        return switch (op) {
            case EQ -> of(new Interval(value, value));
            case GT -> value == Long.MAX_VALUE ? EMPTY : of(new Interval(value + 1, Long.MAX_VALUE));
            case GE -> of(new Interval(value, Long.MAX_VALUE));
            case LT -> value == Long.MIN_VALUE ? EMPTY : of(new Interval(Long.MIN_VALUE, value - 1));
            case LE -> of(new Interval(Long.MIN_VALUE, value));
            case NE -> null;
        };
    }

    /** 从 IN 列表推导取值域：每个值一个单点区间，合并相邻点。 */
    public static KeyDomain fromInList(List<Long> values) {
        List<Interval> points = new ArrayList<>(values.size());
        for (long v : values) {
            points.add(new Interval(v, v));
        }
        return new KeyDomain(normalize(points));
    }

    public List<Interval> intervals() {
        return intervals;
    }

    public boolean isEmpty() {
        return intervals.isEmpty();
    }

    /** 两个取值域求交集（AND 语义）。 */
    public KeyDomain intersect(KeyDomain other) {
        List<Interval> result = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < intervals.size() && j < other.intervals.size()) {
            Interval a = intervals.get(i);
            Interval b = other.intervals.get(j);
            long lo = Math.max(a.lo(), b.lo());
            long hi = Math.min(a.hi(), b.hi());
            if (lo <= hi) {
                result.add(new Interval(lo, hi));
            }
            if (a.hi() < b.hi()) {
                i++;
            } else {
                j++;
            }
        }
        return new KeyDomain(result);
    }

    /** 判断取值域是否与分区区间 {@code [lower, upper)} 有交集。 */
    public boolean intersects(Partition partition) {
        for (Interval interval : intervals) {
            if (interval.lo() < partition.upper() && interval.hi() >= partition.lower()) {
                return true;
            }
        }
        return false;
    }

    private static List<Interval> normalize(List<Interval> raw) {
        List<Interval> sorted = new ArrayList<>(raw);
        sorted.sort((a, b) -> Long.compare(a.lo(), b.lo()));
        List<Interval> merged = new ArrayList<>();
        for (Interval interval : sorted) {
            if (merged.isEmpty()) {
                merged.add(interval);
                continue;
            }
            Interval last = merged.get(merged.size() - 1);
            boolean overlaps = interval.lo() <= last.hi();
            boolean adjacent = last.hi() != Long.MAX_VALUE && interval.lo() == last.hi() + 1;
            if (overlaps || adjacent) {
                merged.set(merged.size() - 1, new Interval(last.lo(), Math.max(last.hi(), interval.hi())));
            } else {
                merged.add(interval);
            }
        }
        return merged;
    }

    @Override
    public String toString() {
        if (intervals.isEmpty()) {
            return "∅";
        }
        StringBuilder sb = new StringBuilder();
        for (Interval interval : intervals) {
            if (sb.length() > 0) {
                sb.append(" ∪ ");
            }
            sb.append('[').append(interval.lo()).append(',').append(interval.hi()).append(']');
        }
        return sb.toString();
    }

    /** 闭区间 [lo, hi]。 */
    public record Interval(long lo, long hi) {
        public Interval {
            if (lo > hi) {
                throw new IllegalArgumentException("区间下界不能大于上界: " + lo + " > " + hi);
            }
        }
    }
}
