package com.example.gsb.join.predicate;

import com.example.gsb.join.model.Row;

/**
 * 连接条件中的一个原子谓词（AND 连接）。
 *
 * <p>三种实现：
 * <ul>
 *   <li>{@link ColumnComparison}：左表列 OP 右表列（连接谓词）</li>
 *   <li>{@link ColumnValue}：某一侧的列 OP 常量（过滤谓词）</li>
 *   <li>{@link InList}：某一侧的列 IN (常量列表)</li>
 * </ul>
 */
public sealed interface Predicate permits ColumnComparison, ColumnValue, InList {

    /** 在一对已经拼好的行上计算谓词是否成立。 */
    boolean test(Row left, Row right);
}
