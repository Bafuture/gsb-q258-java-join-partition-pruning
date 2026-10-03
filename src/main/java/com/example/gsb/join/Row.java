package com.example.gsb.join;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一行数据：列名 -> 值。键列必须能转为 long（分区与边界推导均基于长整型键）。
 */
public final class Row {

    private final Map<String, Object> values;

    private Row(Map<String, Object> values) {
        this.values = values;
    }

    public static Row of(Map<String, Object> values) {
        return new Row(new LinkedHashMap<>(values));
    }

    public static Row of(Object... kv) {
        if ((kv.length & 1) != 0) {
            throw new IllegalArgumentException("列名/值必须成对出现");
        }
        Map<String, Object> m = new LinkedHashMap<>(kv.length / 2);
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return new Row(m);
    }

    public Object get(String column) {
        return values.get(column);
    }

    public long getLong(String column) {
        Object v = values.get(column);
        if (v instanceof Number n) {
            return n.longValue();
        }
        throw new IllegalStateException("列 " + column + " 不是整型，实际值: " + v);
    }

    public Map<String, Object> asMap() {
        return new LinkedHashMap<>(values);
    }

    /** 连接结果行：左表列加 L_ 前缀，右表列加 R_ 前缀。 */
    public static Row merged(Row left, Row right) {
        Map<String, Object> m = new LinkedHashMap<>();
        left.values.forEach((k, v) -> m.put("L_" + k, v));
        right.values.forEach((k, v) -> m.put("R_" + k, v));
        return new Row(m);
    }
}
