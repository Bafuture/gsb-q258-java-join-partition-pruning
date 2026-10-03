package com.example.gsb.join.stats;

import com.example.gsb.join.prune.PruneStatus;
import java.util.List;

/**
 * 一次分区连接的完整统计。
 *
 * @param totalPairs         剪枝前分区对总数
 * @param participatingPairs 剪枝后实际参与连接的分区对数
 * @param pruneRate          剪枝率 = 1 - 参与数/总数
 * @param pruneStatus        剪枝状态
 * @param fallbackReasons    兜底原因（仅兜底时非空）
 * @param totalRowsMatched   全部分区对输出行数之和
 * @param totalComparisons   全部分区对行对比较次数之和
 * @param pairStats          每个分区对的明细统计
 */
public record JoinStats(int totalPairs, int participatingPairs, double pruneRate, PruneStatus pruneStatus,
                        List<String> fallbackReasons, long totalRowsMatched, long totalComparisons,
                        List<PairStats> pairStats) {

    public JoinStats {
        pairStats = List.copyOf(pairStats);
        fallbackReasons = List.copyOf(fallbackReasons);
    }

    /** 人类可读的统计摘要。 */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("剪枝状态: ").append(pruneStatus).append('\n')
                .append("分区对总数: ").append(totalPairs).append('\n')
                .append("参与分区对数: ").append(participatingPairs).append('\n')
                .append("剪枝率: ").append(String.format("%.2f%%", pruneRate * 100)).append('\n')
                .append("输出行数: ").append(totalRowsMatched).append('\n')
                .append("行对比较次数: ").append(totalComparisons);
        for (String reason : fallbackReasons) {
            sb.append("\n未能剪枝原因: ").append(reason);
        }
        for (PairStats ps : pairStats) {
            sb.append("\n  ").append(ps.pair())
                    .append(" 左读=").append(ps.leftRowsScanned())
                    .append(" 右读=").append(ps.rightRowsScanned())
                    .append(" 比较=").append(ps.comparisons())
                    .append(" 命中=").append(ps.matched())
                    .append(" 线程=").append(ps.threadName());
        }
        return sb.toString();
    }
}
