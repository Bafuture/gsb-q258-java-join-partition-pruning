package com.example.gsb.join.exec;

import com.example.gsb.join.model.PartitionedTable;
import com.example.gsb.join.predicate.JoinCondition;

/**
 * 对外门面：一条调用完成“剪枝 -> 并行分区对连接 -> 统计”。
 */
public final class PartitionJoinService {

    private final PartitionJoinExecutor executor;

    public PartitionJoinService(int maxConcurrency) {
        this.executor = new PartitionJoinExecutor(maxConcurrency);
    }

    public PartitionJoinService(PartitionJoinExecutor executor) {
        this.executor = executor;
    }

    public JoinResult join(PartitionedTable left, PartitionedTable right, JoinCondition condition) {
        return executor.join(left, right, condition);
    }
}
