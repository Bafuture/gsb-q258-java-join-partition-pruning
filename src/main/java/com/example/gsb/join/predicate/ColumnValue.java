package com.example.gsb.join.predicate;

import com.example.gsb.join.model.Row;
import com.example.gsb.join.model.Side;

/**
 * 单侧列与常量的比较：{@code side.column OP value}，例如 {@code L.dt >= 10}。
 */
public record ColumnValue(Side side, String column, Op op, long value) implements Predicate {

    public ColumnValue {
        if (side == null || column == null || op == null) {
            throw new IllegalArgumentException("侧、列名与运算符不能为空");
        }
    }

    public static ColumnValue of(Side side, String column, Op op, long value) {
        return new ColumnValue(side, column, op, value);
    }

    @Override
    public boolean test(Row left, Row right) {
        Row row = side == Side.LEFT ? left : right;
        return op.test(row.getLong(column), value);
    }

    @Override
    public String toString() {
        return side + "." + column + " " + op.symbol() + " " + value;
    }
}
