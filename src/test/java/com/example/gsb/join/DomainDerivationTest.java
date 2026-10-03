package com.example.gsb.join;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 等值 / 范围 / IN 三类条件的分区边界推导规则测试。
 * 分区方案固定为 4 个等宽分区：[0,10) [10,20) [20,30) [30,+inf)。
 */
class DomainDerivationTest {

    private static final PartitionScheme SCHEME = PartitionScheme.equalWidth("k", 0, 10, 4);

    private final PartitionPruner pruner = new PartitionPruner();

    private PartitionedTable emptyTable(String name) {
        return PartitionedTable.builder(name, SCHEME).build();
    }

    private List<PartitionPruner.PairPlan> plans(List<Predicate> predicates) {
        return pruner.prune(emptyTable("L"), emptyTable("R"), predicates).pairs();
    }

    @Test
    void equalityDerivesSinglePartition() {
        // k = 15 -> [15,16)，只可能落在分区 1
        KeyDomain d = KeyDomain.fromPredicate(Predicate.Op.EQ, 15);
        assertThat(d.intervals()).containsExactly(new KeyDomain.Interval(15, 16));
        assertThat(SCHEME.partitionsIntersecting(15, 16)).containsExactly(1);

        // 单侧等值：左分区 1 与右表所有分区配对
        List<PartitionPruner.PairPlan> onlyLeft = plans(List.of(
                Predicate.eq(Predicate.Ref.left("k"), 15)));
        assertThat(onlyLeft).extracting(PartitionPruner.PairPlan::leftPartition)
                .containsOnly(1);
        assertThat(onlyLeft).extracting(PartitionPruner.PairPlan::rightPartition)
                .containsExactly(0, 1, 2, 3);

        // 两侧等值（连接条件含分区键等值）：只剩 (1,1)
        List<PartitionPruner.PairPlan> both = plans(List.of(
                Predicate.eq(Predicate.Ref.left("k"), 15),
                Predicate.eq(Predicate.Ref.right("k"), 15)));
        assertThat(both).containsExactly(new PartitionPruner.PairPlan(1, 1));
    }

    @Test
    void rangePredicatesIntersectIntoOnePartition() {
        // k >= 20 -> [20,+inf)；k < 30 -> (-inf,30)；交集 [20,30) -> 分区 2
        KeyDomain d = KeyDomain.fromPredicate(Predicate.Op.GE, 20)
                .intersect(KeyDomain.fromPredicate(Predicate.Op.LT, 30));
        assertThat(d.intervals()).containsExactly(new KeyDomain.Interval(20, 30));
        assertThat(SCHEME.partitionsIntersecting(20, 30)).containsExactly(2);

        List<PartitionPruner.PairPlan> plans = plans(List.of(
                Predicate.ge(Predicate.Ref.left("k"), 20),
                Predicate.lt(Predicate.Ref.left("k"), 30)));
        assertThat(plans).extracting(PartitionPruner.PairPlan::leftPartition).containsOnly(2);

        // k > 5 与 k <= 13 -> [6,14)，横跨分区 0 与分区 1
        KeyDomain cross = KeyDomain.fromPredicate(Predicate.Op.GT, 5)
                .intersect(KeyDomain.fromPredicate(Predicate.Op.LE, 13));
        assertThat(cross.intervals()).containsExactly(new KeyDomain.Interval(6, 14));
        assertThat(SCHEME.partitionsIntersecting(6, 14)).containsExactly(0, 1);
    }

    @Test
    void inPredicateUnionsPointIntervals() {
        // k IN (5, 25, 35) -> [5,6) ∪ [25,26) ∪ [35,36) -> 分区 0, 2, 3
        KeyDomain d = KeyDomain.fromIn(List.of(5L, 25L, 35L));
        assertThat(d.intervals()).containsExactly(
                new KeyDomain.Interval(5, 6),
                new KeyDomain.Interval(25, 26),
                new KeyDomain.Interval(35, 36));
        assertThat(SCHEME.partitionsIntersecting(5, 6)).containsExactly(0);
        assertThat(SCHEME.partitionsIntersecting(25, 26)).containsExactly(2);
        assertThat(SCHEME.partitionsIntersecting(35, 36)).containsExactly(3);

        List<PartitionPruner.PairPlan> plans = plans(List.of(
                Predicate.in(Predicate.Ref.left("k"), 5, 25, 35)));
        assertThat(plans).extracting(PartitionPruner.PairPlan::leftPartition)
                .containsOnly(0, 2, 3);
    }

    @Test
    void inWithDuplicatesAndAdjacencyNormalizes() {
        KeyDomain d = KeyDomain.fromIn(List.of(3L, 4L, 3L, 8L));
        assertThat(d.intervals()).containsExactly(
                new KeyDomain.Interval(3, 5),
                new KeyDomain.Interval(8, 9));
    }

    @Test
    void notEqualExcludesOnlyPointButKeepsPartition() {
        // k != 15 -> (-inf,15) ∪ [16,+inf)；分区 1 仍有 [10,15) 和 [16,20) 两段，不能整区排除
        KeyDomain d = KeyDomain.fromPredicate(Predicate.Op.NE, 15);
        assertThat(d.overlaps(10, 20)).isTrue();
        assertThat(SCHEME.partitionsIntersecting(Long.MIN_VALUE, Long.MAX_VALUE)).containsExactly(0, 1, 2, 3);

        // k != 15 再与 k >= 15 交集 -> [16,+inf) -> 分区 1（[16,20) 段）、2、3
        KeyDomain refined = d.intersect(KeyDomain.fromPredicate(Predicate.Op.GE, 15));
        assertThat(refined.intervals()).containsExactly(new KeyDomain.Interval(16, Long.MAX_VALUE));
    }

    @Test
    void keyKeyComparisonsFilterPairFeasibility() {
        // 相同 4 分区方案，键-键比较只保留区间内可能满足比较的分区对
        List<PartitionPruner.PairPlan> eq = plans(List.of(
                Predicate.eq(Predicate.Ref.left("k"), Predicate.Ref.right("k"))));
        assertThat(eq).allSatisfy(p -> assertThat(p.leftPartition()).isEqualTo(p.rightPartition()))
                .hasSize(4);

        List<PartitionPruner.PairPlan> lt = plans(List.of(
                Predicate.lt(Predicate.Ref.left("k"), Predicate.Ref.right("k"))));
        assertThat(lt).allSatisfy(p -> assertThat(p.leftPartition()).isLessThanOrEqualTo(p.rightPartition()))
                .hasSize(10);

        List<PartitionPruner.PairPlan> gt = plans(List.of(
                Predicate.gt(Predicate.Ref.left("k"), Predicate.Ref.right("k"))));
        assertThat(gt).allSatisfy(p -> assertThat(p.leftPartition()).isGreaterThanOrEqualTo(p.rightPartition()))
                .hasSize(10);

        // NE 在非单点分区下无法排除任何分区对
        List<PartitionPruner.PairPlan> ne = plans(List.of(
                Predicate.ne(Predicate.Ref.left("k"), Predicate.Ref.right("k"))));
        assertThat(ne).hasSize(16);
    }

    @Test
    void contradictoryRangesProduceEmptyDomainAndNoPairs() {
        List<PartitionPruner.PairPlan> plans = plans(List.of(
                Predicate.gt(Predicate.Ref.left("k"), 50),
                Predicate.lt(Predicate.Ref.left("k"), 10)));
        assertThat(plans).isEmpty();
    }
}
