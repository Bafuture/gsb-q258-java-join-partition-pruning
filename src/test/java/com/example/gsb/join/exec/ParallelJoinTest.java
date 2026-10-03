package com.example.gsb.join.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.gsb.join.TestSupport;
import com.example.gsb.join.model.PartitionedTable;
import com.example.gsb.join.predicate.ColumnComparison;
import com.example.gsb.join.predicate.JoinCondition;
import com.example.gsb.join.predicate.Op;
import com.example.gsb.join.stats.PairStats;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 分区对并行连接：并发度受控、确实并行、结果与串行一致。
 */
class ParallelJoinTest {

    private static final int PARTITIONS = 9;
    private static final long WIDTH = 10;

    /** 每个分区 3 行，pk 分别为 10i, 10i+1, 10i+2；两表数据相同。 */
    private static PartitionedTable table(String name) {
        List<long[]> rows = new ArrayList<>();
        for (int i = 0; i < PARTITIONS; i++) {
            for (int j = 0; j < 3; j++) {
                rows.add(new long[] {i * WIDTH + j, i});
            }
        }
        return TestSupport.table(name, PARTITIONS, WIDTH, rows);
    }

    private static JoinCondition equiJoin() {
        return JoinCondition.of(ColumnComparison.of("pk", Op.EQ, "pk"));
    }

    @Test
    void concurrencyNeverExceedsConfiguredLimitAndPairsRunInParallel() {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        PartitionJoinExecutor executor = new PartitionJoinExecutor(3, pair -> {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            active.decrementAndGet();
        });

        JoinResult result = executor.join(table("L"), table("R"), equiJoin());

        assertThat(result.prunePlan().participatingPairs()).isEqualTo(PARTITIONS);
        assertThat(maxActive.get()).isEqualTo(3);
        Set<String> threads = result.stats().pairStats().stream()
                .map(PairStats::threadName).collect(Collectors.toSet());
        assertThat(threads).hasSize(3);
        assertThat(threads).allMatch(t -> t.startsWith("partition-join-"));
    }

    @Test
    void singleThreadExecutionMatchesParallelResult() {
        JoinResult serial = new PartitionJoinExecutor(1).join(table("L"), table("R"), equiJoin());
        JoinResult parallel = new PartitionJoinExecutor(8).join(table("L"), table("R"), equiJoin());

        Set<String> serialThreads = serial.stats().pairStats().stream()
                .map(PairStats::threadName).collect(Collectors.toSet());
        assertThat(serialThreads).hasSize(1);

        // 每个分区对内 pk 完全相同的行一一匹配：9 个分区对 x 3 行 = 27
        assertThat(serial.matchedRows()).hasSize(27);
        assertThat(parallel.matchedRows()).hasSize(27);
        assertThat(parallel.matchedRows().stream().map(MatchedRow::key).collect(Collectors.toList()))
                .isEqualTo(serial.matchedRows().stream().map(MatchedRow::key).collect(Collectors.toList()));
    }

    @Test
    void rejectsNonPositiveConcurrency() {
        assertThatThrownBy(() -> new PartitionJoinExecutor(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
