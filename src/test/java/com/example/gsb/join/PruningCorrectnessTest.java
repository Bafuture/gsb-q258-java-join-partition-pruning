package com.example.gsb.join;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * 正确性验证：随机数据 + 随机条件，剪枝后的连接结果必须与
 * “全量笛卡尔积再过滤”的结果完全一致；同时验证无关分区确实未被扫描。
 */
class PruningCorrectnessTest {

    private static final int ITERATIONS = 60;

    private final PartitionJoinExecutor executor = new PartitionJoinExecutor();

    @Test
    void prunedJoinMatchesBruteForceOnRandomData() {
        Random rnd = new Random(20261003L);
        int prunedIterations = 0;
        for (int iter = 0; iter < ITERATIONS; iter++) {
            int leftParts = 2 + rnd.nextInt(6);
            int rightParts = 2 + rnd.nextInt(6);
            long leftWidth = 1 + rnd.nextInt(5);
            long rightWidth = 1 + rnd.nextInt(5);
            PartitionScheme leftScheme = PartitionScheme.equalWidth("k", 0, leftWidth, leftParts);
            PartitionScheme rightScheme = PartitionScheme.equalWidth("k", 0, rightWidth, rightParts);

            PartitionedTable left = randomTable("L", leftScheme, rnd.nextInt(60), rnd);
            PartitionedTable right = randomTable("R", rightScheme, rnd.nextInt(60), rnd);

            List<Predicate> predicates = randomPredicates(rnd, leftParts, leftWidth, rightParts, rightWidth);

            PartitionJoinExecutor.JoinResult result = executor.join(
                    left, right, predicates, PartitionJoinExecutor.ExecutorConfig.serial());
            List<Row> bruteForce = bruteForce(left, right, predicates);

            assertThat(canonical(result.rows()))
                    .as("迭代 %d：剪枝结果必须与全量连接再过滤一致，条件=%s", iter, predicates)
                    .isEqualTo(canonical(bruteForce));

            assertThat(result.stats().scannedPairs())
                    .as("迭代 %d：参与分区对数不得超过总数", iter)
                    .isLessThanOrEqualTo(result.stats().totalPairs());

            // 被排除的分区对确实无关：其行对不可能满足条件
            for (int lp = 0; lp < left.partitionCount(); lp++) {
                for (int rp = 0; rp < right.partitionCount(); rp++) {
                    PartitionPruner.PairPlan pair = new PartitionPruner.PairPlan(lp, rp);
                    if (!result.plan().pairs().contains(pair)) {
                        for (Row l : left.partition(lp)) {
                            for (Row r : right.partition(rp)) {
                                assertThat(matchesAll(predicates, l, r))
                                        .as("迭代 %d：被剪枝的分区对 (%d,%d) 不应产生结果行", iter, lp, rp)
                                        .isFalse();
                            }
                        }
                    }
                }
            }
            if (result.plan().pruned()) {
                prunedIterations++;
            }
        }
        assertThat(prunedIterations).as("随机迭代中应确实发生剪枝").isGreaterThan(0);
    }

    @Test
    void alwaysFalsePredicateYieldsEmptyResultWithoutScanning() {
        PartitionScheme scheme = PartitionScheme.equalWidth("k", 0, 10, 4);
        PartitionedTable left = PartitionedTable.builder("L", scheme)
                .addRow(Row.of("k", 5, "v", 1)).build();
        PartitionedTable right = PartitionedTable.builder("R", scheme)
                .addRow(Row.of("k", 5, "v", 1)).build();
        PartitionJoinExecutor.JoinResult result = executor.join(left, right,
                List.of(Predicate.alwaysFalse()), PartitionJoinExecutor.ExecutorConfig.serial());
        assertThat(result.rows()).isEmpty();
        assertThat(result.plan().pairs()).isEmpty();
        assertThat(result.stats().scannedPairs()).isZero();
        assertThat(result.stats().pruneRate()).isEqualTo(1.0);
    }

    private static PartitionedTable randomTable(String name, PartitionScheme scheme, int rows, Random rnd) {
        PartitionedTable.Builder b = PartitionedTable.builder(name, scheme);
        long maxKey = Math.min(scheme.lowerBound(scheme.partitionCount() - 1) + 20, Long.MAX_VALUE - 1);
        for (int i = 0; i < rows; i++) {
            long key = Math.floorMod(rnd.nextLong(), maxKey);
            b.addRow(Row.of("k", key, "v", rnd.nextInt(8), "id", name + i));
        }
        return b.build();
    }

    private static List<Predicate> randomPredicates(Random rnd,
                                                    int leftParts, long leftWidth,
                                                    int rightParts, long rightWidth) {
        List<Predicate> predicates = new ArrayList<>();
        // 键-键谓词（等值或范围），引用分区键
        predicates.add(switch (rnd.nextInt(6)) {
            case 0 -> Predicate.eq(Predicate.Ref.left("k"), Predicate.Ref.right("k"));
            case 1 -> Predicate.lt(Predicate.Ref.left("k"), Predicate.Ref.right("k"));
            case 2 -> Predicate.le(Predicate.Ref.left("k"), Predicate.Ref.right("k"));
            case 3 -> Predicate.gt(Predicate.Ref.left("k"), Predicate.Ref.right("k"));
            case 4 -> Predicate.ge(Predicate.Ref.left("k"), Predicate.Ref.right("k"));
            default -> Predicate.ne(Predicate.Ref.left("k"), Predicate.Ref.right("k"));
        });
        // 可选：左侧分区键上的等值/范围/IN 过滤
        long leftMax = leftWidth * leftParts;
        if (rnd.nextBoolean()) {
            switch (rnd.nextInt(4)) {
                case 0 -> predicates.add(Predicate.eq(Predicate.Ref.left("k"), rnd.nextLong(leftMax)));
                case 1 -> {
                    long lo = rnd.nextLong(leftMax);
                    long hi = lo + rnd.nextLong(leftMax - lo + 1);
                    predicates.add(Predicate.ge(Predicate.Ref.left("k"), lo));
                    predicates.add(Predicate.le(Predicate.Ref.left("k"), hi));
                }
                case 2 -> predicates.add(Predicate.in(Predicate.Ref.left("k"),
                        rnd.nextLong(leftMax), rnd.nextLong(leftMax), rnd.nextLong(leftMax)));
                default -> predicates.add(Predicate.ne(Predicate.Ref.left("k"), rnd.nextLong(leftMax)));
            }
        }
        // 可选：右侧分区键过滤
        long rightMax = rightWidth * rightParts;
        if (rnd.nextBoolean()) {
            switch (rnd.nextInt(3)) {
                case 0 -> predicates.add(Predicate.eq(Predicate.Ref.right("k"), rnd.nextLong(rightMax)));
                case 1 -> predicates.add(Predicate.lt(Predicate.Ref.right("k"), rnd.nextLong(rightMax + 1)));
                default -> predicates.add(Predicate.in(Predicate.Ref.right("k"),
                        rnd.nextLong(rightMax), rnd.nextLong(rightMax)));
            }
        }
        // 非分区键条件（残余谓词）：验证剪枝不影响其语义
        if (rnd.nextBoolean()) {
            predicates.add(Predicate.le(Predicate.Ref.left("v"), Predicate.Ref.right("v")));
        }
        if (rnd.nextBoolean()) {
            predicates.add(Predicate.ge(Predicate.Ref.left("v"), 3));
        }
        return predicates;
    }

    private static List<Row> bruteForce(PartitionedTable left, PartitionedTable right, List<Predicate> predicates) {
        List<Row> out = new ArrayList<>();
        for (Row l : left.allRows()) {
            for (Row r : right.allRows()) {
                if (matchesAll(predicates, l, r)) {
                    out.add(Row.merged(l, r));
                }
            }
        }
        return out;
    }

    private static boolean matchesAll(List<Predicate> predicates, Row l, Row r) {
        for (Predicate p : predicates) {
            if (!p.test(l, r)) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> canonical(List<Row> rows) {
        Set<String> set = new TreeSet<>();
        for (Row row : rows) {
            set.add(row.asMap().toString());
        }
        return set;
    }
}
