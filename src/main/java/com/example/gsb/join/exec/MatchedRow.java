package com.example.gsb.join.exec;

import com.example.gsb.join.model.Row;

/**
 * 一对匹配上的连接结果（左行, 右行）。
 */
public record MatchedRow(Row left, Row right) {

    /** 稳定的结果标识：左行 id × 右行 id，用于正确性比对。 */
    public String key() {
        return left.id() + "#" + right.id();
    }
}
