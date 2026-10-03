package com.example.gsb.join.prune;

/**
 * 剪枝结果状态。
 */
public enum PruneStatus {
    /** 成功推导并裁剪了分区（可能裁到 0 个，也可能裁后仍覆盖全部，但推导有效）。 */
    PRUNED,
    /** 无法从条件推导任何分区范围，退回全分区扫描（N×N 分区对）。 */
    FULL_SCAN_FALLBACK
}
