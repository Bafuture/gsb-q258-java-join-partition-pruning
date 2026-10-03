package com.example.gsb.join.stats;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.gsb.join.TestSupport;
import com.example.gsb.join.exec.JoinResult;
import com.example.gsb.join.exec.PartitionJoinService;
import com.example.gsb.join.model.PartitionedTable;
import com.example.gsb.join.model.Side;
import com.example.gsb.join.predicate.ColumnComparison;
import com.example.gsb.join.predicate.ColumnValue;
import com.example.gsb.join.predicate.JoinCondition;
import com.example.gsb.join.predicate.Op;
import com.example.gsb.join.prune.PruneStatus;
import org.junit.jupiter.api.Test;

/**
 * 统计：分区对总数、参与数、剪枝率与各分区对的处理条数。
 */
class JoinStatsTest {

    /** 4 个分区，每个分区 2 行：pk = 10i 与 10i+5。 */
    private static PartitionedTable table(String name) {
        return TestSupport.table(name, 4, 10, TestSupport.rows(
                new long[] {0, 0}, new long[] {5, 0},
                new long[] {10, 1}, new long[] {15, 1},
                new long[] {20, 2}, new long[] {25, 2},
                new long[] {30, 3}, new long[] {35, 3}));
    }

    @Test
    void reportsPairCountsPruneRateAndPerPairRowCounts() {
        JoinCondition condition = JoinCondition.of(
                ColumnComparison.of("pk", Op.EQ, "pk"),
                ColumnValue.of(Side.LEFT, "pk", Op.GE, 10),
                ColumnValue.of(Side.LEFT, "pk", Op.LT, 30));

        JoinResult result = new PartitionJoinService(2).join(table("L"), table("R"), condition);
        JoinStats stats = result.stats();

        assertThat(stats.pruneStatus()).isEqualTo(PruneStatus.PRUNED);
        assertThat(stats.totalPairs()).isEqualTo(16);
        assertThat(stats.participatingPairs()).isEqualTo(2);
        assertThat(stats.pruneRate()).isEqualTo(0.875);
        assertThat(stats.fallbackReasons()).isEmpty();

        // 参与的分区对是 (1,1) 与 (2,2)，各自 2 行 x 2 行，pk 等值各命中 2 对
        assertThat(stats.pairStats()).hasSize(2);
        assertThat(stats.pairStats()).extracting(PairStats::pair)
                .containsExactly("(L1,R1)", "(L2,R2)");
        for (PairStats ps : stats.pairStats()) {
            assertThat(ps.leftRowsScanned()).isEqualTo(2);
            assertThat(ps.rightRowsScanned()).isEqualTo(2);
            assertThat(ps.comparisons()).isEqualTo(4);
            assertThat(ps.matched()).isEqualTo(2);
        }
        assertThat(stats.totalRowsMatched()).isEqualTo(4);
        assertThat(stats.totalComparisons()).isEqualTo(8);
        assertThat(result.matchedRows()).hasSize(4);

        String summary = stats.summary();
        assertThat(summary).contains("分区对总数: 16", "参与分区对数: 2", "剪枝率: 87.50%");
    }

    @Test
    void fallbackStatsExposeReasons() {
        JoinCondition condition = JoinCondition.of(ColumnComparison.of("v", Op.EQ, "v"));

        JoinResult result = new PartitionJoinService(2).join(table("L"), table("R"), condition);
        JoinStats stats = result.stats();

        assertThat(stats.pruneStatus()).isEqualTo(PruneStatus.FULL_SCAN_FALLBACK);
        assertThat(stats.totalPairs()).isEqualTo(16);
        assertThat(stats.participatingPairs()).isEqualTo(16);
        assertThat(stats.pruneRate()).isEqualTo(0.0);
        assertThat(stats.fallbackReasons()).isNotEmpty();
        assertThat(stats.fallbackReasons().get(0)).contains("分区键");
        assertThat(stats.pairStats()).hasSize(16);
        assertThat(stats.summary()).contains("未能剪枝原因");
    }
}
