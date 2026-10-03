package com.example.gsb.join.predicate;

import com.example.gsb.join.model.Row;

/**
 * 跨表列比较：{@code leftColumn OP rightColumn}，例如 {@code l.pk = r.pk}
 * 或非分区键的 {@code l.region_id = r.region_id}。
 */
public record ColumnComparison(String leftColumn, Op op, String rightColumn) implements Predicate {

    public ColumnComparison {
        if (leftColumn == null || rightColumn == null || op == null) {
            throw new IllegalArgumentException("列名与运算符不能为空");
        }
    }

    public static ColumnComparison of(String leftColumn, Op op, String rightColumn) {
        return new ColumnComparison(leftColumn, op, rightColumn);
    }

    @Override
    public boolean test(Row left, Row right) {
        return op.test(left.getLong(leftColumn), right.getLong(rightColumn));
    }

    @Override
    public String toString() {
        return "L." + leftColumn + " " + op.symbol() + " R." + rightColumn;
    }
}
