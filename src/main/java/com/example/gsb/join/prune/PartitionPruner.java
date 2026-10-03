package com.example.gsb.join.prune;

import com.example.gsb.join.domain.KeyDomain;
import com.example.gsb.join.model.Partition;
import com.example.gsb.join.model.PartitionedTable;
import com.example.gsb.join.model.Side;
import com.example.gsb.join.predicate.ColumnComparison;
import com.example.gsb.join.predicate.ColumnValue;
import com.example.gsb.join.predicate.InList;
import com.example.gsb.join.predicate.JoinCondition;
import com.example.gsb.join.predicate.Op;
import com.example.gsb.join.predicate.Predicate;
import java.util.ArrayList;
import java.util.List;

/**
 * 分区剪枝器：根据连接条件推导两侧需要参与连接的分区对。
 *
 * <p>推导规则（两表按同一分区键、同一分区方案分区）：
 * <ol>
 *   <li>单侧分区键谓词（等值/范围/IN）把该侧取值域收窄到 {@link KeyDomain}，
 *       只保留与值域相交的分区；</li>
 *   <li>分区键等值连接 {@code l.pk = r.pk} 时，两侧取值域求交，且只有
 *       “分区区间相交”的分区对才可能产出行（同方案下即对角线配对）；</li>
 *   <li>没有分区键等值连接时，两侧各自裁剪后做笛卡尔积配对；</li>
 *   <li>完全无法从条件推导分区范围时，退回全分区扫描（N×N），并记录兜底原因。</li>
 * </ol>
 */
public final class PartitionPruner {

    /**
     * 生成剪枝计划。
     *
     * @param left      左表
     * @param right     右表（必须与左表同分区键、同分区方案）
     * @param condition 连接条件（AND 合取，可含分区键与非分区键谓词）
     */
    public PrunePlan plan(PartitionedTable left, PartitionedTable right, JoinCondition condition) {
        if (!left.partitionKey().equals(right.partitionKey())) {
            throw new IllegalArgumentException("两表分区键必须一致: " + left.partitionKey() + " vs " + right.partitionKey());
        }
        if (left.partitionCount() != right.partitionCount()) {
            throw new IllegalArgumentException("两表分区方案必须一致");
        }
        String pk = left.partitionKey();
        int totalPairs = left.partitionCount() * right.partitionCount();

        List<PruneNote> notes = new ArrayList<>();
        List<PruneNote> fallbackReasons = new ArrayList<>();
        KeyDomain leftDomain = KeyDomain.ALL;
        KeyDomain rightDomain = KeyDomain.ALL;
        boolean colocatedEquiJoin = false;
        boolean sawPartitionKeyPredicate = false;

        for (Predicate predicate : condition.predicates()) {
            if (predicate instanceof ColumnComparison cc) {
                boolean leftIsPk = cc.leftColumn().equals(pk);
                boolean rightIsPk = cc.rightColumn().equals(pk);
                if (leftIsPk && rightIsPk) {
                    sawPartitionKeyPredicate = true;
                    if (cc.op() == Op.EQ) {
                        colocatedEquiJoin = true;
                    } else {
                        fallbackReasons.add(PruneNote.fallback(
                                PruneNote.Code.NON_EQUI_PARTITION_KEY_JOIN,
                                "分区键跨表比较不是等值（" + cc + "），无法推导同域分区配对"));
                    }
                } else {
                    notes.add(PruneNote.info(PruneNote.Code.NON_PARTITION_KEY_PREDICATE,
                            "非分区键连接条件不参与剪枝: " + cc));
                }
            } else if (predicate instanceof ColumnValue cv) {
                if (cv.column().equals(pk)) {
                    sawPartitionKeyPredicate = true;
                    KeyDomain derived = KeyDomain.fromComparison(cv.op(), cv.value());
                    if (derived == null) {
                        fallbackReasons.add(PruneNote.fallback(
                                PruneNote.Code.UNSUPPORTED_PARTITION_KEY_OPERATOR,
                                "分区键上的 " + cv.op().symbol() + " 谓词无法推导取值域: " + cv));
                    } else if (cv.side() == Side.LEFT) {
                        leftDomain = leftDomain.intersect(derived);
                    } else {
                        rightDomain = rightDomain.intersect(derived);
                    }
                } else {
                    notes.add(PruneNote.info(PruneNote.Code.NON_PARTITION_KEY_PREDICATE,
                            "非分区键过滤条件不参与剪枝: " + cv));
                }
            } else if (predicate instanceof InList in) {
                if (in.column().equals(pk)) {
                    sawPartitionKeyPredicate = true;
                    KeyDomain derived = KeyDomain.fromInList(in.values());
                    if (in.side() == Side.LEFT) {
                        leftDomain = leftDomain.intersect(derived);
                    } else {
                        rightDomain = rightDomain.intersect(derived);
                    }
                } else {
                    notes.add(PruneNote.info(PruneNote.Code.NON_PARTITION_KEY_PREDICATE,
                            "非分区键 IN 条件不参与剪枝: " + in));
                }
            }
        }

        if (!sawPartitionKeyPredicate) {
            fallbackReasons.add(0, PruneNote.fallback(
                    PruneNote.Code.NO_PARTITION_KEY_PREDICATE,
                    "连接条件中没有任何分区键谓词，无法推导分区范围"));
        }

        if (!fallbackReasons.isEmpty()) {
            notes.addAll(fallbackReasons);
            return PrunePlan.fullScanFallback(totalPairs, allPairs(left, right), notes);
        }

        if (colocatedEquiJoin) {
            KeyDomain both = leftDomain.intersect(rightDomain);
            leftDomain = both;
            rightDomain = both;
        } else {
            if (leftDomain != KeyDomain.ALL && rightDomain == KeyDomain.ALL) {
                notes.add(PruneNote.warn(PruneNote.Code.PARTIAL_SIDE_RESTRICTION,
                        "仅左表分区键被约束，右表保持全分区扫描"));
            } else if (rightDomain != KeyDomain.ALL && leftDomain == KeyDomain.ALL) {
                notes.add(PruneNote.warn(PruneNote.Code.PARTIAL_SIDE_RESTRICTION,
                        "仅右表分区键被约束，左表保持全分区扫描"));
            }
        }

        List<Partition> leftParts = select(left, leftDomain);
        List<Partition> rightParts = select(right, rightDomain);
        List<PartitionPair> pairs = new ArrayList<>();
        if (colocatedEquiJoin) {
            for (Partition lp : leftParts) {
                for (Partition rp : rightParts) {
                    if (rangesOverlap(lp, rp)) {
                        pairs.add(new PartitionPair(lp.index(), rp.index()));
                    }
                }
            }
        } else {
            for (Partition lp : leftParts) {
                for (Partition rp : rightParts) {
                    pairs.add(new PartitionPair(lp.index(), rp.index()));
                }
            }
        }
        return PrunePlan.pruned(totalPairs, pairs, notes);
    }

    private static List<Partition> select(PartitionedTable table, KeyDomain domain) {
        List<Partition> result = new ArrayList<>();
        for (Partition partition : table.partitions()) {
            if (domain.intersects(partition)) {
                result.add(partition);
            }
        }
        return result;
    }

    private static boolean rangesOverlap(Partition a, Partition b) {
        return a.lower() < b.upper() && b.lower() < a.upper();
    }

    private static List<PartitionPair> allPairs(PartitionedTable left, PartitionedTable right) {
        List<PartitionPair> pairs = new ArrayList<>();
        for (Partition lp : left.partitions()) {
            for (Partition rp : right.partitions()) {
                pairs.add(new PartitionPair(lp.index(), rp.index()));
            }
        }
        return pairs;
    }
}
