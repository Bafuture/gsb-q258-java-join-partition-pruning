package com.example.gsb.join.prune;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.gsb.join.TestSupport;
import com.example.gsb.join.exec.JoinResult;
import com.example.gsb.join.exec.PartitionJoinExecutor;
import com.example.gsb.join.model.Partition;
import com.example.gsb.join.model.PartitionedTable;
import com.example.gsb.join.model.Side;
import com.example.gsb.join.predicate.ColumnComparison;
import com.example.gsb.join.predicate.ColumnValue;
import com.example.gsb.join.predicate.InList;
import com.example.gsb.join.predicate.JoinCondition;
import com.example.gsb.join.predicate.Op;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 分区剪枝：分区对推导、剪枝前后数量对比与兜底路径。
 */
class PartitionPrunerTest {

    private static final int PARTITIONS = 4;
    private static final long WIDTH = 10;

    private final PartitionPruner pruner = new PartitionPruner();

    private PartitionedTable table(String name) {
        return TestSupport.table(name, PARTITIONS, WIDTH,
                TestSupport.rows(new long[] {0, 0}, new long[] {15, 1}, new long[] {25, 2}, new long[] {35, 3}));
    }

    @Test
    void equiJoinOnPartitionKeyKeepsOnlyDiagonalPairs() {
        PartitionedTable left = table("L");
        PartitionedTable right = table("R");
        JoinCondition condition = JoinCondition.of(ColumnComparison.of("pk", Op.EQ, "pk"));

        PrunePlan plan = pruner.plan(left, right, condition);

        assertThat(plan.status()).isEqualTo(PruneStatus.PRUNED);
        assertThat(plan.totalPairs()).isEqualTo(16);
        assertThat(plan.participatingPairs()).isEqualTo(4);
        assertThat(plan.pruneRate()).isEqualTo(0.75);
        assertThat(plan.pairs()).containsExactly(
                new PartitionPair(0, 0), new PartitionPair(1, 1),
                new PartitionPair(2, 2), new PartitionPair(3, 3));
    }

    @Test
    void inListFilterPrunesToHitPartitions() {
        PartitionedTable left = table("L");
        PartitionedTable right = table("R");
        JoinCondition condition = JoinCondition.of(
                ColumnComparison.of("pk", Op.EQ, "pk"),
                InList.of(Side.LEFT, "pk", 5, 25));

        PrunePlan plan = pruner.plan(left, right, condition);

        assertThat(plan.status()).isEqualTo(PruneStatus.PRUNED);
        assertThat(plan.totalPairs()).isEqualTo(16);
        assertThat(plan.participatingPairs()).isEqualTo(2);
        assertThat(plan.pairs()).containsExactly(new PartitionPair(0, 0), new PartitionPair(2, 2));
    }

    @Test
    void rangeFiltersOnBothSidesIntersectDomains() {
        PartitionedTable left = table("L");
        PartitionedTable right = table("R");
        JoinCondition condition = JoinCondition.of(
                ColumnComparison.of("pk", Op.EQ, "pk"),
                ColumnValue.of(Side.LEFT, "pk", Op.GE, 15),
                ColumnValue.of(Side.RIGHT, "pk", Op.LT, 30));

        PrunePlan plan = pruner.plan(left, right, condition);

        // 左 [15,+∞) 交 右 (-∞,29] = [15,29]，落在分区 1、2
        assertThat(plan.participatingPairs()).isEqualTo(2);
        assertThat(plan.pairs()).containsExactly(new PartitionPair(1, 1), new PartitionPair(2, 2));
    }

    @Test
    void emptyInListPrunesAwayEveryPair() {
        PartitionedTable left = table("L");
        PartitionedTable right = table("R");
        JoinCondition condition = JoinCondition.of(
                ColumnComparison.of("pk", Op.EQ, "pk"),
                new InList(Side.LEFT, "pk", List.of()));

        PrunePlan plan = pruner.plan(left, right, condition);

        assertThat(plan.status()).isEqualTo(PruneStatus.PRUNED);
        assertThat(plan.participatingPairs()).isZero();
        assertThat(plan.pruneRate()).isEqualTo(1.0);
    }

    @Test
    void singleSidedPartitionFilterWithoutEquiJoinPrunesCartesianSide() {
        PartitionedTable left = table("L");
        PartitionedTable right = table("R");
        JoinCondition condition = JoinCondition.of(ColumnValue.of(Side.LEFT, "pk", Op.EQ, 5));

        PrunePlan plan = pruner.plan(left, right, condition);

        assertThat(plan.status()).isEqualTo(PruneStatus.PRUNED);
        // 左只留分区 0，右无法约束 -> 1 x 4 个分区对
        assertThat(plan.totalPairs()).isEqualTo(16);
        assertThat(plan.participatingPairs()).isEqualTo(4);
        assertThat(plan.pairs()).containsExactly(
                new PartitionPair(0, 0), new PartitionPair(0, 1),
                new PartitionPair(0, 2), new PartitionPair(0, 3));
        assertThat(plan.notes()).extracting(PruneNote::code)
                .contains(PruneNote.Code.PARTIAL_SIDE_RESTRICTION);
    }

    @Test
    void nonPartitionKeyPredicateDoesNotBlockEquiJoinPruning() {
        PartitionedTable left = table("L");
        PartitionedTable right = table("R");
        JoinCondition condition = JoinCondition.of(
                ColumnComparison.of("pk", Op.EQ, "pk"),
                ColumnComparison.of("v", Op.EQ, "v"),
                ColumnValue.of(Side.LEFT, "v", Op.GE, 2));

        PrunePlan plan = pruner.plan(left, right, condition);

        assertThat(plan.status()).isEqualTo(PruneStatus.PRUNED);
        assertThat(plan.participatingPairs()).isEqualTo(4);
        assertThat(plan.notes()).extracting(PruneNote::code)
                .containsOnly(PruneNote.Code.NON_PARTITION_KEY_PREDICATE);
    }

    @Test
    void fallsBackWhenNoPartitionKeyPredicate() {
        PartitionedTable left = table("L");
        PartitionedTable right = table("R");
        JoinCondition condition = JoinCondition.of(ColumnComparison.of("v", Op.EQ, "v"));

        PrunePlan plan = pruner.plan(left, right, condition);

        assertFullScanFallback(plan);
        assertThat(plan.fallbackReasons()).extracting(PruneNote::code)
                .contains(PruneNote.Code.NO_PARTITION_KEY_PREDICATE);
    }

    @Test
    void fallsBackOnNonEquiPartitionKeyJoin() {
        PartitionedTable left = table("L");
        PartitionedTable right = table("R");
        JoinCondition condition = JoinCondition.of(ColumnComparison.of("pk", Op.GT, "pk"));

        PrunePlan plan = pruner.plan(left, right, condition);

        assertFullScanFallback(plan);
        assertThat(plan.fallbackReasons()).extracting(PruneNote::code)
                .contains(PruneNote.Code.NON_EQUI_PARTITION_KEY_JOIN);
    }

    @Test
    void fallsBackOnNotEqualPartitionKeyOperator() {
        PartitionedTable left = table("L");
        PartitionedTable right = table("R");
        JoinCondition condition = JoinCondition.of(ColumnValue.of(Side.LEFT, "pk", Op.NE, 10));

        PrunePlan plan = pruner.plan(left, right, condition);

        assertFullScanFallback(plan);
        assertThat(plan.fallbackReasons()).extracting(PruneNote::code)
                .contains(PruneNote.Code.UNSUPPORTED_PARTITION_KEY_OPERATOR);
    }

    @Test
    void prunedPartitionsAreNeverScanned() {
        PartitionedTable left = table("L");
        PartitionedTable right = table("R");
        JoinCondition condition = JoinCondition.of(
                ColumnComparison.of("pk", Op.EQ, "pk"),
                InList.of(Side.LEFT, "pk", 25));

        JoinResult result = new PartitionJoinExecutor(2).join(left, right, condition);

        Set<Integer> usedLeft = result.prunePlan().pairs().stream()
                .map(PartitionPair::leftIndex).collect(Collectors.toSet());
        Set<Integer> usedRight = result.prunePlan().pairs().stream()
                .map(PartitionPair::rightIndex).collect(Collectors.toSet());
        for (Partition p : left.partitions()) {
            if (!usedLeft.contains(p.index())) {
                assertThat(p.scanCount())
                        .as("被剪枝的左分区 %d 不应被扫描", p.index()).isZero();
            }
        }
        for (Partition p : right.partitions()) {
            if (!usedRight.contains(p.index())) {
                assertThat(p.scanCount())
                        .as("被剪枝的右分区 %d 不应被扫描", p.index()).isZero();
            }
        }
    }

    @Test
    void summaryReportsBeforeAndAfterPairCounts() {
        PartitionedTable left = table("L");
        PartitionedTable right = table("R");
        PrunePlan plan = pruner.plan(left, right,
                JoinCondition.of(ColumnComparison.of("pk", Op.EQ, "pk")));

        String summary = plan.summary();
        assertThat(summary).contains("分区对总数=16", "参与=4", "剪枝率=75.0%");
    }

    private static void assertFullScanFallback(PrunePlan plan) {
        assertThat(plan.status()).isEqualTo(PruneStatus.FULL_SCAN_FALLBACK);
        assertThat(plan.participatingPairs()).isEqualTo(16);
        assertThat(plan.pruneRate()).isEqualTo(0.0);
        assertThat(plan.fallbackReasons()).isNotEmpty();
    }
}
