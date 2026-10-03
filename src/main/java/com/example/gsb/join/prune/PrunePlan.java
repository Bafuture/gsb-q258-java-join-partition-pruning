package com.example.gsb.join.prune;

import java.util.List;

/**
 * 剪枝结果：需要参与连接的分区对集合，以及推导过程的完整说明。
 */
public final class PrunePlan {

    private final PruneStatus status;
    private final int totalPairs;
    private final List<PartitionPair> pairs;
    private final List<PruneNote> notes;

    private PrunePlan(PruneStatus status, int totalPairs, List<PartitionPair> pairs, List<PruneNote> notes) {
        this.status = status;
        this.totalPairs = totalPairs;
        this.pairs = List.copyOf(pairs);
        this.notes = List.copyOf(notes);
    }

    public static PrunePlan pruned(int totalPairs, List<PartitionPair> pairs, List<PruneNote> notes) {
        return new PrunePlan(PruneStatus.PRUNED, totalPairs, pairs, notes);
    }

    public static PrunePlan fullScanFallback(int totalPairs, List<PartitionPair> pairs, List<PruneNote> notes) {
        return new PrunePlan(PruneStatus.FULL_SCAN_FALLBACK, totalPairs, pairs, notes);
    }

    public PruneStatus status() {
        return status;
    }

    /** 剪枝前（不做任何裁剪时）的分区对总数 = 左分区数 × 右分区数。 */
    public int totalPairs() {
        return totalPairs;
    }

    /** 剪枝后实际参与连接的分区对。 */
    public List<PartitionPair> pairs() {
        return pairs;
    }

    public int participatingPairs() {
        return pairs.size();
    }

    /** 剪枝率 = 1 - 参与数 / 总数；总数为 0 时定义为 0。 */
    public double pruneRate() {
        return totalPairs == 0 ? 0.0 : 1.0 - (double) pairs.size() / totalPairs;
    }

    public List<PruneNote> notes() {
        return notes;
    }

    /** 兜底原因（仅 FULL_SCAN_FALLBACK 状态下非空）。 */
    public List<PruneNote> fallbackReasons() {
        return notes.stream().filter(n -> n.level() == PruneNote.Level.FALLBACK).toList();
    }

    /** 人类可读的剪枝摘要（中文）。 */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("状态=").append(status)
                .append(", 分区对总数=").append(totalPairs)
                .append(", 参与=").append(pairs.size())
                .append(", 剪枝率=").append(String.format("%.1f%%", pruneRate() * 100));
        for (PruneNote note : notes) {
            sb.append("\n  ").append(note);
        }
        return sb.toString();
    }
}
