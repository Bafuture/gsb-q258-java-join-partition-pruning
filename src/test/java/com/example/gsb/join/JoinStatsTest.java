package com.example.gsb.join;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 统计信息测试：分区对总数、参与数、剪枝率、各分区对处理条数。
 */
class JoinStatsTest {

    private static final PartitionScheme SCHEME = PartitionScheme.equalWidth("k", 0, 10, 4);

    private final PartitionJoinExecutor executor = new PartitionJoinExecutor();

    private PartitionedTable table(String name, long... keys) {
        PartitionedTable.Builder b = PartitionedTable.builder(name, SCHEME);
        for (long k : keys) {
            b.addRow(Row.of("k", k, "v", k));
        }
        return b.build();
    }

    @Test
    void statsReportCountsRateAndPerPairRows() {
        PartitionedTable left = table("L", 5, 15, 25);
        PartitionedTable right = table("R", 5, 15, 35);

        // 分区键等值连接：只有 (0,0) 和 (1,1) 可能产出（k=5 与 k=15）
        PartitionJoinExecutor.JoinResult result = executor.join(
                left, right,
                List.of(Predicate.eq(Predicate.Ref.left("k"), Predicate.Ref.right("k"))),
                PartitionJoinExecutor.ExecutorConfig.ofParallelism(2));

        JoinStats stats = result.stats();
        assertThat(stats.totalPairs()).isEqualTo(16);
        assertThat(stats.scannedPairs()).isEqualTo(4); // 对角 4 个分区对
        assertThat(stats.pruneRate()).isEqualTo(0.75, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(stats.fallback()).isFalse();
        assertThat(stats.outputRows()).isEqualTo(2);
        assertThat(stats.pairStats()).hasSize(4);

        // (0,0)：1 行 × 1 行 = 评估 1 对，产出 1 行
        JoinStats.PairStat zero = find(stats, 0, 0);
        assertThat(zero.leftRows()).isEqualTo(1);
        assertThat(zero.rightRows()).isEqualTo(1);
        assertThat(zero.evaluatedPairs()).isEqualTo(1);
        assertThat(zero.outputRows()).isEqualTo(1);

        // (1,1)：1 × 1 = 评估 1 对，产出 1 行
        assertThat(find(stats, 1, 1).outputRows()).isEqualTo(1);

        // (2,2)：左 1 行 / 右 0 行，评估 0 对，产出 0 行
        JoinStats.PairStat two = find(stats, 2, 2);
        assertThat(two.leftRows()).isEqualTo(1);
        assertThat(two.rightRows()).isZero();
        assertThat(two.evaluatedPairs()).isZero();

        // (3,3)：左 0 行 / 右 1 行
        assertThat(find(stats, 3, 3).rightRows()).isEqualTo(1);

        // 摘要串包含关键指标
        String summary = stats.summary();
        assertThat(summary).contains("分区对总数=16", "参与=4", "剪枝率=75.00%", "输出行=2");
    }

    @Test
    void fallbackStatsReportZeroPruneRateAndAllPairs() {
        PartitionedTable left = table("L", 5, 25);
        PartitionedTable right = table("R", 6, 26);
        PartitionJoinExecutor.JoinResult result = executor.join(
                left, right,
                List.of(Predicate.custom("始终成立的黑盒条件", (l, r) -> true)),
                PartitionJoinExecutor.ExecutorConfig.serial());

        JoinStats stats = result.stats();
        assertThat(stats.fallback()).isTrue();
        assertThat(stats.fallbackReason()).contains("黑盒");
        assertThat(stats.totalPairs()).isEqualTo(16);
        assertThat(stats.scannedPairs()).isEqualTo(16);
        assertThat(stats.pruneRate()).isZero();
        assertThat(stats.outputRows()).isEqualTo(4);
        assertThat(stats.pairStats()).hasSize(16);
    }

    private static JoinStats.PairStat find(JoinStats stats, int lp, int rp) {
        return stats.pairStats().stream()
                .filter(p -> p.leftPartition() == lp && p.rightPartition() == rp)
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少分区对 (" + lp + "," + rp + ")"));
    }
}
