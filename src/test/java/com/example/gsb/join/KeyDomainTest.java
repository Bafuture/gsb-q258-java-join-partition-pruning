package com.example.gsb.join;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.gsb.join.domain.KeyDomain;
import com.example.gsb.join.model.Partition;
import com.example.gsb.join.predicate.Op;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 三类条件（等值 / 范围 / IN）到分区键取值域的推导规则测试。
 */
class KeyDomainTest {

    @Test
    void equalityDerivesSinglePointDomain() {
        KeyDomain domain = KeyDomain.fromComparison(Op.EQ, 42);
        assertThat(domain.intervals()).containsExactly(new KeyDomain.Interval(42, 42));
    }

    @Test
    void rangeComparisonsDeriveHalfOpenBounds() {
        assertThat(KeyDomain.fromComparison(Op.GT, 10).intervals())
                .containsExactly(new KeyDomain.Interval(11, Long.MAX_VALUE));
        assertThat(KeyDomain.fromComparison(Op.GE, 10).intervals())
                .containsExactly(new KeyDomain.Interval(10, Long.MAX_VALUE));
        assertThat(KeyDomain.fromComparison(Op.LT, 10).intervals())
                .containsExactly(new KeyDomain.Interval(Long.MIN_VALUE, 9));
        assertThat(KeyDomain.fromComparison(Op.LE, 10).intervals())
                .containsExactly(new KeyDomain.Interval(Long.MIN_VALUE, 10));
    }

    @Test
    void rangeBoundaryOverflowYieldsEmptyDomain() {
        assertThat(KeyDomain.fromComparison(Op.GT, Long.MAX_VALUE).isEmpty()).isTrue();
        assertThat(KeyDomain.fromComparison(Op.LT, Long.MIN_VALUE).isEmpty()).isTrue();
    }

    @Test
    void notEqualCannotBeDerived() {
        assertThat(KeyDomain.fromComparison(Op.NE, 10)).isNull();
    }

    @Test
    void inListDerivesUnionOfPointsAndMergesAdjacent() {
        KeyDomain domain = KeyDomain.fromInList(List.of(1L, 2L, 3L, 7L, 9L));
        assertThat(domain.intervals()).containsExactly(
                new KeyDomain.Interval(1, 3),
                new KeyDomain.Interval(7, 7),
                new KeyDomain.Interval(9, 9));
    }

    @Test
    void emptyInListDerivesEmptyDomain() {
        assertThat(KeyDomain.fromInList(List.of()).isEmpty()).isTrue();
    }

    @Test
    void intersectComputesAndSemantics() {
        KeyDomain a = KeyDomain.fromComparison(Op.GE, 0).intersect(KeyDomain.fromComparison(Op.LE, 10));
        assertThat(a.intervals()).containsExactly(new KeyDomain.Interval(0, 10));

        KeyDomain b = a.intersect(KeyDomain.fromInList(List.of(3L, 7L, 100L)));
        assertThat(b.intervals()).containsExactly(
                new KeyDomain.Interval(3, 3), new KeyDomain.Interval(7, 7));

        KeyDomain disjoint = KeyDomain.fromComparison(Op.EQ, 1).intersect(KeyDomain.fromComparison(Op.EQ, 2));
        assertThat(disjoint.isEmpty()).isTrue();
    }

    @Test
    void intersectsPartitionUsesHalfOpenPartitionRange() {
        Partition p0 = new Partition(0, 0, 10, List.of());
        Partition p1 = new Partition(1, 10, 20, List.of());

        assertThat(KeyDomain.fromComparison(Op.EQ, 5).intersects(p0)).isTrue();
        assertThat(KeyDomain.fromComparison(Op.EQ, 5).intersects(p1)).isFalse();
        // 边界值 10 属于 [10,20) 而不是 [0,10)
        assertThat(KeyDomain.fromComparison(Op.EQ, 10).intersects(p0)).isFalse();
        assertThat(KeyDomain.fromComparison(Op.EQ, 10).intersects(p1)).isTrue();
        assertThat(KeyDomain.EMPTY.intersects(p0)).isFalse();
    }
}
