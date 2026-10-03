package com.example.gsb.join.predicate;

import com.example.gsb.join.model.Row;
import com.example.gsb.join.model.Side;
import java.util.List;

/**
 * 单侧 IN 谓词：{@code side.column IN (v1, v2, ...)}。
 *
 * <p>IN 列表允许为空，此时结果域为空域（没有任何分区能产出行）。
 */
public record InList(Side side, String column, List<Long> values) implements Predicate {

 public InList {
        if (side == null || column == null || values == null) {
            throw new IllegalArgumentException("侧、列名与值列表不能为空");
        }
        values = List.copyOf(values);
    }

    public static InList of(Side side, String column, long... values) {
        return new InList(side, column, java.util.Arrays.stream(values).boxed().toList());
    }

    @Override
    public boolean test(Row left, Row right) {
        Row row = side == Side.LEFT ? left : right;
        long actual = row.getLong(column);
        for (Long v : values) {
            if (actual == v) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return side + "." + column + " IN " + values;
    }
}
