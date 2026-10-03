package com.example.gsb.join;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.gsb.join.exec.JoinResult;
import com.example.gsb.join.exec.PartitionJoinExecutor;
import com.example.gsb.join.model.Partition;
import com.example.gsb.join.model.PartitionedTable;
import com.example.gsb.join.model.Row;
import com.example.gsb.join.model.Side;
import com.example.gsb.join.predicate.ColumnComparison;
import com.example.gsb.join.predicate.ColumnValue;
import com.example.gsb.join.predicate.InList;
import com.example.gsb.join.predicate.JoinCondition;
import com.example.gsb.join.predicate.Op;
import com.example.gsb.join.predicate.Predicate;
import com.example.gsb.join.prune.PartitionPair;
import com.example.gsb.join.prune.PruneStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 正确性：对每一组随机数据，剪枝后的分区连接结果必须与
 * “全分区 N×N 全量连接后再按条件过滤”的基准结果（多重集）完全一致。
 */
class PrunedJoinCorrectnessTest {

    private static final int ITERATIONS = 100;

    @Test
    void prunedJoinMatchesFullJoinThenFilterOnRandomData() {
        for (int it = 0; it < ITERATIONS; it++) {
            Random rnd = new Random(20261003L + it);
            int partitionCount = 1 + rnd.nextInt(8);
            long width = 1 + rnd.nextInt(20);
            long domainMax = partitionCount * width;

            PartitionedTable left = TestSupport.table("L", partitionCount, width, randomRows(rnd, partitionCount, width));
            PartitionedTable right = TestSupport.table("R", partitionCount, width, randomRows(rnd, partitionCount, width));
            Scenario scenario = randomScenario(rnd, domainMax);

            JoinResult result = new PartitionJoinExecutor(4).join(left, right, scenario.condition());

            // 被剪枝排除的分区绝不能被扫描
            Set<Integer> usedLeft = result.prunePlan().pairs().stream()
                    .map(PartitionPair::leftIndex).collect(Collectors.toSet());
            Set<Integer> usedRight = result.prunePlan().pairs().stream()
                    .map(PartitionPair::rightIndex).collect(Collectors.toSet());
            for (Partition p : left.partitions()) {
                if (!usedLeft.contains(p.index())) {
                    assertThat(p.scanCount()).as("iter %d: 左分区 %d 被剪枝却发生了扫描", it, p.index()).isZero();
                }
            }
            for (Partition p : right.partitions()) {
                if (!usedRight.contains(p.index())) {
                    assertThat(p.scanCount()).as("iter %d: 右分区 %d 被剪枝却发生了扫描", it, p.index()).isZero();
                }
            }

            // 基准：全量 N×N 连接后再过滤
            List<String> expected = fullJoinThenFilter(left, right, scenario.condition());
            List<String> actual = result.matchedRows().stream()
                    .map(m -> m.key()).sorted().collect(Collectors.toList());

            assertThat(actual)
                    .as("iter %d, 条件=%s, 计划=%s", it, scenario.condition(), result.prunePlan().summary())
                    .isEqualTo(expected);

            if (scenario.expectFallback()) {
                assertThat(result.prunePlan().status()).isEqualTo(PruneStatus.FULL_SCAN_FALLBACK);
                assertThat(result.prunePlan().participatingPairs())
                        .isEqualTo(partitionCount * partitionCount);
                assertThat(result.stats().fallbackReasons()).isNotEmpty();
            } else {
                assertThat(result.prunePlan().status()).isEqualTo(PruneStatus.PRUNED);
            }
            assertThat(result.stats().participatingPairs())
                    .isEqualTo(result.prunePlan().participatingPairs());
            assertThat(result.stats().totalPairs())
                    .isEqualTo(partitionCount * partitionCount);
        }
    }

    private static List<String> fullJoinThenFilter(PartitionedTable left, PartitionedTable right,
                                                   JoinCondition condition) {
        List<String> keys = new ArrayList<>();
        for (Partition lp : left.partitions()) {
            for (Row l : lp.scan()) {
                for (Partition rp : right.partitions()) {
                    for (Row r : rp.scan()) {
                        if (condition.test(l, r)) {
                            keys.add(l.id() + "#" + r.id());
                        }
                    }
                }
            }
        }
        keys.sort(String::compareTo);
        return keys;
    }

    private static List<long[]> randomRows(Random rnd, int partitionCount, long width) {
        List<long[]> rows = new ArrayList<>();
        for (int i = 0; i < partitionCount; i++) {
            int count = rnd.nextInt(6);
            for (int j = 0; j < count; j++) {
                long pk = i * width + rnd.nextInt((int) width);
                long v = rnd.nextInt(8);
                rows.add(new long[] {pk, v});
            }
        }
        return rows;
    }

    private record Scenario(JoinCondition condition, boolean expectFallback) {
    }

    private static Scenario randomScenario(Random rnd, long domainMax) {
        Predicate pkEqui = ColumnComparison.of("pk", Op.EQ, "pk");
        return switch (rnd.nextInt(7)) {
            case 0 -> new Scenario(JoinCondition.of(pkEqui), false);
            case 1 -> new Scenario(JoinCondition.of(pkEqui, ColumnComparison.of("v", Op.EQ, "v")), false);
            case 2 -> {
                Op[] rangeOps = {Op.GT, Op.GE, Op.LT, Op.LE};
                Op op = rangeOps[rnd.nextInt(rangeOps.length)];
                yield new Scenario(JoinCondition.of(pkEqui,
                        ColumnValue.of(Side.LEFT, "pk", op, rnd.nextInt((int) domainMax + 1))), false);
            }
            case 3 -> {
                int size = rnd.nextInt(5);
                long[] values = new long[size];
                for (int i = 0; i < size; i++) {
                    values[i] = rnd.nextInt((int) domainMax + 1);
                }
                yield new Scenario(JoinCondition.of(pkEqui, InList.of(Side.LEFT, "pk", values)), false);
            }
            case 4 -> new Scenario(JoinCondition.of(pkEqui,
                    ColumnValue.of(Side.RIGHT, "pk", Op.EQ, rnd.nextInt((int) domainMax + 1)),
                    ColumnValue.of(Side.LEFT, "v", Op.LE, rnd.nextInt(8))), false);
            case 5 -> {
                Op[] rangeOps = {Op.GE, Op.LE};
                yield new Scenario(JoinCondition.of(pkEqui,
                        ColumnValue.of(Side.LEFT, "pk", Op.GE, rnd.nextInt((int) domainMax + 1)),
                        ColumnValue.of(Side.RIGHT, "pk", rangeOps[rnd.nextInt(2)], rnd.nextInt((int) domainMax + 1))),
                        false);
            }
            default -> new Scenario(JoinCondition.of(ColumnComparison.of("v", Op.EQ, "v")), true);
        };
    }
}
