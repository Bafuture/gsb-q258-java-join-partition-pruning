package com.example.gsb.join;

import java.util.ArrayList;
import java.util.List;

/**
 * 分区剪枝器：分析连接条件，推导左右两侧相关分区，枚举必须参与扫描的分区对。
 *
 * <p>剪枝规则总览：
 * <ol>
 *   <li>作用于某侧分区键的 等值/范围/IN/不等 谓词 -> 先在该侧取成 {@link KeyDomain}，
 *       多个谓词取交集，只保留与域有交集的分区；</li>
 *   <li>两侧分区键之间的比较谓词（EQ/NE/LT/LE/GT/GE）-> 对候选分区对按“区间内是否
 *       存在满足比较的取值”做可行性过滤：
 *       <ul>
 *         <li>EQ: 两分区区间有交集即可（同方案下只有相同分区配对）；</li>
 *         <li>LT: 左区间最小值 &lt; 右区间最大值；LE: 左最小 &le; 右最大；</li>
 *         <li>GT: 左区间最大值 &gt; 右区间最小值；GE: 左最大 &ge; 右最小；</li>
 *         <li>NE: 除非两区间是同一个单点区间，否则均可配对；</li>
 *       </ul></li>
 *   <li>只引用非分区键的谓词无法剪枝，作为残余条件在连接时逐行评估，不影响正确性；</li>
 *   <li>出现无法分析的自定义谓词，或没有任何可作用于分区键的谓词时，
 *       退回全分区扫描并记录原因（见 {@link PruningPlan#fallbackReason()}）。</li>
 * </ol>
 */
public final class PartitionPruner {

    public record PairPlan(int leftPartition, int rightPartition) {
    }

    /**
     * @param pairs            剪枝后需要扫描的分区对（空集合表示结果必为空）
     * @param fallback         是否退回全分区扫描
     * @param fallbackReason   未能剪枝的原因（仅 fallback 时非空）
     * @param pruned           是否确实排除了分区对（参与数 &lt; 总数）
     * @param leftDomain       推导出的左表分区键取值域
     * @param rightDomain      推导出的右表分区键取值域
     * @param totalPairs       剪枝前分区对总数 = 左分区数 × 右分区数
     * @param trace            推导过程的可读轨迹
     */
    public record PruningPlan(List<PairPlan> pairs,
                              boolean fallback,
                              String fallbackReason,
                              boolean pruned,
                              KeyDomain leftDomain,
                              KeyDomain rightDomain,
                              int totalPairs,
                              List<String> trace) {
    }

    public PruningPlan prune(PartitionedTable left, PartitionedTable right, List<Predicate> predicates) {
        List<String> trace = new ArrayList<>();
        int totalPairs = left.partitionCount() * right.partitionCount();
        trace.add("左表 %s 共 %d 个分区，右表 %s 共 %d 个分区，剪枝前分区对总数 = %d"
                .formatted(left.name(), left.partitionCount(), right.name(), right.partitionCount(), totalPairs));

        for (Predicate p : predicates) {
            if (p instanceof Predicate.Custom c) {
                trace.add("存在无法分析的自定义谓词（%s），无法保证剪枝正确性".formatted(c.description()));
                return fullScan(left, right, totalPairs,
                        "存在无法分析的自定义谓词: " + c.description(), trace);
            }
            if (p instanceof Predicate.Constant constant && !constant.value()) {
                trace.add("存在恒假谓词，连接结果必为空，无需扫描任何分区对");
                return new PruningPlan(List.of(), false, null, true,
                        KeyDomain.empty(), KeyDomain.empty(), totalPairs, List.copyOf(trace));
            }
        }

        KeyDomain leftDomain = KeyDomain.full();
        KeyDomain rightDomain = KeyDomain.full();
        List<Predicate.KeyKey> keyKeyPredicates = new ArrayList<>();
        boolean sawKeyPredicate = false;

        for (Predicate p : predicates) {
            if (p instanceof Predicate.KeyValue kv && isPartitionKey(kv.ref(), left, right)) {
                sawKeyPredicate = true;
                KeyDomain derived = KeyDomain.fromPredicate(kv.op(), kv.value());
                if (kv.ref().side() == Predicate.Side.LEFT) {
                    leftDomain = leftDomain.intersect(derived);
                } else {
                    rightDomain = rightDomain.intersect(derived);
                }
                trace.add("谓词 %s %s %d 推导%s侧分区键域: %s"
                        .formatted(kv.ref().column(), kv.op(), kv.value(),
                                kv.ref().side() == Predicate.Side.LEFT ? "左" : "右", derived));
            } else if (p instanceof Predicate.KeyIn ki && isPartitionKey(ki.ref(), left, right)) {
                sawKeyPredicate = true;
                KeyDomain derived = KeyDomain.fromIn(ki.values());
                if (ki.ref().side() == Predicate.Side.LEFT) {
                    leftDomain = leftDomain.intersect(derived);
                } else {
                    rightDomain = rightDomain.intersect(derived);
                }
                trace.add("谓词 %s IN %s 推导%s侧分区键域: %s"
                        .formatted(ki.ref().column(), ki.values(),
                                ki.ref().side() == Predicate.Side.LEFT ? "左" : "右", derived));
            } else if (p instanceof Predicate.KeyKey kk
                    && isPartitionKey(kk.left(), left, right)
                    && isPartitionKey(kk.right(), left, right)) {
                sawKeyPredicate = true;
                keyKeyPredicates.add(kk);
                trace.add("谓词 左.%s %s 右.%s 作用于两侧分区键，用于分区对可行性过滤"
                        .formatted(kk.left().column(), kk.op(), kk.right().column()));
            } else if (p instanceof Predicate.Constant) {
                trace.add("恒真谓词，对剪枝无影响");
            } else {
                trace.add("谓词 %s 不引用可推导的分区键，作为残余条件在连接时逐行评估".formatted(p));
            }
        }

        if (!sawKeyPredicate) {
            trace.add("连接条件中没有任何可作用于分区键的谓词，无法推导分区范围");
            return fullScan(left, right, totalPairs, "连接条件未引用分区键，无法推导出任何分区范围", trace);
        }

        if (leftDomain.isEmpty() || rightDomain.isEmpty()) {
            trace.add("推导出的分区键域为空（左域=" + leftDomain + ", 右域=" + rightDomain + "），无分区对可扫描");
            return new PruningPlan(List.of(), false, null, true,
                    leftDomain, rightDomain, totalPairs, List.copyOf(trace));
        }

        int[] leftCandidates = collectPartitions(left.scheme(), leftDomain);
        int[] rightCandidates = collectPartitions(right.scheme(), rightDomain);
        trace.add("左侧候选分区: %s（域 %s）".formatted(toString(leftCandidates), leftDomain));
        trace.add("右侧候选分区: %s（域 %s）".formatted(toString(rightCandidates), rightDomain));

        List<PairPlan> pairs = new ArrayList<>();
        for (int lp : leftCandidates) {
            for (int rp : rightCandidates) {
                boolean feasible = true;
                for (Predicate.KeyKey kk : keyKeyPredicates) {
                    if (!pairFeasible(kk.op(), left.scheme(), lp, right.scheme(), rp)) {
                        feasible = false;
                        break;
                    }
                }
                if (feasible) {
                    pairs.add(new PairPlan(lp, rp));
                }
            }
        }
        trace.add("分区对可行性过滤后需扫描 %d / %d 个分区对".formatted(pairs.size(), totalPairs));

        boolean pruned = pairs.size() < totalPairs;
        if (!pruned) {
            trace.add("注意：虽然完成了推导，但没有任何分区对被排除（条件覆盖全部分区）");
        }
        return new PruningPlan(List.copyOf(pairs), false, null, pruned,
                leftDomain, rightDomain, totalPairs, List.copyOf(trace));
    }

    private static PruningPlan fullScan(PartitionedTable left, PartitionedTable right,
                                        int totalPairs, String reason, List<String> trace) {
        trace.add("兜底策略：退回全分区扫描，参与分区对数 = %d，剪枝率 = 0%%".formatted(totalPairs));
        List<PairPlan> all = new ArrayList<>(totalPairs);
        for (int lp = 0; lp < left.partitionCount(); lp++) {
            for (int rp = 0; rp < right.partitionCount(); rp++) {
                all.add(new PairPlan(lp, rp));
            }
        }
        return new PruningPlan(List.copyOf(all), true, reason, false,
                KeyDomain.full(), KeyDomain.full(), totalPairs, List.copyOf(trace));
    }

    private static boolean isPartitionKey(Predicate.Ref ref, PartitionedTable left, PartitionedTable right) {
        return switch (ref.side()) {
            case LEFT -> ref.column().equals(left.keyColumn());
            case RIGHT -> ref.column().equals(right.keyColumn());
        };
    }

    private static int[] collectPartitions(PartitionScheme scheme, KeyDomain domain) {
        List<Integer> hit = new ArrayList<>();
        for (int p = 0; p < scheme.partitionCount(); p++) {
            if (domain.overlaps(scheme.lowerBound(p), scheme.upperBound(p))) {
                hit.add(p);
            }
        }
        int[] out = new int[hit.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = hit.get(i);
        }
        return out;
    }

    /**
     * 判断两个分区区间内是否存在满足 op 的键值对（x 来自左分区，y 来自右分区）。
     */
    static boolean pairFeasible(Predicate.Op op,
                                PartitionScheme left, int lp,
                                PartitionScheme right, int rp) {
        long llo = left.lowerBound(lp);
        long lhi = left.upperBound(lp);
        long rlo = right.lowerBound(rp);
        long rhi = right.upperBound(rp);
        long lMax = lhi == Long.MIN_VALUE ? Long.MIN_VALUE : lhi - 1;
        long rMax = rhi == Long.MIN_VALUE ? Long.MIN_VALUE : rhi - 1;
        return switch (op) {
            case EQ -> llo < rhi && rlo < lhi;
            case LT -> rhi != Long.MIN_VALUE && llo < rhi - 1;
            case LE -> rhi != Long.MIN_VALUE && llo <= rhi - 1;
            case GT -> lhi != Long.MIN_VALUE && lhi - 1 > rlo;
            case GE -> lhi != Long.MIN_VALUE && lhi - 1 >= rlo;
            case NE -> !(lMax - llo == 0 && rMax - rlo == 0 && llo == rlo);
        };
    }

    private static String toString(int[] ids) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ids.length; i++) {
            sb.append(i == 0 ? "" : ", ").append(ids[i]);
        }
        return sb.append(']').toString();
    }
}
