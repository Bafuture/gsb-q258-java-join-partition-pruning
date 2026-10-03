package com.example.gsb.join;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 兜底路径：条件无法推导分区时退回全分区扫描，并明确标注原因；
 * 兜底结果仍须与全量连接再过滤一致。
 */
class FallbackTest {

    private static final PartitionScheme SCHEME = PartitionScheme.equalWidth("k", 0, 10, 4);

    private final PartitionJoinExecutor executor = new PartitionJoinExecutor();

    private PartitionedTable leftTable() {
        return PartitionedTable.builder("L", SCHEME)
                .addRow(Row.of("k", 3, "v", 7))
                .addRow(Row.of("k", 12, "v", 2))
                .addRow(Row.of("k", 25, "v", 9))
                .build();
    }

    private PartitionedTable rightTable() {
        return PartitionedTable.builder("R", SCHEME)
                .addRow(Row.of("k", 4, "v", 5))
                .addRow(Row.of("k", 12, "v", 8))
                .addRow(Row.of("k", 31, "v", 1))
                .build();
    }

    @Test
    void customPredicateTriggersFullScanFallbackWithReason() {
        List<Predicate> predicates = List.of(
                Predicate.eq(Predicate.Ref.left("k"), Predicate.Ref.right("k")),
                Predicate.custom("左.v 与 右.v 的奇偶性相同",
                        (l, r) -> (l.getLong("v") - r.getLong("v")) % 2 == 0));

        PartitionJoinExecutor.JoinResult result = executor.join(
                leftTable(), rightTable(), predicates, PartitionJoinExecutor.ExecutorConfig.serial());

        assertThat(result.plan().fallback()).isTrue();
        assertThat(result.plan().fallbackReason()).contains("自定义谓词");
        assertThat(result.plan().pruned()).isFalse();
        assertThat(result.stats().fallback()).isTrue();
        assertThat(result.stats().fallbackReason()).isNotBlank();
        assertThat(result.stats().scannedPairs()).isEqualTo(result.stats().totalPairs());
        assertThat(result.stats().pruneRate()).isZero();
        // 兜底扫描 4×4 全部分区对
        assertThat(result.plan().pairs()).hasSize(16);
        // 结果仍正确：k 相等且 v 奇偶相同
        assertThat(result.rows()).allSatisfy(row -> {
            assertThat(row.getLong("L_k")).isEqualTo(row.getLong("R_k"));
            assertThat((row.getLong("L_v") - row.getLong("R_v")) % 2).isZero();
        });
        assertThat(result.rows()).isNotEmpty();
    }

    @Test
    void noPartitionKeyPredicateFallsBackToFullScan() {
        List<Predicate> predicates = List.of(
                Predicate.eq(Predicate.Ref.left("v"), Predicate.Ref.right("v")));

        PartitionJoinExecutor.JoinResult result = executor.join(
                leftTable(), rightTable(), predicates, PartitionJoinExecutor.ExecutorConfig.serial());

        assertThat(result.plan().fallback()).isTrue();
        assertThat(result.plan().fallbackReason()).contains("未引用分区键");
        assertThat(result.stats().scannedPairs()).isEqualTo(16);
        // 结果与全量连接再过滤一致
        assertThat(result.rows()).allSatisfy(row ->
                assertThat(row.getLong("L_v")).isEqualTo(row.getLong("R_v")));
        assertThat(result.rows()).isEmpty(); // 两侧 v 值集合 {7,2,9} 与 {5,8,1} 无交集
    }

    @Test
    void nonKeyResidualPredicateDoesNotForceFallback() {
        // 分区键等值可剪枝；非分区键条件作为残余条件逐行评估，不触发兜底
        List<Predicate> predicates = List.of(
                Predicate.eq(Predicate.Ref.left("k"), Predicate.Ref.right("k")),
                Predicate.le(Predicate.Ref.left("v"), Predicate.Ref.right("v")));

        PartitionJoinExecutor.JoinResult result = executor.join(
                leftTable(), rightTable(), predicates, PartitionJoinExecutor.ExecutorConfig.serial());

        assertThat(result.plan().fallback()).isFalse();
        assertThat(result.plan().pruned()).isTrue();
        assertThat(result.plan().pairs()).hasSize(4); // 仅对角分区对
        assertThat(result.rows()).allSatisfy(row -> {
            assertThat(row.getLong("L_k")).isEqualTo(row.getLong("R_k"));
            assertThat(row.getLong("L_v")).isLessThanOrEqualTo(row.getLong("R_v"));
        });
        // 手工核对：k=12 时 L.v=2 <= R.v=8 命中
        assertThat(result.rows()).hasSize(1);
    }
}
