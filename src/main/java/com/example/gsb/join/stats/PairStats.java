package com.example.gsb.join.stats;

/**
 * 单个分区对的处理统计。
 *
 * @param pair              分区对描述（L 下标, R 下标）
 * @param leftRowsScanned   左分区读取的行数
 * @param rightRowsScanned  右分区读取的行数
 * @param comparisons       嵌套循环的行对比较次数
 * @param matched           满足完整连接条件的输出行数
 * @param threadName        实际处理该分区对的线程名
 * @param elapsedMillis     处理耗时（毫秒）
 */
public record PairStats(String pair, int leftRowsScanned, int rightRowsScanned, long comparisons, long matched,
                        String threadName, long elapsedMillis) {
}
