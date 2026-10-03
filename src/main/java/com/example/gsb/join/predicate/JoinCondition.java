package com.example.gsb.join.predicate;

import com.example.gsb.join.model.Row;
import java.util.Collections;
import java.util.List;

/**
 * 连接条件：若干原子谓词的 AND 合取。
 *
 * <p>允许同时包含分区键谓词（用于剪枝推导）与非分区键谓词
 * （无法剪枝分区，但仍在连接时逐行判定）。
 */
public final class JoinCondition {

    private final List<Predicate> predicates;

    public JoinCondition(List<Predicate> predicates) {
        this.predicates = List.copyOf(predicates);
    }

    public static JoinCondition of(Predicate... predicates) {
        return new JoinCondition(List.of(predicates));
    }

    public List<Predicate> predicates() {
        return Collections.unmodifiableList(predicates);
    }

    /** 一对行是否满足整个合取条件。 */
    public boolean test(Row left, Row right) {
        for (Predicate predicate : predicates) {
            if (!predicate.test(left, right)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return predicates.stream().map(Object::toString).reduce((a, b) -> a + " AND " + b).orElse("<empty>");
    }
}
