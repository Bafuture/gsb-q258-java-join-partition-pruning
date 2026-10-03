package com.example.gsb.join;

import java.util.List;

/**
 * 一次分区连接执行的统计信息。
 *
 * @param totalPairs       剪枝前分区对总数（左分区数 × 右分区数）
 * @param scannedPairs     实际参与扫描的分区对数
 * @param pruneRate        剪枝率 = 1 - scannedPairs / totalPairs
 * @param fallback         是否走了全分区兜底
 * @param fallbackReason   兜底原因（未兜底时为 null）
 * @param pairStats        每个参与分区对的处理明细
 * @param outputRows       连接结果行数
 */
public record JoinStats(int totalPairs,
                        int scannedPairs,
                        double pruneRate,
                        boolean fallback,
                        String fallbackReason,
                        List<PairStat> pairStats,
                        long outputRows) {

    /** 单个分区对的处理明细。 */
    public record PairStat(int leftPartition,
                           int rightPartition,
                           long leftRows,
                           long rightRows,
                           long evaluatedPairs,
                           long outputRows) {
    }

    public String summary() {
        return "分区对总数=%d, 参与=%d, 剪枝率=%.2f%%, 兜底=%s%s, 输出行=%d".formatted(
                totalPairs, scannedPairs, pruneRate * 100,
                fallback, fallback ? "(" + fallbackReason + ")" : "", outputRows);
    }
}
