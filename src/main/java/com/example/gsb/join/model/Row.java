package com.example.gsb.join.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 一行数据：列名 -> 值。
 *
 * <p>分区键列以及参与等值/范围/IN 条件的列必须是 {@link Long} 类型；
 * 其它列可以是任意类型，仅随结果透传。
 */
public final class Row {

    private final long id;
    private final Map<String, Object> columns;

    public Row(long id, Map<String, Object> columns) {
        this.id = id;
        this.columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns));
    }

    /** 行在所属表内的唯一标识，便于测试与结果比对。 */
    public long id() {
        return id;
    }

    public Object get(String column) {
        return columns.get(column);
    }

    public long getLong(String column) {
        Object value = columns.get(column);
        if (!(value instanceof Long longValue)) {
            throw new IllegalStateException("列 " + column + " 不是 bigint 类型: " + value);
        }
        return longValue;
    }

    public Map<String, Object> columns() {
        return columns;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Row row)) {
            return false;
        }
        return id == row.id && Objects.equals(columns, row.columns);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, columns);
    }

    @Override
    public String toString() {
        return "Row#" + id + columns;
    }
}
