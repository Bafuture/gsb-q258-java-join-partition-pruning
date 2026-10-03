package com.example.gsb.join;

import java.util.List;

/**
 * 连接条件（AND 语义下的单个谓词）。
 *
 * <p>按形态分为四类：
 * <ul>
 *   <li>{@link KeyValue}：某侧列与常量比较（等值/范围/不等），若该列是分区键则可推导分区范围；</li>
 *   <li>{@link KeyIn}：某侧列 IN 常量集合，若为分区键则可推导分区范围；</li>
 *   <li>{@link KeyKey}：两侧列之间的比较（等值/范围/不等），若两列都是分区键则可推导分区对；</li>
 *   <li>{@link Custom}：任意自定义条件，剪枝器无法分析，触发全分区兜底。</li>
 * </ul>
 * 其余情况（如引用非分区键列的谓词）作为残余条件在连接时逐行评估，不影响剪枝正确性。
 */
public sealed interface Predicate {

    enum Op {EQ, NE, LT, LE, GT, GE}

    enum Side {LEFT, RIGHT}

    /** 谓词中对某一侧某一列的引用。 */
    record Ref(Side side, String column) {
        public static Ref left(String column) {
            return new Ref(Side.LEFT, column);
        }

        public static Ref right(String column) {
            return new Ref(Side.RIGHT, column);
        }
    }

    /** 在连接出的左右行对上评估该谓词。 */
    boolean test(Row left, Row right);

    static Constant alwaysTrue() {
        return new Constant(true);
    }

    static Constant alwaysFalse() {
        return new Constant(false);
    }

    static KeyValue eq(Ref ref, long value) {
        return new KeyValue(ref, Op.EQ, value);
    }

    static KeyValue ne(Ref ref, long value) {
        return new KeyValue(ref, Op.NE, value);
    }

    static KeyValue lt(Ref ref, long value) {
        return new KeyValue(ref, Op.LT, value);
    }

    static KeyValue le(Ref ref, long value) {
        return new KeyValue(ref, Op.LE, value);
    }

    static KeyValue gt(Ref ref, long value) {
        return new KeyValue(ref, Op.GT, value);
    }

    static KeyValue ge(Ref ref, long value) {
        return new KeyValue(ref, Op.GE, value);
    }

    static KeyIn in(Ref ref, long... values) {
        return new KeyIn(ref, List.of(toBoxed(values)));
    }

    static KeyKey eq(Ref left, Ref right) {
        return new KeyKey(left, right, Op.EQ);
    }

    static KeyKey ne(Ref left, Ref right) {
        return new KeyKey(left, right, Op.NE);
    }

    static KeyKey lt(Ref left, Ref right) {
        return new KeyKey(left, right, Op.LT);
    }

    static KeyKey le(Ref left, Ref right) {
        return new KeyKey(left, right, Op.LE);
    }

    static KeyKey gt(Ref left, Ref right) {
        return new KeyKey(left, right, Op.GT);
    }

    static KeyKey ge(Ref left, Ref right) {
        return new KeyKey(left, right, Op.GE);
    }

    static Custom custom(String description, java.util.function.BiPredicate<Row, Row> fn) {
        return new Custom(description, fn);
    }

    private static Long[] toBoxed(long[] values) {
        Long[] out = new Long[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[i];
        }
        return out;
    }

    static boolean compare(Op op, long a, long b) {
        return switch (op) {
            case EQ -> a == b;
            case NE -> a != b;
            case LT -> a < b;
            case LE -> a <= b;
            case GT -> a > b;
            case GE -> a >= b;
        };
    }

    /** 常量谓词（恒真/恒假）。 */
    record Constant(boolean value) implements Predicate {
        @Override
        public boolean test(Row left, Row right) {
            return value;
        }
    }

    /** 某侧列与常量比较。 */
    record KeyValue(Ref ref, Op op, long value) implements Predicate {
        @Override
        public boolean test(Row left, Row right) {
            Row row = ref.side() == Side.LEFT ? left : right;
            Object v = row.get(ref.column());
            return v instanceof Number n && compare(op, n.longValue(), value);
        }
    }

    /** 某侧列 IN 常量集合。 */
    record KeyIn(Ref ref, List<Long> values) implements Predicate {
        @Override
        public boolean test(Row left, Row right) {
            Row row = ref.side() == Side.LEFT ? left : right;
            Object v = row.get(ref.column());
            return v instanceof Number n && values.contains(n.longValue());
        }
    }

    /** 两侧列之间的比较。 */
    record KeyKey(Ref left, Ref right, Op op) implements Predicate {
        public KeyKey {
            if (left.side() != Side.LEFT || right.side() != Side.RIGHT) {
                throw new IllegalArgumentException("KeyKey 的左操作数必须引用左表，右操作数必须引用右表");
            }
        }

        @Override
        public boolean test(Row leftRow, Row rightRow) {
            Object lv = leftRow.get(left.column());
            Object rv = rightRow.get(right.column());
            if (!(lv instanceof Number ln) || !(rv instanceof Number rn)) {
                return false;
            }
            return compare(op, ln.longValue(), rn.longValue());
        }
    }

    /** 自定义谓词：剪枝器无法分析，触发全分区兜底。 */
    record Custom(String description, java.util.function.BiPredicate<Row, Row> fn) implements Predicate {
        @Override
        public boolean test(Row left, Row right) {
            return fn.test(left, right);
        }
    }
}
